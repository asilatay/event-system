package com.ticketing.unit;

import com.ticketing.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private static final String TEST_SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci1qdW5pdC10ZXN0cy1vbmx5LW5vdC1mb3ItcHJvZHVjdGlvbi11c2U=";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET, 15, 7);
    }

    @Test
    void generatesAccessTokenWithCorrectClaims() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "user@test.com", Set.of("CUSTOMER"));

        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
        assertThat(jwtService.extractEmail(token)).isEqualTo("user@test.com");
        assertThat(jwtService.extractType(token)).isEqualTo("access");
        assertThat(jwtService.extractRoles(token)).containsExactly("CUSTOMER");
    }

    @Test
    void refreshTokenIsMarkedWithRefreshType() {
        String token = jwtService.generateRefreshToken(UUID.randomUUID());
        assertThat(jwtService.extractType(token)).isEqualTo("refresh");
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwtService.generateAccessToken(UUID.randomUUID(), "a@b.com", Set.of("ADMIN"));
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThat(jwtService.isValid(tampered)).isFalse();
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtService otherService = new JwtService(
                "b3RoZXItc2VjcmV0LWtleS1mb3ItanVuaXQtdGVzdHMtb25seS1kaWZmZXJlbnQ=", 15, 7);
        String token = otherService.generateAccessToken(UUID.randomUUID(), "a@b.com", Set.of("ADMIN"));
        assertThat(jwtService.isValid(token)).isFalse();
    }

    @Test
    void sha256ProducesStableHashForSameInput() {
        String h1 = JwtService.sha256("some-refresh-token-value");
        String h2 = JwtService.sha256("some-refresh-token-value");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void sha256ProducesDifferentHashForDifferentInput() {
        String h1 = JwtService.sha256("token-a");
        String h2 = JwtService.sha256("token-b");
        assertThat(h1).isNotEqualTo(h2);
    }
}
