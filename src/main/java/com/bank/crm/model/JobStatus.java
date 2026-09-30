package com.bank.crm.model;

public enum JobStatus {
    QUEUED, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED;

    public boolean isFinished() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == FAILED;
    }
}
