package com.bank.crm.service.bulk;

import com.bank.crm.config.RequestContext;
import com.bank.crm.dto.BulkErrorResponse;
import com.bank.crm.dto.JobResponse;
import com.bank.crm.exception.NotFoundException;
import com.bank.crm.model.*;
import com.bank.crm.repository.BulkLoadErrorRepository;
import com.bank.crm.repository.BulkLoadJobRepository;
import com.bank.crm.service.AuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-threaded bulk loader.
 *
 * <pre>
 *  HTTP thread ── saves file, creates job row, returns jobId immediately
 *        │
 *        ▼
 *  bulk-job coordinator thread (bounded pool = max concurrent jobs, others wait QUEUED)
 *        │  streams the CSV (never holds the whole file in memory) and cuts it into chunks
 *        │  Semaphore(2 x threads) = backpressure: reading pauses when workers fall behind
 *        ▼
 *  per-job worker pool (N threads, N chosen per job)
 *        each chunk -> ChunkProcessor: validate, de-dup, batched INSERT in its own transaction
 *        │
 *        ▼
 *  CompletableFuture.allOf(...) -> persist error report, final status, audit
 * </pre>
 */
@Service
public class BulkLoadService {

    private static final Logger log = LoggerFactory.getLogger(BulkLoadService.class);

    private final BulkLoadProperties props;
    private final BulkLoadJobRepository jobRepository;
    private final BulkLoadErrorRepository errorRepository;
    private final ChunkProcessor chunkProcessor;
    private final SampleDataGenerator generator;
    private final AuditService auditService;
    private final TaskExecutor jobCoordinator;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    /** Live progress of queued/running jobs, polled by the UI. */
    private final Map<String, JobProgress> liveJobs = new ConcurrentHashMap<>();

    public BulkLoadService(BulkLoadProperties props, BulkLoadJobRepository jobRepository,
                           BulkLoadErrorRepository errorRepository, ChunkProcessor chunkProcessor,
                           SampleDataGenerator generator, AuditService auditService,
                           @Qualifier("bulkJobCoordinator") TaskExecutor bulkJobCoordinator, TransactionTemplate transactionTemplate,
                           ObjectMapper objectMapper) {
        this.props = props;
        this.jobRepository = jobRepository;
        this.errorRepository = errorRepository;
        this.chunkProcessor = chunkProcessor;
        this.generator = generator;
        this.auditService = auditService;
        this.jobCoordinator = bulkJobCoordinator;
        this.tx = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ submission

    public JobResponse submitUpload(MultipartFile file, Integer threads, Integer chunkSize) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Please choose a non-empty CSV file");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("upload.csv");
        if (!name.toLowerCase().endsWith(".csv")) throw new IllegalArgumentException("Only .csv files are supported");

        // The multipart temp file is deleted when the request ends, so take our own copy first.
        Path copy = Files.createTempFile("crm-bulk-", ".csv");
        file.transferTo(copy);
        return submit(JobSource.CSV_UPLOAD, Path.of(name).getFileName().toString(), copy, 0, 0, threads, chunkSize);
    }

    public JobResponse submitGenerated(int rows, int invalidPercent, Integer threads, Integer chunkSize) throws IOException {
        if (rows < 1 || rows > props.maxGeneratedRows())
            throw new IllegalArgumentException("rows must be between 1 and " + props.maxGeneratedRows());
        if (invalidPercent < 0 || invalidPercent > 50)
            throw new IllegalArgumentException("invalidPercent must be between 0 and 50");
        Path file = Files.createTempFile("crm-generated-", ".csv");
        return submit(JobSource.GENERATED, "generated-" + rows + ".csv", file, rows, invalidPercent, threads, chunkSize);
    }

