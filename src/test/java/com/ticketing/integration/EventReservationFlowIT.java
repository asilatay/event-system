package com.ticketing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end: register -> login -> create draft -> publish -> reserve -> confirm -> cancel. */
class EventReservationFlowIT extends AbstractIntegrationTest {

    @Test
    void fullHappyPathFlow() throws Exception {
        // 1. Register a fresh organizer and customer.
        register("flow-organizer@ticketing.local", "FlowOrg123!", "ORGANIZER");
        register("flow-customer@ticketing.local", "FlowCust123!", "CUSTOMER");

        String organizerToken = login("flow-organizer@ticketing.local", "FlowOrg123!");
        String customerToken = login("flow-customer@ticketing.local", "FlowCust123!");

        // 2. Organizer creates a draft event (unpublished by default).
        HttpHeaders organizerHeaders = bearer(organizerToken);
        Map<String, Object> createBody = Map.of(
                "title", "Spring Boot Conference", "venue", "Tech Hall",
                "startsAt", Instant.now().plus(10, ChronoUnit.DAYS).toString(),
                "endsAt", Instant.now().plus(11, ChronoUnit.DAYS).toString(),
                "capacity", 5);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/events", HttpMethod.POST, new HttpEntity<>(createBody, organizerHeaders), String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = objectMapper.readTree(createResponse.getBody());
        String eventId = created.get("id").asText();
        assertThat(created.get("published").asBoolean()).isFalse();

        // 3. Draft event must not be visible in public discovery yet.
        JsonNode preLaunch = objectMapper.readTree(
                restTemplate.getForEntity("/api/events/public", String.class).getBody());
        assertThat(containsEventId(preLaunch, eventId)).isFalse();

        // 4. Publish it.
        ResponseEntity<String> publishResponse = restTemplate.exchange(
                "/api/events/" + eventId + "/publish", HttpMethod.POST, new HttpEntity<>(organizerHeaders), String.class);
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 5. Now it must appear in public discovery.
        JsonNode postLaunch = objectMapper.readTree(
                restTemplate.getForEntity("/api/events/public", String.class).getBody());
        assertThat(containsEventId(postLaunch, eventId)).isTrue();

        // 6. Customer reserves 2 seats.
        HttpHeaders customerHeaders = bearer(customerToken);
        customerHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<String> reserveResponse = restTemplate.exchange(
                "/api/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>(Map.of("seats", 2), customerHeaders), String.class);
        assertThat(reserveResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode reservation = objectMapper.readTree(reserveResponse.getBody());
        assertThat(reservation.get("status").asText()).isEqualTo("PENDING");
        String reservationId = reservation.get("id").asText();

        // 7. Confirm it.
        ResponseEntity<String> confirmResponse = restTemplate.exchange(
                "/api/reservations/" + reservationId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(bearer(customerToken)), String.class);
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(confirmResponse.getBody()).get("status").asText()).isEqualTo("CONFIRMED");

        // 8. Cancel it, and confirm seats are released.
        ResponseEntity<String> cancelResponse = restTemplate.exchange(
                "/api/reservations/" + reservationId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(bearer(customerToken)), String.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(cancelResponse.getBody()).get("status").asText()).isEqualTo("CANCELLED");

        JsonNode afterCancel = objectMapper.readTree(
                restTemplate.getForEntity("/api/events/public", String.class).getBody());
        for (JsonNode e : afterCancel) {
            if (e.get("id").asText().equals(eventId)) {
                assertThat(e.get("availableSeats").asInt()).isEqualTo(5); // back to full capacity
            }
        }
    }

    @Test
    void confirmingANonexistentReservationReturns404() throws Exception {
        String customerToken = login("customer@ticketing.local", "Customer123!");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/reservations/" + UUID.randomUUID() + "/confirm", HttpMethod.POST,
                new HttpEntity<>(bearer(customerToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(response.getBody()).get("title").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    private boolean containsEventId(JsonNode events, String eventId) {
        for (JsonNode e : events) {
            if (e.get("id").asText().equals(eventId)) return true;
        }
        return false;
    }
}
