package com.ticketing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

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
class ReservationConcurrencyIT extends AbstractIntegrationTest {

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
                // Each concurrent attempt uses a distinct Idempotency-Key, since these
                // represent different customers/requests, not retries of the same one.
                HttpHeaders headers = bearer(customerToken);
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
}
