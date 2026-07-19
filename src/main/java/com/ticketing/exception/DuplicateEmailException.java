package com.ticketing.exception;

import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends ApiException {
    private static final long serialVersionUID = 1L;

    public DuplicateEmailException(String email) {
        super(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "Email already registered: " + email);
    }
}
