package com.ticketing.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String venue;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private int capacity;

    /**
     * ADR-01 deviation from the literal spec: the spec lists only
     * {id, ownerId, title, venue, startsAt, endsAt, capacity, published, version}.
     * We add `seatsReserved` as a running counter maintained exclusively through
     * a single atomic conditional UPDATE (see EventRepository#tryReserveSeats).
     * Deriving "seats taken" via SUM(reservation.seats) at read time would require
     * either a lock on the whole reservation set or a race window between the
     * SUM and the subsequent INSERT — exactly the oversell bug this case tests for.
     * A denormalized, atomically-updated counter avoids that window entirely.
     */
    @Column(nullable = false)
    private int seatsReserved = 0;

    @Column(nullable = false)
    private boolean published = false;

    /** Optimistic lock for non-capacity fields (title/venue/time edits by the organizer). */
    @Version
    private long version;

    public Event(UUID ownerId, String title, String venue, Instant startsAt, Instant endsAt, int capacity) {
        this.ownerId = ownerId;
        this.title = title;
        this.venue = venue;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.capacity = capacity;
    }

    public int getAvailableSeats() {
        return capacity - seatsReserved;
    }
}
