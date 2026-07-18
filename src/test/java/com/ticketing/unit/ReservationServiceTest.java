package com.ticketing.unit;

import com.ticketing.domain.*;
import com.ticketing.exception.AccessDeniedOwnershipException;
import com.ticketing.exception.EventSoldOutException;
import com.ticketing.exception.InvalidStateTransitionException;
import com.ticketing.repository.EventRepository;
import com.ticketing.repository.ReservationRepository;
import com.ticketing.service.AuditService;
import com.ticketing.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private EventRepository eventRepository;
    @Mock private AuditService auditService;

    @InjectMocks
    private ReservationService reservationService;

    private User customer;
    private Event publishedEvent;

    @BeforeEach
    void setUp() {
        customer = new User("customer@test.com", "hash", Set.of(Role.CUSTOMER));
        setId(customer, UUID.randomUUID());

        publishedEvent = new Event(UUID.randomUUID(), "Concert", "Arena", null, null, 100);
        setId(publishedEvent, UUID.randomUUID());
        publishedEvent.setPublished(true);
    }

    @Test
    void createReservation_succeeds_whenSeatsAvailable() {
        when(eventRepository.findById(publishedEvent.getId())).thenReturn(Optional.of(publishedEvent));
        when(eventRepository.tryReserveSeats(publishedEvent.getId(), 2)).thenReturn(1); // 1 row updated = success
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            setId(r, UUID.randomUUID()); // simulate DB-generated id on persist
            return r;
        });

        Reservation result = reservationService.createReservation(customer, publishedEvent.getId(), 2, "127.0.0.1", "junit");

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(result.getSeats()).isEqualTo(2);
        verify(eventRepository).tryReserveSeats(publishedEvent.getId(), 2);
        verify(auditService).record(eq(customer.getId()), eq("RESERVATION_CREATED"), eq("Reservation"), any(), any(), any());
    }

    @Test
    void createReservation_throwsSoldOut_whenAtomicUpdateAffectsZeroRows() {
        when(eventRepository.findById(publishedEvent.getId())).thenReturn(Optional.of(publishedEvent));
        when(eventRepository.tryReserveSeats(publishedEvent.getId(), 5)).thenReturn(0); // no rows updated = sold out

        assertThatThrownBy(() ->
                reservationService.createReservation(customer, publishedEvent.getId(), 5, "127.0.0.1", "junit"))
                .isInstanceOf(EventSoldOutException.class);

        verify(reservationRepository, never()).save(any());
        verify(auditService).record(eq(customer.getId()), eq("RESERVATION_REJECTED_SOLD_OUT"), any(), any(), any(), any());
    }

    @Test
    void createReservation_rejectsUnpublishedEvent() {
        publishedEvent.setPublished(false);
        when(eventRepository.findById(publishedEvent.getId())).thenReturn(Optional.of(publishedEvent));

        assertThatThrownBy(() ->
                reservationService.createReservation(customer, publishedEvent.getId(), 1, "127.0.0.1", "junit"))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(eventRepository, never()).tryReserveSeats(any(), anyInt());
    }

    @Test
    void confirm_rejectsCallerWhoDoesNotOwnReservation() {
        Reservation reservation = new Reservation(publishedEvent.getId(), UUID.randomUUID(), 1);
        setId(reservation, UUID.randomUUID());
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.confirm(customer, reservation.getId(), "127.0.0.1", "junit"))
                .isInstanceOf(AccessDeniedOwnershipException.class);
    }

    @Test
    void confirm_rejectsAlreadyConfirmedReservation() {
        Reservation reservation = new Reservation(publishedEvent.getId(), customer.getId(), 1);
        setId(reservation, UUID.randomUUID());
        reservation.setStatus(ReservationStatus.CONFIRMED);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> reservationService.confirm(customer, reservation.getId(), "127.0.0.1", "junit"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cancel_releasesSeatsBackToEvent() {
        Reservation reservation = new Reservation(publishedEvent.getId(), customer.getId(), 3);
        setId(reservation, UUID.randomUUID());
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        Reservation result = reservationService.cancel(customer, reservation.getId(), "127.0.0.1", "junit");

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(eventRepository).releaseSeats(publishedEvent.getId(), 3);
    }

    /** Entities use DB-generated ids; reflection sets them for test fixtures only. */
    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
