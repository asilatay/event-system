package com.ticketing.repository;

import com.ticketing.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
    Optional<IdempotencyKey> findByKeyAndEndpointAndCallerId(String key, String endpoint, UUID callerId);
}
