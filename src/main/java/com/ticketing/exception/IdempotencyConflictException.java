package com.ticketing.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends ApiException {
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", message);
    }
}
