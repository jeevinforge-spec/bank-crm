package com.bank.crm.service.bulk;

import com.bank.crm.model.*;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.bank.crm.service.bulk.CsvColumns.*;

/**
 * Converts a raw CSV row into a validated {@link Customer}. Stateless and thread-safe:
 * called concurrently from every bulk-load worker thread.
 */
@Component
public class CustomerRowMapper {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,           // 1985-04-23
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),  // 23/04/1985
            DateTimeFormatter.ofPattern("dd-MM-yyyy")); // 23-04-1985

    private final Validator validator; // Hibernate Validator instances are thread-safe

    public CustomerRowMapper(Validator validator) {
        this.validator = validator;
    }

    public Customer map(RawRow row, String jobId, String loadedBy) {
        Customer c = new Customer();
        c.setCustomerNumber(upper(row.get(CUSTOMER_NUMBER)));
        c.setFirstName(row.get(FIRST_NAME));
        c.setLastName(row.get(LAST_NAME));
        c.setEmail(lower(row.get(EMAIL)));
        c.setPhone(row.get(PHONE));
        c.setDateOfBirth(parseDate(row.get(DATE_OF_BIRTH)));
        c.setAddressLine(row.get(ADDRESS_LINE));
        c.setCity(row.get(CITY));
        c.setState(row.get(STATE));
        c.setPostalCode(row.get(POSTAL_CODE));
        c.setCountry(row.get(COUNTRY));
        c.setAccountType(parseEnum(AccountType.class, ACCOUNT_TYPE, row.get(ACCOUNT_TYPE), null));
        BigDecimal balance = parseDecimal(ACCOUNT_BALANCE, row.get(ACCOUNT_BALANCE));
        c.setAccountBalance(balance == null ? BigDecimal.ZERO : balance);
        c.setAnnualIncome(parseDecimal(ANNUAL_INCOME, row.get(ANNUAL_INCOME)));
        c.setCreditScore(parseInt(CREDIT_SCORE, row.get(CREDIT_SCORE)));
        c.setKycStatus(parseEnum(KycStatus.class, KYC_STATUS, row.get(KYC_STATUS), KycStatus.PENDING));
        c.setRiskCategory(parseEnum(RiskCategory.class, RISK_CATEGORY, row.get(RISK_CATEGORY), RiskCategory.LOW));
        c.setCustomerStatus(parseEnum(CustomerStatus.class, CUSTOMER_STATUS, row.get(CUSTOMER_STATUS), CustomerStatus.ACTIVE));
        c.setBranchCode(upper(row.get(BRANCH_CODE)));
        c.setBulkJobId(jobId);
        c.setCreatedBy(loadedBy);
        c.setUpdatedBy(loadedBy);

        Set<ConstraintViolation<Customer>> violations = validator.validate(c);
        if (!violations.isEmpty()) {
            throw new RowValidationException(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; ")));
        }
        return c;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, f);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        throw new RowValidationException("dateOfBirth '" + value + "' is not a valid date (use yyyy-MM-dd)");
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String column, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase().replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new RowValidationException(column + " '" + value + "' is not one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private static BigDecimal parseDecimal(String column, String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new RowValidationException(column + " '" + value + "' is not a number");
        }
    }

    private static Integer parseInt(String column, String value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new RowValidationException(column + " '" + value + "' is not a whole number");
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }
}
