package com.bank.crm.model;

import jakarta.persistence.*;

@Entity
@Table(name = "bulk_load_error")
public class BulkLoadError {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bulk_load_error_seq_gen")
    @SequenceGenerator(name = "bulk_load_error_seq_gen", sequenceName = "bulk_load_error_seq", allocationSize = 100)
    private Long id;

    @Column(name = "job_id", nullable = false, length = 36)
    private String jobId;

    /** 1-based line number in the source file (header is line 1). */
    @Column(name = "row_num", nullable = false)
    private int rowNumber;

    @Column(name = "customer_number", length = 40)
    private String customerNumber;

    @Column(name = "error_message", nullable = false, length = 500)
    private String errorMessage;

    @Column(name = "raw_data", length = 1000)
    private String rawData;

    protected BulkLoadError() {
    }

    public BulkLoadError(String jobId, int rowNumber, String customerNumber, String errorMessage, String rawData) {
        this.jobId = jobId;
        this.rowNumber = rowNumber;
        this.customerNumber = truncate(customerNumber, 40);
        this.errorMessage = truncate(errorMessage, 500);
        this.rawData = truncate(rawData, 1000);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    public Long getId() { return id; }
    public String getJobId() { return jobId; }
    public int getRowNumber() { return rowNumber; }
    public String getCustomerNumber() { return customerNumber; }
    public String getErrorMessage() { return errorMessage; }
    public String getRawData() { return rawData; }
}
