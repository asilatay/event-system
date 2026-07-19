package com.ticketing.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Without this, Spring Security's default fallback AuthenticationEntryPoint
 * (resolved only from configured mechanisms like httpBasic/formLogin — neither
 * of which this stateless JWT API uses) is Http403ForbiddenEntryPoint, meaning
 * a request with NO token at all would come back as 403 rather than 401. That
 * conflates "you're not who you claim to be" (401) with "you're recognized but
 * not allowed" (403) — a real distinction for API consumers, not just a status
 * code preference. This entry point makes the unauthenticated case explicit.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws java.io.IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication is required to access this resource");
        pd.setTitle("UNAUTHENTICATED");
        pd.setProperty("timestamp", Instant.now());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(objectMapper.writeValueAsString(pd));
    }
}
