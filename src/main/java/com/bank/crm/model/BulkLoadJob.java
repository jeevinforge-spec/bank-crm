package com.bank.crm.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "bulk_load_job")
public class BulkLoadJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, unique = true, length = 36, updatable = false)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private JobSource source;

    @Column(name = "file_name")
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus status;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "thread_count", nullable = false)
    private int threadCount;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "requested_by", nullable = false, length = 50)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** JSON map of worker-thread name -> rows processed, to show how work was spread. */
    @Column(name = "thread_stats", length = 2000)
    private String threadStats;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected BulkLoadJob() {
    }

    public BulkLoadJob(String jobId, JobSource source, String fileName, int threadCount, int chunkSize, String requestedBy) {
        this.jobId = jobId;
        this.source = source;
        this.fileName = fileName;
        this.threadCount = threadCount;
        this.chunkSize = chunkSize;
        this.requestedBy = requestedBy;
        this.status = JobStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getJobId() { return jobId; }
    public JobSource getSource() { return source; }
    public String getFileName() { return fileName; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
    public int getThreadCount() { return threadCount; }
    public int getChunkSize() { return chunkSize; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getThreadStats() { return threadStats; }
    public void setThreadStats(String threadStats) { this.threadStats = threadStats; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage != null && errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
    }
}
