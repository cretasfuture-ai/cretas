package com.cretas.aims.blueprint.interview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建厂访谈里「哪些生产指标要盯」的目录 —— 落到 {@code alert_thresholds}。
 *
 * <h2>为什么需要它</h2>
 *
 * 2026-08-11 实测 {@code alert_thresholds} <b>全库 0 行</b>, 而
 * {@code AnomalyDetectionScheduler} 一直在定时跑:
 * <pre>{@code
 * List<AlertThreshold> thresholds = thresholdRepository.findByFactoryIdAndEnabled(factoryId, true);
 * for (AlertThreshold threshold : thresholds) { ... }
 * }</pre>
 * 0 行 = <b>每次都在遍历一张空表</b>, 一条异常都检测不出来。
 * 与告警规则那次(alert_rules 0 行把整个告警子系统短路)是同一个形状:
 * 机制完好, 只是没有人写过配置。
 *
 * <h2>⛔ 这不是「预警阈值」那一段</h2>
 *
 * 系统里有<b>两张阈值表</b>, 别搞混:
 * <ul>
 *   <li>{@code factory_thresholds} —— 67 行, 画布/定价那套, 由 ThresholdResolverService 消费。
 *       访谈的「预警阈值」段喂的是它。</li>
 *   <li>{@code alert_thresholds}(本目录) —— 0 行, 异常检测那套, 按 production_batches
 *       最近若干批的均值判。两者<b>不互通</b>。</li>
 * </ul>
 *
 * <h2>指标是闭集</h2>
 *
 * 能盯的指标由 {@code AnomalyDetectionServiceImpl#mapMetricToColumn} 的 switch 定死
 * (全是 production_batches 上的列), 不在里面的名字会被记一行 warn 然后<b>静默跳过</b>。
 * ⛔ 所以这份目录必须与那个 switch 逐字一致 —— 有闸盯着
 * ({@code AnomalyMetricCatalogMatchesEvaluatorTest})。
 */
public final class InterviewAnomalyMetricCatalog {

    private InterviewAnomalyMetricCatalog() {
    }

    /** 比较方式 —— 取值必须是 evaluateThreshold 认得的那四个。 */
    public static final String LESS_THAN = "LESS_THAN";
    public static final String GREATER_THAN = "GREATER_THAN";

    /**
     * 一个可盯的指标。
     *
     * @param comparison 默认比较方式 —— 按这个指标「往哪个方向出问题」定:
     *                   良品率是低了出问题, 不良品数是高了出问题
     * @param unit       给人看的单位提示, 免得把 0.95 和 95 填反
     */
    public record Entry(String metricName,
                        String label,
                        String comparison,
                        String unit,
                        String detail) {
    }

    private static final Map<String, Entry> ENTRIES = build();

    private static Map<String, Entry> build() {
        Map<String, Entry> m = new LinkedHashMap<>();
        put(m, new Entry("yield_rate", "良品率", LESS_THAN, "%",
                "最近几批的平均良品率低于这个数就提醒"));
        put(m, new Entry("defect_quantity", "不良品数", GREATER_THAN, "件/批",
                "最近几批的平均不良品数高于这个数就提醒"));
        put(m, new Entry("unit_cost", "单位成本", GREATER_THAN, "元",
                "单位成本高于这个数就提醒"));
        put(m, new Entry("total_cost", "批次总成本", GREATER_THAN, "元",
                "单批总成本高于这个数就提醒"));
        put(m, new Entry("efficiency", "生产效率", LESS_THAN, "",
                "效率低于这个数就提醒"));
        put(m, new Entry("actual_quantity", "实际产量", LESS_THAN, "件/批",
                "单批产量低于这个数就提醒"));
        put(m, new Entry("work_duration_minutes", "工时", GREATER_THAN, "分钟/批",
                "单批工时高于这个数就提醒"));
        put(m, new Entry("labor_cost", "人工成本", GREATER_THAN, "元",
                "单批人工成本高于这个数就提醒"));
        put(m, new Entry("equipment_cost", "设备成本", GREATER_THAN, "元",
                "单批设备成本高于这个数就提醒"));
        put(m, new Entry("material_cost", "物料成本", GREATER_THAN, "元",
                "单批物料成本高于这个数就提醒"));
        return Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, Entry> m, Entry e) {
        m.put(e.metricName(), e);
    }

    public static List<Entry> all() {
        return List.copyOf(ENTRIES.values());
    }

    public static Entry find(String metricName) {
        return metricName == null ? null : ENTRIES.get(metricName.trim());
    }

    /**
     * 默认告警级别。
     *
     * <p>⛔ 不问客户「这个算 WARNING 还是 CRITICAL」—— 问了只会随手填。
     * 与告警规则那一段同一条判据: 严重度是产品定义, 不是客户的业务选择。
     */
    public static final String DEFAULT_LEVEL = "WARNING";
}
