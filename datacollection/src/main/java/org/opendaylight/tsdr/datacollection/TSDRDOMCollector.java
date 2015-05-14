/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.tsdr.datacollection;

import java.io.File;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.sal.binding.api.RpcProviderRegistry;
import org.opendaylight.tsdr.datacollection.handlers.FlowCapableNodeConnectorQueueStatisticsDataHandler;
import org.opendaylight.tsdr.datacollection.handlers.FlowStatisticsDataHandler;
import org.opendaylight.tsdr.datacollection.handlers.NodeConnectorStatisticsChangeHandler;
import org.opendaylight.tsdr.datacollection.handlers.NodeGroupStatisticsChangeHandler;
import org.opendaylight.tsdr.datacollection.handlers.NodeMeterStatisticsChangeHandler;
import org.opendaylight.tsdr.datacollection.handlers.NodeTableStatisticsChangeHandler;
import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.DataCategory;
import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.StoreTSDRMetricRecordInput;
import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.StoreTSDRMetricRecordInputBuilder;
import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.TSDRService;
import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.storetsdrmetricrecord.input.TSDRMetricRecord;
import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.storetsdrmetricrecord.input.TSDRMetricRecordBuilder;
import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.tsdrrecord.RecordKeys;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev100924.Counter64;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowCapableNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowCapableNodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.meters.Meter;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.Table;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.Flow;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.statistics.rev130819.FlowStatisticsData;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.table.statistics.rev131215.FlowTableStatisticsData;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.port.rev130925.queues.Queue;
import org.opendaylight.yang.gen.v1.urn.opendaylight.group.statistics.rev131111.NodeGroupStatistics;
import org.opendaylight.yang.gen.v1.urn.opendaylight.group.types.rev131018.groups.Group;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.meter.statistics.rev131111.NodeMeterStatistics;
import org.opendaylight.yang.gen.v1.urn.opendaylight.port.statistics.rev131214.FlowCapableNodeConnectorStatisticsData;
import org.opendaylight.yang.gen.v1.urn.opendaylight.queue.statistics.rev131216.FlowCapableNodeConnectorQueueStatisticsData;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Sharon Aicler(saichler@gmail.com)
 **/
/*
 * The TSDRDOMCollector is the place to collect metric data that exist in the
 * Inventory model and its augmentations It registers on specific locations in
 * the data broker and every 30 seconds persists the data to the TSDR data
 * storage
 */
public class TSDRDOMCollector {
    // A reference to the data broker
    private DataBroker dataBroker = null;
    // A map representing the instance identifier of the metric collection to
    // the place in the cached builder collection array
    private Map<InstanceIdentifier<?>, ContainerIndex> id2Index = new ConcurrentHashMap<InstanceIdentifier<?>, ContainerIndex>();
    // An array of BuilderContainer, a builder container is a collection of
    // metric builders that serves as a cache
    // so we won't need to instantiate and set all the static meta data of the
    // metric when ever we want to store
    // It is an array to avoid iteration problems and synchronization issues
    // when working with List/Set
    // As we don't really care about synchronization when reading the array, it
    // will be much faster than using
    // some object that we need to synchronize.
    private TSDRMetricRecordBuilderContainer[] containers = new TSDRMetricRecordBuilderContainer[0];
    // Is the collector running, an indication to stop the thresds if it is
    // closed
    private boolean running = true;
    // The reference to the the RPC registry to store the data
    private RpcProviderRegistry rpcRegistry = null;
    // Logger reference
    private static final Logger logger = LoggerFactory
            .getLogger(TSDRDOMCollector.class);
    // for debugging, specify if the logs should go to external file or the
    // karaf log
    private static boolean logToExternalFile = false;

    // collectors
    private Map<Class<? extends DataObject>, TSDRBaseDataHandler> handlers = new ConcurrentHashMap<>();
    private Map<InstanceIdentifier<Node>, Set<InstanceIdentifier<?>>> nodeID2SubIDs = new ConcurrentHashMap<>();

    public TSDRDOMCollector(DataBroker _dataBroker,
            RpcProviderRegistry _rpcRegistry) {
        log("TSDR DOM Collector Started", INFO);
        this.dataBroker = _dataBroker;
        this.rpcRegistry = _rpcRegistry;
        // initialize handlers
        handlers.put(FlowCapableNodeConnectorQueueStatisticsData.class,
                new FlowCapableNodeConnectorQueueStatisticsDataHandler(this));
        handlers.put(FlowStatisticsData.class, new FlowStatisticsDataHandler(
                this));
        handlers.put(FlowCapableNodeConnectorStatisticsData.class,
                new NodeConnectorStatisticsChangeHandler(this));
        handlers.put(NodeGroupStatistics.class,
                new NodeGroupStatisticsChangeHandler(this));
        handlers.put(FlowTableStatisticsData.class,
                new NodeTableStatisticsChangeHandler(this));
        handlers.put(NodeMeterStatistics.class,
                new NodeMeterStatisticsChangeHandler(this));
        new TSDRInventoryNodesPoller(this);
        new StoringThread();
    }

