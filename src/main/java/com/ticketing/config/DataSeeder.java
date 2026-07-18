package com.ticketing.config;

import com.ticketing.domain.Role;
import com.ticketing.domain.User;
import com.ticketing.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Seeds three demo accounts (see README "Seed users") so the case can be
 * exercised end-to-end without a separate registration step. This runs on
 * every startup but is idempotent (checks existsByEmail first), so it is
 * safe with the in-memory H2 database resetting each run.
 *
 * NOT intended for a real deployment — gate this behind a profile
 * (e.g. `@Profile("!prod")`) before shipping past a demo/case-study context.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        seed("admin@ticketing.local", "Admin123!", Set.of(Role.ADMIN));
        seed("organizer@ticketing.local", "Organizer123!", Set.of(Role.ORGANIZER));
        seed("customer@ticketing.local", "Customer123!", Set.of(Role.CUSTOMER));
    }

    private void seed(String email, String rawPassword, Set<Role> roles) {
        if (userRepository.existsByEmail(email)) {
            return;
        }
        userRepository.save(new User(email, passwordEncoder.encode(rawPassword), roles));
    }
}
