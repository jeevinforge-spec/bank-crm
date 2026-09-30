package com.bank.crm.dto;

import com.bank.crm.model.JobSource;
import com.bank.crm.model.JobStatus;

import java.time.Instant;
import java.util.Map;

public record JobResponse(
        String jobId,
        JobSource source,
        String fileName,
        JobStatus status,
        int totalRecords,
        int processedRecords,
        int successCount,
        int failureCount,
        int threadCount,
        int chunkSize,
        String requestedBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        long elapsedMs,
        double recordsPerSecond,
        Map<String, Integer> threadStats,
        String errorMessage
) {
}
