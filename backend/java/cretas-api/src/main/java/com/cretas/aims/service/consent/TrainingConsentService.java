package com.cretas.aims.service.consent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 训练授权查询: 该工厂是否书面授权训练用途 (SmartBI 库 {@code training_consent}, 规则与
 * {@code backend/python/smartbi/services/training_consent.py} 一致: {@code private} /
 * {@code shared_anon} = 已授权; 无行 / {@code none} / 未知值 = 未授权)。
 *
 * <p>Steve 2026-10-24 规则: 授权只决定「原样存还是脱敏后存」, 不决定存不存 ——
 * 意图对应默认用于训练, 配方 / 工艺参数类内容任何工厂都不存 (见 {@link TrainingSampleRules})。
 *
 * <p>读不到授权表 (SmartBI 数据源未启用、连接失败、表不存在) 一律按「未授权」处理,
 * 即脱敏后存, 绝不把「查不到」当成「已授权」。结果按工厂缓存 60 秒, 失败不缓存。
 */
@Slf4j
@Service
public class TrainingConsentService {

    /** 已授权 (原样存) 的授权范围 (与 Python CORPUS_CAPTURE_SCOPES 相同)。 */
    public static final Set<String> CORPUS_CAPTURE_SCOPES = Set.of("private", "shared_anon");

    /** 撤回 (withdrawn_at 非空, 训练条款第十条) 读作 'withdrawn', 与 Python SELECT_SCOPE_SQL 相同。 */
    static final String SELECT_SCOPE_SQL = "SELECT CASE WHEN withdrawn_at IS NOT NULL THEN 'withdrawn' "
            + "ELSE consent_scope END FROM training_consent WHERE factory_id = ?";

    /** 一家工厂对训练样本的状态。只有 AUTHORIZED / DEFAULT 可以写样本。 */
    public enum Status {
        /** 书面授权 (private / shared_anon): 原样存。 */
        AUTHORIZED,
        /** 未授权: 默认参与训练, 脱敏后存。 */
        DEFAULT,
        /** 客户撤回: 退出训练, 不存。 */
        WITHDRAWN,
        /** 查不到 (数据源不可用 / 查询出错 / 没有工厂): 可能掩盖了撤回, 不存。 */
        UNKNOWN;

        public boolean storable() {
            return this == AUTHORIZED || this == DEFAULT;
        }
    }
    private static final long CACHE_MILLIS = 60_000L;

    private final ObjectProvider<DataSource> smartbiDataSource;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Set<String> loggedDenials = ConcurrentHashMap.newKeySet();

    private record Cached(Status status, long expiresAt) {
    }

    @Autowired
    public TrainingConsentService(@Qualifier("smartbiPostgresDataSource") ObjectProvider<DataSource> smartbiDataSource) {
        this(smartbiDataSource, Clock.systemUTC());
    }

    TrainingConsentService(ObjectProvider<DataSource> smartbiDataSource, Clock clock) {
        this.smartbiDataSource = smartbiDataSource;
        this.clock = clock;
    }

    /** 该工厂是否书面授权训练用途 (原样存)。 */
    public boolean isAuthorized(String factoryId) {
        return status(factoryId) == Status.AUTHORIZED;
    }

    /** 该工厂的训练样本状态; 写样本前先看 {@link Status#storable()}。 */
    public Status status(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            logOnce("no factory, sample not stored", null);
            return Status.UNKNOWN;
        }
        long now = clock.millis();
        Cached cached = cache.get(factoryId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.status();
        }
        DataSource dataSource = smartbiDataSource.getIfAvailable();
        if (dataSource == null) {
            logOnce("smartbi datasource unavailable, sample not stored", factoryId);
            return Status.UNKNOWN;
        }
        Status status;
        try {
            List<String> scopes = new JdbcTemplate(dataSource)
                    .queryForList(SELECT_SCOPE_SQL, String.class, factoryId);
            String scope = scopes.isEmpty() ? null : scopes.get(0);
            if ("withdrawn".equals(scope)) {
                status = Status.WITHDRAWN;
            } else if (scope != null && CORPUS_CAPTURE_SCOPES.contains(scope)) {
                status = Status.AUTHORIZED;
            } else {
                status = Status.DEFAULT;
            }
        } catch (RuntimeException e) {
            log.warn("training_consent lookup failed, factory {} sample not stored: {}",
                    factoryId, e.toString());
            return Status.UNKNOWN;
        }
        cache.put(factoryId, new Cached(status, now + CACHE_MILLIS));
        if (status != Status.AUTHORIZED) {
            logOnce("training status " + status, factoryId);
        }
        return status;
    }

    private void logOnce(String reason, String factoryId) {
        if (loggedDenials.add(reason + "|" + factoryId)) {
            log.info("training samples: {} (factory={}); not logged again", reason, factoryId);
        }
    }
}
