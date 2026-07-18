package com.ticketing.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_key",
        uniqueConstraints = @UniqueConstraint(columnNames = {"key_value", "endpoint"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Client-supplied Idempotency-Key header value. */
    @Column(name = "key_value", nullable = false)
    private String key;

    /** e.g. "POST /api/events/{id}/reservations" — same key may be reused across different endpoints. */
    @Column(nullable = false)
    private String endpoint;

    /** SHA-256 of the normalized request body, to detect key-reuse-with-different-payload. */
    @Column(nullable = false)
    private String requestHash;

    /** Serialized JSON of the response body, replayed verbatim on retry. */
    @Lob
    private String responseBody;

    private Integer responseStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyStatus status = IdempotencyStatus.IN_PROGRESS;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Records older than this are eligible for cleanup / can be safely reused. */
    @Column(nullable = false)
    private Instant expiresAt;

    public IdempotencyKey(String key, String endpoint, String requestHash, Instant expiresAt) {
        this.key = key;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.expiresAt = expiresAt;
    }

    public enum IdempotencyStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}
