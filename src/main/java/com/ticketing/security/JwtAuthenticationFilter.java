package com.ticketing.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Stateless JWT auth: extracts Bearer token, validates signature+expiry+type ("access"),
 * and populates the SecurityContext directly from the token's claims — no DB lookup
 * on the hot path. Rejects "refresh" type tokens presented as access tokens, which
 * closes a common vulnerability where a leaked refresh token could otherwise be used
 * directly against protected resource endpoints.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7); // strip the "Bearer " prefix (7 chars)
        try {
            if (!jwtService.isValid(token)) {
                filterChain.doFilter(request, response);
                return;
            }
            if (!"access".equals(jwtService.extractType(token))) {
                // A refresh token must never authenticate a resource request.
                filterChain.doFilter(request, response);
                return;
            }

            String email = jwtService.extractEmail(token);
            List<String> roles = jwtService.extractRoles(token);

            var authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();

            var authToken = new UsernamePasswordAuthenticationToken(email, null, authorities);
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);

        } catch (JwtException | IllegalArgumentException e) {
            // Invalid/tampered token: leave context unauthenticated; downstream
            // authorization will reject with 401/403 as appropriate. We deliberately
            // do not leak parsing details to the client.
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
