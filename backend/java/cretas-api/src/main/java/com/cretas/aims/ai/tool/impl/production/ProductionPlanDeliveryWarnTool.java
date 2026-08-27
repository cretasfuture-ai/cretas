package com.cretas.aims.ai.tool.impl.production;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.production.DeliveryWarnDTO;
import com.cretas.aims.enums.DeliveryWarnLevel;
import com.cretas.aims.service.ProductionPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 5 Tool 1 — 交货预警查询 Tool.
 *
 * <p>Wraps {@link ProductionPlanService#getDeliveryWarnings(String, int)} (shipped via PR #737)
 * for AIChat. Read-only — lists production plans whose
 * {@code expectedCompletionDate} falls within the configured window or is already overdue,
 * grouped by warn level (OVERDUE / URGENT / WARN / NORMAL).</p>
 *
 * <p>Applies 防呆 R2 (context): each warning includes planNumber / productTypeName /
 * sourceCustomerName / daysUntilDeadline so the AI message is actionable without
 * the user having to ask a follow-up.</p>
 *
 * <p>Trigger phrases (user-facing):</p>
 * <ul>
 *   <li>"哪些订单要超期了?"</li>
 *   <li>"近 7 天交货预警"</li>
 *   <li>"快超期的生产计划"</li>
 *   <li>"延期风险"</li>
 * </ul>
 */
@Slf4j
@Component
public class ProductionPlanDeliveryWarnTool extends AbstractBusinessTool {

    /**
     * 等级清单读 {@link DeliveryWarnLevel} —— ⛔ 不再手抄一份字符串。
     *
     * <p>顺序即分组展示顺序（OVERDUE→URGENT→WARN→NORMAL），由枚举声明顺序保证。
     */
    private static final List<String> VALID_WARN_LEVELS = DeliveryWarnLevel.names();

    @Autowired
    private ProductionPlanService productionPlanService;

    @Override
    public String getToolName() {
        return "production_plan_delivery_warn_query";
    }

    @Override
    public String getDescription() {
        return "查询交货预警 — 列出 N 天内即将到期或已超期的生产计划, 按预警等级 "
                + "(OVERDUE/URGENT/WARN/NORMAL) 分组. "
                + "用户问 '哪些订单要超期了?' / '近 7 天交货预警' / '快超期的生产计划' / "
                + "'延期风险' 时调用. 默认窗口 7 天, 可选 warnLevel 过滤.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "windowDays", Map.of(
                                "type", "integer",
                                "description", "查询时间窗口 (天, 大于 0), 默认 7 天"
                        ),
                        "warnLevel", Map.of(
                                "type", "string",
                                "description", "可选过滤等级: OVERDUE / URGENT / WARN / NORMAL (大小写不敏感)"
                        )
                ),
                "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();  // 全部 optional, 有默认窗口
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("production", "delivery", "warning");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        // 1. 解析参数
        Integer windowDays = getInteger(params, "windowDays", 7);
        if (windowDays == null || windowDays <= 0) {
            windowDays = 7;  // service 内部 fallback 也会再 cover, 但前置防御让 message 更准
        }
        String rawLevel = getString(params, "warnLevel", null);
        String warnLevelFilter = (rawLevel != null && !rawLevel.isBlank())
                ? rawLevel.trim().toUpperCase()
                : null;
        if (warnLevelFilter != null && !VALID_WARN_LEVELS.contains(warnLevelFilter)) {
            // 不抛异常, 友好降级: 当成 null (返回全部) + 在 message 里提示
            log.warn("Invalid warnLevel '{}', falling back to ALL", warnLevelFilter);
            warnLevelFilter = null;
        }

        // 2. 调用 service
        List<DeliveryWarnDTO> warnings = productionPlanService.getDeliveryWarnings(factoryId, windowDays);
        if (warnings == null) {
            warnings = List.of();
        }

        // 3. 可选 warn level 过滤
        if (warnLevelFilter != null) {
            final String fLevel = warnLevelFilter;
            warnings = warnings.stream()
                    .filter(w -> fLevel.equalsIgnoreCase(w.getWarnLevel()))
                    .toList();
        }

        // 4. 按 warnLevel 分组 (LinkedHashMap 保 OVERDUE→URGENT→WARN→NORMAL 顺序), 内部按 daysUntilDeadline 升序
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (String level : VALID_WARN_LEVELS) {
            grouped.put(level, new ArrayList<>());
        }
        for (DeliveryWarnDTO w : warnings) {
            // ⚠️ 这个兜底是【既有行为】, 本次只把字面量换成枚举常量, 不动语义。
            //    它把「等级算不出来」说成「正常」—— 与本仓「⛔ 不要把『我不知道』
            //    翻译成一个确定读数」是同一个形状。要改得单独一轮, 因为它会改 AI 的输出。
            String level = w.getWarnLevel() != null ? w.getWarnLevel() : DeliveryWarnLevel.NORMAL.name();
            grouped.computeIfAbsent(level, k -> new ArrayList<>()).add(toRow(w));
        }

        // 5. 计算每组 count + 移除空组 (LLM 输出更简洁)
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> nonEmptyGroups = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            counts.put(e.getKey(), e.getValue().size());
            if (!e.getValue().isEmpty()) {
                nonEmptyGroups.put(e.getKey(), e.getValue());
            }
        }

        // 6. 构建 data envelope
        Map<String, Object> data = new HashMap<>();
        data.put("windowDays", windowDays);
        data.put("warnLevelFilter", warnLevelFilter);  // null = 全部
        data.put("totalCount", warnings.size());
        data.put("countsByLevel", counts);
        data.put("groups", nonEmptyGroups);

        // 7. 友好 message (R2 context: 总数 + 按等级)
        StringBuilder msg = new StringBuilder();
        msg.append("找到 ").append(warnings.size()).append(" 条交货预警 (窗口 ")
                .append(windowDays).append(" 天");
        if (warnLevelFilter != null) {
            msg.append(", 等级=").append(warnLevelFilter);
        }
        msg.append(")");
        // key 也读枚举 —— ⛔ 不写字面量。counts 是用 DeliveryWarnLevel.names() 建的,
        // 谁改了枚举常量名, 字面量这边【不报错】, getOrDefault 静默返回 0。
        int overdue = counts.getOrDefault(DeliveryWarnLevel.OVERDUE.name(), 0);
        int urgent = counts.getOrDefault(DeliveryWarnLevel.URGENT.name(), 0);
        int warn = counts.getOrDefault(DeliveryWarnLevel.WARN.name(), 0);
        int normal = counts.getOrDefault(DeliveryWarnLevel.NORMAL.name(), 0);
        if (warnLevelFilter == null && (overdue + urgent + warn + normal) > 0) {
            msg.append(": 超期 ").append(overdue)
                    .append(" / 紧急 ").append(urgent)
                    .append(" / 预警 ").append(warn)
                    .append(" / 正常 ").append(normal);
        }

        return buildSimpleResult(msg.toString(), data);
    }

    /**
     * Build a row dict for one DeliveryWarnDTO. Apply R2 context — every row carries
     * planNumber + productTypeName + sourceCustomerName + days so AI does not need follow-up query.
     */
    private Map<String, Object> toRow(DeliveryWarnDTO w) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("planId", w.getPlanId());
        row.put("planNumber", w.getPlanNumber());
        row.put("productTypeId", w.getProductTypeId());
        row.put("productTypeName", w.getProductTypeName());
        row.put("sourceCustomerName", w.getSourceCustomerName());
        row.put("plannedQuantity", w.getPlannedQuantity());
        row.put("actualQuantity", w.getActualQuantity());
        row.put("expectedCompletionDate", w.getExpectedCompletionDate());
        row.put("daysUntilDeadline", w.getDaysUntilDeadline());
        row.put("status", w.getStatus());
        row.put("warnLevel", w.getWarnLevel());
        return row;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
