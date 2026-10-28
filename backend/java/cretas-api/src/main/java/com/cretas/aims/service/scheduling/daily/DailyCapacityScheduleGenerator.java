package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.dto.scheduling.AutoScheduleItem;
import com.cretas.aims.dto.scheduling.DailyCapacityOverride;
import com.cretas.aims.dto.scheduling.DailyCapacityRequiredDTO;
import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.bom.PlanBomItems;
import com.cretas.aims.service.scheduling.core.SchedulingPlanCrudService;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.DayOut;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.JobIn;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.JobOut;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * APS v2: 选几个生产计划 → 按产品「一天能做多少」按天排出哪天做哪个计划、做多少,
 * 落成一份调度计划 (每个排出的生产计划一条 {@code LineSchedule}, 按天分配存 {@code daily_allocations})。
 *
 * <p>口径 (Steve 2026-10-28 裁定, 不要改):
 * <ul>
 *   <li>日产能按产品取 (计划的 {@code product_type_id}): <b>人工优先</b> —— 产品填了日产能 (单位能换成计划单位) 就用人工的;
 *       没填用经验日产能 ({@link ExperienceCapacityService}), 结果标「按历史估算」;
 *       两者都有且相差超过 {@link #DIFF_WARN_RATIO} 时提示 (仍用人工)。</li>
 *   <li>两样都没有: ⛔ 不拦住不排, 也 ⛔ 不拿默认值猜 —— 返回 {@code outcome=CAPACITY_REQUIRED} 与需补清单, 什么都不落
 *       (落了的话同一天再提交会撞「该日期已存在调度计划」409, 用户补完也排不了)。
 *       前端当场让人填, 带 {@code dailyCapacityOverrides} 重新提交: 先保存到产品, 再排。</li>
 *   <li>联产计划 (一张图出多个成品, {@link PlanBomItems#isMultiOutput}): 计划量是计划单位下的投入 / 合并量
 *       (原料归属锚点的计划单位是原料 kg), 单个成品的入库量折不回去 ⇒ 不用历史估算, 直接问
 *       「这个计划一天能做多少」, 存在计划主产品 (锚点) 上。</li>
 *   <li>只排剩余量 = 计划量 − 已入库成品 (本计划的生产成品入库, 换得成计划单位的部分)。</li>
 *   <li>按天排, 不排到每道工序几点; 排程的计划开始 = 第一天 00:00, 计划结束 = 最后一天 23:59。⛔ 不自动派工。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyCapacityScheduleGenerator {

    /** 人工与历史相差超过这个比例 (以历史为基准) 时提示。 */
    static final BigDecimal DIFF_WARN_RATIO = new BigDecimal("0.30");
    static final String SOURCE_MANUAL = "MANUAL";
    static final String SOURCE_EXPERIENCE = "EXPERIENCE";
    static final String LABEL_MANUAL = "人工填写";
    static final String LABEL_EXPERIENCE = "按历史估算";
    static final LocalTime DAY_END = LocalTime.of(23, 59);

    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionLineRepository lineRepository;
    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SchedulingPlanRepository schedulingPlanRepository;
    private final ExperienceCapacityService experienceCapacityService;
    private final ProductDailyCapacityService productDailyCapacityService;
    private final SchedulingPlanCrudService crudService;

    /** 一个计划要排的东西。 */
    private record PlanWork(ProductionPlan plan, String label, String unit, boolean joint,
                            BigDecimal reported, BigDecimal remaining, Capacity capacity, Long firstBatchId) {
    }

    /** 一个 (产品, 计划单位) 的日产能判定。perDay 为空 = 要人补。 */
    record Capacity(BigDecimal perDay, String source, BigDecimal manualPerDay, ExperienceCapacity experience,
                    DailyCapacityRequiredDTO required) {
    }

    @Transactional
    public SchedulingPlanDTO generate(String factoryId, GenerateScheduleRequest request, Long userId) {
        if (request == null || request.getPlanDate() == null) {
            throw new BusinessException(400, "计划日期不能为空");
        }
        LocalDate firstDay = request.getPlanDate();
        List<ProductionPlan> plans = resolvePlans(factoryId, request);
        ProductionLine line = resolveLine(factoryId, request.getProductionLineId());
        // 先判同日已有调度计划: 不要等人补完日产能才告诉他这天已经排过了
        schedulingPlanRepository.findByFactoryIdAndPlanDateAndDeletedAtIsNull(factoryId, firstDay)
                .ifPresent(existing -> {
                    throw new BusinessException(409, "该日期已存在调度计划, 请勿重复创建")
                            .withHint("如需查看请打开该日期已有的调度计划")
                            .withHintTarget(existing.getId());
                });

        List<String> warnings = new ArrayList<>();
        List<ProductionPlan> active = new ArrayList<>();
        for (ProductionPlan plan : plans) {
            if (plan.getStatus() == ProductionPlanStatus.COMPLETED || plan.getStatus() == ProductionPlanStatus.CANCELLED) {
                warnings.add("计划「" + planLabel(plan) + "」" + plan.getStatus().getDisplayName() + "，不排");
                continue;
            }
            active.add(plan);
        }

        applyOverrides(factoryId, request.getDailyCapacityOverrides(), active, userId);

        LocalDate today = LocalDate.now();
        Map<String, Capacity> capacityByKey = new HashMap<>();
        Map<String, DailyCapacityRequiredDTO> required = new LinkedHashMap<>();
        List<PlanWork> works = new ArrayList<>();
        for (ProductionPlan plan : active) {
            String unit = planUnit(plan);
            boolean joint = PlanBomItems.isMultiOutput(plan);
            ProductType product = productTypeRepository.findByIdAndFactoryId(plan.getProductTypeId(), factoryId)
                    .orElse(null);
            if (product == null) {
                warnings.add("计划「" + planLabel(plan) + "」的产品不存在或已删除，没有排");
                continue;
            }
            // 同一产品同一单位: 联产与单品计划的经验值口径不同 (联产不用历史), 分开判
            Capacity capacity = capacityByKey.computeIfAbsent(capacityKey(product.getId(), unit) + (joint ? "|J" : ""),
                    k -> resolveCapacity(factoryId, product, unit, joint, today, warnings));
            if (capacity.perDay() == null) {
                DailyCapacityRequiredDTO item = required.computeIfAbsent(capacityKey(product.getId(), unit),
                        k -> capacity.required());
                item.getPlanNumbers().add(planLabel(plan));
            }
            Reported reported = reportedOutput(factoryId, plan, unit);
            if (reported.note() != null) {
                warnings.add("计划「" + planLabel(plan) + "」" + reported.note());
            }
            BigDecimal planned = plan.getPlannedQuantity() == null ? BigDecimal.ZERO : plan.getPlannedQuantity();
            BigDecimal remaining = planned.subtract(reported.quantity()).max(BigDecimal.ZERO);
            works.add(new PlanWork(plan, planLabel(plan), unit, joint, reported.quantity(), remaining, capacity,
                    firstOpenBatchId(factoryId, plan)));
        }

        if (!required.isEmpty()) {
            SchedulingPlanDTO pending = new SchedulingPlanDTO();
            pending.setFactoryId(factoryId);
            pending.setPlanDate(firstDay);
            pending.setOutcome(SchedulingPlanDTO.OUTCOME_CAPACITY_REQUIRED);
            pending.setCapacityRequired(new ArrayList<>(required.values()));
            pending.setLineSchedules(List.of());
            pending.setWarnings(warnings);
            log.info("APS 自动排程: factoryId={}, 需补日产能 {} 个产品, 本次不落库", factoryId, required.size());
            return pending;
        }

        List<JobIn> jobs = works.stream()
                .map(w -> new JobIn(w.plan().getId(), w.remaining(), w.capacity().perDay()))
                .toList();
        List<JobOut> allocated = DailyCapacityEngine.allocate(jobs, firstDay);

        List<AutoScheduleItem> items = new ArrayList<>();
        for (int i = 0; i < works.size(); i++) {
            PlanWork w = works.get(i);
            JobOut out = allocated.get(i);
            if (out.status() == Status.NOTHING_REMAINING) {
                warnings.add("计划「" + w.label() + "」已入库 " + plain(w.reported()) + " " + w.unit()
                        + "，达到计划量，不需要排");
                continue;
            }
            if (out.status() == Status.EXCEEDS_HORIZON) {
                warnings.add("计划「" + w.label() + "」按日产能 " + plain(w.capacity().perDay()) + " " + w.unit()
                        + "/天要排 " + DailyCapacityEngine.MAX_DAYS + " 天以上，没有排；请核对日产能是否填错");
                continue;
            }
            if (out.status() != Status.SCHEDULED) {
                // NEEDS_CAPACITY 已在上面整体返回需补清单, 走不到这里; 防御: 不静默丢
                throw new IllegalStateException("unexpected allocation status " + out.status() + " for " + w.label());
            }
            Integer quantity = roundedQuantity(out.totalQuantity());
            items.add(new AutoScheduleItem(line.getId(), w.plan().getId(), w.firstBatchId(), items.size(),
                    out.firstDay().atStartOfDay(), out.lastDay().atTime(DAY_END), quantity,
                    allocationJson(w, out)));
        }

        if (items.isEmpty()) {
            throw new BusinessException(422, "没有能排出的计划：" + String.join("；", warnings))
                    .withCode("APS_NOTHING_SCHEDULABLE")
                    .withHint("原因见上面每个计划的说明（已完成、已取消、已入库达到计划量，或日产能填得明显不对）")
                    .withSeverity("warning");
        }

        String notes = "按产品日产能按天排程（不自动派工）";
        SchedulingPlanDTO created = crudService.createAutoSchedulePlan(factoryId, firstDay,
                firstDay + " 自动排程", notes, items, userId);
        SchedulingPlanDTO result = crudService.getPlan(factoryId, created.getId());
        result.setWarnings(warnings);
        result.setOutcome(SchedulingPlanDTO.OUTCOME_SCHEDULED);
        result.setCapacityRequired(List.of());
        log.info("APS 自动排程: factoryId={}, schedulingPlanId={}, 排出 {} 个计划, 提示 {} 条",
                factoryId, result.getId(), items.size(), warnings.size());
        return result;
    }

    // ==================== 日产能 ====================

    private Capacity resolveCapacity(String factoryId, ProductType product, String unit, boolean joint,
                                     LocalDate today, List<String> warnings) {
        String name = displayName(factoryId, product);
        BigDecimal manual = null;
        boolean unitMismatch = false;
        if (product.getDailyCapacity() != null && product.getDailyCapacity().signum() > 0) {
            Optional<BigDecimal> converted = CapacityUnits.convert(product.getDailyCapacity(),
                    product.getDailyCapacityUnit(), unit);
            if (converted.isPresent()) {
                manual = converted.get().setScale(DailyCapacityEngine.QTY_SCALE, RoundingMode.HALF_UP);
                if (manual.signum() <= 0) {
                    manual = null;
                }
            } else {
                unitMismatch = true;
            }
        }
        ExperienceCapacity experience = joint
                ? ExperienceCapacity.unavailable("联产计划（一张图出多个成品）按计划量排，单个成品的入库量折不回计划量，不用历史估算")
                : experienceCapacityService.experience(factoryId, product.getId(), unit, today);

        // 人工优先: 填了 (且单位对得上) 就用人工的; 经验值只做参考
        if (manual != null) {
            if (experience.available() && differsTooMuch(manual, experience.perDay())) {
                warnings.add("「" + name + "」人工填的日产能 " + plain(manual) + " " + unit + "/天，历史估算 "
                        + plain(experience.perDay()) + " " + unit + "/天（" + experience.note() + "），相差超过 30%，"
                        + "按人工的排；请核对");
            }
            return new Capacity(manual, SOURCE_MANUAL, manual, experience, null);
        }
        if (experience.available()) {
            warnings.add("「" + name + "」没有填日产能，" + LABEL_EXPERIENCE + " " + plain(experience.perDay()) + " "
                    + unit + "/天（" + experience.note() + "）");
            return new Capacity(experience.perDay(), SOURCE_EXPERIENCE, null, experience, null);
        }
        DailyCapacityRequiredDTO item = DailyCapacityRequiredDTO.builder()
                .productTypeId(product.getId())
                .productName(name)
                .unit(unit)
                .jointPlan(joint)
                .question(joint
                        ? "这个联产计划（" + name + "）一天能做多少 " + unit + "？"
                        : "「" + name + "」一天能做多少 " + unit + "？")
                .reason(unitMismatch ? "UNIT_MISMATCH" : "MISSING")
                .currentQuantityPerDay(unitMismatch ? product.getDailyCapacity() : null)
                .currentUnit(unitMismatch ? product.getDailyCapacityUnit() : null)
                .experienceQuantityPerDay(null)
                .experienceNote(experience.note())
                .planNumbers(new ArrayList<>())
                .build();
        return new Capacity(null, null, null, experience, item);
    }

    static boolean differsTooMuch(BigDecimal manual, BigDecimal experience) {
        if (experience == null || experience.signum() <= 0) {
            return false;
        }
        return manual.subtract(experience).abs().compareTo(experience.multiply(DIFF_WARN_RATIO)) > 0;
    }

    /** 排程页当场补的日产能: 整批校验 (本厂产品 / 本次计划用到 / 单位对得上) 通过后才写, 不留半截。 */
    private void applyOverrides(String factoryId, List<DailyCapacityOverride> overrides, List<ProductionPlan> plans,
                                Long userId) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        Map<String, Set<String>> unitsByProduct = new HashMap<>();
        for (ProductionPlan plan : plans) {
            unitsByProduct.computeIfAbsent(plan.getProductTypeId(), k -> new LinkedHashSet<>()).add(planUnit(plan));
        }
        Set<String> seen = new LinkedHashSet<>();
        List<DailyCapacityOverride> validated = new ArrayList<>();
        for (DailyCapacityOverride o : overrides) {
            if (o == null) {
                throw new BusinessException(400, "日产能明细不能为空");
            }
            ProductType product = productDailyCapacityService.requireOwnProduct(factoryId, o.getProductTypeId());
            ProductDailyCapacityService.normalizeQuantity(o.getQuantityPerDay());
            String unit = ProductDailyCapacityService.normalizeUnit(o.getUnit());
            if (!seen.add(product.getId())) {
                throw new BusinessException(400, "同一个产品的日产能填了两次: " + product.getId());
            }
            Set<String> planUnits = unitsByProduct.get(product.getId());
            if (planUnits == null) {
                throw new BusinessException(400, "产品「" + product.getName() + "」不在本次要排的计划里，不能在这里改它的日产能")
                        .withHintTarget("dailyCapacityOverrides");
            }
            boolean matches = planUnits.stream().anyMatch(pu -> CapacityUnits.factor(unit, pu).isPresent());
            if (!matches) {
                throw new BusinessException(400, "「" + product.getName() + "」的日产能单位应为 "
                        + String.join(" / ", planUnits) + "（与计划单位一致），填的是 " + unit)
                        .withHintTarget("dailyCapacityOverrides");
            }
            validated.add(o);
        }
        for (DailyCapacityOverride o : validated) {
            productDailyCapacityService.save(factoryId, o.getProductTypeId().trim(), o.getQuantityPerDay(), o.getUnit(),
                    userId);
        }
    }

    // ==================== 已入库 (已报产出) ====================

    private record Reported(BigDecimal quantity, String note) {
    }

    /** 本计划已入库的成品 (计划主产品与目标成品), 换成计划单位; 换不了的不扣并提示。副产 (不在目标里) 不算。 */
    private Reported reportedOutput(String factoryId, ProductionPlan plan, String unit) {
        Set<String> products = new LinkedHashSet<>();
        products.add(plan.getProductTypeId());
        if (plan.getTargetFinishedGoodIds() != null) {
            products.addAll(plan.getTargetFinishedGoodIds());
        }
        BigDecimal sum = BigDecimal.ZERO;
        Map<String, BigDecimal> unconvertible = new LinkedHashMap<>();
        for (Object[] r : finishedGoodsBatchRepository.sumProductionOutputByPlan(factoryId, plan.getId())) {
            String productTypeId = (String) r[0];
            String rowUnit = (String) r[1];
            BigDecimal qty = ExperienceCapacityService.toDecimal(r[2]);
            if (!products.contains(productTypeId) || qty == null || qty.signum() <= 0) {
                continue;
            }
            Optional<BigDecimal> converted = CapacityUnits.convert(qty, rowUnit, unit);
            if (converted.isPresent()) {
                sum = sum.add(converted.get());
            } else {
                unconvertible.merge(rowUnit == null ? "未填单位" : rowUnit, qty, BigDecimal::add);
            }
        }
        String note = null;
        if (!unconvertible.isEmpty()) {
            List<String> parts = new ArrayList<>();
            unconvertible.forEach((u, q) -> parts.add(plain(q) + " " + u));
            note = "已入库成品 " + String.join("、", parts) + " 折不成计划单位 " + unit + "，没有从计划量里扣";
        }
        return new Reported(sum.setScale(DailyCapacityEngine.QTY_SCALE, RoundingMode.HALF_UP), note);
    }

    // ==================== 入参解析 ====================

    private List<ProductionPlan> resolvePlans(String factoryId, GenerateScheduleRequest request) {
        Set<String> ids = new LinkedHashSet<>();
        if (request.getPlanIds() != null) {
            for (String id : request.getPlanIds()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        if (request.getBatchIds() != null) {
            for (Long batchId : request.getBatchIds()) {
                if (batchId == null) {
                    continue;
                }
                ProductionBatch batch = batchRepository.findByIdAndFactoryId(batchId, factoryId)
                        .orElseThrow(() -> new BusinessException(404, "批次不存在或不属于当前工厂: " + batchId));
                if (batch.getProductionPlanId() == null) {
                    throw new BusinessException(400, "批次 " + batch.getBatchNumber() + " 没有关联生产计划，不能自动排程");
                }
                ids.add(batch.getProductionPlanId());
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException(400, "请至少选择一个生产计划");
        }
        List<ProductionPlan> plans = new ArrayList<>();
        for (String id : ids) {
            plans.add(productionPlanRepository.findByIdAndFactoryId(id, factoryId)
                    .orElseThrow(() -> new BusinessException(404, "生产计划不存在或不属于当前工厂: " + id)));
        }
        return plans;
    }

    private ProductionLine resolveLine(String factoryId, String productionLineId) {
        if (productionLineId != null && !productionLineId.isBlank()) {
            ProductionLine line = lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull(productionLineId, factoryId)
                    .orElseThrow(() -> new BusinessException(404, "产线不存在或不属于当前工厂: " + productionLineId));
            if (line.getStatus() != ProductionLine.LineStatus.active) {
                throw new BusinessException(400, "产线「" + line.getName() + "」当前不可排程，请换一条产线");
            }
            return line;
        }
        List<ProductionLine> active = lineRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(
                factoryId, ProductionLine.LineStatus.active);
        if (active.size() == 1) {
            return active.get(0);
        }
        if (active.isEmpty()) {
            throw new BusinessException(409, "工厂还没有可用的产线，不能自动排程")
                    .withCode("APS_NO_PRODUCTION_LINE")
                    .withHint("先到「产线管理」新建一条产线");
        }
        throw new BusinessException(400, "工厂有 " + active.size() + " 条产线，请选择要排在哪条产线上")
                .withCode("APS_PRODUCTION_LINE_REQUIRED")
                .withSeverity("warning");
    }

    // ==================== 小工具 ====================

    private Long firstOpenBatchId(String factoryId, ProductionPlan plan) {
        return batchRepository.findByFactoryIdAndProductionPlanId(factoryId, plan.getId()).stream()
                .filter(b -> b.getStatus() != ProductionBatchStatus.COMPLETED
                        && b.getStatus() != ProductionBatchStatus.CANCELLED)
                .map(ProductionBatch::getId)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private String displayName(String factoryId, ProductType product) {
        return ScheduleProductNames.displayName(rawMaterialTypeRepository, factoryId, product);
    }

    private static Map<String, Object> allocationJson(PlanWork w, JobOut out) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("unit", w.unit());
        json.put("capacityPerDay", w.capacity().perDay());
        json.put("capacitySource", w.capacity().source());
        json.put("capacitySourceLabel", SOURCE_MANUAL.equals(w.capacity().source()) ? LABEL_MANUAL : LABEL_EXPERIENCE);
        ExperienceCapacity exp = w.capacity().experience();
        json.put("experiencePerDay", exp != null && exp.available() ? exp.perDay() : null);
        json.put("experienceNote", exp != null ? exp.note() : null);
        json.put("jointPlan", w.joint());
        json.put("plannedQuantity", w.plan().getPlannedQuantity());
        json.put("reportedQuantity", w.reported());
        json.put("remainingQuantity", w.remaining());
        List<Map<String, Object>> days = new ArrayList<>();
        for (DayOut d : out.days()) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.date().toString());
            day.put("quantity", d.quantity());
            day.put("shareOfDay", d.shareOfDay());
            days.add(day);
        }
        json.put("days", days);
        return json;
    }

    private static String capacityKey(String productTypeId, String unit) {
        return productTypeId + "\u0000" + unit;
    }

    private static String planUnit(ProductionPlan plan) {
        return plan.getPlannedUnit() == null ? "" : plan.getPlannedUnit().trim();
    }

    private static String planLabel(ProductionPlan plan) {
        return plan.getPlanNumber() != null ? plan.getPlanNumber() : plan.getId();
    }

    static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    /** 排程数量取整 (四舍五入), 为正但不足 0.5 时记 1; 没有量 → null。 */
    static Integer roundedQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return null;
        }
        int rounded = quantity.setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(rounded, 1);
    }
}
