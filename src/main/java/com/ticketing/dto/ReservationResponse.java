package com.ticketing.dto;

import com.ticketing.domain.Reservation;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID eventId,
        UUID userId,
        String status,
        int seats,
        Instant createdAt
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.getId(), r.getEventId(), r.getUserId(),
                r.getStatus().name(), r.getSeats(), r.getCreatedAt());
    }
}
