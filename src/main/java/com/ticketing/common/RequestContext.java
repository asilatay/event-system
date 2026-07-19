package com.ticketing.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Bundles the two request-derived fields (client IP, User-Agent) that nearly
 * every mutating service method threads through to AuditService. Kept as one
 * value object rather than two adjacent String parameters: two same-typed
 * parameters in a row is exactly the shape the compiler cannot catch if a
 * caller accidentally passes them in swapped order.
 */
public record RequestContext(String ip, String userAgent) {

    public static RequestContext from(HttpServletRequest request) {
        return new RequestContext(RequestUtils.clientIp(request), request.getHeader("User-Agent"));
    }
}
