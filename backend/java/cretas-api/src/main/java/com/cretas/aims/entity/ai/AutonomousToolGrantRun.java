package com.cretas.aims.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One consumption of an {@link AutonomousToolGrant} for one run key. The unique (grant, run key)
 * makes a repeated run reuse this row and its gateway idempotency key instead of consuming the
 * budget again, so a lost response is replayed, never re-executed.
 */
@Entity
@Table(name = "autonomous_tool_grant_runs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_atgr_grant_run", columnNames = {"grant_id", "run_key"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutonomousToolGrantRun {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "grant_id", length = 36, nullable = false, updatable = false)
    private String grantId;

    @Column(name = "run_key", length = 150, nullable = false, updatable = false)
    private String runKey;

    @Column(name = "idempotency_key", length = 200, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "consumed_at", nullable = false, updatable = false)
    private LocalDateTime consumedAt;

    public static AutonomousToolGrantRun record(String id, String grantId, String runKey, LocalDateTime now) {
        AutonomousToolGrantRun run = new AutonomousToolGrantRun();
        run.id = id;
        run.grantId = grantId;
        run.runKey = runKey;
        run.idempotencyKey = "autonomous:" + grantId + ":" + runKey;
        run.consumedAt = now;
        return run;
    }
}
