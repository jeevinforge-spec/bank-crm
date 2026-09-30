package com.bank.crm.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is acting and from where. There is no login in this showcase, so the UI sends the
 * operator name in an {@code X-User} header. Background (bulk-load) threads have no request,
 * so they capture these values up front and pass them along explicitly.
 */
public final class RequestContext {

    public static final String USER_HEADER = "X-User";
    private static final String DEFAULT_USER = "system";

    private RequestContext() {
    }

    public static String currentUser() {
        HttpServletRequest req = currentRequest();
        if (req == null) return DEFAULT_USER;
        String user = req.getHeader(USER_HEADER);
        if (user == null || user.isBlank()) return DEFAULT_USER;
        user = user.trim().replaceAll("[^A-Za-z0-9._@ -]", "");
        if (user.isEmpty()) return DEFAULT_USER;
        return user.length() > 50 ? user.substring(0, 50) : user;
    }

    public static String clientIp() {
        HttpServletRequest req = currentRequest();
        return req == null ? null : req.getRemoteAddr();
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }
}
