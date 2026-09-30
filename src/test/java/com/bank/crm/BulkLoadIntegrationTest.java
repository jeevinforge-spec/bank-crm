package com.bank.crm;

import com.bank.crm.dto.CustomerRequest;
import com.bank.crm.dto.CustomerResponse;
import com.bank.crm.dto.JobResponse;
import com.bank.crm.exception.ConflictException;
import com.bank.crm.model.*;
import com.bank.crm.repository.AuditLogRepository;
import com.bank.crm.repository.CustomerRepository;
import com.bank.crm.service.CustomerService;
import com.bank.crm.service.bulk.BulkLoadService;
import com.bank.crm.service.bulk.SampleDataGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:crmtest;DB_CLOSE_DELAY=-1")
class BulkLoadIntegrationTest {

    @Autowired BulkLoadService bulkLoadService;
    @Autowired CustomerService customerService;
    @Autowired CustomerRepository customerRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired SampleDataGenerator generator;

    @Test
    void loads_10000_customers_with_8_threads() throws Exception {
        long before = customerRepository.count();

        JobResponse job = await(bulkLoadService.submitGenerated(10_000, 0, 8, 500).jobId());

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.successCount()).isEqualTo(10_000);
        assertThat(job.failureCount()).isZero();
        assertThat(customerRepository.count() - before).isEqualTo(10_000);
        // work really was spread over several worker threads
        assertThat(job.threadStats()).hasSizeGreaterThan(1);
        assertThat(job.threadStats().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10_000);
        assertThat(auditLogRepository.findAll()).anyMatch(a ->
                a.getAction() == AuditAction.BULK_LOAD_COMPLETED && job.jobId().equals(a.getJobId()));
        System.out.printf("10,000 rows loaded in %d ms (%.0f rows/s) across %s%n",
                job.elapsedMs(), job.recordsPerSecond(), job.threadStats());
    }

    @Test
    void invalid_rows_are_rejected_individually_without_failing_the_job() throws Exception {
        long before = customerRepository.count();

        JobResponse job = await(bulkLoadService.submitGenerated(3_000, 10, 4, 200).jobId());

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.successCount() + job.failureCount()).isEqualTo(3_000);
        assertThat(job.failureCount()).isBetween(150, 500);
        assertThat(customerRepository.count() - before).isEqualTo(job.successCount());
        assertThat(bulkLoadService.errors(job.jobId(), 1000)).hasSize(job.failureCount());

        // per-chunk audit rows must add up to the job totals (they are written concurrently)
        var chunkPattern = java.util.regex.Pattern.compile(": (\\d+) inserted, (\\d+) rejected");
        int[] sums = new int[2];
        auditLogRepository.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.BULK_CHUNK_LOADED && job.jobId().equals(a.getJobId()))
                .forEach(a -> {
                    var m = chunkPattern.matcher(a.getDetails());
                    assertThat(m.find()).isTrue();
                    sums[0] += Integer.parseInt(m.group(1));
                    sums[1] += Integer.parseInt(m.group(2));
                });
        assertThat(sums[0]).isEqualTo(job.successCount());
        assertThat(sums[1]).isEqualTo(job.failureCount());
    }

    @Test
    void reloading_the_same_file_rejects_every_row_as_duplicate() throws Exception {
        StringWriter csv = new StringWriter();
        generator.write(csv, 1_000, 0);
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        JobResponse first = await(bulkLoadService.submitUpload(
                new MockMultipartFile("file", "c.csv", "text/csv", bytes), 4, 100).jobId());
        JobResponse second = await(bulkLoadService.submitUpload(
                new MockMultipartFile("file", "c.csv", "text/csv", bytes), 4, 100).jobId());

        assertThat(first.successCount()).isEqualTo(1_000);
        assertThat(second.successCount()).isZero();
        assertThat(second.failureCount()).isEqualTo(1_000);
    }

    @Test
    void crud_operations_are_audited_with_field_level_changes() {
        CustomerResponse created = customerService.create(request("audit.test@examplebank.test", new BigDecimal("100.00"), null));
        CustomerResponse updated = customerService.update(created.id(),
                request("audit.test@examplebank.test", new BigDecimal("2500.50"), created.version()));

        assertThat(updated.accountBalance()).isEqualByComparingTo("2500.50");
        assertThat(auditLogRepository.findAll()).anyMatch(a -> a.getAction() == AuditAction.UPDATE
                && String.valueOf(created.id()).equals(a.getEntityId())
                && a.getChanges().contains("accountBalance") && !a.getChanges().contains("firstName"));

        assertThatThrownBy(() -> customerService.create(request("AUDIT.TEST@examplebank.test", BigDecimal.ONE, null)))
                .isInstanceOf(ConflictException.class);
        assertThat(auditLogRepository.findAll()).anyMatch(a -> a.getOutcome() == AuditOutcome.FAILURE);
    }

    private static CustomerRequest request(String email, BigDecimal balance, Long version) {
        return new CustomerRequest(null, "Ada", "Lovelace", email, "+44 207 946 0000", LocalDate.of(1990, 1, 15),
                "12 King St", "London", "England", "EC1 2AB", "United Kingdom", AccountType.SAVINGS, balance,
                new BigDecimal("85000"), 780, KycStatus.VERIFIED, RiskCategory.LOW, CustomerStatus.ACTIVE, "BR001", version);
    }

    private JobResponse await(String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        JobResponse job;
        do {
            Thread.sleep(100);
            job = bulkLoadService.getJob(jobId);
        } while (!job.status().isFinished() && System.currentTimeMillis() < deadline);
        return job;
    }
}
