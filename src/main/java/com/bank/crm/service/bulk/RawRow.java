package com.bank.crm.service.bulk;

import java.util.Map;

/**
 * One CSV line as read by the coordinator thread. Parsing into a {@code Customer} (and
 * validation) is deferred to the worker threads so that CPU work is parallelised too.
 */
public record RawRow(int lineNumber, Map<String, String> values, String raw) {

    public String get(String column) {
        String v = values.get(column);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }
}
