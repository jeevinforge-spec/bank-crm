package com.bank.crm.controller;

import com.bank.crm.dto.AuditLogResponse;
import com.bank.crm.dto.PageResponse;
import com.bank.crm.model.AuditAction;
import com.bank.crm.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only: the audit trail can be queried but never edited or deleted through the API. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public PageResponse<AuditLogResponse> search(@RequestParam(required = false) AuditAction action,
                                                 @RequestParam(required = false) String entityType,
                                                 @RequestParam(required = false) String jobId,
                                                 @RequestParam(required = false) String user,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return auditService.search(action, entityType, jobId, user, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }
}