    private JobResponse submit(JobSource source, String fileName, Path file, int generateRows, int invalidPercent,
                               Integer requestedThreads, Integer requestedChunk) {
        int threads = props.resolveThreads(requestedThreads);
        int chunkSize = props.resolveChunkSize(requestedChunk);
        String jobId = UUID.randomUUID().toString();
        String user = RequestContext.currentUser();
        String ip = RequestContext.clientIp();

        BulkLoadJob job = jobRepository.save(new BulkLoadJob(jobId, source, fileName, threads, chunkSize, user));
        JobProgress progress = new JobProgress(jobId, user, ip, props.maxStoredErrors());
        if (generateRows > 0) progress.expectedRecords(generateRows);
        liveJobs.put(jobId, progress);

        auditService.recordIndependent(AuditAction.BULK_LOAD_QUEUED, "BulkLoadJob", jobId, user, ip, jobId,
                AuditOutcome.SUCCESS, String.format("%s job queued: %s, %d threads, chunk size %d",
                        source, fileName, threads, chunkSize), null);

        try {
            jobCoordinator.execute(() -> run(job.getJobId(), file, generateRows, invalidPercent, threads, chunkSize));
        } catch (RejectedExecutionException e) {
            liveJobs.remove(jobId);
            deleteQuietly(file);
            finishJob(jobId, JobStatus.FAILED, progress, "Too many jobs waiting; try again later");
            throw new IllegalStateException("Bulk-load queue is full; try again later");
        }
        return toResponse(job, progress);
    }

    // ------------------------------------------------------------------ execution (coordinator thread)

