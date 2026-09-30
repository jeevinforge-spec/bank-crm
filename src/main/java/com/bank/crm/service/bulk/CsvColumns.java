package com.bank.crm.service.bulk;

import java.util.List;

/** The CSV contract for bulk customer files. Header names are matched case-insensitively. */
public final class CsvColumns {

    public static final String CUSTOMER_NUMBER = "customer_number";
    public static final String FIRST_NAME = "first_name";
    public static final String LAST_NAME = "last_name";
    public static final String EMAIL = "email";
    public static final String PHONE = "phone";
    public static final String DATE_OF_BIRTH = "date_of_birth";
    public static final String ADDRESS_LINE = "address_line";
    public static final String CITY = "city";
    public static final String STATE = "state";
    public static final String POSTAL_CODE = "postal_code";
    public static final String COUNTRY = "country";
    public static final String ACCOUNT_TYPE = "account_type";
    public static final String ACCOUNT_BALANCE = "account_balance";
    public static final String ANNUAL_INCOME = "annual_income";
    public static final String CREDIT_SCORE = "credit_score";
    public static final String KYC_STATUS = "kyc_status";
    public static final String RISK_CATEGORY = "risk_category";
    public static final String CUSTOMER_STATUS = "customer_status";
    public static final String BRANCH_CODE = "branch_code";

    public static final List<String> ALL = List.of(CUSTOMER_NUMBER, FIRST_NAME, LAST_NAME, EMAIL, PHONE,
            DATE_OF_BIRTH, ADDRESS_LINE, CITY, STATE, POSTAL_CODE, COUNTRY, ACCOUNT_TYPE, ACCOUNT_BALANCE,
            ANNUAL_INCOME, CREDIT_SCORE, KYC_STATUS, RISK_CATEGORY, CUSTOMER_STATUS, BRANCH_CODE);

    public static final List<String> REQUIRED = List.of(CUSTOMER_NUMBER, FIRST_NAME, LAST_NAME, EMAIL,
            DATE_OF_BIRTH, COUNTRY, ACCOUNT_TYPE, BRANCH_CODE);

    private CsvColumns() {
    }

    public static String normalize(String header) {
        return header == null ? "" : header.trim().toLowerCase().replace(' ', '_').replace("﻿", "");
    }
}
