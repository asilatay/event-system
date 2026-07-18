package com.ticketing.repository;

import com.ticketing.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    List<Event> findByOwnerId(UUID ownerId);

    /**
     * ADR-02 — Oversell prevention.
     *
     * This single UPDATE is the ONLY place seatsReserved is ever mutated for a
     * reservation attempt. Correctness relies on two facts:
     *
     *  1. The WHERE clause re-checks capacity in the SAME statement that performs
     *     the increment, so there is no read-then-write gap for another
     *     transaction to interleave in. The database serializes concurrent
     *     UPDATEs to the same row (row-level locking) regardless of the
     *     transaction isolation level configured above READ_UNCOMMITTED.
     *  2. The method returns the number of affected rows. If a competing request
     *     already pushed seatsReserved to the point where this one no longer
     *     fits, 0 rows are affected — no exception, no partial state — and the
     *     service layer treats that as "sold out" and rolls back the reservation.
     *
     * This avoids SELECT ... FOR UPDATE (which holds a lock for the whole
     * transaction and hurts throughput under load) while giving the same
     * correctness guarantee as pessimistic locking, scoped to a single row and
     * a single statement.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Event e
            SET e.seatsReserved = e.seatsReserved + :seats
            WHERE e.id = :eventId
              AND e.published = true
              AND (e.seatsReserved + :seats) <= e.capacity
            """)
    int tryReserveSeats(@Param("eventId") UUID eventId, @Param("seats") int seats);

    /**
     * Used by cancel/confirm-rollback flows to release seats back to the pool.
     * Also a single atomic statement; no read-then-write gap.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Event e
            SET e.seatsReserved = e.seatsReserved - :seats
            WHERE e.id = :eventId
              AND e.seatsReserved - :seats >= 0
            """)
    int releaseSeats(@Param("eventId") UUID eventId, @Param("seats") int seats);

    @Query("""
            SELECT e FROM Event e
            WHERE e.published = true
              AND (:from IS NULL OR e.startsAt >= :from)
              AND (:to IS NULL OR e.endsAt <= :to)
              AND (:q IS NULL OR LOWER(e.title) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(e.venue) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    List<Event> searchPublic(@Param("from") Instant from, @Param("to") Instant to, @Param("q") String q);
}
