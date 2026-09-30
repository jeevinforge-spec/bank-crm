package com.bank.crm.service.bulk;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crm.bulk")
public record BulkLoadProperties(
        int defaultThreads,
        int maxThreads,
        int defaultChunkSize,
        int maxChunkSize,
        int maxGeneratedRows,
        int maxStoredErrors,
        int maxConcurrentJobs
) {
    public int resolveThreads(Integer requested) {
        int t = requested == null ? defaultThreads : requested;
        return Math.max(1, Math.min(t, maxThreads));
    }

    public int resolveChunkSize(Integer requested) {
        int c = requested == null ? defaultChunkSize : requested;
        return Math.max(10, Math.min(c, maxChunkSize));
    }
}
