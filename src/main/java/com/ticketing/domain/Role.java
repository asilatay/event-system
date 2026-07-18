package com.ticketing.domain;

/**
 * ADMIN: full access to all resources.
 * ORGANIZER: full access to events they own.
 * CUSTOMER: reservation operations only.
 *
 * A user may hold multiple roles (e.g. an ORGANIZER who also books tickets
 * as a CUSTOMER), hence User.roles is a Set<Role>, not a single field.
 */
public enum Role {
    ADMIN,
    ORGANIZER,
    CUSTOMER
}
