package com.bank.crm.dto;

import com.bank.crm.model.AuditAction;
import com.bank.crm.model.AuditLog;
import com.bank.crm.model.AuditOutcome;

import java.time.Instant;

public record AuditLogResponse(Long id, Instant eventTime, AuditAction action, String entityType, String entityId,
                               String performedBy, String clientIp, String jobId, AuditOutcome outcome,
                               String details, String changes) {

    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(a.getId(), a.getEventTime(), a.getAction(), a.getEntityType(), a.getEntityId(),
                a.getPerformedBy(), a.getClientIp(), a.getJobId(), a.getOutcome(), a.getDetails(), a.getChanges());
    }
}
