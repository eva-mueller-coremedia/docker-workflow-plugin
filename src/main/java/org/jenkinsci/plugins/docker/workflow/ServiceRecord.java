package org.jenkinsci.plugins.docker.workflow;

import org.jenkinsci.plugins.docker.commons.fingerprint.ContainerRecord;

import java.io.Serializable;

public class ServiceRecord implements Serializable {



    private String serviceName;
    private String taskId;

    private ContainerRecord containerRecord;

    public ServiceRecord(String serviceName, String taskId, ContainerRecord containerRecord) {
        this.serviceName = serviceName;
        this.taskId = taskId;
        this.containerRecord = containerRecord;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getTaskId() {
        return taskId;
    }

    public ContainerRecord getContainerRecord() {
        return containerRecord;
    }
}
