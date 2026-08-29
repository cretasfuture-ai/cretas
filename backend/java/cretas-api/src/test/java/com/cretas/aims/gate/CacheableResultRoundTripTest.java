package com.cretas.aims.gate;

import com.cretas.aims.config.CacheConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缓存往返闸 —— 放进 {@code @Cacheable} 结果里的东西，必须**读得回来**。
 *
 * <h3>它守的那次事故（2026-08-29 逐屏走查抓到）</h3>
 * {@code getOnTimeDeliveryReport} 带 {@code @Cacheable("onTimeDelivery")}，
 * 而它往结果里放的是 {@code orderDetails.subList(0, 20)} —— 一个
 * {@code java.util.ArrayList$SubList}。
 *
 * <p>这个类型 <b>能被 Jackson 写出去，却读不回来</b>（没有无参构造）：
 * <pre>
 * SerializationException: Could not read JSON: Cannot construct instance of
 *   `java.util.ArrayList$SubList` (no Creators, like default constructor, exist)
 *   (through reference chain: java.util.HashMap["orderDetails"])
 * </pre>
 *
 * <p>⚠️ 于是它是一个 <b>自我投毒</b> 的缓存：第一次调用（未命中）成功，
 * 顺手把毒条目写进 Redis；此后每一次命中缓存都 500，直到 TTL 过期。
 * <b>第一个打开这张报表的人，把它给所有人弄坏了。</b>
 *
 * <p>症状极具迷惑性：同一屏先看到数据，几分钟后变成「暂无OTIF数据」——
 * 前端把 500 吃进 catch 里当成了「没有数据」。
 *
 * <h3>判据</h3>
 * ⛔ 「写出去成功」不等于「读得回来」。缓存的判据必须是 <b>往返</b>。
 *
 * <p>本闸刻意<b>不</b>去扫源码里的 {@code .subList(}。那样只能挡住已知的这一种，
 * 而实测 {@code Map.keySet()} / {@code Map.values()} 同样读不回来。
 * 往返测能一次盖住这一整类。
 *
 * <p>实测名单（2026-08-29 逐个跑过，⛔ 不是推断）：
 * <ul>
 *   <li><b>读不回来</b>：{@code subList}（ArrayList$SubList）、
 *       {@code keySet()}（AbstractMap$1）、{@code values()}（AbstractMap$2）</li>
 *   <li><b>能往返</b>：{@code ArrayList}、{@code Arrays.asList}、{@code List.of}、
 *       {@code Collections.unmodifiableXxx}、{@code toUnmodifiableList}、
 *       {@code Stream.toList}、{@code Map.of}</li>
 * </ul>
 * ⇒ 共同点是「<b>由另一个集合支撑的视图</b>」，<b>不是</b>「不可变」。
 * ⚠️ 我最初把 {@code Arrays.asList} 也写成会坏 —— 那是猜的，实测它没事。
 * 这条更正留在这里，因为「坏的是视图」比「坏的是不可变集合」是个完全不同的判据。
 */
class CacheableResultRoundTripTest {

    /** ⛔ 用生产那一份，不自己新建 ObjectMapper（否则只证明「我抄的那份能往返」）。 */
    private static final GenericJackson2JsonRedisSerializer SERIALIZER =
            CacheConfig.createRedisSerializer();

    private static Object roundTrip(Object value) {
        return SERIALIZER.deserialize(SERIALIZER.serialize(value));
    }

    /** 复刻 getOnTimeDeliveryReport 返回的那张 map 的形状。 */
    private static Map<String, Object> otifReportShaped(List<Map<String, Object>> orderDetails) {
        Map<String, Object> report = new HashMap<>();
        report.put("factoryId", "F006");
        report.put("startDate", LocalDate.of(2026, 8, 23));
        report.put("endDate", LocalDate.of(2026, 8, 29));
        report.put("totalOrders", 0);
        report.put("onTimeRate", null);
        report.put("inFullRate", null);
        report.put("otifRate", null);
        report.put("target", null);

        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "2026-08-29");
        day.put("totalOrders", 0);
        day.put("otifRate", null);
        dailyTrend.add(day);
        report.put("dailyTrend", dailyTrend);

        report.put("orderDetails", orderDetails);
        return report;
    }

    private static List<Map<String, Object>> sampleDetails() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderNo", "SO-2026082" + i);
            row.put("onTime", i % 2 == 0);
            rows.add(row);
        }
        return rows;
    }

    @Nested
    @DisplayName("阳性对照 —— 仪器活着")
    class PositiveControls {

        @Test
        @DisplayName("修好之后的形状(包成 ArrayList)能完整往返")
        void fixedShapeRoundTrips() {
            List<Map<String, Object>> details = sampleDetails();
            Map<String, Object> report = otifReportShaped(
                    new ArrayList<>(details.subList(0, Math.min(20, details.size()))));

            Object back = roundTrip(report);

            assertThat(back).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) back;
            assertThat(m.get("factoryId")).isEqualTo("F006");
            assertThat((List<?>) m.get("orderDetails")).hasSize(20);
            assertThat(m).containsEntry("otifRate", null);
        }

        @Test
        @DisplayName("LocalDate / null 值本身不是问题 —— 免得把锅算错")
        void datesAndNullsAreFine() {
            Map<String, Object> m = new HashMap<>();
            m.put("d", LocalDate.of(2026, 8, 29));
            m.put("n", null);
            assertThatCode(() -> roundTrip(m)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("🔴 会读不回来的那些类型 —— 这就是事故本身")
    class PoisonousTypes {

        @Test
        @DisplayName("subList 的返回值: 写得出去, 读不回来")
        void subListSerializesButFailsToDeserialize() {
            List<Map<String, Object>> details = sampleDetails();
            Map<String, Object> poisoned = otifReportShaped(
                    details.subList(0, Math.min(20, details.size())));

            // 写出去这一步是成功的 —— 正因为如此, 毒条目才进得了 Redis
            byte[] bytes = SERIALIZER.serialize(poisoned);
            assertThat(bytes).isNotEmpty();

            // 读回来才炸
            assertThatThrownBy(() -> SERIALIZER.deserialize(bytes))
                    .isInstanceOf(SerializationException.class)
                    .hasMessageContaining("SubList");
        }

        @Test
        @DisplayName("keySet() / values() 同样读不回来 —— 坏的是「视图」这一类, 不是 subList 一个类型")
        void backingViewsAlsoFail() {
            // 实测(2026-08-29 逐个跑过一遍, ⛔ 不是推断):
            //   读不回来: subList(ArrayList$SubList) / keySet(AbstractMap$1) / values(AbstractMap$2)
            //   能往返  : ArrayList / Arrays.asList / List.of / Collections.unmodifiableXxx
            //             / toUnmodifiableList / Stream.toList / Map.of
            // ⇒ 共同点是「**由另一个集合支撑的视图**」, 不是「不可变」。
            //   我最初以为 Arrays.asList 也会坏 —— 那是猜的, 实测它没事。
            for (Object view : List.of(
                    new ArrayList<>(List.of("a", "b", "c")).subList(0, 2),
                    Map.of("k", 1).keySet(),
                    Map.of("k", 1).values())) {
                Map<String, Object> m = new HashMap<>();
                m.put("rows", view);
                byte[] bytes = SERIALIZER.serialize(m);
                assertThat(bytes).as("视图型集合**写得出去** —— 毒条目正是这样进的缓存").isNotEmpty();
                assertThatThrownBy(() -> SERIALIZER.deserialize(bytes))
                        .as("%s 应当读不回来", view.getClass().getName())
                        .isInstanceOf(SerializationException.class);
            }
        }

        @Test
        @DisplayName("阴性对照: 不可变集合并不在此列 —— ⛔ 闸不许宽到把它们也算成毒")
        void immutableCollectionsAreFine() {
            for (Object ok : List.of(
                    Arrays.asList("a", "b"),
                    List.of("a", "b"),
                    Collections.unmodifiableList(new ArrayList<>(List.of("a"))),
                    Map.of("k", 1))) {
                Map<String, Object> m = new HashMap<>();
                m.put("rows", ok);
                assertThatCode(() -> roundTrip(m))
                        .as("%s 实测可以往返", ok.getClass().getName())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    @DisplayName("生产那个方法真实返回的形状能往返 —— 直接对着被修的那一处")
    void theActualReportShapeRoundTrips() {
        List<Map<String, Object>> details = sampleDetails();

        // 与 ProductionReportServiceImpl#getOnTimeDeliveryReport 里那一行逐字同形
        Object orderDetails = new ArrayList<>(details.subList(0, Math.min(20, details.size())));

        Map<String, Object> report = otifReportShaped(
                castRows(orderDetails));

        assertThatCode(() -> roundTrip(report))
                .as("这张 map 会整个进 Redis; 读不回来就是每次命中缓存都 500")
                .doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(Object o) {
        return (List<Map<String, Object>>) o;
    }
}
