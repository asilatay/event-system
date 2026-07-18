package com.ticketing.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a caller has the right ROLE but does not own the specific resource. */
public class AccessDeniedOwnershipException extends ApiException {
    public AccessDeniedOwnershipException(String message) {
        super(HttpStatus.FORBIDDEN, "OWNERSHIP_VIOLATION", message);
    }
}
