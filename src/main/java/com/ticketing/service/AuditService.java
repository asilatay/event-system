package com.ticketing.service;

import com.ticketing.domain.AuditLog;
import com.ticketing.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ADR-05 — Audit logging is written in REQUIRES_NEW so that a rollback of the
 * business transaction (e.g. reservation failed because sold out) does not
 * also erase the audit trail entry recording that the attempt happened.
 * Security-relevant audit records must survive the very failures they document.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, String action, String resourceType, String resourceId, String ip, String userAgent) {
        auditLogRepository.save(new AuditLog(actorId, action, resourceType, resourceId, ip, userAgent));
    }
}
