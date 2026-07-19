package com.ticketing.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyKeyRequiredException extends ApiException {
    private static final long serialVersionUID = 1L;

    public IdempotencyKeyRequiredException() {
        super(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required for this endpoint");
    }
}
