package com.cretas.aims.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存配置 - 支持 Redis 和内存缓存降级
 *
 * 当 Redis 可用时使用 Redis 缓存，否则降级到内存缓存
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * 创建支持 Java 8 时间类型的 Redis JSON 序列化器。
     *
     * <p>⚠️ 2026-08-29 改成 {@code public static}：闸要拿**这一份**去做往返验证。
     * 在测试里另建一个 ObjectMapper 就是形态 D（同一个东西有两份，它一定会漂）——
     * 那样的闸只能证明「我抄的那份能往返」，证明不了 Redis 里那份能。
     *
     * <p>它守的是一次真实事故：{@code getOnTimeDeliveryReport} 往结果里放了
     * {@code List.subList()} 的返回值（{@code ArrayList$SubList}）。
     * 那个类型 <b>能被写出去、读不回来</b>（没有无参构造），于是缓存自我投毒：
     * 第一次调用成功并写入毒条目，此后每次命中缓存都 500。
     */
    public static GenericJackson2JsonRedisSerializer createRedisSerializer() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        om.activateDefaultTyping(om.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        return new GenericJackson2JsonRedisSerializer(om);
    }

    /**
     * RedisTemplate<String, Object> — CacheService 需要此类型
     */
    @Bean
    public RedisTemplate<String, Object> redisObjectTemplate() {
        if (redisConnectionFactory == null) {
            return null;
        }
        GenericJackson2JsonRedisSerializer serializer = createRedisSerializer();
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        return template;
    }

    /**
     * 缓存管理器 — Redis 可用时用 Redis，否则内存降级
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        if (redisConnectionFactory != null) {
            return buildRedisCacheManager(redisConnectionFactory);
        }
        return buildSimpleCacheManager();
    }

    private CacheManager buildRedisCacheManager(RedisConnectionFactory connectionFactory) {
        log.info("使用 Redis 缓存管理器");

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(createRedisSerializer()));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("aiAnalysisResults", defaultConfig.entryTtl(Duration.ofDays(7)));
        cacheConfigurations.put("aiIntents", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("aiQuota", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("dashboardStats", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        // AI意图服务缓存
        cacheConfigurations.put("allIntents", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentsByCategory", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentCategories", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentsBySensitivity", defaultConfig.entryTtl(Duration.ofHours(1)));
        // Legacy 缓存（向后兼容）
        cacheConfigurations.put("allIntents_legacy", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentsByCategory_legacy", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentCategories_legacy", defaultConfig.entryTtl(Duration.ofHours(1)));
        // AI Tool 数据查询缓存 - 提升 SSE 流式响应性能
        cacheConfigurations.put("qualityStats", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put("shipmentStats", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put("customerStats", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigurations.put("alertStats", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("equipmentStats", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigurations.put("productTypes", defaultConfig.entryTtl(Duration.ofHours(4)));
        cacheConfigurations.put("productTypeOptions", defaultConfig.entryTtl(Duration.ofHours(4)));
        cacheConfigurations.put("materialTypes", defaultConfig.entryTtl(Duration.ofHours(6)));
        cacheConfigurations.put("equipmentList", defaultConfig.entryTtl(Duration.ofHours(2)));
        cacheConfigurations.put("customerList", defaultConfig.entryTtl(Duration.ofHours(2)));
        // AI 报告缓存
        cacheConfigurations.put("dailySummary", defaultConfig.entryTtl(Duration.ofHours(24)));
        cacheConfigurations.put("weeklySummary", defaultConfig.entryTtl(Duration.ofDays(7)));
        cacheConfigurations.put("aiToolResult", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // ========== 报表缓存 (2026-01-14) ==========
        // 实时性要求高的报表
        cacheConfigurations.put("realtimeReport", defaultConfig.entryTtl(Duration.ofMinutes(1)));
        // 中等实时性报表
        cacheConfigurations.put("oeeReport", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("kpiMetrics", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("costVarianceReport", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put("capacityUtilization", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put("onTimeDelivery", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put("productionByProduct", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        // 稳定数据报表
        cacheConfigurations.put("monthlyReport", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("yearlyReport", defaultConfig.entryTtl(Duration.ofHours(24)));
        cacheConfigurations.put("inventoryReport", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigurations.put("financeReport", defaultConfig.entryTtl(Duration.ofHours(1)));

        // ========== 业务流程图导航 (U-NAV-1, Sprint 2 Track G) ==========
        // 首页/列表页节点 count, 5 分钟可接受 staleness, 无需即时 evict
        cacheConfigurations.put("workflowStats", defaultConfig.entryTtl(Duration.ofMinutes(5)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    /**
     * Redis 不可用时的降级缓存。
     *
     * 🔴 2026-09-01 修: 原来是 `SimpleCacheManager` + 一份**写死的缓存名单**。
     * 那份名单是「本仓用到的全部 @Cacheable 名字」的第二份副本, 它漂了 ——
     * `allUnits` / `unitsByCategory` 从来没被加进去, 于是任何一次
     * `@Cacheable("allUnits")` 在降级路径上直接 400:
     *   「Cannot find cache named 'allUnits'」
     * 本机(没起 Redis)实测: 单位目录接口全挂, 回料抽屉的「计量单位」下拉只剩当前单位。
     * 生产用 Redis, RedisCacheManager 按需建缓存, 所以这个缺陷在生产上看不见 ——
     * 「只在降级路径上坏」正是它能活这么久的原因。
     *
     * ⇒ 换成 `CaffeineCacheManager`: 它**按需创建**缓存, 那份名单从物理上不再需要,
     *   也就漂不起来了(⛔ 不要再加回一份 String[] 清单来「补全」)。
     */
    private CacheManager buildSimpleCacheManager() {
        log.warn("Redis 不可用，使用 Caffeine 内存缓存作为降级方案（按需建缓存）");
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(10)));
        return cacheManager;
    }
}