    // Adds a new builder to the builder container, the first metric for the
    // InstanceIdenfier will create
    // the builder container.
    public void addBuilderToContainer(InstanceIdentifier<Node> nodeID,
            InstanceIdentifier<?> id, TSDRMetricRecordBuilder builder) {
        TSDRMetricRecordBuilderContainer container = null;
        // We want to synchronize here because when adding a new builder we want
        // to make sure there
        // is only one builder container per metric path as we might get on the
        // same InstanceIdentifier two notification in a very short time
        // and we don't want to instantiate two containers for the same metric
        // path.
        synchronized (id2Index) {
            ContainerIndex index = id2Index.get(id);
            if (index != null) {
                container = containers[index.index];
            } else {
                container = new TSDRMetricRecordBuilderContainer();
                TSDRMetricRecordBuilderContainer temp[] = new TSDRMetricRecordBuilderContainer[containers.length + 1];
                System.arraycopy(containers, 0, temp, 0, containers.length);
                id2Index.put(id, new ContainerIndex(containers.length));
                Set<InstanceIdentifier<?>> nodeIDs = nodeID2SubIDs.get(nodeID);
                if (nodeIDs == null) {
                    nodeIDs = new HashSet<InstanceIdentifier<?>>();
                    nodeID2SubIDs.put(nodeID, nodeIDs);
                }
                nodeIDs.add(id);
                temp[containers.length] = container;
                containers = temp;
            }
        }
        // once we have the container, synchronization of the builders array
        // inside the container
        // is under the container responsibility
        container.addBuilder(builder);
    }

    public void removeBuilderContailer(InstanceIdentifier<?> id) {
        synchronized (id2Index) {
            ContainerIndex index = id2Index.remove(id);
            if (index != null) {
                TSDRMetricRecordBuilderContainer temp[] = new TSDRMetricRecordBuilderContainer[containers.length - 1];
                if (index.index == 0) {
                    System.arraycopy(containers, 1, temp, 0, temp.length);
                } else if (index.index == containers.length - 1) {
                    System.arraycopy(containers, 0, temp, 0, temp.length);
                } else {
                    System.arraycopy(containers, 0, temp, 0, index.index);
                    System.arraycopy(containers, index.index + 1, temp,
                            index.index, containers.length - (index.index + 1));
                }
                for (ContainerIndex ndx : id2Index.values()) {
                    if (ndx.index > index.index) {
                        ndx.index--;
                    }
                }
                containers = temp;
            }
        }
    }

    // Retrieve a BuilderContainer according to the InstanceIdentifier
    public TSDRMetricRecordBuilderContainer getTSDRMetricRecordBuilderContainer(
            InstanceIdentifier<?> id) {
        ContainerIndex index = this.id2Index.get(id);
        if (index != null) {
            return containers[index.index];
        }
        return null;
    }

    // Create a new TSDRMetricRecordBuilder and adds it to its builder container
    // according to the instanceIdentifier
    public void createTSDRMetricRecordBuilder(InstanceIdentifier<Node> nodeID,
            InstanceIdentifier<?> id, List<RecordKeys> recKeys,
            String metricName, String value, DataCategory category) {
        TSDRMetricRecordBuilder builder = new TSDRMetricRecordBuilder();
        builder.setRecordKeys(recKeys);
        builder.setNodeID(getNodeIDFrom(recKeys));
        builder.setMetricName(metricName);
        builder.setTSDRDataCategory(category);
        Counter64 _value = null;
        try {
            _value = new Counter64(new BigInteger(value));
        } catch (Exception err) {
            log("Failed to set the counter value metric=" + metricName + " - "
                    + value, ERROR);
        }
        builder.setMetricValue(_value);
        builder.setTimeStamp(System.currentTimeMillis());
        addBuilderToContainer(nodeID, id, builder);
    }

    // Finds the handler for this statistics and apply it
    public void applyCollector(InstanceIdentifier<Node> nodeID,
            InstanceIdentifier<?> id, DataObject dataObject,
            Class<? extends DataObject> cls) {
        if (dataObject == null)
            return;
        TSDRBaseDataHandler c = handlers.get(cls);
        if (c == null) {
            log("Error, can't find collector for " + cls.getSimpleName(), ERROR);
            return;
        }
        c.handleData(nodeID, id, dataObject);
    }

