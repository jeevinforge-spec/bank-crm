package com.bank.crm.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record DashboardStats(
        long totalCustomers,
        BigDecimal totalBalance,
        List<AccountTypeStat> byAccountType,
        Map<String, Long> byKycStatus,
        Map<String, Long> byRiskCategory,
        long auditEvents,
        List<JobResponse> recentJobs
) {
    public record AccountTypeStat(String accountType, long count, BigDecimal balance) {
    }
}
