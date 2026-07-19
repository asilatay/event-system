package com.ticketing.exception;

import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends ApiException {
    private static final long serialVersionUID = 1L;

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }
}
