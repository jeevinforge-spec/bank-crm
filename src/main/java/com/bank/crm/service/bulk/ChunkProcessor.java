package com.bank.crm.service.bulk;

import com.bank.crm.model.AuditAction;
import com.bank.crm.model.AuditOutcome;
import com.bank.crm.model.Customer;
import com.bank.crm.repository.CustomerRepository;
import com.bank.crm.service.AuditService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Processes one chunk of rows on a worker thread:
 * <ol>
 *   <li>parse + validate every row (CPU work, in parallel across workers)</li>
 *   <li>reject duplicates - within the file (shared concurrent set) and against the DB (one query per chunk)</li>
 *   <li>insert the survivors in a single transaction using Hibernate JDBC batching</li>
 *   <li>if that transaction fails (e.g. a race on a unique key with another job), retry row-by-row
 *       so one bad row cannot sink the other good rows in its chunk</li>
 *   <li>write one audit record for the chunk</li>
 * </ol>
 * Each chunk commits independently, so a failure in one chunk never rolls back another.
 */
@Component
public class ChunkProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChunkProcessor.class);

    @PersistenceContext
    private EntityManager entityManager; // shared, thread-safe proxy bound to the current thread's transaction

    private final TransactionTemplate newTransaction;
    private final CustomerRepository customerRepository;
    private final CustomerRowMapper rowMapper;
    private final AuditService auditService;
    private final int jdbcBatchSize;

    public ChunkProcessor(PlatformTransactionManager txManager, CustomerRepository customerRepository,
                          CustomerRowMapper rowMapper, AuditService auditService,
                          @Value("${spring.jpa.properties.hibernate.jdbc.batch_size:500}") int jdbcBatchSize) {
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.customerRepository = customerRepository;
        this.rowMapper = rowMapper;
        this.auditService = auditService;
        this.jdbcBatchSize = jdbcBatchSize;
    }

    private record Candidate(RawRow row, Customer customer) {
    }

    public void process(List<RawRow> rows, JobProgress progress) {
        int chunkNo = progress.nextChunkNumber();
        long start = System.nanoTime();
        int firstLine = rows.get(0).lineNumber();
        int lastLine = rows.get(rows.size() - 1).lineNumber();

        // 1 + 2a: map/validate, and reject duplicates within the file
        List<Candidate> candidates = new ArrayList<>(rows.size());
        for (RawRow row : rows) {
            try {
                Customer c = rowMapper.map(row, progress.jobId(), progress.requestedBy());
                if (!progress.claimCustomerNumber(c.getCustomerNumber())) {
                    fail(progress, row, "Duplicate customer_number within file: " + c.getCustomerNumber());
                } else if (!progress.claimEmail(c.getEmail())) {
                    fail(progress, row, "Duplicate email within file: " + c.getEmail());
                } else {
                    candidates.add(new Candidate(row, c));
                }
            } catch (RowValidationException e) {
                fail(progress, row, e.getMessage());
            }
        }

        // 2b: reject rows that already exist in the database (one query per key per chunk).
        // If the pre-check itself fails, carry on: the unique constraints still protect the
        // table and the row-by-row fallback below reports any duplicates precisely.
        try {
            candidates = rejectExisting(candidates, progress);
        } catch (RuntimeException e) {
            log.warn("Duplicate pre-check failed for chunk {} of job {}: {}", chunkNo, progress.jobId(), e.getMessage());
        }

        // 3 + 4: batch insert, falling back to row-by-row isolation on failure
        int inserted = 0;
        if (!candidates.isEmpty()) {
            try {
                inserted = insertBatch(candidates);
                progress.recordSuccess(inserted);
            } catch (RuntimeException batchFailure) {
                log.debug("Chunk {} of job {} failed as a batch ({}); retrying row by row",
                        chunkNo, progress.jobId(), batchFailure.getMessage());
                inserted = insertOneByOne(candidates, progress);
            }
        }

        // 5: audit the chunk. Every row is either inserted or rejected exactly once, so the
        // chunk's own numbers come from its own size - never from the job-wide counters, which
        // other worker threads are updating concurrently.
        int failed = rows.size() - inserted;
        long ms = (System.nanoTime() - start) / 1_000_000;
        try {
            auditService.recordIndependent(AuditAction.BULK_CHUNK_LOADED, "Customer", null,
                    progress.requestedBy(), progress.clientIp(), progress.jobId(),
                    failed == 0 ? AuditOutcome.SUCCESS : inserted == 0 ? AuditOutcome.FAILURE : AuditOutcome.PARTIAL,
                    String.format("Chunk #%d (lines %d-%d) on %s: %d inserted, %d rejected in %d ms",
                            chunkNo, firstLine, lastLine, Thread.currentThread().getName(), inserted, failed, ms),
                    null);
        } catch (RuntimeException e) {
            // the rows are already committed and counted; a failed audit write must not re-count them
            log.error("Could not write chunk audit for job {} chunk {}", progress.jobId(), chunkNo, e);
        }
    }

    private List<Candidate> rejectExisting(List<Candidate> candidates, JobProgress progress) {
        if (candidates.isEmpty()) return candidates;
        Set<String> existingNumbers = new HashSet<>(customerRepository.findExistingCustomerNumbers(
                candidates.stream().map(c -> c.customer().getCustomerNumber()).toList()));
        Set<String> existingEmails = new HashSet<>(customerRepository.findExistingEmails(
                candidates.stream().map(c -> c.customer().getEmail()).toList()));
        if (existingNumbers.isEmpty() && existingEmails.isEmpty()) return candidates;

        List<Candidate> remaining = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            if (existingNumbers.contains(c.customer().getCustomerNumber())) {
                fail(progress, c.row(), "customer_number already exists: " + c.customer().getCustomerNumber());
            } else if (existingEmails.contains(c.customer().getEmail())) {
                fail(progress, c.row(), "email already registered: " + c.customer().getEmail());
            } else {
                remaining.add(c);
            }
        }
        return remaining;
    }

    private int insertBatch(List<Candidate> candidates) {
        return newTransaction.execute(status -> {
            int i = 0;
            for (Candidate c : candidates) {
                entityManager.persist(c.customer());
                // flush + clear every JDBC batch: sends one batched INSERT and keeps the
                // persistence context (first-level cache) from growing without bound
                if (++i % jdbcBatchSize == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }
            entityManager.flush();
            entityManager.clear();
            return i;
        });
    }

    private int insertOneByOne(List<Candidate> candidates, JobProgress progress) {
        int inserted = 0;
        for (Candidate c : candidates) {
            Customer customer = c.customer();
            customer.setId(null);       // the failed batch assigned ids/versions; reset so
            customer.setVersion(null);  // Hibernate treats the entity as new again
            try {
                newTransaction.executeWithoutResult(status -> {
                    entityManager.persist(customer);
                    entityManager.flush();
                });
                progress.recordSuccess(1);
                inserted++;
            } catch (RuntimeException e) {
                fail(progress, c.row(), "Database rejected row: " + rootMessage(e));
            }
        }
        return inserted;
    }

    private static void fail(JobProgress progress, RawRow row, String message) {
        progress.recordFailure(row.lineNumber(), row.get(CsvColumns.CUSTOMER_NUMBER), message, row.raw());
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
