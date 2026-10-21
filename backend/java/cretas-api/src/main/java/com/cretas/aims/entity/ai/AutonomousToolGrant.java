package com.cretas.aims.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Delegated authority for one autonomous source (a cron config) to run one
 * write tool as one factory user with one exact parameter digest, a run budget and an expiry.
 * Revocation is recorded, never deleted.
 */
@Entity
@Table(name = "autonomous_tool_grants", indexes = {
        @Index(name = "idx_atg_lookup", columnList = "factory_id, source_type, source_ref, tool_name, run_as_user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutonomousToolGrant {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    /** SCHEDULER (the only source with grants for now). */
    @Column(name = "source_type", length = 16, nullable = false, updatable = false)
    private String sourceType;

    /** Scheduler config id. */
    @Column(name = "source_ref", length = 100, nullable = false, updatable = false)
    private String sourceRef;

    @Column(name = "tool_name", length = 100, nullable = false, updatable = false)
    private String toolName;

    @Column(name = "run_as_user_id", nullable = false, updatable = false)
    private Long runAsUserId;

    @Column(name = "parameters_digest", length = 128, nullable = false, updatable = false)
    private String parametersDigest;

    @Column(name = "max_runs", nullable = false, updatable = false)
    private Integer maxRuns;

    @Column(name = "used_runs", nullable = false)
    private Integer usedRuns;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "granted_by", nullable = false, updatable = false)
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private LocalDateTime grantedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static AutonomousToolGrant issue(String id, String factoryId, String sourceType, String sourceRef,
            String toolName, Long runAsUserId, String parametersDigest, int maxRuns, LocalDateTime expiresAt,
            Long grantedBy, LocalDateTime now) {
        AutonomousToolGrant grant = new AutonomousToolGrant();
        grant.id = id;
        grant.factoryId = factoryId;
        grant.sourceType = sourceType;
        grant.sourceRef = sourceRef;
        grant.toolName = toolName;
        grant.runAsUserId = runAsUserId;
        grant.parametersDigest = parametersDigest;
        grant.maxRuns = maxRuns;
        grant.usedRuns = 0;
        grant.expiresAt = expiresAt;
        grant.grantedBy = grantedBy;
        grant.grantedAt = now;
        return grant;
    }

    /** Usable now: not revoked, not expired, budget left. */
    public boolean usableAt(LocalDateTime now) {
        return revokedAt == null && now.isBefore(expiresAt) && usedRuns < maxRuns;
    }

    public void consumeOne() {
        if (usedRuns >= maxRuns) throw new IllegalStateException("grant budget exhausted");
        usedRuns = usedRuns + 1;
    }

    public void revoke(Long by, LocalDateTime now) {
        if (revokedAt == null) {
            revokedAt = now;
            revokedBy = by;
        }
    }
}
