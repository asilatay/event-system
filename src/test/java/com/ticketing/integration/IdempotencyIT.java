package com.ticketing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIT extends AbstractIntegrationTest {

    @Test
    void sameKeyAndSameBody_replaysOriginalResponseWithoutDoubleBooking() throws Exception {
        String organizerToken = login("organizer@ticketing.local", "Organizer123!");
        String customerToken = login("customer@ticketing.local", "Customer123!");
        String eventId = createAndPublishEvent(organizerToken, 10);

        HttpHeaders headers = bearer(customerToken);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        Map<String, Object> body = Map.of("seats", 3);

        ResponseEntity<String> first = restTemplate.exchange(
                "/api/events/" + eventId + "/reservations", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstReservationId = objectMapper.readTree(first.getBody()).get("id").asText();

        // Retry with the exact same key + body — must replay, not create a second reservation.
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/events/" + eventId + "/reservations", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String secondReservationId = objectMapper.readTree(second.getBody()).get("id").asText();

        assertThat(secondReservationId).isEqualTo(firstReservationId);

        // Only 3 seats should have been taken total, not 6.
        ResponseEntity<String> publicEvents = restTemplate.getForEntity("/api/events/public", String.class);
        JsonNode events = objectMapper.readTree(publicEvents.getBody());
        for (JsonNode e : events) {
            if (e.get("id").asText().equals(eventId)) {
                assertThat(e.get("availableSeats").asInt()).isEqualTo(7);
            }
        }
    }

    @Test
    void sameKeyDifferentBody_isRejectedAsConflict() throws Exception {
        String organizerToken = login("organizer@ticketing.local", "Organizer123!");
        String customerToken = login("customer@ticketing.local", "Customer123!");
        String eventId = createAndPublishEvent(organizerToken, 10);

        HttpHeaders headers = bearer(customerToken);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<String> first = restTemplate.exchange(
                "/api/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>(Map.of("seats", 1), headers), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>(Map.of("seats", 2), headers), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void missingIdempotencyKey_isRejected() throws Exception {
        String organizerToken = login("organizer@ticketing.local", "Organizer123!");
        String customerToken = login("customer@ticketing.local", "Customer123!");
        String eventId = createAndPublishEvent(organizerToken, 10);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>(Map.of("seats", 1), bearer(customerToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
