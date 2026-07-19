package com.ticketing.service;

import com.ticketing.domain.IdempotencyKey;
import com.ticketing.repository.IdempotencyKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Pulled out of IdempotencyService as its own Spring bean specifically so that
 * @Transactional(REQUIRES_NEW) is applied through the proxy. Calling a
 * @Transactional method on `this` from within the same class bypasses the
 * proxy entirely (self-invocation) and the propagation setting would be
 * silently ignored — a classic and easy-to-miss Spring AOP pitfall. Splitting
 * the transactional boundary into a distinct bean, invoked from the outside,
 * guarantees each step below actually commits independently as designed.
 */
@Service
public class IdempotencyTransactionalOps {

    private final IdempotencyKeyRepository repository;
    private final long ttlHours;

    public IdempotencyTransactionalOps(IdempotencyKeyRepository repository,
                                        @Value("${app.idempotency.ttl-hours}") long ttlHours) {
        this.repository = repository;
        this.ttlHours = ttlHours;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyKey beginRecord(String key, String endpoint, UUID callerId, String requestHash) {
        IdempotencyKey record = new IdempotencyKey(key, endpoint, callerId, requestHash,
                Instant.now().plus(ttlHours, ChronoUnit.HOURS));
        return repository.saveAndFlush(record); // flush forces the unique-constraint check now
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRecord(UUID id, String responseJson) {
        IdempotencyKey record = repository.findById(id).orElseThrow();
        record.setStatus(IdempotencyKey.IdempotencyStatus.COMPLETED);
        record.setResponseBody(responseJson);
        record.setResponseStatus(200); // placeholder only: never read back, see IdempotencyKey#responseStatus
        repository.save(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRecord(UUID id) {
        repository.findById(id).ifPresent(record -> {
            record.setStatus(IdempotencyKey.IdempotencyStatus.FAILED);
            repository.save(record);
        });
    }
}
