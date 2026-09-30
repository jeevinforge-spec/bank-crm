package com.bank.crm.dto;

import com.bank.crm.model.AccountType;
import com.bank.crm.model.CustomerStatus;
import com.bank.crm.model.KycStatus;
import com.bank.crm.model.RiskCategory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create/update payload. Field rules live on the {@link com.bank.crm.model.Customer} entity
 * so REST edits and bulk loads are validated by exactly the same constraints.
 */
public record CustomerRequest(
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
        Long version
) {
}
