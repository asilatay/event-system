package com.ticketing.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateEventRequest(
        @NotBlank String title,
        @NotBlank String venue,
        @NotNull @Future Instant startsAt,
        @NotNull Instant endsAt,
        @Min(1) int capacity
) {}
