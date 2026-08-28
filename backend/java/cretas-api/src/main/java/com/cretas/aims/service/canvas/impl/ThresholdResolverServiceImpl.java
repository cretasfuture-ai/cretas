package com.cretas.aims.service.canvas.impl;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Canvas-Thresholds 解析服务实现 (Phase A P0-1).
 *
 * <p>使用本地 Caffeine cache. 多实例部署时, 任何节点写入后通过
 * {@link com.cretas.aims.controller.CanvasThresholdsController} 调用 {@link #invalidate}
 * 失效本地缓存; 跨节点失效暂未实现 (TTL 兜底)。Phase B+ 可加 Redis pub/sub broadcast 做秒级失效。
 *
 * <h2>两档 TTL —— 因为「晚 5 分钟变色」和「晚 5 分钟拦货」不是同一件事 (2026-08-29)</h2>
 * <table border="1">
 *   <tr><th></th><th>TTL</th><th>读到旧值的后果</th></tr>
 *   <tr><td>预警类 (绝大多数, 例 {@code inventory.turnover.red})</td>
 *       <td>{@value #STANDARD_CACHE_TTL_MINUTES} 分钟</td>
 *       <td>看板上晚一会儿变色 —— 没人受伤</td></tr>
 *   <tr><td><b>闸门类</b> ({@link ThresholdKeys#GATING_KEYS})</td>
 *       <td><b>{@value #GATING_CACHE_TTL_SECONDS} 秒</b></td>
 *       <td>🔴 <b>动作被挡下来</b>: 管理员在 A 节点把
 *           {@code sales.delivery.over_ship_rate} 调宽了, B 节点仍按旧值 409 拦货 ——
 *           现场是「我刚调宽了怎么还是发不了」, 而每一层都不报错</td></tr>
 * </table>
 *
 * <p>⚠️ 残留窗口: 跨节点最长仍有 {@value #GATING_CACHE_TTL_SECONDS} 秒不一致 (写入节点是立即的,
 * 走 {@link #invalidate})。⛔ 这不是「解决了跨节点失效」—— 那要等 Redis pub/sub;
 * 这里只是把闸门类的不一致窗口从 5 分钟压到 {@value #GATING_CACHE_TTL_SECONDS} 秒。
 *
 * <p><b>为什么不是「闸门类完全不走缓存」</b>: 缓存里存着
 * {@link #ABSENT_SENTINEL}, 它挡掉的是「这个 key 一行都没有」时的每次调用都打 DB。
 * 完全不缓存等于把这层保护也去掉, 而调用点是不是在循环里<b>不是 resolver 能保证的属性</b>
 * (今天 {@code SalesServiceImpl.resolveOverShipLimit} 在逐行循环外只调一次, 明天未必)。
 * 短 TTL 同时给了「有界的陈旧」和「有界的负载」。
 *
 * <p>⛔ 「哪些 key 算闸门」的定义<b>只有一处</b>: {@link ThresholdKeys#GATING_KEYS}。
 * 本类不重写一份判据, 只调 {@link ThresholdKeys#isGating(String)}。
 *
 * <p>解析优先级: per-factory → global ({@code "*"}) → caller fallback default. 任何 fallback
 * 都不抛异常, 保证业务 service 在 DB 不可达 / 缓存 miss / 行不存在时仍能继续运行。
 *
 * @since Canvas Phase A (2026-05-21)
 */
@Slf4j
@Service
public class ThresholdResolverServiceImpl implements ThresholdResolverService {

    /** 预警类阈值的缓存 TTL (分钟). */
    static final long STANDARD_CACHE_TTL_MINUTES = 5;

    /**
     * 闸门类阈值的缓存 TTL (秒).
     *
     * <p>取值理由: 管理员「改完 → 回到现场 → 重试那个被挡的动作」的最短现实间隔远大于 10 秒,
     * 所以 10 秒足以让「我刚调宽了怎么还是发不了」这个现场消失; 同时它仍是一个<b>有界</b>的
     * 缓存, 挡得住调用点被挪进循环时的 DB 打击。
     */
    static final long GATING_CACHE_TTL_SECONDS = 10;

    private final FactoryThresholdRepository repository;

    /**
     * Sentinel object cached when DB has no row for the key — distinguishes "looked up,
     * absent" (use caller's fallback) from "never looked up" (re-query DB). Without this,
     * a missing row would re-hit DB on every call.
     */
    private static final FactoryThreshold ABSENT_SENTINEL = new FactoryThreshold();

    /**
     * 预警类 key 的缓存。Cache key = {@code factoryId + ":" + thresholdKey}; value is the
     * FactoryThreshold or {@link #ABSENT_SENTINEL}. Resolver also caches the global lookup
     * separately so a per-factory miss followed by a global hit doesn't traverse DB twice
     * on subsequent hits.
     */
    private final Cache<String, FactoryThreshold> cache;

    /**
     * 闸门类 key 的缓存 —— 同样的结构, 只是 TTL 短得多。
     *
     * <p>{@code maximumSize} 小得多是因为闸门类 key 按定义就是少数几个
     * ({@link ThresholdKeys#GATING_KEYS}), 乘以工厂数也远到不了 5000。
     */
    private final Cache<String, FactoryThreshold> gatingCache;

    @Autowired
    public ThresholdResolverServiceImpl(FactoryThresholdRepository repository) {
        this(repository, Ticker.systemTicker());
    }

    /**
     * 测试用构造器 —— 传一个可推进的 {@link Ticker} 才能<b>在不 sleep 的前提下</b>
     * 真正观测到两档 TTL 的行为差异。
     *
     * <p>⚠️ 没有它的话, 「闸门类走短 TTL」这件事只能靠断言两个 Cache 对象不同来"证明",
     * 而那种断言守的是实现细节, 不是行为。
     */
    ThresholdResolverServiceImpl(FactoryThresholdRepository repository, Ticker ticker) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(STANDARD_CACHE_TTL_MINUTES))
                .maximumSize(5000)
                .ticker(ticker)
                .build();
        this.gatingCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(GATING_CACHE_TTL_SECONDS))
                .maximumSize(500)
                .ticker(ticker)
                .build();
    }

    // ==================== Typed getters ====================

    @Override
    public int getInteger(String factoryId, String thresholdKey, int defaultValue) {
        Optional<FactoryThreshold> opt = resolve(factoryId, thresholdKey);
        if (opt.isEmpty()) {
            return defaultValue;
        }
        try {
            return opt.get().asInteger();
        } catch (Exception e) {
            log.warn("threshold {} (factoryId={}) parse-as-integer failed value={}, falling back to default {}",
                    thresholdKey, factoryId, opt.get().getThresholdValue(), defaultValue, e);
            return defaultValue;
        }
    }

    @Override
    public BigDecimal getBigDecimal(String factoryId, String thresholdKey, BigDecimal defaultValue) {
        Optional<FactoryThreshold> opt = resolve(factoryId, thresholdKey);
        if (opt.isEmpty()) {
            return defaultValue;
        }
        try {
            return opt.get().asBigDecimal();
        } catch (Exception e) {
            log.warn("threshold {} (factoryId={}) parse-as-decimal failed value={}, falling back to default {}",
                    thresholdKey, factoryId, opt.get().getThresholdValue(), defaultValue, e);
            return defaultValue;
        }
    }

    @Override
    public double getDouble(String factoryId, String thresholdKey, double defaultValue) {
        Optional<FactoryThreshold> opt = resolve(factoryId, thresholdKey);
        if (opt.isEmpty()) {
            return defaultValue;
        }
        try {
            return opt.get().asDouble();
        } catch (Exception e) {
            log.warn("threshold {} (factoryId={}) parse-as-double failed value={}, falling back to default {}",
                    thresholdKey, factoryId, opt.get().getThresholdValue(), defaultValue, e);
            return defaultValue;
        }
    }

    @Override
    public String getString(String factoryId, String thresholdKey, String defaultValue) {
        Optional<FactoryThreshold> opt = resolve(factoryId, thresholdKey);
        return opt.map(FactoryThreshold::getThresholdValue).orElse(defaultValue);
    }

    @Override
    public Optional<FactoryThreshold> resolve(String factoryId, String thresholdKey) {
        if (factoryId == null || thresholdKey == null) {
            return Optional.empty();
        }
        // Step 1: per-factory lookup (skip if caller already asked for global)
        if (!FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID.equals(factoryId)) {
            FactoryThreshold perFactory = loadFromCacheOrDb(factoryId, thresholdKey);
            if (perFactory != null && perFactory != ABSENT_SENTINEL && Boolean.TRUE.equals(perFactory.getEnabled())) {
                return Optional.of(perFactory);
            }
        }
        // Step 2: global fallback
        FactoryThreshold global = loadFromCacheOrDb(
                FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID, thresholdKey);
        if (global != null && global != ABSENT_SENTINEL && Boolean.TRUE.equals(global.getEnabled())) {
            return Optional.of(global);
        }
        return Optional.empty();
    }

    /**
     * 这个 key 该用哪一档缓存。
     *
     * <p>⚠️ 判据只有一处 —— {@link ThresholdKeys#isGating(String)}。
     * 注意它按 <b>thresholdKey</b> 分档而不是按 factoryId: 同一个闸门 key 的
     * per-factory 行和全局 {@code '*'} 行<b>都</b>要走短 TTL, 否则
     * 「工厂把自己的 override 删了、要回落到全局」那条路依然会卡 5 分钟。
     */
    private Cache<String, FactoryThreshold> cacheFor(String thresholdKey) {
        return ThresholdKeys.isGating(thresholdKey) ? gatingCache : cache;
    }

    /** 两档缓存的快照, 供失效操作遍历 —— ⛔ 新增一档时只需要改这里。 */
    private List<Cache<String, FactoryThreshold>> allCaches() {
        return List.of(cache, gatingCache);
    }

    /**
     * Cache-then-DB lookup. Returns {@link #ABSENT_SENTINEL} (not null) when DB has no row,
     * so a missing key is itself cached (avoids per-call DB hit for keys that never exist).
     */
    private FactoryThreshold loadFromCacheOrDb(String factoryId, String thresholdKey) {
        String cacheKey = cacheKey(factoryId, thresholdKey);
        return cacheFor(thresholdKey).get(cacheKey, k -> {
            FactoryThreshold loaded = repository
                    .findByFactoryIdAndThresholdKey(factoryId, thresholdKey)
                    .orElse(null);
            return loaded != null ? loaded : ABSENT_SENTINEL;
        });
    }

    // ==================== Cache management ====================

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 两档都失效, 不是「按 isGating 挑一档失效」—— 那样在
     * {@link ThresholdKeys#GATING_KEYS} 增删一个 key 之后, 旧那一档里的条目会成为
     * <b>永远失效不掉</b>的僵尸 (进程重启前一直被读到)。失效多打一档零代价。
     */
    @Override
    public void invalidate(String factoryId, String thresholdKey) {
        String key = cacheKey(factoryId, thresholdKey);
        allCaches().forEach(c -> c.invalidate(key));
    }

    @Override
    public void invalidateFactory(String factoryId) {
        if (factoryId == null) return;
        // Caffeine has no prefix-invalidate; iterate snapshot keyset.
        allCaches().forEach(c -> c.asMap().keySet().stream()
                .filter(k -> k.startsWith(factoryId + ":"))
                .forEach(c::invalidate));
    }

    @Override
    public void invalidateAll() {
        allCaches().forEach(Cache::invalidateAll);
    }

    private String cacheKey(String factoryId, String thresholdKey) {
        return Objects.requireNonNull(factoryId) + ":" + Objects.requireNonNull(thresholdKey);
    }
}
