package com.bank.crm.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "customer")
public class Customer {

    /**
     * SEQUENCE + pooled allocation (500 ids per DB round trip) keeps Hibernate's
     * JDBC insert batching enabled. IDENTITY would force one INSERT per round trip.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_seq_gen")
    @SequenceGenerator(name = "customer_seq_gen", sequenceName = "customer_seq", allocationSize = 500)
    private Long id;

    @NotBlank
    @Pattern(regexp = "^[A-Z0-9-]{4,20}$", message = "must be 4-20 chars of A-Z, 0-9 or '-'")
    @Column(name = "customer_number", nullable = false, unique = true, length = 20)
    private String customerNumber;

    @NotBlank @Size(max = 60)
    @Column(name = "first_name", nullable = false, length = 60)
    private String firstName;

    @NotBlank @Size(max = 60)
    @Column(name = "last_name", nullable = false, length = 60)
    private String lastName;

    @NotBlank @Email @Size(max = 120)
    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Pattern(regexp = "^\\+?[0-9 -]{7,20}$", message = "must be a valid phone number")
    @Column(length = 20)
    private String phone;

    @NotNull @Past
    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Size(max = 200)
    @Column(name = "address_line", length = 200)
    private String addressLine;

    @Size(max = 80)
    @Column(length = 80)
    private String city;

    @Size(max = 80)
    @Column(length = 80)
    private String state;

    @Size(max = 15)
    @Column(name = "postal_code", length = 15)
    private String postalCode;

    @NotBlank @Size(max = 60)
    @Column(nullable = false, length = 60)
    private String country;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2)
    @Column(name = "account_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal accountBalance = BigDecimal.ZERO;

    @DecimalMin("0.00") @Digits(integer = 16, fraction = 2)
    @Column(name = "annual_income", precision = 18, scale = 2)
    private BigDecimal annualIncome;

    @Min(300) @Max(900)
    @Column(name = "credit_score")
    private Integer creditScore;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    private KycStatus kycStatus = KycStatus.PENDING;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_category", nullable = false, length = 10)
    private RiskCategory riskCategory = RiskCategory.LOW;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_status", nullable = false, length = 20)
    private CustomerStatus customerStatus = CustomerStatus.ACTIVE;

    @NotBlank @Size(max = 10)
    @Column(name = "branch_code", nullable = false, length = 10)
    private String branchCode;

    /** The bulk job that loaded this record, if any (traceability back to the audit trail). */
    @Column(name = "bulk_job_id", length = 36, updatable = false)
    private String bulkJobId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 50)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 50)
    private String updatedBy;

    /** Optimistic locking so two operators can't silently overwrite each other's edits. */
    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (createdBy == null) createdBy = "system";
        if (updatedBy == null) updatedBy = createdBy;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ---- getters / setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCustomerNumber() { return customerNumber; }
    public void setCustomerNumber(String customerNumber) { this.customerNumber = customerNumber; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }
    public BigDecimal getAccountBalance() { return accountBalance; }
    public void setAccountBalance(BigDecimal accountBalance) { this.accountBalance = accountBalance; }
    public BigDecimal getAnnualIncome() { return annualIncome; }
    public void setAnnualIncome(BigDecimal annualIncome) { this.annualIncome = annualIncome; }
    public Integer getCreditScore() { return creditScore; }
    public void setCreditScore(Integer creditScore) { this.creditScore = creditScore; }
    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }
    public RiskCategory getRiskCategory() { return riskCategory; }
    public void setRiskCategory(RiskCategory riskCategory) { this.riskCategory = riskCategory; }
    public CustomerStatus getCustomerStatus() { return customerStatus; }
    public void setCustomerStatus(CustomerStatus customerStatus) { this.customerStatus = customerStatus; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getBulkJobId() { return bulkJobId; }
    public void setBulkJobId(String bulkJobId) { this.bulkJobId = bulkJobId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
