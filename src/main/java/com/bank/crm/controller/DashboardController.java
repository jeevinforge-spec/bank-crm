package com.bank.crm.controller;

import com.bank.crm.dto.DashboardStats;
import com.bank.crm.model.*;
import com.bank.crm.repository.CustomerRepository;
import com.bank.crm.service.AuditService;
import com.bank.crm.service.bulk.BulkLoadService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final CustomerRepository customerRepository;
    private final AuditService auditService;
    private final BulkLoadService bulkLoadService;

    public DashboardController(CustomerRepository customerRepository, AuditService auditService,
                               BulkLoadService bulkLoadService) {
        this.customerRepository = customerRepository;
        this.auditService = auditService;
        this.bulkLoadService = bulkLoadService;
    }

    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public DashboardStats dashboard() {
        List<DashboardStats.AccountTypeStat> byType = customerRepository.statsByAccountType().stream()
                .map(r -> new DashboardStats.AccountTypeStat(r[0].toString(), (Long) r[1], (BigDecimal) r[2]))
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .toList();
        return new DashboardStats(
                customerRepository.count(),
                customerRepository.totalBalance(),
                byType,
                toCountMap(customerRepository.countByKycStatus()),
                toCountMap(customerRepository.countByRiskCategory()),
                auditService.count(),
                bulkLoadService.recentJobs(5));
    }

    /** Enum values for the UI dropdowns, so the front end never hard-codes them. */
    @GetMapping("/meta")
    public Map<String, List<String>> meta() {
        return Map.of(
                "accountTypes", names(AccountType.values()),
                "kycStatuses", names(KycStatus.values()),
                "riskCategories", names(RiskCategory.values()),
                "customerStatuses", names(CustomerStatus.values()),
                "auditActions", names(AuditAction.values()));
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        rows.forEach(r -> map.put(r[0].toString(), (Long) r[1]));
        return map;
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
