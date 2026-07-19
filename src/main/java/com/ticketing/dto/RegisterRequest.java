package com.ticketing.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RegisterRequest(
        @NotNull @Email @Size(max = 255) String email,
        @NotNull @Size(min = 8, max = 100, message = "Password must be at least 8 characters") String password,
        @NotEmpty Set<String> roles
) {
    // Defensive copy into an immutable set: without this, the caller's mutable Set
    // reference is stored directly, and the auto-generated roles() accessor hands
    // that same reference back out - either side could then mutate what looks like
    // an immutable record's state.
    public RegisterRequest {
        roles = roles == null ? null : Set.copyOf(roles);
    }
}
