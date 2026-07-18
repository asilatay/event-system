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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core correctness test for the whole case: fires many concurrent
 * reservation requests (each with its own Idempotency-Key, simulating
 * distinct customers) at a single event with a small, fixed capacity, and
 * asserts that the number of successful reservations never exceeds capacity
 * — proving the atomic conditional UPDATE in EventRepository#tryReserveSeats
 * actually holds under real concurrent load, not just in single-threaded logic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationConcurrencyIT {

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

    private static final int CAPACITY = 20;
    private static final int CONCURRENT_ATTEMPTS = 60; // 3x oversubscribed
    private static final int SEATS_PER_RESERVATION = 1;

    @Test
    void concurrentReservationsNeverExceedEventCapacity() throws Exception {
        String organizerToken = login("organizer@ticketing.local", "Organizer123!");
        String customerToken = login("customer@ticketing.local", "Customer123!");

        String eventId = createAndPublishEvent(organizerToken, CAPACITY);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>(CONCURRENT_ATTEMPTS);

        for (int i = 0; i < CONCURRENT_ATTEMPTS; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(customerToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                // Each concurrent attempt uses a distinct Idempotency-Key, since these
                // represent different customers/requests, not retries of the same one.
                headers.set("Idempotency-Key", UUID.randomUUID().toString());

                Map<String, Object> body = Map.of("seats", SEATS_PER_RESERVATION);
                ResponseEntity<String> response = restTemplate.exchange(
                        "/api/events/" + eventId + "/reservations", HttpMethod.POST,
                        new HttpEntity<>(body, headers), String.class);
                return response.getStatusCode().value();
            }));
        }

        startLatch.countDown(); // release all threads at once to maximize contention
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        int successCount = 0;
        int soldOutCount = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) successCount++;
            else if (status == 409) soldOutCount++;
        }

        assertThat(successCount).isEqualTo(CAPACITY);
        assertThat(soldOutCount).isEqualTo(CONCURRENT_ATTEMPTS - CAPACITY);

        // Cross-check against the event's own bookkeeping.
        ResponseEntity<String> publicEvents = restTemplate.getForEntity("/api/events/public", String.class);
        JsonNode events = objectMapper.readTree(publicEvents.getBody());
        for (JsonNode e : events) {
            if (e.get("id").asText().equals(eventId)) {
                assertThat(e.get("availableSeats").asInt()).isEqualTo(0);
            }
        }
    }

    private String createAndPublishEvent(String organizerToken, int capacity) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(organizerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "title", "Concurrency Test Event", "venue", "Load Test Arena",
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
