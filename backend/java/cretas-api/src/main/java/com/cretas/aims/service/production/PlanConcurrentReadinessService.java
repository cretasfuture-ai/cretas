package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.PlanConcurrentReadinessDTO;
import com.cretas.aims.dto.production.PlanConcurrentReadinessDTO.*;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.service.bom.PlanBomItems;
import com.cretas.aims.service.processentry.ProductionInventoryOwnershipGuard;
import com.cretas.aims.service.scheduling.daily.CapacityUnits;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.util.InventoryExpiry;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Advisory snapshot only: never allocates material or changes a start decision. */
@Service
@RequiredArgsConstructor
public class PlanConcurrentReadinessService {
    private static final int MAX_PLANS = 500;
    private static final int MAX_BATCHES_PER_MATERIAL = 2000;
    private static final String COMPANY_POOL = "COMPANY";
    private static final Set<ProductionPlanStatus> ACTIVE = Set.of(ProductionPlanStatus.PLANNED,
            ProductionPlanStatus.PENDING, ProductionPlanStatus.IN_PROGRESS, ProductionPlanStatus.PAUSED);
    private static final Set<ProductionPlanStatus> STARTED = Set.of(ProductionPlanStatus.IN_PROGRESS, ProductionPlanStatus.PAUSED);

    private final ProductionPlanRepository plans;
    private final PlanStandardCostLineRepository standards;
    private final BomRecipeItemRepository bomItems;
    private final MaterialBatchRepository materials;
    private final RawMaterialTypeRepository materialTypes;
    private final ProductionPlanBatchUsageRepository planReservations;
    private final ProductionBatchRepository batches;
    private final MaterialConsumptionRepository consumptions;
    private final ProductionReportRepository reports;
    private final WarehouseResolver warehouses;
    private final FinishedGoodsBatchRepository finishedGoods;
    private final ProcessSecretRecipeGuard secrets;

