package com.ticketing.repository;

import com.ticketing.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomic conditional UPDATE, same pattern as EventRepository#tryReserveSeats
     * (ADR-02): the "is this token still usable" check and the revocation happen
     * in one statement, so two concurrent refresh calls with the same token cannot
     * both read revoked=false before either commits. Only one call can ever flip
     * this row from unrevoked to revoked; the loser's affected-row-count is 0,
     * which the service treats as an invalid/already-used token rather than
     * racing to hand out two independent token pairs from one refresh token.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE RefreshToken t
            SET t.revoked = true
            WHERE t.tokenHash = :tokenHash AND t.revoked = false AND t.expiresAt > :now
            """)
    int tryRevoke(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
}
