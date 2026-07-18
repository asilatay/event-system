package com.ticketing.dto;

import com.ticketing.domain.Event;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(
        UUID id,
        UUID ownerId,
        String title,
        String venue,
        Instant startsAt,
        Instant endsAt,
        int capacity,
        int availableSeats,
        boolean published,
        long version
) {
    public static EventResponse from(Event e) {
        return new EventResponse(
                e.getId(), e.getOwnerId(), e.getTitle(), e.getVenue(),
                e.getStartsAt(), e.getEndsAt(), e.getCapacity(),
                e.getAvailableSeats(), e.isPublished(), e.getVersion());
    }
}
