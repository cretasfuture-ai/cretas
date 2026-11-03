package com.cretas.aims.service.secrecy;

import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工厂级保密配置的唯一判断点: {@link #isOn(String, SecrecyItem)}。
 *
 * <ul>
 *   <li>按工厂缓存 {@link #CACHE_TTL} (10 秒); 平台改了设置立即失效 ({@link #invalidate}, 提交后再清一次)。</li>
 *   <li>读库失败、工厂为 null / 空 / {@code PLATFORM} → 一律 {@code true} (偏向保密, fail-closed)。读失败不缓存。</li>
 *   <li>没有行 → 默认值: 关; {@link SecrecyItem#AI_SECRECY_AUDIT} 跟随 {@link SecrecyItem#PROCESS_SECRET}。</li>
 * </ul>
 *
 * <p>⛔ 调用方必须传<b>数据或策略所属的工厂</b>, 不是请求者 / 写入者的工厂 (出口裁剪逐行按行的工厂判)。</p>
 */
@Service
public class FactorySecrecyConfig {

    private static final Logger log = LoggerFactory.getLogger(FactorySecrecyConfig.class);

    public static final Duration CACHE_TTL = Duration.ofSeconds(10);
    static final String PLATFORM = "PLATFORM";

    /** 存的值: ON / OFF / FOLLOW (跟随工艺保密); 没有行 = UNSET。 */
    public enum Stored { UNSET, ON, OFF, FOLLOW }

    private record Cached(Map<SecrecyItem, Stored> stored, long loadedAtMillis) { }

    private final FactorySecrecySettingRepository repository;
    /** 读配置在自己的只读事务里 (挂起调用方事务): 读失败不把调用方事务标成只能回滚。null = 直接读 (单测)。 */
    private final TransactionTemplate readTx;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 生产: 主库事务管理器是 {@code @Primary} 的 primaryTransactionManager; 切片测试里只有一个 transactionManager。
     * 两种都由 {@link ObjectProvider#getIfAvailable()} 取到。
     */
    @Autowired
    public FactorySecrecyConfig(FactorySecrecySettingRepository repository,
                                ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this(repository, transactionManagers == null ? null : transactionManagers.getIfAvailable());
    }

    public FactorySecrecyConfig(FactorySecrecySettingRepository repository,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        if (transactionManager == null) {
            this.readTx = null;
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.setReadOnly(true);
            this.readTx = template;
        }
    }

    private FactorySecrecyConfig() {
        this.repository = null;
        this.readTx = null;
    }

    private static final FactorySecrecyConfig ALL_ON = new FactorySecrecyConfig();

    /**
     * 没有 Spring 容器时 (单测直接 new 被测类) 的默认实例: 每一项都当开启 —— 与配置化之前的行为完全一致,
     * 也是 fail-closed 的方向。生产一律由 Spring 注入真实实例 ({@code FactorySecrecyBeanWiringContractTest})。
     */
    public static FactorySecrecyConfig allOn() {
        return ALL_ON;
    }

    /** 工厂 {@code factoryId} 的 {@code item} 此刻是否开启。读不到 / 工厂不明 → true。 */
    public boolean isOn(String factoryId, SecrecyItem item) {
        if (item == null) throw new IllegalArgumentException("item");
        if (repository == null || unknownFactory(factoryId)) return true;
        Optional<Map<SecrecyItem, Stored>> stored = load(factoryId.trim());
        if (stored.isEmpty()) return true;
        return resolve(stored.get(), item);
    }

    /** 该工厂全部项的生效值 (feature-config 下发、平台页展示用)。读不到 / 工厂不明 → 全部 true。 */
    public Map<SecrecyItem, Boolean> effective(String factoryId) {
        Map<SecrecyItem, Boolean> out = new EnumMap<>(SecrecyItem.class);
        Optional<Map<SecrecyItem, Stored>> stored = repository == null || unknownFactory(factoryId)
                ? Optional.empty() : load(factoryId.trim());
        for (SecrecyItem item : SecrecyItem.values()) {
            out.put(item, stored.map(s -> resolve(s, item)).orElse(true));
        }
        return out;
    }

    /** {@code {CODE: bool}} 形式 (feature-config 的 {@code secrecy})。 */
    public Map<String, Boolean> effectiveByCode(String factoryId) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        effective(factoryId).forEach((item, on) -> out.put(item.name(), on));
        return Collections.unmodifiableMap(out);
    }

    /** 直接读库 (不走缓存, 读失败照样抛): 平台写入 / 展示「存的是什么」用。 */
    public Map<SecrecyItem, Stored> storedFresh(String factoryId) {
        if (repository == null) throw new IllegalStateException("no repository");
        return readStored(factoryId);
    }

    /** 平台改了设置后调用: 立即清, 事务提交后再清一次 (提交前并发读可能把旧值重新载入)。 */
    public void invalidate(String factoryId) {
        if (factoryId == null) return;
        String key = factoryId.trim();
        cache.remove(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.remove(key);
                }

                @Override
                public void afterCompletion(int status) {
                    cache.remove(key);
                }
            });
        }
    }

    static boolean unknownFactory(String factoryId) {
        return factoryId == null || factoryId.isBlank() || PLATFORM.equalsIgnoreCase(factoryId.trim());
    }

    static boolean resolve(Map<SecrecyItem, Stored> stored, SecrecyItem item) {
        Stored value = stored.getOrDefault(item, Stored.UNSET);
        if (value == Stored.ON) return true;
        if (value == Stored.OFF) return false;
        // UNSET 或 FOLLOW: 按默认值
        if (item.defaultValue() == SecrecyItem.Default.FOLLOW_PROCESS_SECRET
                && (value == Stored.FOLLOW || value == Stored.UNSET)) {
            return resolve(stored, SecrecyItem.PROCESS_SECRET);
        }
        return false;
    }

    private Optional<Map<SecrecyItem, Stored>> load(String factoryId) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(factoryId);
        if (cached != null && now - cached.loadedAtMillis() < CACHE_TTL.toMillis()) {
            return Optional.of(cached.stored());
        }
        try {
            Map<SecrecyItem, Stored> stored = readTx == null ? readStored(factoryId)
                    : readTx.execute(status -> readStored(factoryId));
            cache.put(factoryId, new Cached(stored, now));
            return Optional.of(stored);
        } catch (RuntimeException e) {
            // 读不到配置: 一律当开启 (偏向保密), 不缓存失败
            log.error("[secrecy-config] read failed for factory {}, treating every item as ON: {}",
                    factoryId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Map<SecrecyItem, Stored> readStored(String factoryId) {
        Map<SecrecyItem, Stored> stored = new EnumMap<>(SecrecyItem.class);
        for (FactorySecrecySetting row : repository.findByFactoryId(factoryId)) {
            Optional<SecrecyItem> item = SecrecyItem.fromCode(row.getItemCode());
            if (item.isEmpty()) continue;   // 库里有 CHECK, 这里只是防御
            stored.put(item.get(), row.getEnabled() == null ? Stored.FOLLOW
                    : row.getEnabled() ? Stored.ON : Stored.OFF);
        }
        return Collections.unmodifiableMap(stored);
    }
}
