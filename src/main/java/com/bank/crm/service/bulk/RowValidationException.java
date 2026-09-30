package com.bank.crm.service.bulk;

/** A single CSV row is invalid. Recorded as a row error; never aborts the job. */
public class RowValidationException extends RuntimeException {
    public RowValidationException(String message) {
        super(message, null, false, false); // no stack trace: this is expected, high-volume control flow
    }
}
