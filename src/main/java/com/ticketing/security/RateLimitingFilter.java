package com.ticketing.security;

import com.ticketing.common.RequestUtils;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR-04 — Rate limiting.
 *
 * Two separate buckets per client key (IP, or user id once authenticated):
 *  - auth endpoints (/api/auth/**): tight limit — this is where credential
 *    stuffing / brute force happens.
 *  - reservation creation (/api/events/{eventId}/reservations): tighter limit than
 *    general read traffic, since this is the write path that contends for
 *    the oversell guard and is the most likely target of scripted abuse
 *    ("grab all the tickets").
 *
 * In-memory ConcurrentHashMap<key, Bucket> is adequate for a single-instance
 * case-study deployment. For a real multi-instance production deployment this
 * would move to a shared store (Redis via bucket4j-redis) so limits are
 * enforced cluster-wide rather than per-node.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, Bucket> authBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> reservationBuckets = new ConcurrentHashMap<>();

    private final int authCapacity;
    private final int authRefillPerMinute;
    private final int reservationCapacity;
    private final int reservationRefillPerMinute;

    public RateLimitingFilter(
            @Value("${app.rate-limit.auth-endpoints.capacity}") int authCapacity,
            @Value("${app.rate-limit.auth-endpoints.refill-per-minute}") int authRefillPerMinute,
            @Value("${app.rate-limit.reservation-endpoints.capacity}") int reservationCapacity,
            @Value("${app.rate-limit.reservation-endpoints.refill-per-minute}") int reservationRefillPerMinute) {
        this.authCapacity = authCapacity;
        this.authRefillPerMinute = authRefillPerMinute;
        this.reservationCapacity = reservationCapacity;
        this.reservationRefillPerMinute = reservationRefillPerMinute;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String clientKey = clientKey(request);

        Bucket bucket = null;
        if (path.startsWith("/api/auth/")) {
            bucket = authBuckets.computeIfAbsent(clientKey, k -> newBucket(authCapacity, authRefillPerMinute));
        } else if (path.matches("^/api/events/[^/]+/reservations$")) {
            bucket = reservationBuckets.computeIfAbsent(clientKey, k -> newBucket(reservationCapacity, reservationRefillPerMinute));
        }

        if (bucket != null && !bucket.tryConsume(1)) {
            // Can't throw RateLimitExceededException + let GlobalExceptionHandler map it:
            // this filter runs before the DispatcherServlet, outside @RestControllerAdvice's reach.
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"type":"https://ticketing.example.com/errors/rate_limit_exceeded",
                     "title":"RATE_LIMIT_EXCEEDED",
                     "status":429,
                     "detail":"Too many requests, please try again later"}
                    """);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Bucket newBucket(int capacity, int refillPerMinute) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(refillPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    /** Prefer authenticated principal once available; fall back to remote IP pre-auth. */
    private String clientKey(HttpServletRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        // auth is never null here: Spring Security's AnonymousAuthenticationFilter always
        // fills the context with a principal literally named "anonymousUser" pre-login.
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return "ip:" + RequestUtils.clientIp(request);
    }
}