    // REQUIRES_NEW prevents an outer READ_COMMITTED transaction from weakening this snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public PlanConcurrentReadinessDTO read(String factoryId, String planId) {
        if (blank(factoryId) || blank(planId)) throw new BusinessException(400, "缺少工厂或生产计划身份");
        ProductionPlan target = plans.findByIdAndFactoryId(planId, factoryId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在"));
        LocalDate through = planDate(target);
        LocalDate today = InventoryExpiry.today();
        LocalDate usableOn = through == null || through.isBefore(today) ? today : through;
        List<String> notes = new ArrayList<>();
        notes.add("截至本计划生产日期的未完成计划及已开工未结束计划；生产日期优先取批次日期，否则取计划日期，不按创建日期推测。");
        notes.add("可支持比例只参考锁定标准物料，不代表排程、预留或开工许可；N1默认裁定保持原样。");
        notes.add("下界先满足其他计划未占用需求，上界先满足本计划；本计划已持有预留计入支持，库存净可用不再重复扣预留。");
        notes.add("比例按剩余标准需求（标准总需求减已正式消耗）核算，不等于整个计划的实际完工比例。");
        if (!ACTIVE.contains(target.getStatus())) {
            requireFullProcessAccess(factoryId, List.of(target));
            notes.add("本计划为草稿或终态，不新增同期需求；已入库进度仍独立显示。");
            return new PlanConcurrentReadinessDTO(planId, target.getPlanNumber(), target.getStatus().name(), through,
                    LocalDateTime.now(), "DUE_THROUGH_PLAN_DATE_AND_STARTED", 0, 0, notes,
                    new MaterialCoverage("UNVERIFIED", "NO_TARGET_DEMAND", null, null, List.of()), progress(factoryId, target), true);
        }
        var page = plans.findByFactoryIdAndStatusIn(factoryId, ACTIVE, PageRequest.of(0, MAX_PLANS));
        List<ProductionPlan> cohort = new ArrayList<>();
        int unverified = 0;
        if (through == null) { notes.add("本计划生产日期缺失，无法确定需求窗口。"); unverified++; }
        if (page.getTotalElements() > MAX_PLANS) { notes.add("活跃计划超出单次核算上限，未给出完整比例。"); unverified++; }
        for (ProductionPlan plan : page.getContent()) {
            if (!factoryId.equals(plan.getFactoryId()) || plan.getDeletedAt() != null || !ACTIVE.contains(plan.getStatus())) continue;
            LocalDate date = planDate(plan);
            if (!STARTED.contains(plan.getStatus()) && date == null) { unverified++; continue; }
            if (STARTED.contains(plan.getStatus()) || (through != null && date != null && !date.isAfter(through))) cohort.add(plan);
        }
        if (ACTIVE.contains(target.getStatus()) && cohort.stream().noneMatch(p -> p.getId().equals(planId))) cohort.add(target);
        // Aggregates must not reveal protected peer requirements or omit them and overstate readiness.
        requireFullProcessAccess(factoryId, cohort);
        Set<String> eligibleWarehouses = new HashSet<>();
        try { eligibleWarehouses.addAll(warehouses.productionPickingWarehouseIds(factoryId)); }
        catch (BusinessException missingWarehouse) { notes.add("生产可领料仓的默认配置待核对。"); unverified++; }
        if (eligibleWarehouses.isEmpty()) { notes.add("未找到生产可领料仓库。"); unverified++; }
        List<PlanConcurrentDemandCalculator.DemandFact> demands = new ArrayList<>();
        Map<String, String> units = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, List<MaterialBatch>> materialCache = new HashMap<>();
        Map<String, BigDecimal> attributedReservations = new HashMap<>();
        for (ProductionPlan plan : cohort) {
            try {
                List<Requirement> required = requirements(factoryId, plan);
                Map<String, BigDecimal> held = heldByBatch(factoryId, plan);
                Map<String, BigDecimal> used = usedByBatch(factoryId, plan);
                Set<String> requiredIds = new HashSet<>();
                required.forEach(r -> requiredIds.add(r.materialId()));
                for (String id : union(held.keySet(), used.keySet())) {
                    if (held.getOrDefault(id, BigDecimal.ZERO).signum() == 0 && used.getOrDefault(id, BigDecimal.ZERO).signum() == 0) continue;
                    MaterialBatch reference = materials.findByIdAndFactoryId(id, factoryId)
                            .filter(b -> b.getDeletedAt() == null).orElseThrow(() -> gap("库存引用缺失"));
                    if ("PRODUCTION_BATCH".equalsIgnoreCase(reference.getSourceDocType())) throw gap("在制品回投不能直接抵扣原料标准需求");
                    if (!requiredIds.contains(reference.getMaterialTypeId())) throw gap("替代料或额外领用需核对固定需求归属");
                }
                List<PlanConcurrentDemandCalculator.DemandFact> planFacts = new ArrayList<>();
                Map<String, BigDecimal> planAttributed = new HashMap<>();
                for (Requirement r : required) {
                    String unit = units.computeIfAbsent(r.materialId(), ignored -> r.unit());
                    BigDecimal total = convert(r.total(), r.unit(), unit);
                    BigDecimal consumed = BigDecimal.ZERO;
                    BigDecimal reserved = BigDecimal.ZERO;
                    List<MaterialBatch> stock = stock(factoryId, r.materialId(), materialCache);
                    Set<String> knownBatchIds = new HashSet<>();
                    for (MaterialBatch batch : stock) {
                        knownBatchIds.add(batch.getId());
                        BigDecimal heldQuantity = held.getOrDefault(batch.getId(), BigDecimal.ZERO);
                        BigDecimal usedQuantity = used.getOrDefault(batch.getId(), BigDecimal.ZERO);
                        if (heldQuantity.signum() > 0 || usedQuantity.signum() > 0) {
                            if (batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) throw gap("预留或消耗的库存归属不在公司库存池内");
                            if (heldQuantity.signum() > 0 && (batch.getStatus() != MaterialBatchStatus.AVAILABLE
                                    || "PRODUCTION_BATCH".equalsIgnoreCase(batch.getSourceDocType()) || !eligibleWarehouses.contains(batch.getWarehouseId())
                                    || (batch.getExpiryDate() != null && batch.getExpiryDate().isBefore(usableOn)))) throw gap("持有预留的状态、仓库或到期日待核对");
                            reserved = reserved.add(convert(heldQuantity, batch.getQuantityUnit(), unit));
                            consumed = consumed.add(convert(usedQuantity, batch.getQuantityUnit(), unit));
                            planAttributed.merge(batch.getId(), heldQuantity, BigDecimal::add);
                        }
                    }
                    for (String id : union(held.keySet(), used.keySet())) if (!knownBatchIds.contains(id)
                            && (held.getOrDefault(id, BigDecimal.ZERO).signum() > 0 || used.getOrDefault(id, BigDecimal.ZERO).signum() > 0)) {
                        MaterialBatch reference = materials.findByIdAndFactoryId(id, factoryId).orElseThrow(() -> gap("库存引用缺失"));
                        if (r.materialId().equals(reference.getMaterialTypeId())) throw gap("库存引用不完整");
                    }
                    names.putIfAbsent(r.materialId(), r.name());
                    planFacts.add(new PlanConcurrentDemandCalculator.DemandFact(plan.getId(), r.materialId(), COMPANY_POOL,
                            unit, plan.getStatus().name(), total, consumed, reserved));
                }
                planAttributed.forEach((id, q) -> attributedReservations.merge(id, q, BigDecimal::add));
                demands.addAll(planFacts);
            } catch (ReadGap gap) { unverified++; notes.add(gap.getMessage()); }
        }
        List<PlanConcurrentDemandCalculator.StockFact> stockFacts = new ArrayList<>();
        try {
            for (Map.Entry<String, String> item : units.entrySet()) {
                BigDecimal free = BigDecimal.ZERO;
                for (MaterialBatch batch : stock(factoryId, item.getKey(), materialCache)) {
                    if (batch.getReceiptQuantity() == null || batch.getUsedQuantity() == null || batch.getReservedQuantity() == null
                            || batch.getUsedQuantity().signum() < 0 || batch.getReservedQuantity().signum() < 0
                            || batch.getCurrentQuantity().signum() < 0) throw gap("库存计量或完整性待核对");
                    if (attributedReservations.getOrDefault(batch.getId(), BigDecimal.ZERO).compareTo(batch.getReservedQuantity()) > 0) throw gap("归属预留超过库存预留总量");
                    if (batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED || batch.getStatus() != MaterialBatchStatus.AVAILABLE
                            || "PRODUCTION_BATCH".equalsIgnoreCase(batch.getSourceDocType()) || !eligibleWarehouses.contains(batch.getWarehouseId())) continue;
                    LocalDate expiry = batch.getExpiryDate();
                    if (expiry != null && expiry.isBefore(usableOn)) continue;
                    if (batch.getCurrentQuantity().signum() > 0) free = free.add(convert(batch.getCurrentQuantity(), batch.getQuantityUnit(), item.getValue()));
                }
                stockFacts.add(new PlanConcurrentDemandCalculator.StockFact(item.getKey(), COMPANY_POOL, item.getValue(), free));
            }
        } catch (ReadGap gap) { unverified++; notes.add(gap.getMessage()); }
        if (unverified > 0) notes.add("部分计划或库存缺少可核验依据，不将未覆盖部分当作零需求。");
        var result = PlanConcurrentDemandCalculator.calculate(new PlanConcurrentDemandCalculator.Input(planId, demands, stockFacts, unverified == 0));
        List<Material> rows = result.materials().stream().map(r -> new Material(r.key().materialId(), names.get(r.key().materialId()), r.unit(),
                r.required(), r.consumed(), r.ownHeldReserved(), r.otherUnoccupied(), r.stockFree(), r.lowerPercent(), r.upperPercent())).toList();
        return new PlanConcurrentReadinessDTO(planId, target.getPlanNumber(), target.getStatus().name(), through,
                LocalDateTime.now(), "DUE_THROUGH_PLAN_DATE_AND_STARTED", cohort.size(), unverified,
                notes.stream().distinct().toList(), new MaterialCoverage(result.status().name(), result.reason().name(),
                result.lowerPercent(), result.upperPercent(), rows), progress(factoryId, target), true);
    }

