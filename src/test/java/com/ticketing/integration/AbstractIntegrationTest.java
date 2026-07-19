package com.ticketing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared bootstrap for the *IT classes: a RestTemplate wired to the random port
 * with TestRestTemplate-like error handling (removed in Spring Boot 4.0), plus
 * the login/register/event-creation helpers every one of them needs. Extracted
 * after the same ~15-line RestTemplate setup and near-identical login() /
 * createAndPublishEvent() helpers turned up duplicated verbatim across four
 * separate test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    protected RestTemplate restTemplate;

    protected final ObjectMapper objectMapper = new ObjectMapper();

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

    protected HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected void register(String email, String password, String role) {
        Map<String, Object> body = Map.of("email", email, "password", password, "roles", List.of(role));
        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/register", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    protected String login(String email, String password) throws Exception {
        Map<String, String> body = Map.of("email", email, "password", password);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/login", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("accessToken").asText();
    }

    protected String createAndPublishEvent(String organizerToken, int capacity) throws Exception {
        HttpHeaders headers = bearer(organizerToken);

        Map<String, Object> body = Map.of(
                "title", "Test Event", "venue", "Test Hall",
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
}
