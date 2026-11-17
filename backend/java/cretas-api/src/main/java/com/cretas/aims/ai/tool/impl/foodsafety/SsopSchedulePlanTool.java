package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 排某天的 SSOP 清洁计划 (WRITE + Preview, 意图 SSOP_SCHEDULE_PLAN_CREATE, 2026-11-17 v3 复核缺口)。
 *
 * <p>复核原话:「明天的清洁计划给排一下吧」。
 *
 * <p>复用现成的业务对象, 不新造: 清洁标准模板 {@link SsopProcedure} + 清洁任务 {@link SsopExecutionRecord}
 * (状态 SCHEDULED), 与每天 06:00 的 {@code SsopDailyScheduleScheduler} 建的是<b>同一种记录、同一条幂等规则</b>
 * (同一模板同一天已有记录就跳过)。区别只是: 定时任务只排「今天」的每日模板, 这里可以提前排指定日期,
 * 也可以点名排非每日的模板 (每周/每月/每班)。提前排好的每日模板, 到那天 06:00 定时任务会按幂等规则跳过, 不会重复。
 *
 * <p>写入前必走写确认卡: {@link #supportsPreview()} 给出将新建哪些、哪些已排过; 用户确认后才落库。
 * 权限同建清洁模板 ({@code ssop_procedure_create}): quality:read_write。
 */
@Slf4j
@Component
public class SsopSchedulePlanTool extends AbstractBusinessTool {

    static final Set<String> ALLOWED_SHIFTS = Set.of("MORNING", "AFTERNOON", "NIGHT");
    private static final String DEFAULT_SHIFT = "MORNING";
    private static final int MAX_DAYS_AHEAD = 14;

    @Autowired
    private SsopProcedureRepository procedureRepository;

    @Autowired
    private SsopExecutionRecordRepository recordRepository;

    @Override
    public String getToolName() {
        return "ssop_schedule_plan";
    }

    @Override
    public String getDescription() {
        return "排某一天的 SSOP 清洁计划 (WRITE + Preview): 按启用的清洁标准模板生成那天的待执行清洁任务, 默认排明天、早班、全部每日模板;"
                + " 也可点名模板编码排每周/每月的。已排过的跳过不重复。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("date", Map.of("type", "string", "format", "date",
                "description", "排哪一天, yyyy-MM-dd, 不传 = 明天; 只能今天到 " + MAX_DAYS_AHEAD + " 天后"));
        properties.put("shift", Map.of("type", "string", "enum", List.of("MORNING", "AFTERNOON", "NIGHT"),
                "description", "班次, 默认早班 MORNING"));
        properties.put("procedureCodes", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "只排这几个模板 (模板编码); 不传 = 全部启用的每日模板"));
        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    /** 只新建清洁任务、不改已有业务行 (确认前状态核对按新建型处理, 见 ToolExecutor#previewCreatesNewRecordOnly)。 */
    @Override
    public boolean previewCreatesNewRecordOnly() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    /** 一次排程的计算结果 (预览与执行共用, 两边说的是同一件事)。 */
    record Plan(LocalDate date, String shift, List<SsopProcedure> toCreate, List<SsopProcedure> alreadyScheduled,
                List<String> unknownCodes) {
    }

    Plan plan(String factoryId, Map<String, Object> params) {
        LocalDate date = parseDate(getString(params, "date"));
        String shift = getString(params, "shift", DEFAULT_SHIFT).trim().toUpperCase();
        if (!ALLOWED_SHIFTS.contains(shift)) {
            throw new BusinessException(400, "班次 " + shift + " 不对, 只能是早班/中班/夜班 (MORNING/AFTERNOON/NIGHT)");
        }
        List<String> codes = stringList(params.get("procedureCodes"));
        List<SsopProcedure> active = procedureRepository.findByFactoryIdAndActiveTrue(factoryId).stream()
                .filter(p -> Objects.equals(factoryId, p.getFactoryId()))
                .toList();
        List<SsopProcedure> selected;
        List<String> unknown = new ArrayList<>();
        if (codes.isEmpty()) {
            selected = active.stream().filter(p -> "DAILY".equals(p.getFrequency())).toList();
        } else {
            selected = new ArrayList<>();
            for (String code : codes) {
                active.stream().filter(p -> code.equals(p.getProcedureCode())).findFirst()
                        .ifPresentOrElse(selected::add, () -> unknown.add(code));
            }
        }
        List<SsopProcedure> toCreate = new ArrayList<>();
        List<SsopProcedure> already = new ArrayList<>();
        for (SsopProcedure p : selected) {
            if (recordRepository.existsByProcedureIdAndExecutionDate(p.getId(), date)) {
                already.add(p);
            } else {
                toCreate.add(p);
            }
        }
        return new Plan(date, shift, toCreate, already, unknown);
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Plan plan = plan(factoryId, params);
        Map<String, Object> result = describePlan(plan);
        result.put("status", "PREVIEW");
        result.put("canDo", !plan.toCreate().isEmpty() && plan.unknownCodes().isEmpty());
        result.put("message", previewMessage(factoryId, plan));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Plan plan = plan(factoryId, params);
        if (!plan.unknownCodes().isEmpty()) {
            throw new BusinessException(400, "没有这些启用的清洁模板: " + String.join("、", plan.unknownCodes()));
        }
        Long userId = getUserId(context);
        List<Long> created = new ArrayList<>();
        for (SsopProcedure p : plan.toCreate()) {
            // 同一模板同一天已有记录就跳过 (与 SsopDailyScheduleScheduler 同一条幂等规则); 确认前后之间被别人排了也不重复。
            if (recordRepository.existsByProcedureIdAndExecutionDate(p.getId(), plan.date())) {
                continue;
            }
            SsopExecutionRecord rec = SsopExecutionRecord.builder()
                    .factoryId(factoryId)
                    .procedureId(p.getId())
                    .executionDate(plan.date())
                    .shift(plan.shift())
                    .status("SCHEDULED")
                    .notes("AI 对话排程")
                    .build();
            created.add(recordRepository.save(rec).getId());
        }
        log.info("ssop_schedule_plan — factory={} date={} shift={} created={} skipped={} userId={}",
                factoryId, plan.date(), plan.shift(), created.size(), plan.alreadyScheduled().size(), userId);
        Map<String, Object> data = describePlan(plan);
        data.put("status", created.isEmpty() ? "NOTHING_TO_CREATE" : "CREATED");
        data.put("createdRecordIds", created);
        data.put("count", created.size());
        data.put("actionHint", "/foodsafety/ssop/executions?date=" + plan.date());
        String msg = created.isEmpty()
                ? "ℹ️ " + plan.date() + " 的清洁计划都已经排过了, 没有新建。"
                : "✅ 已排好 " + plan.date() + " " + shiftLabel(plan.shift()) + "的清洁任务 " + created.size() + " 项: "
                        + names(plan.toCreate()) + (plan.alreadyScheduled().isEmpty() ? "" : "; 已排过跳过 "
                        + plan.alreadyScheduled().size() + " 项");
        return buildSimpleResult(msg, data);
    }

    private Map<String, Object> describePlan(Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", plan.date().toString());
        m.put("shift", plan.shift());
        m.put("toCreate", plan.toCreate().stream().map(this::procedureRow).toList());
        m.put("alreadyScheduled", plan.alreadyScheduled().stream().map(this::procedureRow).toList());
        m.put("unknownCodes", plan.unknownCodes());
        return m;
    }

    private Map<String, Object> procedureRow(SsopProcedure p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("procedureCode", p.getProcedureCode());
        m.put("name", p.getName());
        m.put("target", p.getTarget());
        m.put("frequency", p.getFrequency());
        return m;
    }

    private String previewMessage(String factoryId, Plan plan) {
        if (!plan.unknownCodes().isEmpty()) {
            return "⚠️ 没有这些启用的清洁模板: " + String.join("、", plan.unknownCodes()) + "。请核对模板编码。";
        }
        if (plan.toCreate().isEmpty() && plan.alreadyScheduled().isEmpty()) {
            List<SsopProcedure> active = procedureRepository.findByFactoryIdAndActiveTrue(factoryId);
            return active.isEmpty()
                    ? "⚠️ 本厂还没有启用的清洁标准模板, 先建模板再排计划。"
                    : "⚠️ 本厂没有启用的每日清洁模板。要排哪几个? 现有模板: " + names(active);
        }
        if (plan.toCreate().isEmpty()) {
            return "ℹ️ " + plan.date() + " 的清洁计划都已经排过了 (" + names(plan.alreadyScheduled()) + "), 不用再排。";
        }
        return "📋 将排 " + plan.date() + " " + shiftLabel(plan.shift()) + "的清洁任务 " + plan.toCreate().size() + " 项: "
                + names(plan.toCreate())
                + (plan.alreadyScheduled().isEmpty() ? "" : "; 已排过不重复 " + plan.alreadyScheduled().size() + " 项")
                + "。确认后生成待执行任务。";
    }

    private static String names(List<SsopProcedure> procedures) {
        return procedures.stream().map(SsopProcedure::getName).collect(Collectors.joining("、"));
    }

    private static String shiftLabel(String shift) {
        return switch (shift) {
            case "AFTERNOON" -> "中班";
            case "NIGHT" -> "夜班";
            default -> "早班";
        };
    }

    private static LocalDate parseDate(String raw) {
        LocalDate today = LocalDate.now();
        if (raw == null || raw.isBlank()) {
            return today.plusDays(1);
        }
        LocalDate d;
        try {
            d = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "日期格式不对: " + raw + ", 请用 yyyy-MM-dd");
        }
        if (d.isBefore(today) || d.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new BusinessException(400, "清洁计划只能排今天到 " + MAX_DAYS_AHEAD + " 天后, " + d + " 不在范围内");
        }
        return d;
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(o -> o.toString().trim()).filter(s -> !s.isEmpty())
                    .distinct().toList();
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return List.of();
        }
        return List.of(s.split("[,，、\\s]+")).stream().filter(x -> !x.isEmpty()).distinct().toList();
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }

    /** 与建清洁模板 ({@code ssop_procedure_create}) 同口径: 食安属质检, 写要质检写权限。 */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("quality:read_write");
    }
}
