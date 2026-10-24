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
 * 训练授权查询 —— Java 侧写入任何训练 / 微调 / 标注用样本之前的唯一判断点。
 *
 * <p>Owner 规则 (Steve, 2026-10-22): 工厂的真实数据未经该工厂明确授权, 不进入任何训练集。
 * 授权唯一来源是 SmartBI 库的 {@code training_consent} 表 (Python 迁移 V20261001_01,
 * 规则与 {@code backend/python/smartbi/services/training_consent.py} 一致):
 * {@code private} / {@code shared_anon} 允许入库; 无行 / {@code none} / 未知值 → 不允许。
 *
 * <p>读不到授权表 (SmartBI 数据源未启用、连接失败、表不存在) 一律按「未授权」处理 (fail closed),
 * 绝不把「查不到」当成「同意了」。结果按工厂缓存 60 秒, 失败不缓存。
 */
@Slf4j
@Service
public class TrainingConsentService {

    /** 允许把该工厂样本存入训练语料的授权范围 (与 Python CORPUS_CAPTURE_SCOPES 相同)。 */
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
     * 该工厂是否授权把它的真实数据存入训练语料。{@code false} 时调用方不得写入样本。
     */
    public boolean mayStoreTrainingData(String factoryId) {
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
            log.warn("training_consent lookup failed, treating factory {} as not consented: {}",
                    factoryId, e.toString());
            return false;
        }
        cache.put(factoryId, new Cached(allowed, now + CACHE_MILLIS));
        if (!allowed) {
            logDenialOnce("no training consent", factoryId);
        }
        return allowed;
    }

    private void logDenialOnce(String reason, String factoryId) {
        if (loggedDenials.add(reason + "|" + factoryId)) {
            log.info("training sample not stored: {} (factory={}); further skips are not logged",
                    reason, factoryId);
        }
    }
}
