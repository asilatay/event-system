package com.ticketing.service;

import com.ticketing.domain.Event;
import com.ticketing.domain.Role;
import com.ticketing.domain.User;
import com.ticketing.dto.CreateEventRequest;
import com.ticketing.dto.UpdateEventRequest;
import com.ticketing.exception.AccessDeniedOwnershipException;
import com.ticketing.exception.InvalidStateTransitionException;
import com.ticketing.exception.ResourceNotFoundException;
import com.ticketing.repository.EventRepository;
import jakarta.persistence.OptimisticLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final AuditService auditService;

    public EventService(EventRepository eventRepository, AuditService auditService) {
        this.eventRepository = eventRepository;
        this.auditService = auditService;
    }

    @Transactional
    public Event createEvent(User caller, CreateEventRequest request, String ip, String userAgent) {
        if (request.endsAt().isBefore(request.startsAt())) {
            throw new IllegalArgumentException("endsAt must not be before startsAt");
        }
        Event event = new Event(caller.getId(), request.title(), request.venue(),
                request.startsAt(), request.endsAt(), request.capacity());
        event = eventRepository.save(event);
        auditService.record(caller.getId(), "EVENT_CREATED", "Event", event.getId().toString(), ip, userAgent);
        return event;
    }

    @Transactional
    public Event updateEvent(User caller, UUID eventId, UpdateEventRequest request, String ip, String userAgent) {
        Event event = getOrThrow(eventId);
        assertOwnerOrAdmin(caller, event);

        // ADR-02 (part 2) — explicit version check rather than overwriting the
        // managed entity's @Version field with the client-supplied value.
        // `event` here is already a JPA-managed entity carrying the CURRENT DB
        // version (loaded by getOrThrow's findById). If we blindly did
        // event.setVersion(request.version()), we would be telling Hibernate
        // "pretend this is the version you loaded", which changes what value
        // Hibernate uses in the WHERE clause of its own optimistic-lock UPDATE —
        // behavior that differs subtly across JPA providers and is easy to get
        // wrong. Comparing explicitly and letting Hibernate's own dirty-checked
        // @Version increment happen normally is the safe, portable approach.
        if (event.getVersion() != request.version()) {
            throw new InvalidStateTransitionException(
                    "Event was modified concurrently by someone else; reload and retry with the latest version");
        }

        event.setTitle(request.title());
        event.setVenue(request.venue());
        event.setStartsAt(request.startsAt());
        event.setEndsAt(request.endsAt());
        event.setCapacity(request.capacity());

        try {
            event = eventRepository.save(event);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            // Belt-and-braces: covers the narrow window where a concurrent update
            // commits between our check above and this flush.
            throw new InvalidStateTransitionException(
                    "Event was modified concurrently by someone else; reload and retry with the latest version");
        }

        auditService.record(caller.getId(), "EVENT_UPDATED", "Event", event.getId().toString(), ip, userAgent);
        return event;
    }

    @Transactional
    public Event publishEvent(User caller, UUID eventId, String ip, String userAgent) {
        Event event = getOrThrow(eventId);
        assertOwnerOrAdmin(caller, event);

        if (event.isPublished()) {
            throw new InvalidStateTransitionException("Event is already published");
        }
        if (event.getCapacity() <= 0) {
            throw new InvalidStateTransitionException("Cannot publish an event with zero capacity");
        }

        event.setPublished(true);
        event = eventRepository.save(event);
        auditService.record(caller.getId(), "EVENT_PUBLISHED", "Event", event.getId().toString(), ip, userAgent);
        return event;
    }

    @Transactional(readOnly = true)
    public List<Event> listEvents(User caller, UUID ownerIdFilter) {
        // ADMIN can filter by any owner (or see none-filtered — kept simple: admin
        // must also pass ownerId here; a "list everything" admin endpoint could be
        // added but is out of scope for the case and would need pagination anyway).
        if (caller.getRoles().contains(Role.ADMIN)) {
            return ownerIdFilter != null ? eventRepository.findByOwnerId(ownerIdFilter) : eventRepository.findAll();
        }
        // Non-admins only ever see their own events regardless of the ownerId query param,
        // to avoid leaking other organizers' draft events through the filter.
        return eventRepository.findByOwnerId(caller.getId());
    }

    @Transactional(readOnly = true)
    public List<Event> searchPublic(Instant from, Instant to, String q) {
        return eventRepository.searchPublic(from, to, q);
    }

    @Transactional(readOnly = true)
    public Event getOrThrow(UUID eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
    }

    private void assertOwnerOrAdmin(User caller, Event event) {
        boolean isAdmin = caller.getRoles().contains(Role.ADMIN);
        boolean isOwner = event.getOwnerId().equals(caller.getId());
        if (!isAdmin && !isOwner) {
            throw new AccessDeniedOwnershipException("You do not own this event");
        }
    }
}
