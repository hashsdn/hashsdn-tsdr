/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 * Copyright (c) 2015 xFlow Research Inc. and others, All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.tsdr.netflow;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.GuardedBy;
import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.DataCategory;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.config.tsdr.collector.spi.rev150915.InsertTSDRLogRecordInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.config.tsdr.collector.spi.rev150915.TsdrCollectorSpiService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.config.tsdr.collector.spi.rev150915.inserttsdrlogrecord.input.TSDRLogRecord;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.config.tsdr.collector.spi.rev150915.inserttsdrlogrecord.input.TSDRLogRecordBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NetFlow Collector to receive netflow packets and store into TSDR data store.
 *
 * Currently only NetFlow Version 5 is supported.
 *
 * @author <a href="mailto:yuling_c@dell.com">YuLing Chen</a>
 * @author <a href="mailto:muhammad.umair@xflowresearch.com">Umair Bhatti</a>
 * @author <a href="mailto:saichler@xgmail.com">Sharon Aicler</a>
 *
 * Created: December 1, 2015
 * Modified: Aug 02, 2016
 */
public class TSDRNetflowCollectorImpl extends Thread implements AutoCloseable {

    private static final long PERSIST_CHECK_INTERVAL_IN_MILLISECONDS = 5000;
    private static final long INCOMING_QUEUE_WAIT_INTERVAL_IN_MILLISECONDS = 2000;
    private static final int FLOW_SIZE_FOR_NETFLOW_PACKET = 48;
    private static final Logger logger = LoggerFactory.getLogger(TSDRNetflowCollectorImpl.class);
    private static byte packetsCountForTests = 0; //Just to test the counts of packet for test
    private final TsdrCollectorSpiService collectorSPIService;
    private DatagramSocket socket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    @GuardedBy("incomingNetFlow")
    private final LinkedList<DatagramPacket> incomingNetFlow = new LinkedList<>();

    /**
     * Constructor
     * @param _collectorSPIService
     */
    public TSDRNetflowCollectorImpl(TsdrCollectorSpiService _collectorSPIService) {
        super("TSDR NetFlow Listener");
        this.setDaemon(true);
        this.collectorSPIService = _collectorSPIService;
    }

    public long getIncomingNetflowSize(){
        return incomingNetFlow.size();
    }

    public byte getPacketCount(){
        return packetsCountForTests;
    }

    public void init() {
        BindException lastEx = null;
        Stopwatch sw = Stopwatch.createStarted();
        while (sw.elapsed(TimeUnit.SECONDS) <= 30) {
            try {
                this.socket = new DatagramSocket(2055);
                this.start();
                new NetFlowProcessor();
                logger.info("TSDRNetflowCollectorImpl initialized");
                return;
            } catch (BindException e) {
                // Address already in use - retry
                Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
            } catch (SocketException e) {
                logger.error("Error creating DatagramSocket on port 2055.", e);
                close();
                return;
            }
        }

        logger.error("Collector service already running. Failed to bind it again on Port 2055.", lastEx);
        close();
    }

