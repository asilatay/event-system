package com.ticketing.controller;

import com.ticketing.common.RequestContext;
import com.ticketing.domain.Event;
import com.ticketing.domain.User;
import com.ticketing.dto.CreateEventRequest;
import com.ticketing.dto.EventResponse;
import com.ticketing.dto.UpdateEventRequest;
import com.ticketing.security.CurrentUserResolver;
import com.ticketing.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
@Tag(name = "Events")
public class EventController {

    private final EventService eventService;
    private final CurrentUserResolver currentUserResolver;

    public EventController(EventService eventService, CurrentUserResolver currentUserResolver) {
        this.eventService = eventService;
        this.currentUserResolver = currentUserResolver;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @Operation(summary = "Create a draft event owned by the authenticated organizer/admin")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request, HttpServletRequest httpRequest) {
        User caller = currentUserResolver.resolve();
        Event event = eventService.createEvent(caller, request, RequestContext.from(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(EventResponse.from(event));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @Operation(summary = "Update an event; caller must be the owner or an admin")
    public ResponseEntity<EventResponse> updateEvent(@PathVariable UUID id,
                                                      @Valid @RequestBody UpdateEventRequest request,
                                                      HttpServletRequest httpRequest) {
        User caller = currentUserResolver.resolve();
        Event event = eventService.updateEvent(caller, id, request, RequestContext.from(httpRequest));
        return ResponseEntity.ok(EventResponse.from(event));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @Operation(summary = "Publish a draft event, making it visible in public discovery")
    public ResponseEntity<EventResponse> publishEvent(@PathVariable UUID id, HttpServletRequest httpRequest) {
        User caller = currentUserResolver.resolve();
        Event event = eventService.publishEvent(caller, id, RequestContext.from(httpRequest));
        return ResponseEntity.ok(EventResponse.from(event));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @Operation(summary = "List events; organizers see only their own, admins may filter by ownerId")
    public ResponseEntity<List<EventResponse>> listEvents(@RequestParam(required = false) UUID ownerId) {
        User caller = currentUserResolver.resolve();
        List<EventResponse> events = eventService.listEvents(caller, ownerId).stream()
                .map(EventResponse::from).toList();
        return ResponseEntity.ok(events);
    }

    @GetMapping("/public")
    @Operation(summary = "Public discovery of published events (no auth required)")
    public ResponseEntity<List<EventResponse>> discoverPublicEvents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q) {
        List<EventResponse> events = eventService.searchPublic(from, to, q).stream()
                .map(EventResponse::from).toList();
        return ResponseEntity.ok(events);
    }
}
