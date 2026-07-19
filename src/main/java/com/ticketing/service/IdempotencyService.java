package com.ticketing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.domain.IdempotencyKey;
import com.ticketing.exception.IdempotencyConflictException;
import com.ticketing.exception.IdempotencyKeyRequiredException;
import com.ticketing.repository.IdempotencyKeyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ADR-06 - Idempotency implementation.
 *
 * The race-safety comes from relying on the DB unique constraint on
 * (key_value, endpoint, caller_id) rather than a "check-then-insert" in application
 * code. caller_id is part of the constraint (not just key+endpoint) so that two
 * different callers who happen to submit the same key with the same body never
 * collide on one row and get handed each other's response:
 *
 *  1. beginRecord() tries to INSERT a new row with status=IN_PROGRESS in its
 *     OWN committed transaction (REQUIRES_NEW, in IdempotencyTransactionalOps),
 *     so the row becomes visible to other transactions immediately, before the
 *     (possibly slow) business action runs.
 *  2. If two requests race with the same key, only one INSERT wins; the other
 *     fails with a unique constraint violation, which we catch and treat as
 *     "someone else owns this key" - we then load the existing row and act on
 *     its actual status (replay, conflict, or reject) rather than guessing.
 *  3. On success we UPDATE the row to COMPLETED with the serialized response
 *     (also its own transaction), so a subsequent retry with the same key can
 *     replay that exact response without re-running the business logic.
 *  4. On business-logic failure we mark the row FAILED so a legitimate retry
 *     (same key, e.g. after a transient 5xx) is allowed to try again - a
 *     failed attempt must not permanently "burn" the key.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final IdempotencyTransactionalOps txOps;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyRepository repository,
                               IdempotencyTransactionalOps txOps,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.txOps = txOps;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(String idempotencyKey, String endpoint, UUID callerId, Object requestPayload,
                          Class<T> responseType, Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }

        String requestHash = hash(requestPayload);
        IdempotencyKey record;
        try {
            record = txOps.beginRecord(idempotencyKey, endpoint, callerId, requestHash);
        } catch (DataIntegrityViolationException raceLost) {
            return handleExisting(idempotencyKey, endpoint, callerId, requestHash, responseType);
        }

        try {
            T result = action.get();
            txOps.completeRecord(record.getId(), serialize(result));
            return result;
        } catch (RuntimeException ex) {
            txOps.failRecord(record.getId());
            throw ex;
        }
    }

    private <T> T handleExisting(String key, String endpoint, UUID callerId, String requestHash, Class<T> responseType) {
        // Scoped to (key, endpoint, callerId): a different caller reusing the same key
        // and body finds no row here (their own beginRecord() would have inserted a
        // distinct one instead of colliding), so they can never be handed this caller's
        // stored response.
        IdempotencyKey existing = repository.findByKeyAndEndpointAndCallerId(key, endpoint, callerId)
                .orElseThrow(() -> new IdempotencyConflictException(
                        "Idempotency-Key conflict could not be resolved; please retry"));

        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key '" + key + "' was already used with a different request payload");
        }

        return switch (existing.getStatus()) {
            case COMPLETED -> deserialize(existing.getResponseBody(), responseType);
            case IN_PROGRESS -> throw new IdempotencyConflictException(
                    "A request with this Idempotency-Key is already being processed");
            case FAILED -> throw new IdempotencyConflictException(
                    "The previous request with this Idempotency-Key failed; retry with a new key");
        };
    }

    private String hash(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash idempotency request payload", e);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotent response", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize stored idempotent response", e);
        }
    }
}