    @Override
    public void run(){
        if(this.socket==null || this.socket.isClosed()){
            close();
        }else {
            while (running.get()) {
                byte data[] = new byte[1024];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                try {
                    socket.receive(packet);
                    handleNetFlow(packet);
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        logger.error("Error while handleling netflow packets.", e);
                    }
                    close();
                }
            }
        }
    }

    @Override
    public void close(){
        if (running.compareAndSet(true, false)) {
            if(socket!=null){
                socket.close();
            }
            synchronized(incomingNetFlow){
                incomingNetFlow.notifyAll();
            }
        }
    }

    public void handleNetFlow(DatagramPacket packet){
        synchronized(incomingNetFlow){
            incomingNetFlow.add(packet);
            packetsCountForTests = (byte) (packetsCountForTests + 1);
            incomingNetFlow.notifyAll();
        }
    }

    private class NetFlowProcessor extends Thread{
        private TSDRLogRecordBuilder recordbuilder;

        NetFlowProcessor(){
            super("TSDR NetFlow Processor");
            this.setDaemon(true);
            this.start();
            logger.debug("NetFlow Processor thread initialized");
        }

        @Override
        public void run(){
            DatagramPacket packet = null;
            LinkedList<TSDRLogRecord> netFlowQueue = new LinkedList<>();
            long lastPersisted = System.currentTimeMillis();
            while(running.get()){
                synchronized(incomingNetFlow) {
                    if (incomingNetFlow.isEmpty()) {
                        logger.debug("No Pkts in queue");
                        try {
                            incomingNetFlow.wait(INCOMING_QUEUE_WAIT_INTERVAL_IN_MILLISECONDS);
                        } catch (InterruptedException e) {
                            logger.error("Interrupted while waiting on incoming queue", e);
                        }
                    }
                    if (!incomingNetFlow.isEmpty()) {
                        packet = incomingNetFlow.removeFirst();
                    }
                }
                if(packet != null){
                    logger.debug("Pkts found");
                    byte[] buff = packet.getData();
                    String srcIp = packet.getAddress().getHostAddress().trim();
                    int netFlowVersion = new Integer(NetflowPacketParser.convert(buff, 0, 2)).intValue();
                    long currentTimeStamp = System.currentTimeMillis();
                    if(netFlowVersion == 9){
                        int flowCount = new Integer(NetflowPacketParser.convert(buff, 2, 2)).intValue();
                        int flowCounter = 1;
                        int dataBufferOffset = 20;
                        int flowsetid = Integer.parseInt(NetflowPacketParser.convert(buff, dataBufferOffset, 2));
                        int flowsetLength = Integer.parseInt(NetflowPacketParser.convert(buff, dataBufferOffset + 2, 2));
                        if(flowsetid == 0){
                            dataBufferOffset += 4;
                            NetflowPacketParser.fillFlowSetTemplateMap(buff, dataBufferOffset, flowCount);
                            dataBufferOffset += flowsetLength;
                            flowsetid = Integer.parseInt(NetflowPacketParser.convert(buff, dataBufferOffset, 2));
                            flowsetLength = Integer.parseInt(NetflowPacketParser.convert(buff, dataBufferOffset + 2, 2));
                            dataBufferOffset += 4;
                        }
                        int packetLength = (flowsetLength - 4) / flowCount;
                        while(flowCounter <= flowCount && dataBufferOffset + packetLength < buff.length){
                            recordbuilder = new TSDRLogRecordBuilder();
                            NetflowPacketParser parser = new NetflowPacketParser(buff);
                            parser.addFormat(buff, dataBufferOffset);
                            /*Fill up the RecordBuilder object*/
                            recordbuilder.setNodeID(srcIp);
                            recordbuilder.setTimeStamp(currentTimeStamp);
                            recordbuilder.setIndex(flowCounter);
                            recordbuilder.setTSDRDataCategory(DataCategory.NETFLOW);
                            recordbuilder.setRecordFullText(parser.toString());
                            if(logger.isDebugEnabled()) {
                                logger.debug(parser.toString());
                            }
                            recordbuilder.setRecordAttributes(parser.getRecordAttributes());
                            TSDRLogRecord logRecord =  recordbuilder.build();
                            if(logRecord!=null){
                                netFlowQueue.add(logRecord);
                            }
                            dataBufferOffset += packetLength;
                            flowCounter += 1;
                        }
                    }else{
                        int flowCount = new Integer(NetflowPacketParser.convert(buff, 2, 2)).intValue();
                        int flowCounter = 1;
                        int dataBufferOffset = 0;
                        while(flowCounter <= flowCount && dataBufferOffset + FLOW_SIZE_FOR_NETFLOW_PACKET < buff.length){
                            recordbuilder = new TSDRLogRecordBuilder();
                            NetflowPacketParser parser = new NetflowPacketParser(buff);
                            parser.addFormat(buff, dataBufferOffset);
                            dataBufferOffset += FLOW_SIZE_FOR_NETFLOW_PACKET;
                            /*Fill up the RecordBuilder object*/
                            recordbuilder.setNodeID(srcIp);
                            recordbuilder.setTimeStamp(currentTimeStamp);
                            recordbuilder.setIndex(flowCounter);
                            recordbuilder.setTSDRDataCategory(DataCategory.NETFLOW);
                            recordbuilder.setRecordFullText(parser.toString());
                            if(logger.isDebugEnabled()){
                                logger.debug(parser.toString());
                            }
                            recordbuilder.setRecordAttributes(parser.getRecordAttributes());
                            TSDRLogRecord logRecord =  recordbuilder.build();
                            if(logRecord!=null){
                                netFlowQueue.add(logRecord);
                            }
                            flowCounter += 1;
                        }
                    }
                    if(System.currentTimeMillis() - lastPersisted > PERSIST_CHECK_INTERVAL_IN_MILLISECONDS && !netFlowQueue.isEmpty() && packet != null){
                        LinkedList<TSDRLogRecord> queue = null;
                        if(System.currentTimeMillis() - lastPersisted > PERSIST_CHECK_INTERVAL_IN_MILLISECONDS && !netFlowQueue.isEmpty()){
                            lastPersisted = System.currentTimeMillis();
                            queue = netFlowQueue;
                            netFlowQueue = new LinkedList<>();
                        }
                        if(queue!=null){
                            store(queue);
                        }
                    }
                    packet = null;
                }
            }
        }
    }
   /**
     * Store the data into TSDR data store
     * @param queue
    */
    private void store(List<TSDRLogRecord> queue){
        InsertTSDRLogRecordInputBuilder input = new InsertTSDRLogRecordInputBuilder();
        input.setTSDRLogRecord(queue);
        input.setCollectorCodeName("TSDRNetFlowCollector");
        collectorSPIService.insertTSDRLogRecord(input.build());
    }
}
