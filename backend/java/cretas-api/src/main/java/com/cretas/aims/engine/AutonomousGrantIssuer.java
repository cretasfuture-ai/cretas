package com.cretas.aims.engine;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ai.AutonomousToolGrantRepository;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of issuing, listing and revoking scheduler grants, each in one transaction.
 *
 * <p>Who may manage grants: the request's factory user, re-read from the database and locked for
 * the decision (active, same factory, role factory_super_admin now). A factory super admin holds
 * every permission by the existing permission semantics, so permission overrides cannot take this
 * away; what stops a grantor is revoking the grant, deactivating the account or changing its role
 * (re-checked at every scheduled consumption, see {@link #consumeForScheduledFire}).
 *
 * <p>Issuing locks the grantor's user row (shared) and the config row (exclusive) before checking,
 * so a concurrent deactivation, role change or config edit commits either before the check (and is
 * seen) or after the grant (and then stops its runs at the gateway / by revision). One active grant
 * per binding: issuing again while one is active is refused rather than silently adding budget.
 * Editing or disabling a config revokes its active grants in the same transaction as the save, so
 * restoring the old content or re-enabling never revives them.
 */
@Service
@RequiredArgsConstructor
public class AutonomousGrantIssuer {
    public static final String GRANT_ROLE = "factory_super_admin";

    private final AutonomousGrantService grants;
    private final AutonomousToolGrantRepository grantRepository;
    private final FactorySchedulerConfigRepository schedules;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    /** The current grant administrator of this factory, read from the database (no lock). */
    @Transactional(readOnly = true)
    public User currentAdmin(String factoryId, Long actorId) {
        return checkedAdmin(factoryId, entityManager.find(User.class, actorId));
    }

    @Transactional
    public AutonomousToolGrant issue(String factoryId, Long actorId, long configId, String toolName,
            String descriptorVersion, int maxRuns, LocalDateTime expiresAt, LocalDateTime now) {
        User grantor = checkedAdmin(factoryId, entityManager.find(User.class, actorId, LockModeType.PESSIMISTIC_READ));
        FactorySchedulerConfig config = entityManager.find(FactorySchedulerConfig.class, configId,
                LockModeType.PESSIMISTIC_WRITE);
        if (config == null || !factoryId.equals(config.getFactoryId())) {
            throw new BusinessException(404, "定时任务不存在或不属于本厂");
        }
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new BusinessException(409, "该定时任务已停用，不能签发授权").withHint("先启用任务，再签发授权");
        }
        if (!toolName.equals(config.getToolOrMethod())) {
            throw new BusinessException(400, "授权的工具与该任务配置的工具不一致：" + config.getToolOrMethod());
        }
        if (!Objects.equals(grantor.getId(), config.getRunAsUserId())) {
            throw new BusinessException(409, "只能为你自己启用（执行人是你）的任务签发授权")
                    .withHint("先以你的账号启用该定时任务，再签发授权");
        }
        AutonomousGrantService.Binding binding = SchedulerGrantBinding.of(config, descriptorVersion, objectMapper);
        boolean active = grantRepository.lockBoundGrants(binding.factoryId(), binding.sourceType(),
                        binding.sourceRef(), binding.toolName(), binding.runAsUserId(), binding.parametersDigest())
                .stream().anyMatch(grant -> grant.usableFor(binding.configRevision(), binding.descriptorVersion(), now));
        if (active) {
            throw new BusinessException(409, "该任务已有生效中的授权，不能重复签发")
                    .withHint("要增加次数或延长期限，先撤销现有授权再重新签发");
        }
        try {
            return grants.issue(binding, maxRuns, expiresAt, grantor.getId(), now);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(400, invalid.getMessage());
        }
    }

    @Transactional
    public AutonomousToolGrant revoke(String factoryId, Long actorId, String grantId, LocalDateTime now) {
        User by = checkedAdmin(factoryId, entityManager.find(User.class, actorId, LockModeType.PESSIMISTIC_READ));
        return grants.revoke(factoryId, grantId, by.getId(), now)
                .orElseThrow(() -> new BusinessException(404, "授权不存在或不属于本厂"));
    }

    /**
     * Consumes a grant for one scheduled fire against the config as it is now, in one physical
     * transaction: the config row is locked FOR UPDATE first (lock order config then grant), the fire's
     * binding must equal the binding of the locked row, the grant's budget is decremented and its run
     * recorded ({@link AutonomousGrantService#consume} joins this transaction), and the config is
     * marked RUNNING. Either all of it commits or none of it does, so a crash can never leave a
     * consumed run beside a config still showing the previous slot. An edit, disable or delete of the
     * config either committed before (and is seen here) or waits for this commit. A replay consumes
     * nothing and leaves the config as the executing instance left it. The tool runs outside.
     *
     * <p>A new run is only charged to a grant whose grantor is, now, an active user of this factory
     * with role factory_super_admin; losing that role or being deactivated stops the grantor's grants
     * here. The grantor row is read without a lock (issuing locks user then config; taking them in the
     * other order here could deadlock), so a role change committing during this transaction may miss
     * this one run, which the gateway still checks against the run-as user's current permissions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AutonomousGrantService.Consumption> consumeForScheduledFire(
            AutonomousGrantService.Binding fireBinding, String runKey, LocalDateTime now) {
        long configId;
        try {
            configId = Long.parseLong(fireBinding.sourceRef());
        } catch (NumberFormatException notAConfig) {
            return Optional.empty();
        }
        FactorySchedulerConfig config = entityManager.find(FactorySchedulerConfig.class, configId,
                LockModeType.PESSIMISTIC_WRITE);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())
                || !Objects.equals(fireBinding.factoryId(), config.getFactoryId())) {
            return Optional.empty();
        }
        AutonomousGrantService.Binding current =
                SchedulerGrantBinding.of(config, fireBinding.descriptorVersion(), objectMapper);
        if (!current.equals(fireBinding)) {
            return Optional.empty();
        }
        Optional<AutonomousGrantService.Consumption> consumed = grants.consume(current, runKey, now,
                grantorId -> stillAdmin(config.getFactoryId(), grantorId));
        if (consumed.isPresent() && !consumed.get().replay()) {
            entityManager.createQuery("update FactorySchedulerConfig c set c.lastExecutionStatus = 'RUNNING',"
                            + " c.lastExecutedAt = :now, c.lastExecutionError = null where c.id = :id")
                    .setParameter("now", now).setParameter("id", configId).executeUpdate();
        }
        return consumed;
    }

    /**
     * Records the gateway outcome of a consumed run, once: a later call (a replay, a duplicate) never
     * overwrites it. Outcome is SUCCEEDED / FAILED / DENIED / OUTCOME_UNKNOWN.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordOutcome(String grantId, String runKey, String outcome, String auditEventId, LocalDateTime now) {
        return entityManager.createQuery("update AutonomousToolGrantRun r set r.outcome = :outcome,"
                        + " r.auditEventId = :audit, r.resolvedAt = :now"
                        + " where r.grantId = :grantId and r.runKey = :runKey and r.outcome is null")
                .setParameter("outcome", outcome).setParameter("audit", auditEventId).setParameter("now", now)
                .setParameter("grantId", grantId).setParameter("runKey", runKey).executeUpdate();
    }

    /** The outcome recorded for a consumed run, if it was resolved. */
    @Transactional(readOnly = true)
    public Optional<String> recordedOutcome(String grantId, String runKey) {
        return entityManager.createQuery("select r.outcome from AutonomousToolGrantRun r"
                        + " where r.grantId = :grantId and r.runKey = :runKey and r.outcome is not null", String.class)
                .setParameter("grantId", grantId).setParameter("runKey", runKey).getResultStream().findFirst();
    }

    /**
     * Saves a scheduler config and, when what it runs changed or it is now disabled, revokes its
     * active grants in the same transaction.
     *
     * @param revisionBefore the config revision before the request's changes (null for a new config)
     */
    @Transactional
    public FactorySchedulerConfig saveConfig(FactorySchedulerConfig config, String revisionBefore, Long editorId,
            LocalDateTime now) {
        FactorySchedulerConfig saved = schedules.save(config);
        boolean disabled = !Boolean.TRUE.equals(saved.getEnabled());
        if (saved.getId() != null && (disabled || !Objects.equals(revisionBefore, revisionOf(saved)))) {
            revokeActiveForConfig(saved.getFactoryId(), saved.getId(), editorId, now);
        }
        return saved;
    }

    /** Config revision as far as a grant is concerned; independent of the tool version. */
    public String revisionOf(FactorySchedulerConfig config) {
        return SchedulerGrantBinding.of(config, "", objectMapper).configRevision();
    }

    private int revokeActiveForConfig(String factoryId, Long configId, Long by, LocalDateTime now) {
        return entityManager.createQuery("update AutonomousToolGrant g set g.revokedAt = :now, g.revokedBy = :by,"
                        + " g.version = g.version + 1 where g.factoryId = :factoryId and g.sourceType = 'SCHEDULER'"
                        + " and g.sourceRef = :sourceRef and g.revokedAt is null")
                .setParameter("now", now).setParameter("by", by).setParameter("factoryId", factoryId)
                .setParameter("sourceRef", String.valueOf(configId)).executeUpdate();
    }

    private boolean stillAdmin(String factoryId, Long grantorId) {
        User grantor = grantorId == null ? null : entityManager.find(User.class, grantorId);
        return grantor != null && Boolean.TRUE.equals(grantor.getIsActive()) && factoryId.equals(grantor.getFactoryId())
                && GRANT_ROLE.equalsIgnoreCase(grantor.getRoleCode());
    }

    private static User checkedAdmin(String factoryId, User user) {
        if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || !factoryId.equals(user.getFactoryId())) {
            throw new BusinessException(403, "当前账号已停用或不属于本厂，不能管理自动任务授权").withHint("请重新登录后再试");
        }
        if (!GRANT_ROLE.equalsIgnoreCase(user.getRoleCode())) {
            throw new BusinessException(403, "当前账号已不是本厂超级管理员，不能管理自动任务授权")
                    .withCode("AUTONOMOUS_GRANT_ADMIN_REQUIRED")
                    .withHint("角色以数据库为准；角色刚被调整的话请重新登录");
        }
        return user;
    }
}
