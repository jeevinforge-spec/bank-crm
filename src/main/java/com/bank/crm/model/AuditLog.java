package com.bank.crm.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_log_seq_gen")
    @SequenceGenerator(name = "audit_log_seq_gen", sequenceName = "audit_log_seq", allocationSize = 50)
    private Long id;

    @Column(name = "event_time", nullable = false, updatable = false)
    private Instant eventTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private AuditAction action;

    @Column(name = "entity_type", nullable = false, length = 40, updatable = false)
    private String entityType;

    @Column(name = "entity_id", length = 40, updatable = false)
    private String entityId;

    @Column(name = "performed_by", nullable = false, length = 50, updatable = false)
    private String performedBy;

    @Column(name = "client_ip", length = 45, updatable = false)
    private String clientIp;

    @Column(name = "job_id", length = 36, updatable = false)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AuditOutcome outcome;

    @Column(length = 1000, updatable = false)
    private String details;

    /** JSON describing field-level changes ({"field": {"from": x, "to": y}}) or the full snapshot. */
    @Lob
    @Column(updatable = false)
    private String changes;

    protected AuditLog() {
    }

    public AuditLog(AuditAction action, String entityType, String entityId, String performedBy,
                    String clientIp, String jobId, AuditOutcome outcome, String details, String changes) {
        this.eventTime = Instant.now();
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.performedBy = performedBy;
        this.clientIp = clientIp;
        this.jobId = jobId;
        this.outcome = outcome;
        this.details = details != null && details.length() > 1000 ? details.substring(0, 1000) : details;
        this.changes = changes;
    }

    public Long getId() { return id; }
    public Instant getEventTime() { return eventTime; }
    public AuditAction getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getPerformedBy() { return performedBy; }
    public String getClientIp() { return clientIp; }
    public String getJobId() { return jobId; }
    public AuditOutcome getOutcome() { return outcome; }
    public String getDetails() { return details; }
    public String getChanges() { return changes; }
}
