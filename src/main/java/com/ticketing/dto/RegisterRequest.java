package com.ticketing.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RegisterRequest(
        @NotNull @Email String email,
        @NotNull @Size(min = 8, max = 100, message = "Password must be at least 8 characters") String password,
        @NotEmpty Set<String> roles
) {}
