package com.cretas.aims.gate;

import com.cretas.aims.config.CacheConfig;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 意图配置缓存往返闸 (PROTO-DEMO-PERM, 2026-11-18)。
 *
 * <p>事故: prod 演示厂 LSMDEMO 打开「一线角色 AI 操作权限」每次都报「系统处理异常」(追踪码 9C029606 / 367759CB)。
 * 异常栈: {@code FrontlineAiWriteGrantService.list → writeIntents → IntentConfigManagementServiceImpl.getAllIntents
 * (@Cacheable "allIntents") → RedisCache.lookup → SerializationException: Unexpected token (START_ARRAY),
 * expected VALUE_STRING}。缓存里的字节是 {@code [["com.cretas.aims.entity.config.AIIntentConfig",{…}],…]} ——
 * 顶层列表没有类型标记。
 *
 * <p>成因: 方法返回 {@code Stream.toList()} (运行时类型 final 的 {@code ImmutableCollections$ListN});
 * 序列化器 {@code DefaultTyping.NON_FINAL} 对<b>顶层</b> final 类型不写类型标记, 读回时却要求有。
 * {@link CacheableResultRoundTripTest} 里「List.of / Stream.toList 能往返」只在<b>嵌在 Map 里</b>时成立,
 * 本类补上「作为 @Cacheable 顶层返回值」这一维。
 *
 * <p>判据是往返 (写得出去 ≠ 读得回来), 用的是生产那一份序列化器, ⛔ 不自建 ObjectMapper。
 */
class IntentConfigCacheRoundTripContractTest {

    private static final GenericJackson2JsonRedisSerializer SERIALIZER = CacheConfig.createRedisSerializer();

    private AIIntentConfigRepository repo;
    private IntentConfigManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(AIIntentConfigRepository.class);
        service = new IntentConfigManagementServiceImpl(repo);
        when(repo.findBlockedIntentCodesInFactoryScope("LSMDEMO")).thenReturn(List.of());
        when(repo.findByFactoryIdOrPlatformLevel("LSMDEMO")).thenReturn(rows());
        when(repo.findByFactoryIdAndCategory("LSMDEMO", "DATA_OPERATION")).thenReturn(rows());
        when(repo.findByFactoryIdAndSensitivity("LSMDEMO", "MEDIUM")).thenReturn(rows());
        when(repo.findByIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc()).thenReturn(rows());
        when(repo.findByIntentCategoryAndIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc("DATA_OPERATION"))
                .thenReturn(rows());
    }

    /** 与 prod 毒条目同形: 平台级 (factoryId=null) 的写操作意图。代码不在目录里 ⇒ project 原样返回。 */
    private static List<AIIntentConfig> rows() {
        List<AIIntentConfig> list = new ArrayList<>();
        for (String code : List.of("QA_CACHE_PROBE_A", "QA_CACHE_PROBE_B")) {
            AIIntentConfig c = new AIIntentConfig();
            c.setIntentCode(code);
            c.setIntentName("缓存探针 " + code);
            c.setIntentCategory("DATA_OPERATION");
            c.setSensitivityLevel("MEDIUM");
            c.setBusinessType("COMMON");
            list.add(c);
        }
        return list;
    }

    private static Object roundTrip(Object value) {
        return SERIALIZER.deserialize(SERIALIZER.serialize(value));
    }

    @Test
    @DisplayName("阳性对照: 顶层 Stream.toList() 写得出去、读不回来 —— 这就是 prod 那条异常")
    void topLevelStreamToListIsPoison() {
        List<AIIntentConfig> poisoned = rows().stream().toList();
        byte[] bytes = SERIALIZER.serialize(poisoned);
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .as("顶层没有类型标记: 以 [[ 开头, 与 prod 日志里的字节同形")
                .startsWith("[[\"com.cretas.aims.entity.config.AIIntentConfig\"");
        assertThatThrownBy(() -> SERIALIZER.deserialize(bytes))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("expected VALUE_STRING");
        assertThatThrownBy(() -> SERIALIZER.deserialize(SERIALIZER.serialize(List.of())))
                .as("空的 List.of() 在顶层同样读不回来")
                .isInstanceOf(SerializationException.class);
    }

    @Test
    @DisplayName("服务里每个 @Cacheable 列表方法的真实返回值都能经生产序列化器往返, 内容不丢")
    void everyCachedListMethodRoundTrips() {
        Map<String, Supplier<List<?>>> cached = new LinkedHashMap<>();
        cached.put("getAllIntents(factoryId)", () -> service.getAllIntents("LSMDEMO"));
        cached.put("getAllIntents()", () -> service.getAllIntents());
        cached.put("getIntentsByCategory(factoryId,c)", () -> service.getIntentsByCategory("LSMDEMO", "DATA_OPERATION"));
        cached.put("getIntentsByCategory(c)", () -> service.getIntentsByCategory("DATA_OPERATION"));
        cached.put("getIntentsBySensitivity(factoryId,s)", () -> service.getIntentsBySensitivity("LSMDEMO", "MEDIUM"));
        cached.put("getAllCategories(factoryId)", () -> service.getAllCategories("LSMDEMO"));
        cached.put("getAllCategories()", () -> service.getAllCategories());
        cached.put("getIntentsByCategory(blank,c)", () -> service.getIntentsByCategory(" ", "DATA_OPERATION"));
        cached.put("getIntentsBySensitivity(blank,s)", () -> service.getIntentsBySensitivity(" ", "MEDIUM"));
        cached.put("getAllCategories(blank)", () -> service.getAllCategories(" "));

        List<String> failures = new ArrayList<>();
        int nonEmpty = 0;
        for (Map.Entry<String, Supplier<List<?>>> e : cached.entrySet()) {
            List<?> value = e.getValue().get();
            if (!value.isEmpty()) nonEmpty++;
            try {
                Object back = roundTrip(value);
                if (!(back instanceof List<?> list) || list.size() != value.size()) {
                    failures.add(e.getKey() + ": 读回 " + back);
                }
            } catch (SerializationException ex) {
                failures.add(e.getKey() + " (" + value.getClass().getName() + "): " + ex.getMessage().split("\n")[0]);
            }
        }
        // Y 一定会发生: 有数据的方法确实返回了数据, 否则「全空也能往返」会让本条恒绿
        assertThat(nonEmpty).as("应有 7 个方法返回非空列表").isEqualTo(7);
        assertThat(failures).as("读不回来的缓存返回值 (每条都会让命中缓存的请求 500)").isEmpty();
    }

    @Test
    @DisplayName("读回来的内容是同一批意图 (不只是条数对)")
    void roundTripPreservesIntentCodes() {
        @SuppressWarnings("unchecked")
        List<AIIntentConfig> back = (List<AIIntentConfig>) roundTrip(service.getAllIntents("LSMDEMO"));
        assertThat(back).extracting(AIIntentConfig::getIntentCode)
                .containsExactly("QA_CACHE_PROBE_A", "QA_CACHE_PROBE_B");
        assertThat(roundTrip(service.getAllCategories("LSMDEMO"))).isEqualTo(List.of("DATA_OPERATION"));
    }
}
