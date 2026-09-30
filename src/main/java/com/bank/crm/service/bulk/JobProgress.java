package com.bank.crm.service.bulk;

import com.bank.crm.model.BulkLoadError;
import com.bank.crm.model.JobStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live, lock-free state of a running bulk job. Shared by the coordinator thread (reading the
 * file), all worker threads (writing chunks) and HTTP threads (polling for progress), so every
 * field is either immutable, volatile, atomic or a concurrent collection.
 */
public class JobProgress {

    private final String jobId;
    private final String requestedBy;
    private final String clientIp;
    private final int maxStoredErrors;

    private volatile JobStatus status = JobStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile int expectedRecords;

    private final AtomicInteger recordsRead = new AtomicInteger();
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicInteger chunkCounter = new AtomicInteger();

    /** rows processed per worker thread - shows how the work was spread across the pool */
    private final Map<String, AtomicInteger> perThread = new ConcurrentHashMap<>();

    /** keys seen so far in this file, to reject in-file duplicates across chunks/threads */
    private final Set<String> seenCustomerNumbers = ConcurrentHashMap.newKeySet();
    private final Set<String> seenEmails = ConcurrentHashMap.newKeySet();

    private final Queue<BulkLoadError> errors = new ConcurrentLinkedQueue<>();
    private final AtomicInteger storedErrors = new AtomicInteger();

    public JobProgress(String jobId, String requestedBy, String clientIp, int maxStoredErrors) {
        this.jobId = jobId;
        this.requestedBy = requestedBy;
        this.clientIp = clientIp;
        this.maxStoredErrors = maxStoredErrors;
    }

    public void recordSuccess(int rows) {
        successCount.addAndGet(rows);
        perThread.computeIfAbsent(workerName(), k -> new AtomicInteger()).addAndGet(rows);
    }

    public void recordFailure(int lineNumber, String customerNumber, String message, String raw) {
        failureCount.incrementAndGet();
        perThread.computeIfAbsent(workerName(), k -> new AtomicInteger()).incrementAndGet();
        // keep memory bounded: only the first N errors are kept for the report
        if (storedErrors.incrementAndGet() <= maxStoredErrors) {
            errors.add(new BulkLoadError(jobId, lineNumber, customerNumber, message, raw));
        }
    }

    /** @return true if this customer number has not been seen earlier in the same file */
    public boolean claimCustomerNumber(String number) {
        return seenCustomerNumbers.add(number);
    }

    public boolean claimEmail(String email) {
        return seenEmails.add(email);
    }

    public Map<String, Integer> threadStatsSnapshot() {
        Map<String, Integer> snapshot = new TreeMap<>();
        perThread.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    private static String workerName() {
        String name = Thread.currentThread().getName();
        int idx = name.indexOf("worker-");
        return idx >= 0 ? name.substring(idx) : name;
    }

    public String jobId() { return jobId; }
    public String requestedBy() { return requestedBy; }
    public String clientIp() { return clientIp; }
    public JobStatus status() { return status; }
    public void status(JobStatus status) { this.status = status; }
    public Instant startedAt() { return startedAt; }
    public void startedAt(Instant startedAt) { this.startedAt = startedAt; }
    public int expectedRecords() { return expectedRecords; }
    public void expectedRecords(int expected) { this.expectedRecords = expected; }
    public int incrementRecordsRead() { return recordsRead.incrementAndGet(); }
    public int recordsRead() { return recordsRead.get(); }
    public int successCount() { return successCount.get(); }
    public int failureCount() { return failureCount.get(); }
    public int processed() { return successCount.get() + failureCount.get(); }
    public int nextChunkNumber() { return chunkCounter.incrementAndGet(); }
    public Queue<BulkLoadError> errors() { return errors; }
}
