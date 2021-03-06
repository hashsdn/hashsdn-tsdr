/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.tsdr.collectors.cmc;

import java.util.List;

import org.opendaylight.yang.gen.v1.opendaylight.tsdr.rev150219.storetsdrmetricrecord.input.TSDRMetricRecord;
/**
 * @author Sharon Aicler(saichler@gmail.com)
 **/
public interface ICollectorService {

    public void store(TSDRMetricRecord record);
    public void store(List<TSDRMetricRecord> recordList);

}
