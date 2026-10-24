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

    static final String SELECT_SCOPE_SQL = "SELECT consent_scope FROM training_consent WHERE factory_id = ?";
    private static final long CACHE_MILLIS = 60_000L;

    private final ObjectProvider<DataSource> smartbiDataSource;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Set<String> loggedDenials = ConcurrentHashMap.newKeySet();

    private record Cached(boolean allowed, long expiresAt) {
    }

    @Autowired
    public TrainingConsentService(@Qualifier("smartbiPostgresDataSource") ObjectProvider<DataSource> smartbiDataSource) {
        this(smartbiDataSource, Clock.systemUTC());
    }

    TrainingConsentService(ObjectProvider<DataSource> smartbiDataSource, Clock clock) {
        this.smartbiDataSource = smartbiDataSource;
        this.clock = clock;
    }

    /**
     * 该工厂是否书面授权训练用途。{@code true} 原样存, {@code false} 调用方须脱敏后存。
     */
    public boolean isAuthorized(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            logDenialOnce("no factory", null);
            return false;
        }
        long now = clock.millis();
        Cached cached = cache.get(factoryId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.allowed();
        }
        DataSource dataSource = smartbiDataSource.getIfAvailable();
        if (dataSource == null) {
            logDenialOnce("smartbi datasource unavailable", factoryId);
            return false;
        }
        boolean allowed;
        try {
            List<String> scopes = new JdbcTemplate(dataSource)
                    .queryForList(SELECT_SCOPE_SQL, String.class, factoryId);
            allowed = !scopes.isEmpty() && CORPUS_CAPTURE_SCOPES.contains(scopes.get(0));
        } catch (RuntimeException e) {
            log.warn("training_consent lookup failed, treating factory {} as not authorized: {}",
                    factoryId, e.toString());
            return false;
        }
        cache.put(factoryId, new Cached(allowed, now + CACHE_MILLIS));
        if (!allowed) {
            logDenialOnce("no training authorization, samples are desensitized", factoryId);
        }
        return allowed;
    }

    private void logDenialOnce(String reason, String factoryId) {
        if (loggedDenials.add(reason + "|" + factoryId)) {
            log.info("training samples desensitized: {} (factory={}); not logged again",
                    reason, factoryId);
        }
    }
}
