package com.ticketing.common;

import jakarta.servlet.http.HttpServletRequest;

/** Stateless HTTP request helpers, independent of who/what is making the request. */
public final class RequestUtils {

    private RequestUtils() {
    }

    public static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        return forwardedFor != null ? forwardedFor.split(",")[0].trim() : request.getRemoteAddr();
    }
}
