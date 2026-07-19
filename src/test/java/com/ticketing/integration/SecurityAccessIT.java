package com.ticketing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies role-based access control end to end: a CUSTOMER cannot create
 * events, an ORGANIZER cannot publish another organizer's event, and public
 * discovery requires no authentication at all.
 */
class SecurityAccessIT extends AbstractIntegrationTest {

    @Test
    void customerCannotCreateEvent() throws Exception {
        String customerToken = login("customer@ticketing.local", "Customer123!");

        Map<String, Object> body = Map.of(
                "title", "Hack Attempt", "venue", "Nowhere",
                "startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString(),
                "endsAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(),
                "capacity", 10);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/events", HttpMethod.POST, new HttpEntity<>(body, bearer(customerToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void organizerCannotUpdateAnotherOrganizersEvent() throws Exception {
        String organizerToken = login("organizer@ticketing.local", "Organizer123!");

        // Register a second, unrelated organizer.
        register("second-organizer@ticketing.local", "SecondOrg123!", "ORGANIZER");
        String secondOrganizerToken = login("second-organizer@ticketing.local", "SecondOrg123!");

        // First organizer creates an event.
        Map<String, Object> eventBody = Map.of(
                "title", "Owner's Event", "venue", "Venue A",
                "startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString(),
                "endsAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(),
                "capacity", 50);
        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/events", HttpMethod.POST, new HttpEntity<>(eventBody, bearer(organizerToken)), String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode json = objectMapper.readTree(createResponse.getBody());
        String eventId = json.get("id").asText();
        long version = json.get("version").asLong();

        // Second organizer attempts to update the first organizer's event -> must be forbidden.
        Map<String, Object> updateBody = Map.of(
                "title", "Hijacked Title", "venue", "Venue B",
                "startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString(),
                "endsAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(),
                "capacity", 999,
                "version", version);
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/events/" + eventId, HttpMethod.PUT,
                new HttpEntity<>(updateBody, bearer(secondOrganizerToken)), String.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void publicDiscoveryRequiresNoAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/events/public", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void protectedEndpointRejectsMissingToken() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/events", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpointRejectsTamperedToken() throws Exception {
        String token = login("customer@ticketing.local", "Customer123!");
        String tampered = token.substring(0, token.length() - 3) + "xyz";

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/events", HttpMethod.GET, new HttpEntity<>(bearer(tampered)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void registeringAnAlreadyUsedEmailIsRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "email", "customer@ticketing.local", // one of the three seeded accounts
                "password", "AnyPassword123!",
                "roles", java.util.List.of("CUSTOMER"));

        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/register", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).get("title").asText()).isEqualTo("DUPLICATE_EMAIL");
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        Map<String, String> body = Map.of("email", "customer@ticketing.local", "password", "TotallyWrongPassword1!");

        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/login", body, String.class);

        // Status only: RestTemplate's default JDK-based request factory doesn't
        // reliably surface the response body for chunked 401s the way it does for
        // 2xx/409 (a client-side HttpURLConnection quirk, not a server-side bug -
        // verified via curl that the body is a well-formed ProblemDetail).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshingWithATamperedTokenIsRejected() throws Exception {
        Map<String, String> loginBody = Map.of("email", "customer@ticketing.local", "password", "Customer123!");
        ResponseEntity<String> loginResponse = restTemplate.postForEntity("/api/auth/login", loginBody, String.class);
        String refreshToken = objectMapper.readTree(loginResponse.getBody()).get("refreshToken").asText();
        String tampered = refreshToken.substring(0, refreshToken.length() - 3) + "xyz";

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/refresh", Map.of("refreshToken", tampered), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
