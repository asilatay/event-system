package com.ticketing.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADR-03 — JWT strategy.
 *
 * Access token: short-lived (15 min default), stateless, carries roles as a claim
 * so authorization checks never need a DB round trip per request.
 *
 * Refresh token: longer-lived (7 days default), but NOT stored as a bare JWT
 * anywhere server-side. Only its SHA-256 hash is persisted (see RefreshTokenStore),
 * mirroring the same principle as password hashing: if the store leaks, the
 * tokens inside it are not directly usable. This also lets us revoke a refresh
 * token (logout, compromise) without needing a distributed JWT blacklist.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey signingKey;
    private final long accessTokenTtlMinutes;
    private final long refreshTokenTtlDays;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes,
            @Value("${app.jwt.refresh-token-ttl-days}") long refreshTokenTtlDays) {
        // No fallback to a fixed, source-committed secret: a default that "just works"
        // is exactly the failure mode where a deployment silently forgets to set
        // JWT_SECRET and ends up signing tokens with a value anyone can read on GitHub.
        // Instead, generate a random key for this process only when unset, so local/demo
        // runs still start with zero config, but tokens never survive a restart and no
        // fixed secret ever exists to leak. A real deployment must set JWT_SECRET.
        if (secret == null || secret.isBlank()) {
            byte[] randomKeyBytes = new byte[64];
            new SecureRandom().nextBytes(randomKeyBytes);
            this.signingKey = Keys.hmacShaKeyFor(randomKeyBytes);
            log.warn("app.jwt.secret (JWT_SECRET) is not set - generated a random signing key "
                    + "for this run. Tokens will not survive a restart. Set JWT_SECRET explicitly "
                    + "for any deployment that needs to persist sessions across restarts.");
        } else {
            // Secret is base64-encoded in config; decode to raw key bytes for HMAC-SHA256.
            this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        }
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    public String generateAccessToken(UUID userId, String email, Set<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("roles", roles)
                .claim("type", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtlMinutes, ChronoUnit.MINUTES)))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact();
    }

    /** Raw refresh token string (a random JWT with no sensitive claims beyond subject). */
    public String generateRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenTtlDays, ChronoUnit.DAYS)))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact();
    }

    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parseAndValidate(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isExpired(String token) {
        try {
            return parseAndValidate(token).getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseAndValidate(token).getSubject());
    }

    public String extractEmail(String token) {
        return parseAndValidate(token).get("email", String.class);
    }

    // Comes back as a List even though generateAccessToken puts in a Set: JJWT/Jackson
    // serialize the claim as a JSON array, and JSON arrays always deserialize to List.
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        return (List<String>) parseAndValidate(token).get("roles", List.class);
    }

    public String extractType(String token) {
        return parseAndValidate(token).get("type", String.class);
    }

    public Instant extractExpiration(String token) {
        return parseAndValidate(token).getExpiration().toInstant();
    }

    /** SHA-256 hash for storing/looking up refresh tokens without ever persisting the raw JWT. */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
