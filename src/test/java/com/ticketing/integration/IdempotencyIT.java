package com.ticketing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyIT {

    @LocalServerPort
    private int port;

    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void initRestTemplate() {
        restTemplate = new RestTemplate();
        // TestRestTemplate (removed in Spring Boot 4.0) never threw on 4xx/5xx so
        // tests could assert on status codes directly; replicate that here.
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
    }

    @Test
    void sameKeyAndSameBody_replaysOriginalResponseWithoutDoubleBooking() throws Exception {
        String organizerToken = login("organizer@ticketing.local", "Organizer123!");
        String customerToken = login("customer@ticketing.local", "Customer123!");
        String eventId = createAndPublishEvent(organizerToken, 10);

        String idempotencyKey = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(customerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);

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

        String idempotencyKey = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(customerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);

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

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(customerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>(Map.of("seats", 1), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String createAndPublishEvent(String organizerToken, int capacity) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(organizerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "title", "Idempotency Test Event", "venue", "Test Hall",
                "startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString(),
                "endsAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(),
                "capacity", capacity);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/events", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String eventId = objectMapper.readTree(createResponse.getBody()).get("id").asText();

        ResponseEntity<String> publishResponse = restTemplate.exchange(
                "/api/events/" + eventId + "/publish", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        return eventId;
    }

    private String login(String email, String password) throws Exception {
        Map<String, String> body = Map.of("email", email, "password", password);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/login", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }
}