    private void run(String jobId, Path file, int generateRows, int invalidPercent, int threads, int chunkSize) {
        JobProgress progress = liveJobs.get(jobId);
        progress.status(JobStatus.RUNNING);
        progress.startedAt(Instant.now());
        updateJob(jobId, job -> {
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(progress.startedAt());
        });
        auditService.recordIndependent(AuditAction.BULK_LOAD_STARTED, "BulkLoadJob", jobId, progress.requestedBy(),
                progress.clientIp(), jobId, AuditOutcome.SUCCESS,
                "Started on " + Thread.currentThread().getName() + " with " + threads + " worker threads", null);

        ExecutorService workers = newWorkerPool(jobId, threads);
        try {
            if (generateRows > 0) {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    generator.write(w, generateRows, invalidPercent);
                }
            } else {
                progress.expectedRecords(estimateRows(file));
            }

            List<CompletableFuture<Void>> futures = streamChunks(file, chunkSize, threads, workers, progress);
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            persistErrors(progress);
            JobStatus finalStatus = progress.failureCount() == 0 ? JobStatus.COMPLETED : JobStatus.COMPLETED_WITH_ERRORS;
            finishJob(jobId, finalStatus, progress, null);
        } catch (Exception e) {
            log.error("Bulk job {} failed", jobId, e);
            shutdown(workers); // let chunks already dispatched finish so counts are accurate
            persistErrors(progress);
            finishJob(jobId, JobStatus.FAILED, progress, e.getMessage());
        } finally {
            shutdown(workers);
            deleteQuietly(file);
            liveJobs.remove(jobId);
        }
    }

    /**
     * Reads the CSV on the coordinator thread and hands fixed-size chunks to the worker pool.
     * The semaphore caps in-flight chunks at 2 x threads so a huge file can't flood memory:
     * when all permits are taken, reading blocks until a worker finishes a chunk.
     */
    private List<CompletableFuture<Void>> streamChunks(Path file, int chunkSize, int threads,
                                                       ExecutorService workers, JobProgress progress)
            throws IOException, InterruptedException {
        Semaphore inFlight = new Semaphore(threads * 2);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true)
                .get();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            Map<String, String> headerMap = headerMapping(parser.getHeaderNames());
            List<RawRow> chunk = new ArrayList<>(chunkSize);
            for (CSVRecord record : parser) {
                // +1 because the header is line 1 in the user's spreadsheet
                int line = (int) record.getRecordNumber() + 1;
                Map<String, String> values = new HashMap<>(headerMap.size() * 2);
                headerMap.forEach((original, normalized) ->
                        values.put(normalized, record.isMapped(original) && record.isSet(original) ? record.get(original) : null));
                chunk.add(new RawRow(line, values, String.join(",", record.toList())));
                progress.incrementRecordsRead();

                if (chunk.size() == chunkSize) {
                    futures.add(dispatch(chunk, workers, inFlight, progress));
                    chunk = new ArrayList<>(chunkSize);
                }
            }
            if (!chunk.isEmpty()) futures.add(dispatch(chunk, workers, inFlight, progress));
        }
        progress.expectedRecords(progress.recordsRead()); // exact total now known
        return futures;
    }

    private CompletableFuture<Void> dispatch(List<RawRow> chunk, ExecutorService workers, Semaphore inFlight,
                                             JobProgress progress) throws InterruptedException {
        inFlight.acquire();
        return CompletableFuture.runAsync(() -> {
            try {
                chunkProcessor.process(chunk, progress);
            } catch (RuntimeException e) {
                // never let one chunk kill the job: mark every row of this chunk as failed
                log.error("Unexpected failure processing chunk in job {}", progress.jobId(), e);
                for (RawRow row : chunk) {
                    progress.recordFailure(row.lineNumber(), row.get(CsvColumns.CUSTOMER_NUMBER),
                            "Chunk failed: " + e.getMessage(), row.raw());
                }
            } finally {
                inFlight.release();
            }
        }, workers);
    }

    /** Maps original header -> normalized column name, and fails fast if required columns are missing. */
    private static Map<String, String> headerMapping(List<String> headers) {
        if (headers == null || headers.isEmpty()) throw new IllegalArgumentException("CSV file has no header row");
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String h : headers) mapping.put(h, CsvColumns.normalize(h));
        List<String> missing = CsvColumns.REQUIRED.stream().filter(r -> !mapping.containsValue(r)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("CSV is missing required columns: " + missing);
        return mapping;
    }

    private static ExecutorService newWorkerPool(String jobId, int threads) {
        String prefix = "bulk-" + jobId.substring(0, 8) + "-worker-";
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, prefix + String.format("%02d", counter.incrementAndGet()));
            t.setDaemon(true);
            return t;
        };
        // Fixed pool; queue depth is bounded in practice by the dispatch semaphore.
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);
    }

    private static void shutdown(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void persistErrors(JobProgress progress) {
        List<BulkLoadError> errors = new ArrayList<>(progress.errors());
        if (errors.isEmpty()) return;
        errors.sort(Comparator.comparingInt(BulkLoadError::getRowNumber));
        tx.executeWithoutResult(s -> errorRepository.saveAll(errors));
    }

    private void finishJob(String jobId, JobStatus status, JobProgress progress, String error) {
        Instant now = Instant.now();
        long duration = progress.startedAt() == null ? 0 : Duration.between(progress.startedAt(), now).toMillis();
        Map<String, Integer> threadStats = progress.threadStatsSnapshot();
        progress.status(status);
        updateJob(jobId, job -> {
            job.setStatus(status);
            job.setTotalRecords(Math.max(progress.recordsRead(), progress.processed()));
            job.setSuccessCount(progress.successCount());
            job.setFailureCount(progress.failureCount());
            job.setCompletedAt(now);
            job.setDurationMs(duration);
            job.setThreadStats(toJson(threadStats));
            job.setErrorMessage(error);
        });

        double rate = duration > 0 ? progress.processed() * 1000.0 / duration : 0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", progress.recordsRead());
        summary.put("inserted", progress.successCount());
        summary.put("rejected", progress.failureCount());
        summary.put("durationMs", duration);
        summary.put("recordsPerSecond", Math.round(rate));
        summary.put("rowsPerThread", threadStats);
        auditService.recordIndependent(
                status == JobStatus.FAILED ? AuditAction.BULK_LOAD_FAILED : AuditAction.BULK_LOAD_COMPLETED,
                "BulkLoadJob", jobId, progress.requestedBy(), progress.clientIp(), jobId,
                status == JobStatus.COMPLETED ? AuditOutcome.SUCCESS
                        : status == JobStatus.FAILED ? AuditOutcome.FAILURE : AuditOutcome.PARTIAL,
                String.format("%s: %d inserted, %d rejected in %d ms (%.0f rows/s)%s", status,
                        progress.successCount(), progress.failureCount(), duration, rate,
                        error == null ? "" : " - " + error),
                summary);
        log.info("Bulk job {} {}: {} inserted, {} rejected in {} ms", jobId, status,
                progress.successCount(), progress.failureCount(), duration);
    }

    private void updateJob(String jobId, java.util.function.Consumer<BulkLoadJob> change) {
        tx.executeWithoutResult(s -> jobRepository.findByJobId(jobId).ifPresent(change));
    }

    /** Line count minus header - only used to size the progress bar before parsing finishes. */
    private static int estimateRows(Path file) {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return (int) Math.max(0, lines.filter(l -> !l.isBlank()).count() - 1);
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }

    /** Jobs can't survive a restart (their worker threads are gone) - mark any leftovers as failed. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedJobs() {
        tx.executeWithoutResult(s -> jobRepository.findByStatusIn(List.of(JobStatus.QUEUED, JobStatus.RUNNING))
                .forEach(job -> {
                    job.setStatus(JobStatus.FAILED);
                    job.setCompletedAt(Instant.now());
                    job.setErrorMessage("Interrupted by application restart");
                }));
    }

    // ------------------------------------------------------------------ queries

    public JobResponse getJob(String jobId) {
        BulkLoadJob job = jobRepository.findByJobId(jobId)
                .orElseThrow(() -> new NotFoundException("Job " + jobId + " not found"));
        return toResponse(job, liveJobs.get(jobId));
    }

    public List<JobResponse> recentJobs(int limit) {
        return jobRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream().map(j -> toResponse(j, liveJobs.get(j.getJobId()))).toList();
    }

    public List<BulkErrorResponse> errors(String jobId, int limit) {
        return errorRepository.findByJobIdOrderByRowNumberAsc(jobId, PageRequest.of(0, Math.min(Math.max(limit, 1), 1000)))
                .stream().map(BulkErrorResponse::from).toList();
    }

    private JobResponse toResponse(BulkLoadJob job, JobProgress live) {
        if (live != null && !live.status().isFinished()) {
            Instant started = live.startedAt();
            long elapsed = started == null ? 0 : Duration.between(started, Instant.now()).toMillis();
            int processed = live.processed();
            return new JobResponse(job.getJobId(), job.getSource(), job.getFileName(), live.status(),
                    Math.max(live.expectedRecords(), live.recordsRead()), processed, live.successCount(),
                    live.failureCount(), job.getThreadCount(), job.getChunkSize(), job.getRequestedBy(),
                    job.getCreatedAt(), started, null, elapsed, rate(processed, elapsed),
                    live.threadStatsSnapshot(), null);
        }
        long elapsed = job.getDurationMs() == null ? 0 : job.getDurationMs();
        int processed = job.getSuccessCount() + job.getFailureCount();
        return new JobResponse(job.getJobId(), job.getSource(), job.getFileName(), job.getStatus(),
                job.getTotalRecords(), processed, job.getSuccessCount(), job.getFailureCount(),
                job.getThreadCount(), job.getChunkSize(), job.getRequestedBy(), job.getCreatedAt(),
                job.getStartedAt(), job.getCompletedAt(), elapsed, rate(processed, elapsed),
                fromJson(job.getThreadStats()), job.getErrorMessage());
    }

    private static double rate(int processed, long elapsedMs) {
        return elapsedMs > 0 ? Math.round(processed * 10_000.0 / elapsedMs) / 10.0 : 0;
    }

    private String toJson(Map<String, Integer> stats) {
        try {
            String json = objectMapper.writeValueAsString(stats);
            return json.length() > 2000 ? null : json;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Integer> fromJson(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<TreeMap<String, Integer>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temp file {}", file);
        }
    }
}
