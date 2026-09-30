package com.bank.crm.dto;

import com.bank.crm.model.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CustomerResponse(
        Long id,
        String customerNumber,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDate dateOfBirth,
        String addressLine,
        String city,
        String state,
        String postalCode,
        String country,
        AccountType accountType,
        BigDecimal accountBalance,
        BigDecimal annualIncome,
        Integer creditScore,
        KycStatus kycStatus,
        RiskCategory riskCategory,
        CustomerStatus customerStatus,
        String branchCode,
        String bulkJobId,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy,
        Long version
) {
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(c.getId(), c.getCustomerNumber(), c.getFirstName(), c.getLastName(),
                c.getEmail(), c.getPhone(), c.getDateOfBirth(), c.getAddressLine(), c.getCity(), c.getState(),
                c.getPostalCode(), c.getCountry(), c.getAccountType(), c.getAccountBalance(), c.getAnnualIncome(),
                c.getCreditScore(), c.getKycStatus(), c.getRiskCategory(), c.getCustomerStatus(), c.getBranchCode(),
                c.getBulkJobId(), c.getCreatedAt(), c.getUpdatedAt(), c.getCreatedBy(), c.getUpdatedBy(), c.getVersion());
    }
}
