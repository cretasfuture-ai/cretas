package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductEquipmentRequirementRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository;
import com.cretas.aims.service.bom.PlanBomItems;
import com.cretas.aims.service.production.PlanCostVarianceCalculator;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.EquipmentDayBlock;
import com.cretas.aims.service.scheduling.daily.DailyCapacityEngine.FixedOccupancy;
import com.cretas.aims.service.scheduling.daily.MaterialReadiness.Arrival;
import com.cretas.aims.service.scheduling.daily.MaterialReadiness.Need;
import com.cretas.aims.service.scheduling.daily.MaterialReadiness.Supply;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 读库组装自动排程的约束 (食品 DAG 缺口 18)。只读; 缺数据一律降级为提示, ⛔ 不抛异常让排程整体失败。
 *
 * <ul>
 *   <li>设备: {@code product_equipment_requirements}; 报废 ⇒ 该计划不排; 检修中 ⇒ 只提示 (不知道哪天修好);
 *       {@code next_maintenance_date} 在排程范围内 ⇒ 那天不排; 没配设备 ⇒ 不核对并提示。</li>
 *   <li>固定占用: 本厂还在用的排程任务 (自动排的与人工改过的都算), 本次允许挪的 ({@code movable}) 与本次要排的计划自己的除外。
 *       有按天分量 ({@code daily_allocations.days}) 的按它; 没有的 (人工排 / 旧版) 按计划时段内每天占满整天。</li>
 *   <li>原料: 开计划时的标准物料快照 × 剩余量; 可用库存 = 公司库存可领料仓净可用; 在走采购单的未到量与预计到货日。</li>
 *   <li>半成品依赖: 计划配方里的半成品 / 子产品行, 本次一起排的计划里谁产出它, 就依赖谁。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ScheduleConstraintReader {

    private static final String SCRAPPED = "scrapped";
    private static final String MAINTENANCE = "maintenance";

    private final ProductEquipmentRequirementRepository equipmentRequirements;
    private final EquipmentRepository equipmentRepository;
    private final ApsConstraintQueryRepository constraintQueries;
    private final ProductionPlanRepository planRepository;
    private final ProductTypeRepository productTypeRepository;
    private final PlanStandardCostLineRepository standardLines;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final BomRecipeItemRepository bomItemRepository;
    private final SemiFinishedInventoryRepository semiFinishedInventoryRepository;

    /** 本次要排的一个计划。{@code remaining} 是计划单位下的剩余量。 */
    public record PlanInput(ProductionPlan plan, String label, String unit, BigDecimal remaining, boolean joint) {
    }

    public record PlanConstraint(Set<String> equipmentIds, String unavailableReason, Set<String> predecessorPlanIds,
                                 List<String> notes) {
    }

    public record Constraints(Map<String, PlanConstraint> byPlan, List<FixedOccupancy> fixed,
                              List<EquipmentDayBlock> blocks, Map<String, String> equipmentLabels,
                              Map<String, List<Need>> materialNeeds, Map<String, Supply> materialSupply,
                              List<String> warnings) {
    }

    public Constraints read(String factoryId, LocalDate firstDay, String lineId, List<PlanInput> plans,
                            Collection<String> movableLineScheduleIds) {
        Set<String> movable = movableLineScheduleIds == null ? Set.of() : Set.copyOf(movableLineScheduleIds);
        LocalDate horizonEnd = firstDay.plusDays(DailyCapacityEngine.MAX_DAYS - 1L);
        List<String> warnings = new ArrayList<>();
        EquipmentCache equipment = new EquipmentCache(factoryId);

        Map<String, List<String>> notes = new LinkedHashMap<>();
        Map<String, Set<String>> equipmentByPlan = new HashMap<>();
        Map<String, String> unavailable = new HashMap<>();
        for (PlanInput p : plans) {
            List<String> n = notes.computeIfAbsent(p.plan().getId(), k -> new ArrayList<>());
            EquipmentInfo info = equipment.forProduct(p.plan().getProductTypeId());
            equipmentByPlan.put(p.plan().getId(), info.usable());
            n.addAll(info.notes());
            if (info.unavailableReason() != null) {
                unavailable.put(p.plan().getId(), info.unavailableReason());
            }
        }

        // 设备保养日
        List<EquipmentDayBlock> blocks = new ArrayList<>();
        for (FactoryEquipment e : equipment.loaded()) {
            LocalDate next = e.getNextMaintenanceDate();
            if (next != null && !next.isBefore(firstDay) && !next.isAfter(horizonEnd)) {
                blocks.add(new EquipmentDayBlock(String.valueOf(e.getId()), next,
                        "设备「" + equipment.label(e) + "」" + next + " 保养"));
            }
        }

        // 固定占用
        Set<String> planIdsInRun = new LinkedHashSet<>();
        plans.forEach(p -> planIdsInRun.add(p.plan().getId()));
        List<FixedOccupancy> fixed = new ArrayList<>();
        Map<String, ProductionPlan> otherPlans = new HashMap<>();
        Set<String> alreadyScheduled = new LinkedHashSet<>();
        for (LineSchedule ls : constraintQueries.findLiveEndingFrom(factoryId, firstDay.atStartOfDay())) {
            if (movable.contains(ls.getId())) {
                continue;
            }
            if (ls.getProductionPlanId() != null && planIdsInRun.contains(ls.getProductionPlanId())) {
                alreadyScheduled.add(ls.getProductionPlanId());
                continue;
            }
            ProductionPlan owner = ls.getProductionPlanId() == null ? null
                    : otherPlans.computeIfAbsent(ls.getProductionPlanId(),
                            id -> planRepository.findByIdAndFactoryId(id, factoryId).orElse(null));
            String productTypeId = owner == null ? null : owner.getProductTypeId();
            String label = owner == null ? "已有排程任务" : "计划 " + planLabel(owner);
            Set<String> equip = equipment.forProduct(productTypeId).usable();
            boolean sameLine = lineId != null && lineId.equals(ls.getProductionLineId());
            for (Map.Entry<LocalDate, BigDecimal> day : occupiedDays(ls, firstDay, horizonEnd).entrySet()) {
                fixed.add(new FixedOccupancy(day.getKey(), sameLine, day.getValue(), productTypeId, equip, label));
            }
        }
        for (PlanInput p : plans) {
            if (alreadyScheduled.contains(p.plan().getId())) {
                warnings.add("计划「" + p.label() + "」在别的排程里已经排过（还没做完），这次又排了一遍；"
                        + "请确认后把旧的那份取消，避免重复");
            }
        }

        // 原料
        Map<String, List<Need>> needs = new LinkedHashMap<>();
        Map<String, Supply> supply = new HashMap<>();
        for (PlanInput p : plans) {
            needs.put(p.plan().getId(), materialNeeds(factoryId, p, notes.get(p.plan().getId()), supply));
        }

        // 半成品依赖
        Map<String, Set<String>> predecessors = dependencies(factoryId, plans, notes);

        Map<String, PlanConstraint> byPlan = new LinkedHashMap<>();
        for (PlanInput p : plans) {
            String id = p.plan().getId();
            byPlan.put(id, new PlanConstraint(equipmentByPlan.getOrDefault(id, Set.of()), unavailable.get(id),
                    predecessors.getOrDefault(id, Set.of()), List.copyOf(notes.get(id))));
        }
        return new Constraints(byPlan, fixed, blocks, equipment.labels(), needs, supply, warnings);
    }

    // ==================== 设备 ====================

    private record EquipmentInfo(Set<String> usable, String unavailableReason, List<String> notes) {
    }

    private final class EquipmentCache {
        private final String factoryId;
        private final Map<String, EquipmentInfo> byProduct = new HashMap<>();
        private final Map<Long, FactoryEquipment> rows = new LinkedHashMap<>();

        EquipmentCache(String factoryId) {
            this.factoryId = factoryId;
        }

        EquipmentInfo forProduct(String productTypeId) {
            if (productTypeId == null || productTypeId.isBlank()) {
                return new EquipmentInfo(Set.of(), null, List.of());
            }
            return byProduct.computeIfAbsent(productTypeId, this::load);
        }

        private EquipmentInfo load(String productTypeId) {
            List<Long> ids = equipmentRequirements.findByFactoryIdAndProductTypeId(factoryId, productTypeId).stream()
                    .map(ProductEquipmentRequirement::getEquipmentId).distinct().sorted().toList();
            if (ids.isEmpty()) {
                return new EquipmentInfo(Set.of(), null,
                        List.of("产品没有配置要用的设备，没核对设备占用；可在「工厂配置」里补上"));
            }
            for (FactoryEquipment e : equipmentRepository.findByIdIn(ids)) {
                if (factoryId.equals(e.getFactoryId()) && e.getDeletedAt() == null) {
                    rows.putIfAbsent(e.getId(), e);
                }
            }
            Set<String> usable = new LinkedHashSet<>();
            List<String> notes = new ArrayList<>();
            List<String> scrapped = new ArrayList<>();
            for (Long id : ids) {
                FactoryEquipment e = rows.get(id);
                if (e == null) {
                    notes.add("配置的设备 #" + id + " 在本厂找不到，没核对它的占用");
                    continue;
                }
                String status = e.getStatus() == null ? "" : e.getStatus().toLowerCase(Locale.ROOT);
                if (SCRAPPED.equals(status)) {
                    scrapped.add(label(e));
                    continue;
                }
                if (MAINTENANCE.equals(status)) {
                    notes.add("设备「" + label(e) + "」现在是检修状态，不知道哪天修好，排程没有避开；请确认");
                }
                usable.add(String.valueOf(id));
            }
            String unavailableReason = scrapped.isEmpty() ? null
                    : "要用的设备「" + String.join("、", scrapped) + "」已报废，没有排；请在「工厂配置」里换设备";
            return new EquipmentInfo(Set.copyOf(usable), unavailableReason, List.copyOf(notes));
        }

        Collection<FactoryEquipment> loaded() {
            return rows.values();
        }

        String label(FactoryEquipment e) {
            String name = e.getEquipmentName() != null ? e.getEquipmentName() : String.valueOf(e.getId());
            return e.getEquipmentCode() != null && !e.getEquipmentCode().isBlank()
                    ? name + "（" + e.getEquipmentCode() + "）" : name;
        }

        Map<String, String> labels() {
            Map<String, String> m = new HashMap<>();
            rows.values().forEach(e -> m.put(String.valueOf(e.getId()), label(e)));
            return m;
        }
    }

    // ==================== 固定占用 ====================

    /** 已有任务每天占这条线多少。有按天分量用它; 没有的按计划时段内每天占满。只取 [from, to] 范围内。 */
    static Map<LocalDate, BigDecimal> occupiedDays(LineSchedule ls, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        Map<String, Object> alloc = ls.getDailyAllocations();
        Object days = alloc == null ? null : alloc.get("days");
        if (days instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> d)) {
                    continue;
                }
                LocalDate date = parseDate(d.get("date"));
                BigDecimal share = toDecimal(d.get("shareOfDay"));
                if (date == null || date.isBefore(from) || date.isAfter(to)) {
                    continue;
                }
                out.merge(date, share == null || share.signum() <= 0 ? BigDecimal.ONE : share, BigDecimal::add);
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        LocalDate start = ls.getPlannedStartTime().toLocalDate();
        LocalDate end = ls.getPlannedEndTime().toLocalDate();
        for (LocalDate d = start.isBefore(from) ? from : start; !d.isAfter(end) && !d.isAfter(to); d = d.plusDays(1)) {
            out.put(d, BigDecimal.ONE);
        }
        return out;
    }

    // ==================== 原料 ====================

    private List<Need> materialNeeds(String factoryId, PlanInput p, List<String> notes, Map<String, Supply> supply) {
        if (p.remaining() == null || p.remaining().signum() <= 0) {
            return List.of();
        }
        List<PlanStandardCostLine> lines = standardLines.findByFactoryIdAndPlanIdOrderByIdAsc(factoryId, p.plan().getId());
        if (lines.stream().noneMatch(l -> PlanStandardCostLine.TYPE_MATERIAL.equals(l.getLineType()))) {
            notes.add("计划没有开计划时的标准物料快照，原料够不够没核对");
            return List.of();
        }
        BigDecimal kg = CapacityUnits.convert(p.remaining(), p.unit(), "kg").orElse(null);
        List<Need> needs = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (PlanCostVarianceCalculator.MaterialQuantity q
                : PlanCostVarianceCalculator.standardMaterialQuantities(lines, p.remaining(), kg, p.joint())) {
            if (q.materialTypeId() == null) {
                continue;
            }
            Optional<RawMaterialType> material = rawMaterialTypeRepository.findByIdAndFactoryId(q.materialTypeId(), factoryId);
            String name = material.map(RawMaterialType::getName).orElse(q.materialTypeId());
            if (q.quantity() == null) {
                unknown.add(name);
                continue;
            }
            String stockUnit = material.map(RawMaterialType::getUnit).orElse(null);
            Optional<BigDecimal> converted = stockUnit == null ? Optional.empty()
                    : CapacityUnits.convert(q.quantity(), q.unit(), stockUnit);
            if (converted.isEmpty()) {
                unknown.add(name);
                continue;
            }
            needs.add(new Need(q.materialTypeId(), name, converted.get()));
            supply.computeIfAbsent(q.materialTypeId(), id -> supply(factoryId, id, stockUnit));
        }
        if (!unknown.isEmpty()) {
            notes.add("原料「" + String.join("、", unknown) + "」的用量算不出来（配方缺出成率或单位对不上），没核对够不够");
        }
        return needs;
    }

    private Supply supply(String factoryId, String materialTypeId, String stockUnit) {
        BigDecimal free = materialBatchRepository.sumAvailableRawStockQuantityByMaterialType(factoryId, materialTypeId);
        List<Arrival> arrivals = new ArrayList<>();
        boolean unknown = false;
        for (Object[] row : constraintQueries.findOpenPurchaseQuantities(factoryId, materialTypeId)) {
            LocalDate date = parseDate(row[0]);
            BigDecimal open = toDecimal(row[1]);
            Optional<BigDecimal> converted = open == null ? Optional.empty()
                    : CapacityUnits.convert(open, (String) row[2], stockUnit);
            if (date == null || converted.isEmpty()) {
                unknown = true;
                continue;
            }
            arrivals.add(new Arrival(date, converted.get()));
        }
        return new Supply(free == null ? BigDecimal.ZERO : free, arrivals, unknown);
    }

    // ==================== 半成品依赖 ====================

    private Map<String, Set<String>> dependencies(String factoryId, List<PlanInput> plans, Map<String, List<String>> notes) {
        // 谁产出什么: 计划主产品 + 目标成品; 产品编码也认 (半成品引用码可能写的是编码)
        Map<String, Set<String>> producers = new HashMap<>();
        Map<String, String> codeToProduct = new HashMap<>();
        for (PlanInput p : plans) {
            Set<String> outputs = new LinkedHashSet<>();
            if (p.plan().getProductTypeId() != null) {
                outputs.add(p.plan().getProductTypeId());
            }
            if (p.plan().getTargetFinishedGoodIds() != null) {
                outputs.addAll(p.plan().getTargetFinishedGoodIds());
            }
            for (String out : outputs) {
                producers.computeIfAbsent(out, k -> new LinkedHashSet<>()).add(p.plan().getId());
                productTypeRepository.findByIdAndFactoryId(out, factoryId).map(ProductType::getCode)
                        .filter(c -> !c.isBlank()).ifPresent(c -> codeToProduct.putIfAbsent(c, out));
            }
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (PlanInput p : plans) {
            List<BomRecipeItem> items = PlanBomItems.pinnedOnly(factoryId, p.plan(), bomItemRepository);
            if (items.isEmpty()) {
                items = PlanBomItems.resolve(factoryId, p.plan(), bomItemRepository);
            }
            Set<String> pre = new LinkedHashSet<>();
            Set<String> unresolved = new LinkedHashSet<>();
            for (BomRecipeItem item : items) {
                if (BomRecipeItem.isByproductCategory(item.getMaterialCategory())) {
                    continue;
                }
                List<String> refs = new ArrayList<>();
                if (item.getSubProductTypeId() != null && !item.getSubProductTypeId().isBlank()) {
                    refs.add(item.getSubProductTypeId());
                }
                String ref = item.getSemiFinishedRefCode();
                if (ref != null && !ref.isBlank()) {
                    refs.add(ref);
                    String byCode = codeToProduct.get(ref);
                    if (byCode != null) {
                        refs.add(byCode);
                    }
                }
                if (refs.isEmpty()) {
                    continue;
                }
                Set<String> found = new LinkedHashSet<>();
                for (String r : refs) {
                    for (String producer : producers.getOrDefault(r, Set.of())) {
                        if (!producer.equals(p.plan().getId())) {
                            found.add(producer);
                        }
                    }
                }
                if (!found.isEmpty()) {
                    pre.addAll(found);
                    continue;
                }
                boolean inStock = false;
                for (String r : refs) {
                    BigDecimal available = semiFinishedInventoryRepository.sumAvailableByProduct(factoryId, r);
                    if (available != null && available.signum() > 0) {
                        inStock = true;
                        break;
                    }
                }
                if (!inStock) {
                    unresolved.add(item.getMaterialName() != null ? item.getMaterialName() : refs.get(0));
                }
            }
            if (!pre.isEmpty()) {
                result.put(p.plan().getId(), pre);
            }
            if (!unresolved.isEmpty()) {
                notes.get(p.plan().getId()).add("要用的半成品「" + String.join("、", unresolved)
                        + "」这次没有一起排产出它的计划，库存里也没有可用的，没核对；请一起排上做它的计划");
            }
        }
        return result;
    }

    // ==================== 小工具 ====================

    private static String planLabel(ProductionPlan plan) {
        return plan.getPlanNumber() != null ? plan.getPlanNumber() : plan.getId();
    }

    static LocalDate parseDate(Object o) {
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof java.time.LocalDateTime t) {
            return t.toLocalDate();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    static BigDecimal toDecimal(Object o) {
        return ExperienceCapacityService.toDecimal(o);
    }
}
