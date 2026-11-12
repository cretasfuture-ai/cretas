package com.cretas.aims.engine;

import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only reconciliation of executions whose outcome nobody resolved. It reports and never
 * changes state; an IN_DOUBT gateway record must be resolved by looking up the business result,
 * never by re-running.
 *
 * <p>Confirmation tokens: {@code PreviewTokenServiceImpl#reconcileStaleExecutingTokens} turns an
 * EXECUTING token nobody resolved into OUTCOME_UNKNOWN after a few minutes, and the confirm path marks
 * OUTCOME_UNKNOWN directly when a write tool failed after it may have committed. So the signal a human
 * must look at is OUTCOME_UNKNOWN (counted within {@code unknown-lookback-hours} of being marked);
 * EXECUTING older than the stale window is still counted (reconciliation not running is itself a finding).
 * The purchase lane replays by its consumed grant, not by the token staying EXECUTING.</p>
 *
 * <p>Three outcomes: CLEAN (all counts 0), FOUND (something stale, logged as WARN with sample
 * ids), UNMEASURED (a query failed, logged as ERROR). UNMEASURED is never reported as clean.</p>
 *
 * <p>Off unless {@code cretas.execution-reconciliation.enabled=true}: merging it must not start a
 * new periodic job in production.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cretas.execution-reconciliation.enabled", havingValue = "true")
public class ExecutionReconciliationReporter {
    private static final int SAMPLE_LIMIT = 10;

    private final EntityManager entityManager;
    private final Duration staleAfter;
    private volatile Snapshot last;

    /** OUTCOME_UNKNOWN tokens marked within this many hours are reported (they never clear by themselves). */
    @Value("${cretas.execution-reconciliation.unknown-lookback-hours:24}")
    private long unknownLookbackHours = 24;

    public ExecutionReconciliationReporter(EntityManager entityManager,
            @Value("${cretas.execution-reconciliation.stale-minutes:30}") long staleMinutes) {
        this.entityManager = entityManager;
        this.staleAfter = Duration.ofMinutes(staleMinutes);
    }

    /** status: CLEAN / FOUND / UNMEASURED. Counts are null when UNMEASURED. */
    public record Snapshot(String status, LocalDateTime cutoff, Long executingTokens, Long outcomeUnknownTokens,
            Long inDoubtRecords,
            Long staleInProgressRecords, Long unfinishedAudits, Long unresolvedGrantRuns, List<String> samples,
            String error) { }

    @Scheduled(initialDelayString = "${cretas.execution-reconciliation.initial-delay-ms:300000}",
            fixedDelayString = "${cretas.execution-reconciliation.interval-ms:900000}")
    public void scheduledReport() {
        Snapshot snapshot = report(LocalDateTime.now());
        last = snapshot;
        switch (snapshot.status()) {
            case "CLEAN" -> log.info("EXECUTION_RECONCILIATION status=CLEAN cutoff={}", snapshot.cutoff());
            case "FOUND" -> log.warn("EXECUTION_RECONCILIATION status=FOUND cutoff={} executingTokens={} "
                            + "outcomeUnknownTokens={} inDoubt={} "
                            + "staleInProgress={} unfinishedAudits={} unresolvedGrantRuns={} samples={}", snapshot.cutoff(),
                    snapshot.executingTokens(), snapshot.outcomeUnknownTokens(), snapshot.inDoubtRecords(), snapshot.staleInProgressRecords(),
                    snapshot.unfinishedAudits(), snapshot.unresolvedGrantRuns(), snapshot.samples());
            default -> log.error("EXECUTION_RECONCILIATION status=UNMEASURED cutoff={} error={}",
                    snapshot.cutoff(), snapshot.error());
        }
    }

    public Snapshot lastSnapshot() { return last; }

    @Transactional(readOnly = true)
    public Snapshot report(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(staleAfter);
        try {
            List<String> tokens = entityManager.createQuery(
                    "select concat(coalesce(t.factoryId, '-'), ':token:', cast(t.id as string)) from IntentPreviewToken t"
                            + " where t.status = :status and coalesce(t.claimedAt, t.createdAt) < :cutoff"
                            + " order by t.id", String.class)
                    .setParameter("status", TokenStatus.EXECUTING).setParameter("cutoff", cutoff).getResultList();
            // 结果不明: 对账从 EXECUTING 转来的 + 确认流程在「可能已写」时直接标的。终态, 不会自己消失,
            // 所以按「标记时间在回看窗口内」数, 只取样本行。
            LocalDateTime unknownSince = now.minusHours(Math.max(1, unknownLookbackHours));
            long unknownCount = entityManager.createQuery(
                    "select count(t) from IntentPreviewToken t where t.status = :status and t.resolvedAt >= :since",
                    Long.class).setParameter("status", TokenStatus.OUTCOME_UNKNOWN)
                    .setParameter("since", unknownSince).getSingleResult();
            List<String> unknownTokens = entityManager.createQuery(
                    "select concat(coalesce(t.factoryId, '-'), ':unknown-token:', cast(t.id as string)) from IntentPreviewToken t"
                            + " where t.status = :status and t.resolvedAt >= :since order by t.id", String.class)
                    .setParameter("status", TokenStatus.OUTCOME_UNKNOWN).setParameter("since", unknownSince)
                    .setMaxResults(SAMPLE_LIMIT).getResultList();
            List<String> inDoubt = entityManager.createQuery(
                    "select concat(coalesce(r.tenantId, '-'), ':idempotency:', r.id) from ToolExecutionIdempotencyRecord r"
                            + " where r.state = :state order by r.id", String.class)
                    .setParameter("state", ToolExecutionIdempotencyRecord.State.IN_DOUBT).getResultList();
            List<String> inProgress = entityManager.createQuery(
                    "select concat(coalesce(r.tenantId, '-'), ':idempotency:', r.id) from ToolExecutionIdempotencyRecord r"
                            + " where r.state = :state and r.updatedAt < :cutoff order by r.id", String.class)
                    .setParameter("state", ToolExecutionIdempotencyRecord.State.IN_PROGRESS)
                    .setParameter("cutoff", cutoff).getResultList();
            List<String> audits = entityManager.createQuery(
                    "select concat(coalesce(e.tenantId, '-'), ':audit:', e.id) from ToolExecutionAuditEvent e"
                            + " where e.state = :state and e.startedAt < :cutoff order by e.id", String.class)
                    .setParameter("state", ToolExecutionAuditEvent.State.STARTED)
                    .setParameter("cutoff", cutoff).getResultList();
            // A delegated scheduled run that consumed its grant but never recorded an outcome. These rows
            // are never cleared automatically, so they are counted in the database and only a capped
            // sample is loaded.
            long grantRunCount = entityManager.createQuery(
                    "select count(r) from AutonomousToolGrantRun r where r.outcome is null and r.consumedAt < :cutoff",
                    Long.class).setParameter("cutoff", cutoff).getSingleResult();
            List<String> grantRuns = entityManager.createQuery(
                    "select concat(r.grantId, ':grant-run:', r.runKey) from AutonomousToolGrantRun r"
                            + " where r.outcome is null and r.consumedAt < :cutoff order by r.consumedAt", String.class)
                    .setParameter("cutoff", cutoff).setMaxResults(SAMPLE_LIMIT).getResultList();
            long total = tokens.size() + unknownCount + inDoubt.size() + inProgress.size() + audits.size() + grantRunCount;
            List<String> samples = java.util.stream.Stream.of(tokens, unknownTokens, inDoubt, inProgress, audits, grantRuns)
                    .flatMap(List::stream).limit(SAMPLE_LIMIT).toList();
            return new Snapshot(total == 0 ? "CLEAN" : "FOUND", cutoff, (long) tokens.size(), unknownCount,
                    (long) inDoubt.size(),
                    (long) inProgress.size(), (long) audits.size(), grantRunCount, samples, null);
        } catch (RuntimeException unmeasured) {
            return new Snapshot("UNMEASURED", cutoff, null, null, null, null, null, null, List.of(),
                    unmeasured.getClass().getSimpleName() + ": " + unmeasured.getMessage());
        }
    }
}
