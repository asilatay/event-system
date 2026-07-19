package com.ticketing.exception;

import org.springframework.http.HttpStatus;

public class EventSoldOutException extends ApiException {
    private static final long serialVersionUID = 1L;

    public EventSoldOutException(String eventId) {
        super(HttpStatus.CONFLICT, "EVENT_SOLD_OUT", "Not enough seats available for event: " + eventId);
    }
}
