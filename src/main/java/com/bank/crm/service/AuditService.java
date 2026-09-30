package com.bank.crm.service;

import com.bank.crm.config.RequestContext;
import com.bank.crm.dto.AuditLogResponse;
import com.bank.crm.dto.PageResponse;
import com.bank.crm.model.AuditAction;
import com.bank.crm.model.AuditLog;
import com.bank.crm.model.AuditOutcome;
import com.bank.crm.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an audit event inside the caller's transaction, so the business change and its
     * audit row commit (or roll back) together - there is never a change without an audit row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String entityType, String entityId, String details, Object changes) {
        repository.save(new AuditLog(action, entityType, entityId, RequestContext.currentUser(),
                RequestContext.clientIp(), null, AuditOutcome.SUCCESS, details, toJson(changes)));
    }

    /**
     * Records an audit event in its own transaction. Used for failures (the business transaction
     * has rolled back but the attempt must still be recorded) and for bulk-load events raised
     * from background threads, which pass user/IP explicitly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(AuditAction action, String entityType, String entityId, String performedBy,
                                  String clientIp, String jobId, AuditOutcome outcome, String details, Object changes) {
        repository.save(new AuditLog(action, entityType, entityId, performedBy, clientIp, jobId, outcome,
                details, toJson(changes)));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(AuditAction action, String entityType, String jobId,
                                                 String performedBy, int page, int size) {
        Specification<AuditLog> spec = (root, q, cb) -> cb.conjunction();
        if (action != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), action));
        if (entityType != null && !entityType.isBlank())
            spec = spec.and((root, q, cb) -> cb.equal(root.get("entityType"), entityType));
        if (jobId != null && !jobId.isBlank())
            spec = spec.and((root, q, cb) -> cb.equal(root.get("jobId"), jobId));
        if (performedBy != null && !performedBy.isBlank())
            spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("performedBy")),
                    "%" + performedBy.toLowerCase() + "%"));
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "eventTime", "id"));
        return PageResponse.of(repository.findAll(spec, pageable), AuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    /** Builds a {"field": {"from": old, "to": new}} map containing only the fields that changed. */
    public static Map<String, Map<String, Object>> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        for (var entry : after.entrySet()) {
            Object oldValue = before.get(entry.getKey());
            if (!Objects.equals(normalize(oldValue), normalize(entry.getValue()))) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", oldValue);
                change.put("to", entry.getValue());
                changes.put(entry.getKey(), change);
            }
        }
        return changes;
    }

    // BigDecimal 100.0 vs 100.00 should not register as a change
    private static Object normalize(Object v) {
        return v instanceof java.math.BigDecimal bd ? bd.stripTrailingZeros() : v;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
