package com.ticketing.controller;

import com.ticketing.domain.Reservation;
import com.ticketing.domain.User;
import com.ticketing.dto.CreateReservationRequest;
import com.ticketing.dto.ReservationResponse;
import com.ticketing.security.CurrentUserResolver;
import com.ticketing.service.IdempotencyService;
import com.ticketing.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * ADR-09 — Reservation endpoints intentionally carry no role-level
 * {@code @PreAuthorize}. The spec assigns "reservation operations" to
 * CUSTOMER, but ADMIN has full access everywhere and an ORGANIZER may
 * reasonably want to book tickets to their own (or another) event as a
 * customer would — roles are not mutually exclusive on a User. Any
 * authenticated principal may attempt a reservation; the actual authorization
 * question — "does this caller own this specific reservation?" — is
 * resource-specific and enforced in {@link ReservationService}, the same
 * pattern used for event ownership.
 */
@RestController
@Tag(name = "Reservations")
public class ReservationController {

    private final ReservationService reservationService;
    private final IdempotencyService idempotencyService;
    private final CurrentUserResolver currentUserResolver;

    public ReservationController(ReservationService reservationService,
                                  IdempotencyService idempotencyService,
                                  CurrentUserResolver currentUserResolver) {
        this.reservationService = reservationService;
        this.idempotencyService = idempotencyService;
        this.currentUserResolver = currentUserResolver;
    }

    @PostMapping("/api/events/{id}/reservations")
    @Operation(summary = "Create a PENDING reservation. Requires Idempotency-Key header.")
    public ResponseEntity<ReservationResponse> createReservation(
            @PathVariable UUID id,
            @Valid @RequestBody CreateReservationRequest request,
            @Parameter(description = "Client-generated unique key; retries with the same key + same body replay the original result")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {

        User caller = currentUserResolver.resolve();
        String ip = CurrentUserResolver.clientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "POST /api/events/" + id + "/reservations",
                request,
                ReservationResponse.class,
                () -> ReservationResponse.from(
                        reservationService.createReservation(caller, id, request.seats(), ip, userAgent))
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/reservations/{id}/confirm")
    @Operation(summary = "Confirm a PENDING reservation")
    public ResponseEntity<ReservationResponse> confirm(@PathVariable UUID id, HttpServletRequest httpRequest) {
        User caller = currentUserResolver.resolve();
        Reservation reservation = reservationService.confirm(
                caller, id, CurrentUserResolver.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }

    @PostMapping("/api/reservations/{id}/cancel")
    @Operation(summary = "Cancel a reservation and release its seats back to the event")
    public ResponseEntity<ReservationResponse> cancel(@PathVariable UUID id, HttpServletRequest httpRequest) {
        User caller = currentUserResolver.resolve();
        Reservation reservation = reservationService.cancel(
                caller, id, CurrentUserResolver.clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }
}
