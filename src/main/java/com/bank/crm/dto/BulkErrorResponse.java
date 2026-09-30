package com.bank.crm.dto;

import com.bank.crm.model.BulkLoadError;

public record BulkErrorResponse(int rowNumber, String customerNumber, String errorMessage, String rawData) {

    public static BulkErrorResponse from(BulkLoadError e) {
        return new BulkErrorResponse(e.getRowNumber(), e.getCustomerNumber(), e.getErrorMessage(), e.getRawData());
    }
}