    // Extract the statistics from a node and updates the builders with the
    // updated data
    public void collectStatistics(Node node) {
        try {
            if (node != null) {
                InstanceIdentifier<Node> nodeID = InstanceIdentifier.create(
                        Nodes.class).child(Node.class, node.getKey());
                FlowCapableNode fcnode = node
                        .getAugmentation(FlowCapableNode.class);
                if (fcnode != null) {
                    List<Meter> meters = fcnode.getMeter();
                    if (meters != null) {
                        for (Meter meter : meters) {
                            NodeMeterStatistics nodeMeterStatistics = meter
                                    .getAugmentation(NodeMeterStatistics.class);
                            if (nodeMeterStatistics != null) {
                                InstanceIdentifier<NodeMeterStatistics> mIID = InstanceIdentifier
                                        .create(Nodes.class)
                                        .child(Node.class, node.getKey())
                                        .augmentation(FlowCapableNode.class)
                                        .child(Meter.class, meter.getKey())
                                        .augmentation(NodeMeterStatistics.class);
                                applyCollector(nodeID, mIID,
                                        nodeMeterStatistics,
                                        NodeMeterStatistics.class);
                            }
                        }
                    }
                    // Node Flow Statistics
                    List<Table> tables = fcnode.getTable();
                    if (tables != null) {
                        for (Table t : tables) {
                            FlowTableStatisticsData data = t
                                    .getAugmentation(FlowTableStatisticsData.class);
                            if (data != null) {
                                InstanceIdentifier<FlowTableStatisticsData> tIID = InstanceIdentifier
                                        .create(Nodes.class)
                                        .child(Node.class, node.getKey())
                                        .augmentation(FlowCapableNode.class)
                                        .child(Table.class, t.getKey())
                                        .augmentation(
                                                FlowTableStatisticsData.class);
                                applyCollector(nodeID, tIID, data,
                                        FlowTableStatisticsData.class);
                            }
                            // Flow Statistics
                            if (t.getFlow() != null) {
                                for (Flow flow : t.getFlow()) {
                                    FlowStatisticsData flowStatisticsData = flow
                                            .getAugmentation(FlowStatisticsData.class);
                                    if (flowStatisticsData != null) {
                                        InstanceIdentifier<FlowStatisticsData> tIID = InstanceIdentifier
                                                .create(Nodes.class)
                                                .child(Node.class,
                                                        node.getKey())
                                                .augmentation(
                                                        FlowCapableNode.class)
                                                .child(Table.class, t.getKey())
                                                .child(Flow.class,
                                                        flow.getKey())
                                                .augmentation(
                                                        FlowStatisticsData.class);
                                        applyCollector(nodeID, tIID,
                                                flowStatisticsData,
                                                FlowStatisticsData.class);
                                    }
                                }
                            }
                        }
                    }
                    // Node Group Statistics
                    List<Group> groups = fcnode.getGroup();
                    if (groups != null) {
                        for (Group g : groups) {
                            NodeGroupStatistics ngs = g
                                    .getAugmentation(NodeGroupStatistics.class);
                            InstanceIdentifier<NodeGroupStatistics> tIID = InstanceIdentifier
                                    .create(Nodes.class)
                                    .child(Node.class, node.getKey())
                                    .augmentation(FlowCapableNode.class)
                                    .child(Group.class, g.getKey())
                                    .augmentation(NodeGroupStatistics.class);
                            applyCollector(nodeID, tIID, ngs,
                                    NodeGroupStatistics.class);
                        }
                    }
                }

                // Node Connector Statistics
                List<NodeConnector> ports = node.getNodeConnector();
                if (ports != null) {
                    for (NodeConnector nc : ports) {
                        FlowCapableNodeConnectorStatisticsData fnc = nc
                                .getAugmentation(FlowCapableNodeConnectorStatisticsData.class);
                        InstanceIdentifier<FlowCapableNodeConnectorStatisticsData> tIID = InstanceIdentifier
                                .create(Nodes.class)
                                .child(Node.class, node.getKey())
                                .child(NodeConnector.class, nc.getKey())
                                .augmentation(
                                        FlowCapableNodeConnectorStatisticsData.class);

                        applyCollector(nodeID, tIID, fnc,
                                FlowCapableNodeConnectorStatisticsData.class);

                        FlowCapableNodeConnector fcnc = nc
                                .getAugmentation(FlowCapableNodeConnector.class);
                        List<Queue> queues = fcnc.getQueue();
                        if (queues != null) {
                            for (Queue q : queues) {
                                InstanceIdentifier<FlowCapableNodeConnectorQueueStatisticsData> tIID2 = InstanceIdentifier
                                        .create(Nodes.class)
                                        .child(Node.class, node.getKey())
                                        .child(NodeConnector.class, nc.getKey())
                                        .augmentation(
                                                FlowCapableNodeConnector.class)
                                        .child(Queue.class, q.getKey())
                                        .augmentation(
                                                FlowCapableNodeConnectorQueueStatisticsData.class);
                                applyCollector(
                                        nodeID,
                                        tIID2,
                                        q.getAugmentation(FlowCapableNodeConnectorQueueStatisticsData.class),
                                        FlowCapableNodeConnectorQueueStatisticsData.class);
                            }
                        }
                    }
                }
            }
        } catch (Exception err) {
            log("Failed to register on metric data due to the following exception:",
                    ERROR);
            log(err);
        }
    }

