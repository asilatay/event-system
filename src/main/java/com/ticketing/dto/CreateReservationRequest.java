package com.ticketing.dto;

import jakarta.validation.constraints.Min;

public record CreateReservationRequest(
        @Min(1) int seats
) {}
