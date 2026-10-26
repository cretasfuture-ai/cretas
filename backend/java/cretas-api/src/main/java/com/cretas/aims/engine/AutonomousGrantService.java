package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.entity.ai.AutonomousToolGrantRun;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.repository.ai.AutonomousToolGrantRepository;
import com.cretas.aims.repository.ai.AutonomousToolGrantRunRepository;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delegated grants for autonomous writes. Consumption is the authorization commit point: the
 * bound grants are locked, a run key that was already consumed replays its original gateway
 * idempotency key (no second budget, no second effect), otherwise one usable grant (not revoked,
 * not expired, budget left) is consumed once. Consumption is the authorization commit point:
 * revocation or expiry stops every run that has not consumed yet; a consumed run is not undone,
 * and a replay of it never executes again (the runner reports the gateway's recorded result).
 */
@Service
@RequiredArgsConstructor
public class AutonomousGrantService {
    public static final int MAX_RUNS_LIMIT = 1000;
    public static final int MAX_DAYS = 90;

    private final AutonomousToolGrantRepository grants;
    private final AutonomousToolGrantRunRepository runs;
    private final ToolExecutionIdempotencyRepository gatewayLedger;

    /**
     * grantId, the gateway idempotency key for this run, the tool descriptor version recorded when
     * the run first consumed the grant, and whether this run key was seen before.
     */
    public record Consumption(String grantId, String idempotencyKey, String descriptorVersion, boolean replay) { }

    /**
     * What a grant is bound to. A new run consumes only a grant matching all of it; a replay of an
     * already consumed run key is found whatever the current config revision or tool version.
     */
    public record Binding(String factoryId, String sourceType, String sourceRef, String toolName, Long runAsUserId,
            String parametersDigest, String configRevision, String descriptorVersion) { }

    /**
     * Revision of a scheduler config as far as a grant is concerned: any change to what runs, when,
     * with which parameters or as whom gives a different revision. Execution metadata is not part of
     * it (updated_at moves on every fire and cannot serve).
     */
    public static String configRevision(String taskCode, String cronExpression, String toolName,
            String parametersDigest, Long runAsUserId) {
        String canonical = String.join("\u001f", String.valueOf(taskCode), String.valueOf(cronExpression),
                String.valueOf(toolName), String.valueOf(parametersDigest), String.valueOf(runAsUserId));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Joins the caller's transaction: a scheduled fire consumes inside the issuer's single transaction
     * together with the config lock and the RUNNING mark, so none of them can commit without the others.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<Consumption> consume(Binding binding, String runKey, LocalDateTime now) {
        List<AutonomousToolGrant> bound = grants.lockBoundGrants(binding.factoryId(), binding.sourceType(),
                binding.sourceRef(), binding.toolName(), binding.runAsUserId(), binding.parametersDigest());
        for (AutonomousToolGrant grant : bound) {
            Optional<AutonomousToolGrantRun> seen = runs.findByGrantIdAndRunKey(grant.getId(), runKey);
            if (seen.isPresent()) {
                // Same run again (lost response, retried event): replay, never consume or execute twice.
                return Optional.of(new Consumption(grant.getId(), seen.get().getIdempotencyKey(),
                        seen.get().getDescriptorVersion(), true));
            }
        }
        for (AutonomousToolGrant grant : bound) {
            if (grant.usableFor(binding.configRevision(), binding.descriptorVersion(), now)) {
                grant.consumeOne();
                AutonomousToolGrantRun run = runs.save(
                        AutonomousToolGrantRun.record(UUID.randomUUID().toString(), grant.getId(), runKey,
                                binding.descriptorVersion(), now));
                grants.save(grant);
                return Optional.of(new Consumption(grant.getId(), run.getIdempotencyKey(),
                        run.getDescriptorVersion(), false));
            }
        }
        return Optional.empty();
    }

    /**
     * Read-only: what the gateway recorded for a consumed run. A replayed run reports this and never
     * executes again; empty means the first attempt never reached the gateway. Looked up under the
     * descriptor version recorded at consumption, not the tool's current version.
     */
    @Transactional(readOnly = true)
    public Optional<ToolExecutionIdempotencyRecord> gatewayRecord(String factoryId, Long runAsUserId, String toolName,
            Consumption consumption) {
        return gatewayLedger.findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
                factoryId, PrincipalType.USER, String.valueOf(runAsUserId), toolName, consumption.descriptorVersion(),
                ToolCommandDigest.persistentSecretFingerprint(consumption.idempotencyKey()));
    }

    @Transactional
    public AutonomousToolGrant issue(Binding binding, int maxRuns, LocalDateTime expiresAt, Long grantedBy,
            LocalDateTime now) {
        if (maxRuns < 1 || maxRuns > MAX_RUNS_LIMIT) {
            throw new IllegalArgumentException("maxRuns must be between 1 and " + MAX_RUNS_LIMIT);
        }
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusDays(MAX_DAYS))) {
            throw new IllegalArgumentException("expiresAt must be in the future and within " + MAX_DAYS + " days");
        }
        return grants.save(AutonomousToolGrant.issue(UUID.randomUUID().toString(), binding.factoryId(),
                binding.sourceType(), binding.sourceRef(), binding.toolName(), binding.runAsUserId(),
                binding.parametersDigest(), binding.configRevision(), binding.descriptorVersion(), maxRuns,
                expiresAt, grantedBy, now));
    }

    @Transactional
    public Optional<AutonomousToolGrant> revoke(String factoryId, String grantId, Long by, LocalDateTime now) {
        Optional<AutonomousToolGrant> grant = grants.lockByIdAndFactoryId(grantId, factoryId);
        grant.ifPresent(row -> {
            row.revoke(by, now);
            grants.save(row);
        });
        return grant;
    }

    @Transactional(readOnly = true)
    public List<AutonomousToolGrant> list(String factoryId) {
        return grants.findByFactoryIdOrderByGrantedAtDesc(factoryId);
    }
}
