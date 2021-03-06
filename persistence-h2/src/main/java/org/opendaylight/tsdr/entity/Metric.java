/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.tsdr.entity;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

/**
 * Represents the Metric table in store's persistent entry
 *
 * Automated id generated will be the primary key of the table
 *
 *
 * @author <a href="mailto:syedbahm@cisco.com">Basheeruddin Ahmed</a>
 *
 */

@Entity
public class Metric {
    @Id
    @GeneratedValue
    private Long id;
    private Long metricTimeStamp;
    private String metricName;
    private Double metricValue;
    private String metricCategory;
    private String nodeId;
    private String metricDetails;

    public Metric() {
    }

    public Metric(Long timeStamp, String metricName, double metricValue) {
        super();
        this.metricTimeStamp = timeStamp;
        this.metricName = metricName;
        this.metricValue = metricValue;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMetricTimeStamp() {
        return metricTimeStamp;
    }

    public void setMetricTimeStamp(Long metricTimeStamp) {
        this.metricTimeStamp = metricTimeStamp;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public Double getMetricValue() {
        return metricValue;
    }

    public void setMetricValue(Double metricValue) {
        this.metricValue = metricValue;
    }

    public String getMetricCategory() {
        return metricCategory;
    }

    public void setMetricCategory(String metricCategory) {
        this.metricCategory = metricCategory;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getMetricDetails() {
        return metricDetails;
    }

    public void setMetricDetails(String metricDetails) {
        this.metricDetails = metricDetails;
    }
}