    private String getNodeIDFrom(List<RecordKeys> recordKeys) {
        String result = null;
        for (RecordKeys key : recordKeys) {
            if (key.getKeyName().equalsIgnoreCase("Node")) {
                if (key.getKeyValue() != null) {
                    return key.getKeyValue();
                }
            }
        }
        return result;
    }

    // This class is the storing thread, every 30 seconds it will wake up and
    // iterate over the builder container array and create
    // metric data list out of the container builders, wrap it up as input for
    // the RPC and invoke the storage RPC method.

    private class StoringThread extends Thread {
        public StoringThread() {
            this.setName("TSDR Storing Thread");
            this.start();
            log("Storing Thread Started", INFO);
        }

        public void run() {
            while (running) {
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException err) {
                    log("Storing Thread Interrupted.", ERROR);
                }
                try {
                    for (int i = 0; i < containers.length; i++) {
                        try {
                            TSDRMetricRecordBuilderContainer bc = containers[i];
                            StoreTSDRMetricRecordInputBuilder input = new StoreTSDRMetricRecordInputBuilder();
                            List<TSDRMetricRecord> list = new LinkedList<>();
                            for (TSDRMetricRecordBuilder builder : bc
                                    .getBuilders()) {
                                list.add(builder.build());
                            }
                            input.setTSDRMetricRecord(list);
                            store(input.build());
                            // store.storeTSDRMetricRecord(input.build());
                        } catch (Exception err) {
                            log("Fail to store data due to the following exception:",
                                    ERROR);
                            log(err);
                        }
                    }
                } catch (Exception err) {
                    log("Fail to iterate over builder containers due to the following error:",
                            ERROR);
                    log(err);
                }
            }
        }
    }

    // Invoke the storage rpc method
    private void store(StoreTSDRMetricRecordInput input) {
        TSDRService tsdrService = this.rpcRegistry
                .getRpcService(TSDRService.class);
        tsdrService.storeTSDRMetricRecord(input);
        log("Data Storage called", DEBUG);
    }

    // For debugging, enable the ability to output to a different file to avoid
    // looking for TSDR logs in the main log.
    public static PrintStream out = null;
    public static final int INFO = 1;
    public static final int DEBUG = 2;
    public static final int ERROR = 3;
    public static final int WARNING = 4;

    public static synchronized void log(Exception e) {
        if (logToExternalFile) {
            try {
                if (out == null) {
                    File f = new File("/tmp/tsdr.log");
                    out = new PrintStream(f);
                }
                e.printStackTrace(out);
                out.flush();
            } catch (Exception err) {
                err.printStackTrace();
            }
        } else {
            logger.error(e.getMessage(), e);
        }
    }

    public static synchronized void log(String str, int type) {
        if (logToExternalFile) {
            try {
                if (out == null) {
                    File f = new File("/tmp/tsdr.log");
                    out = new PrintStream(f);
                }
                out.println(str);
                out.flush();
            } catch (Exception err) {
                err.printStackTrace();
            }
        } else {
            switch (type) {
            case INFO:
                logger.info(str);
                break;
            case DEBUG:
                logger.debug(str);
                break;
            case ERROR:
                logger.error(str);
                break;
            case WARNING:
                logger.warn(str);
                break;
            default:
                logger.debug(str);
            }
        }
    }

    public void removeAllNodeBuilders(InstanceIdentifier<Node> nodeID) {
        Set<InstanceIdentifier<?>> subIDs = nodeID2SubIDs.get(nodeID);
        for (InstanceIdentifier<?> subID : subIDs) {
            removeBuilderContailer(subID);
        }
        log("Removed all data for node-" + nodeID, INFO);
    }

    public DataBroker getDataBroker() {
        return this.dataBroker;
    }

    public boolean isRunning() {
        return this.running;
    }

    private class ContainerIndex {
        public ContainerIndex(Integer _index) {
            this.index = _index;
        }

        private Integer index = -1;
    }
}
