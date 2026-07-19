package com.ticketing.service;

import com.ticketing.common.RequestContext;
import com.ticketing.domain.Event;
import com.ticketing.domain.Reservation;
import com.ticketing.domain.ReservationStatus;
import com.ticketing.domain.Role;
import com.ticketing.domain.User;
import com.ticketing.exception.AccessDeniedOwnershipException;
import com.ticketing.exception.EventSoldOutException;
import com.ticketing.exception.InvalidStateTransitionException;
import com.ticketing.exception.ResourceNotFoundException;
import com.ticketing.repository.EventRepository;
import com.ticketing.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final EventRepository eventRepository;
    private final AuditService auditService;

    public ReservationService(ReservationRepository reservationRepository,
                               EventRepository eventRepository,
                               AuditService auditService) {
        this.reservationRepository = reservationRepository;
        this.eventRepository = eventRepository;
        this.auditService = auditService;
    }

    /**
     * ADR-02 in action: the atomic conditional UPDATE (tryReserveSeats) is the
     * ONLY thing standing between this method and an oversold event under
     * concurrent load. Everything else here (existence/published checks) is a
     * fast-fail for the common case; the actual correctness guarantee lives in
     * the repository query, not in this method's control flow.
     */
    @Transactional
    public Reservation createReservation(User caller, UUID eventId, int seats, RequestContext ctx) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));

        if (!event.isPublished()) {
            throw new InvalidStateTransitionException("Cannot reserve seats for an unpublished event");
        }

        int updatedRows = eventRepository.tryReserveSeats(eventId, seats);
        if (updatedRows == 0) {
            auditService.record(caller.getId(), "RESERVATION_REJECTED_SOLD_OUT", "Event", eventId.toString(), ctx);
            throw new EventSoldOutException(eventId.toString());
        }

        Reservation reservation = new Reservation(eventId, caller.getId(), seats);
        reservation = reservationRepository.save(reservation);

        auditService.record(caller.getId(), "RESERVATION_CREATED", "Reservation", reservation.getId().toString(), ctx);
        return reservation;
    }

    @Transactional
    public Reservation confirm(User caller, UUID reservationId, RequestContext ctx) {
        Reservation reservation = getOrThrow(reservationId);
        assertReservationOwnerOrAdmin(caller, reservation);

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "Only a PENDING reservation can be confirmed (current: " + reservation.getStatus() + ")");
        }

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation = reservationRepository.save(reservation);

        auditService.record(caller.getId(), "RESERVATION_CONFIRMED", "Reservation", reservation.getId().toString(), ctx);
        return reservation;
    }

    @Transactional
    public Reservation cancel(User caller, UUID reservationId, RequestContext ctx) {
        Reservation reservation = getOrThrow(reservationId);
        assertReservationOwnerOrEventOwnerOrAdmin(caller, reservation);

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            throw new InvalidStateTransitionException("Reservation is already cancelled");
        }

        // Release the seats this reservation was holding, whether it was PENDING
        // or CONFIRMED — both states represent seats taken out of the pool.
        eventRepository.releaseSeats(reservation.getEventId(), reservation.getSeats());

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation = reservationRepository.save(reservation);

        auditService.record(caller.getId(), "RESERVATION_CANCELLED", "Reservation", reservation.getId().toString(), ctx);
        return reservation;
    }

    @Transactional(readOnly = true)
    public Reservation getOrThrow(UUID reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));
    }

    private void assertReservationOwnerOrAdmin(User caller, Reservation reservation) {
        boolean isAdmin = caller.getRoles().contains(Role.ADMIN);
        boolean isOwner = reservation.getUserId().equals(caller.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedOwnershipException("You do not own this reservation");
        }
    }

    private void assertReservationOwnerOrEventOwnerOrAdmin(User caller, Reservation reservation) {
        boolean isAdmin = caller.getRoles().contains(Role.ADMIN);
        boolean isReservationOwner = reservation.getUserId().equals(caller.getId());
        if (isAdmin || isReservationOwner) {
            return;
        }
        // Organizer may cancel a reservation on their own event (e.g. handling a
        // customer support request) without being the customer themselves.
        Event event = eventRepository.findById(reservation.getEventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event", reservation.getEventId()));
        if (!event.getOwnerId().equals(caller.getId())) {
            throw new AccessDeniedOwnershipException("You do not have permission to cancel this reservation");
        }
    }
}
