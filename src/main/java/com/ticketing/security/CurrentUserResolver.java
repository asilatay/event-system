package com.ticketing.security;

import com.ticketing.domain.User;
import com.ticketing.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The JWT filter only puts the user's email into the SecurityContext (to avoid
 * a DB hit per request just to authenticate). Controllers that need the full
 * User entity (e.g. for id/roles in business logic) resolve it once here,
 * explicitly, at the point of use rather than on every request.
 */
@Component
public class CurrentUserResolver {

    private final UserRepository userRepository;

    public CurrentUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Not authenticated");
        }
        String email = auth.getName();
        // AccessDeniedException (403), not 401: a token with a valid signature for a
        // user deleted since it was issued is treated as a permissions gap here.
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Unknown user"));
    }
}