    private record Requirement(String materialId, String name, String unit, BigDecimal total) { }

    private void requireFullProcessAccess(String factoryId, List<ProductionPlan> cohort) {
        Set<String> products = new LinkedHashSet<>();
        Set<String> recipes = new LinkedHashSet<>();
        for (ProductionPlan plan : cohort) {
            if (!blank(plan.getProductTypeId())) products.add(plan.getProductTypeId());
            if (plan.getTargetFinishedGoodIds() != null) products.addAll(plan.getTargetFinishedGoodIds());
            if (plan.getWorkflowOutputUnitsByProduct() != null) products.addAll(plan.getWorkflowOutputUnitsByProduct().keySet());
            recipes.addAll(PlanBomItems.pinnedRecipeIds(plan));
        }
        secrets.requireProtectedAmong(factoryId, products, ProcessSecretAction.VIEW_FULL, "PLAN_CONCURRENT_READINESS");
        for (String recipe : recipes) secrets.requireRecipe(factoryId, recipe, ProcessSecretAction.VIEW_FULL, "PLAN_CONCURRENT_READINESS");
    }

    private List<Requirement> requirements(String factoryId, ProductionPlan plan) {
        if (plan.getPlannedQuantity() == null || plan.getPlannedQuantity().signum() <= 0 || blank(plan.getPlannedUnit())) throw gap("计划数量或固定单位缺失");
        if (PlanBomItems.isMultiOutput(plan)) throw gap("联产计划需按固定产出逐项核对，未合并为单一物料比例");
        List<PlanStandardCostLine> lines = standards.findByFactoryIdAndPlanIdOrderByIdAsc(factoryId, plan.getId());
        if (lines.isEmpty()) throw gap("存在未捕获固定标准物料快照的计划");
        Set<String> pinnedRecipes = PlanBomItems.pinnedRecipeIds(plan);
        if (pinnedRecipes.isEmpty()) throw gap("固定配方身份缺失");
        List<BomRecipeItem> pinnedItems = bomItems.findByRecipeIdsWithRecipe(factoryId, pinnedRecipes);
        if (pinnedItems.isEmpty()) throw gap("固定配方的物料范围缺失");
        Set<String> expectedMaterials = new HashSet<>();
        for (BomRecipeItem item : pinnedItems) {
            if (BomRecipeItem.isByproductCategory(item.getMaterialCategory())) continue;
            if (BomRecipeItem.isSemiFinishedRow(item)) throw gap("回料需另核在制品来源，未并入原料仓支持比例");
            if (blank(item.getMaterialTypeId())) throw gap("固定配方物料身份缺失");
            expectedMaterials.add(item.getMaterialTypeId());
        }
        Set<String> snapshotMaterials = new HashSet<>();
        for (PlanStandardCostLine line : lines) if (PlanStandardCostLine.TYPE_MATERIAL.equals(line.getLineType())) {
            if (!pinnedRecipes.contains(line.getBomRecipeId())) throw gap("标准物料与计划固定配方不一致");
            Integer version = plan.getSelectedBomVersionsByProduct() == null ? null
                    : plan.getSelectedBomVersionsByProduct().get(line.getProductTypeId());
            if (version == null && Objects.equals(plan.getSelectedBomRecipeId(), line.getBomRecipeId())) version = plan.getSelectedBomVersion();
            if (version == null || !version.equals(line.getBomVersion())) throw gap("标准物料与计划固定版本不一致");
            snapshotMaterials.add(line.getMaterialTypeId());
        }
        if (!snapshotMaterials.containsAll(expectedMaterials)) throw gap("固定配方存在未覆盖的物料需求");
        BigDecimal kg = CapacityUnits.convert(plan.getPlannedQuantity(), plan.getPlannedUnit(), "kg").orElse(null);
        List<Requirement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var quantity : PlanCostVarianceCalculator.standardMaterialQuantities(lines, plan.getPlannedQuantity(), kg, false)) {
            if (blank(quantity.materialTypeId()) || quantity.quantity() == null || quantity.quantity().signum() < 0 || blank(quantity.unit())) throw gap("锁定标准物料的用量或单位不可核验");
            if (!seen.add(quantity.materialTypeId())) throw gap("锁定标准物料存在重复归属");
            RawMaterialType material = materialTypes.findByIdAndFactoryId(quantity.materialTypeId(), factoryId)
                    .filter(m -> m.getDeletedAt() == null).orElseThrow(() -> gap("物料身份缺失"));
            if (plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED
                    && !ProductionInventoryOwnershipGuard.isCompanyStockEligibleCategory(material.getMainCategory())) {
                // Customer/order/item eligibility pools overlap and must not be naively added to company stock.
                throw gap("客供主料需按客户和订单库存池核对，未并入公司库存比例");
            }
            result.add(new Requirement(quantity.materialTypeId(), material.getName(), quantity.unit(), quantity.quantity()));
        }
        if (result.isEmpty()) throw gap("锁定标准快照没有可核验的物料需求");
        return result;
    }

    private Map<String, BigDecimal> heldByBatch(String factoryId, ProductionPlan plan) {
        Map<String, BigDecimal> held = new HashMap<>();
        for (ProductionPlanBatchUsage row : planReservations.findByProductionPlanId(plan.getId())) {
            if (row.getDeletedAt() != null) continue;
            if (!plan.getId().equals(row.getProductionPlanId()) || blank(row.getMaterialBatchId()) || row.getReservedQuantity() == null || row.getReservedQuantity().signum() < 0) throw gap("计划预留归属待核对");
            held.merge(row.getMaterialBatchId(), row.getReservedQuantity(), BigDecimal::add);
        }
        Map<String, BigDecimal> reportHeldByBatch = new HashMap<>();
        Set<Long> seenReports = new HashSet<>();
        for (ProductionBatch batch : batches.findByFactoryIdAndProductionPlanId(factoryId, plan.getId())) {
            if (batch.getDeletedAt() != null) continue;
            for (ProductionReport report : reports.findYieldReportsByBatch(factoryId, batch.getId())) {
                if (!WorkshopReportReservationService.holdsReservation(report) || !seenReports.add(report.getId())) continue;
                if (!factoryId.equals(report.getFactoryId())) throw gap("报工预留归属待核对");
                WorkshopReportReservationService.reservationOf(report).forEach((lot, quantity) -> reportHeldByBatch.merge(lot, quantity, BigDecimal::add));
            }
        }
        for (Map.Entry<String, BigDecimal> entry : reportHeldByBatch.entrySet()) {
            String lot = entry.getKey();
            BigDecimal reportHeld = entry.getValue();
            if (reportHeld.signum() > 0 && held.getOrDefault(lot, BigDecimal.ZERO).signum() > 0) throw gap("计划与报工预留同时命中，需先核对归属是否重叠");
            held.merge(lot, reportHeld, BigDecimal::add);
        }
        return held;
    }

    private Map<String, BigDecimal> usedByBatch(String factoryId, ProductionPlan plan) {
        Map<String, BigDecimal> used = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (ProductionBatch batch : batches.findByFactoryIdAndProductionPlanId(factoryId, plan.getId())) {
            if (batch.getDeletedAt() != null) continue;
            for (MaterialConsumption c : consumptions.findByProductionBatchIdAndFactoryId(batch.getId(), factoryId)) {
                if (c.getDeletedAt() != null || !seen.add(c.getId())) continue;
                if (!factoryId.equals(c.getFactoryId()) || (c.getProductionPlanId() != null && !plan.getId().equals(c.getProductionPlanId()))
                        || blank(c.getBatchId()) || c.getQuantity() == null || c.getQuantity().signum() < 0) throw gap("正式消耗归属待核对");
                if (c.getInterimSettledAt() == null) throw gap("存在未确认正式扣减的消耗记录");
                used.merge(c.getBatchId(), c.getQuantity(), BigDecimal::add);
            }
        }
        return used;
    }

    private List<MaterialBatch> stock(String factoryId, String materialId, Map<String, List<MaterialBatch>> cache) {
        List<MaterialBatch> list = cache.computeIfAbsent(materialId, ignored -> materials.findByFactoryIdAndMaterialTypeId(factoryId, materialId).stream()
                .filter(b -> factoryId.equals(b.getFactoryId()) && materialId.equals(b.getMaterialTypeId()) && b.getDeletedAt() == null).toList());
        if (list.size() > MAX_BATCHES_PER_MATERIAL) throw gap("物料批次超出核算上限");
        return list;
    }

    private Progress progress(String factoryId, ProductionPlan plan) {
        BigDecimal planned = plan.getPlannedQuantity();
        if (planned == null || planned.signum() <= 0 || blank(plan.getPlannedUnit()) || PlanBomItems.isMultiOutput(plan)) return unknownProgress(plan, "计划量、单位或联产产出归属待核对");
        String product = plan.getTargetFinishedGoodIds() != null && plan.getTargetFinishedGoodIds().size() == 1 ? plan.getTargetFinishedGoodIds().getFirst() : plan.getProductTypeId();
        if (blank(product)) return unknownProgress(plan, "成品身份待核对");
        BigDecimal received = BigDecimal.ZERO;
        for (Object[] row : finishedGoods.sumProductionOutputByPlan(factoryId, plan.getId())) {
            if (!product.equals(String.valueOf(row[0]))) continue;
            if (!(row[2] instanceof BigDecimal quantity) || quantity.signum() < 0) return unknownProgress(plan, "成品入库数量待核对");
            Optional<BigDecimal> converted = CapacityUnits.convert(quantity, (String) row[1], plan.getPlannedUnit());
            if (converted.isEmpty()) return unknownProgress(plan, "成品入库单位无法换算到计划单位");
            received = received.add(converted.get());
        }
        return new Progress("VERIFIED", "FINISHED_GOODS_RECEIVED", planned, received, planned.subtract(received).max(BigDecimal.ZERO),
                received.multiply(BigDecimal.valueOf(100)).divide(planned, 6, RoundingMode.HALF_UP), plan.getPlannedUnit(),
                "实际成品入库口径，排除退货和已冲销入库；不代表尚未入库的报工量，终态不自动等于100%。");
    }

    private Progress unknownProgress(ProductionPlan plan, String reason) {
        return new Progress("UNVERIFIED", "FINISHED_GOODS_RECEIVED", plan.getPlannedQuantity(), null, null, null, plan.getPlannedUnit(), reason);
    }
    private static LocalDate planDate(ProductionPlan p) { return p.getBatchDate() != null ? p.getBatchDate() : p.getPlannedDate(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static BigDecimal convert(BigDecimal quantity, String from, String to) {
        return CapacityUnits.convert(quantity, from, to).orElseThrow(() -> gap("物料单位不能按固定换算核对"));
    }
    private static Set<String> union(Set<String> left, Set<String> right) { Set<String> result = new HashSet<>(left); result.addAll(right); return result; }
    private static ReadGap gap(String message) { return new ReadGap(message); }
    private static final class ReadGap extends RuntimeException { private ReadGap(String message) { super(message); } }
}
