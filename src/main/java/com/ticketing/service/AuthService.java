package com.ticketing.service;

import com.ticketing.domain.RefreshToken;
import com.ticketing.domain.Role;
import com.ticketing.domain.User;
import com.ticketing.dto.AuthResponse;
import com.ticketing.dto.LoginRequest;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.exception.DuplicateEmailException;
import com.ticketing.exception.InvalidCredentialsException;
import com.ticketing.exception.InvalidRefreshTokenException;
import com.ticketing.repository.RefreshTokenRepository;
import com.ticketing.repository.UserRepository;
import com.ticketing.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final long accessTokenTtlMinutes;

    public AuthService(UserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        AuditService auditService,
                        org.springframework.core.env.Environment env) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.accessTokenTtlMinutes = Long.parseLong(env.getProperty("app.jwt.access-token-ttl-minutes", "15"));
    }

    @Transactional
    public User register(RegisterRequest request, String ip, String userAgent) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }

        // Unrecognized role names aren't caught by @NotEmpty on the DTO; they fail here
        // with an IllegalArgumentException, surfaced as a generic 400 by GlobalExceptionHandler.
        Set<Role> roles = request.roles().stream()
                .map(String::toUpperCase)
                .map(Role::valueOf)
                .collect(Collectors.toSet());

        // BCrypt hash only; the plaintext password never touches the DB, logs, or audit trail.
        User user = new User(request.email(), passwordEncoder.encode(request.password()), roles);
        user = userRepository.save(user);

        auditService.record(user.getId(), "USER_REGISTERED", "User", user.getId().toString(), ip, userAgent);
        return user;
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ip, String userAgent) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> {
                    // Same exception for "no such user" and "wrong password" -
                    // never reveal which one it was (prevents email enumeration).
                    auditService.record(null, "LOGIN_FAILED", "User", request.email(), ip, userAgent);
                    return new InvalidCredentialsException();
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.record(user.getId(), "LOGIN_FAILED", "User", user.getId().toString(), ip, userAgent);
            throw new InvalidCredentialsException();
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        AuthResponse tokens = issueTokenPair(user);
        auditService.record(user.getId(), "LOGIN_SUCCESS", "User", user.getId().toString(), ip, userAgent);
        return tokens;
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenJwt, String ip, String userAgent) {
        if (!jwtService.isValid(refreshTokenJwt) || !"refresh".equals(safeExtractType(refreshTokenJwt))) {
            throw new InvalidRefreshTokenException();
        }

        String hash = JwtService.sha256(refreshTokenJwt);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);

        // Rotation: the old refresh token is revoked as soon as it is used once.
        // If a stolen refresh token is replayed after the legitimate client already
        // rotated it, this revocation causes the replay to fail immediately.
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        AuthResponse tokens = issueTokenPair(user);
        auditService.record(user.getId(), "TOKEN_REFRESHED", "User", user.getId().toString(), ip, userAgent);
        return tokens;
    }

    private AuthResponse issueTokenPair(User user) {
        Set<String> roleNames = user.getRoles().stream().map(Enum::name).collect(Collectors.toSet());
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), roleNames);
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        RefreshToken record = new RefreshToken(
                user.getId(),
                JwtService.sha256(refreshToken),
                jwtService.extractExpiration(refreshToken));
        refreshTokenRepository.save(record);

        return AuthResponse.of(accessToken, refreshToken, accessTokenTtlMinutes * 60);
    }

    private String safeExtractType(String token) {
        try {
            return jwtService.extractType(token);
        } catch (Exception e) {
            return null;
        }
    }
}
