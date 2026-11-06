package com.cretas.aims.service.supplyimpact;

import com.cretas.aims.dto.supplyimpact.SupplyImpactDTO;
import com.cretas.aims.dto.supplyimpact.SupplyImpactBoxExposure;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.bom.BomItemSubstitute;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomItemSubstituteRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.bom.PlanBomItems;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import com.cretas.aims.service.production.PlanCostVarianceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.cretas.aims.dto.supplyimpact.SupplyImpactDTO.*;

/**
 * 从供应商 / 原料品种出发的影响查询 (断供 / 质量 / 涨价)。只读, 关系库查询, 不写任何数据。
 *
 * <p>复用, 不另写一套: 质量 → {@link LineageGraphAssembler#assembleImpact} (追溯/召回同一条下游); 涨价 →
 * 开计划时的标准成本快照行 + {@link PlanCostVarianceCalculator#planStandardQuantity} (食品 DAG 缺口 09 同口径);
 * 配方依赖 → 当前生效配方 + 选择组 ({@code substituteGroup}) + 登记替代料。</p>
 *
 * <p>缺数据如实进 {@code gaps}: 原料没登记供应商、配方没绑物料、计划没有快照、单位换不过去…… 不猜。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplyImpactService {

    public static final int MAX_HORIZON_DAYS = 180;
    private static final Set<ProductionPlanStatus> OPEN_PLAN = EnumSet.of(
            ProductionPlanStatus.PLANNED, ProductionPlanStatus.PENDING,
            ProductionPlanStatus.IN_PROGRESS, ProductionPlanStatus.PAUSED);
    private static final List<SalesOrderStatus> OPEN_ORDER = List.of(
            SalesOrderStatus.CONFIRMED, SalesOrderStatus.PENDING_FINANCE_REVIEW, SalesOrderStatus.FINANCE_APPROVED,
            SalesOrderStatus.PROCESSING, SalesOrderStatus.PARTIAL_DELIVERED);
    private static final List<PurchaseOrderStatus> OPEN_PO = List.of(
            PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PENDING_FINANCE_REVIEW,
            PurchaseOrderStatus.FINANCE_APPROVED, PurchaseOrderStatus.PARTIAL_RECEIVED);
    private static final Set<MaterialBatchStatus> STOCK_STATUS = EnumSet.of(
            MaterialBatchStatus.AVAILABLE, MaterialBatchStatus.IN_STOCK, MaterialBatchStatus.FRESH,
            MaterialBatchStatus.FROZEN, MaterialBatchStatus.RESERVED);

    private final SupplierRepository supplierRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final SupplierMaterialRepository supplierMaterialRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final BomRecipeItemRepository bomRecipeItemRepository;
    private final BomItemSubstituteRepository bomItemSubstituteRepository;
    private final ProductionPlanRepository planRepository;
    private final PlanStandardCostLineRepository costLineRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final LineageGraphAssembler lineageGraphAssembler;
    private final UnitConversionService unitConversionService;
    private final SupplyImpactBoxExposureService boxExposureService;

    // ───────────────────────── 1. 断供 ─────────────────────────

    @Transactional(readOnly = true)
    public DisruptionView disruption(String factoryId, String supplierId, String materialTypeId, Integer horizonDays) {
        int horizon = clampHorizon(horizonDays);
        Scope scope = resolveScope(factoryId, supplierId, materialTypeId);
        List<Gap> gaps = new ArrayList<>(scope.gaps);
        List<MaterialRef> refs = materialRefs(factoryId, scope, gaps);

        List<RecipeDependency> recipes = new ArrayList<>();
        Map<String, List<BomRecipeItem>> itemsByMaterial = new LinkedHashMap<>();
        for (String mid : scope.materialIds) {
            List<BomRecipeItem> items = bomRecipeItemRepository.findCurrentByMaterial(factoryId, mid);
            itemsByMaterial.put(mid, items);
            for (BomRecipeItem it : items) {
                recipes.add(dependency(factoryId, it, scope.materialIds));
            }
        }

        List<PlanImpact> plans = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(horizon);
        List<ProductionPlan> candidate = planRepository.findByFactoryIdAndStatusIn(factoryId, OPEN_PLAN).stream()
                .filter(p -> p.getPlannedDate() == null || !p.getPlannedDate().isAfter(end))
                .toList();
        for (ProductionPlan plan : candidate) {
            plans.addAll(planImpacts(factoryId, plan, scope.materialIds, itemsByMaterial, gaps));
        }
        plans.sort(java.util.Comparator.comparing(PlanImpact::getPlannedDate,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));

        List<StockCover> stock = new ArrayList<>();
        for (String mid : scope.materialIds) {
            stock.add(stockCover(factoryId, mid, scope, plans, horizon, gaps));
        }

        Set<String> planIds = plans.stream().map(PlanImpact::getPlanId).collect(Collectors.toSet());
        Set<String> productIds = recipes.stream().map(RecipeDependency::getProductTypeId).collect(Collectors.toSet());
        List<OrderImpact> orders = orders(factoryId, candidate.stream().filter(p -> planIds.contains(p.getId())).toList(),
                productIds, gaps);

        return DisruptionView.builder().subject(scope.subject).horizonDays(horizon).materials(refs)
                .recipes(recipes).plans(plans).stock(stock).orders(orders).gaps(gaps).build();
    }

    // ───────────────────────── 2. 质量 ─────────────────────────

    @Transactional(readOnly = true)
    public QualityView quality(String factoryId, String supplierId, String materialTypeId, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(400, "起始日期不能晚于结束日期");
        }
        Scope scope = resolveScope(factoryId, supplierId, materialTypeId);
        List<Gap> gaps = new ArrayList<>(scope.gaps);
        List<MaterialBatch> batches = scope.batches(materialBatchRepository, factoryId).stream()
                .filter(b -> scope.materialIds.contains(b.getMaterialTypeId()))
                .filter(b -> from == null || (b.getReceiptDate() != null && !b.getReceiptDate().isBefore(from)))
                .filter(b -> to == null || (b.getReceiptDate() != null && !b.getReceiptDate().isAfter(to)))
                .toList();
        if ((from != null || to != null)
                && scope.batches(materialBatchRepository, factoryId).stream().anyMatch(b -> b.getReceiptDate() == null)) {
            gaps.add(gap("BATCH_NO_RECEIPT_DATE", "有批次没有入库日期, 日期范围无法判断, 已排除出本次范围"));
        }
        if (batches.isEmpty()) {
            gaps.add(gap("NO_BATCH_IN_RANGE", "所选范围内没有查到该供应商/原料的入库批次"));
        }

        Map<String, String> supplierNames = new LinkedHashMap<>();
        Map<String, String> materialNames = new LinkedHashMap<>();
        List<QualityBatchRef> refs = new ArrayList<>();
        Map<String, DownstreamNode> prod = new LinkedHashMap<>();
        Map<String, DownstreamNode> fin = new LinkedHashMap<>();
        Map<String, DownstreamNode> ship = new LinkedHashMap<>();
        boolean lineageComplete = true;
        for (MaterialBatch b : batches) {
            BigDecimal remaining = nz(b.getReceiptQuantity()).subtract(nz(b.getUsedQuantity()));
            refs.add(QualityBatchRef.builder().batchId(b.getId()).batchNumber(b.getBatchNumber())
                    .materialName(materialName(factoryId, b.getMaterialTypeId(), materialNames))
                    .supplierName(supplierName(factoryId, b.getSupplierId(), supplierNames))
                    .receiptDate(b.getReceiptDate()).receiptQuantity(b.getReceiptQuantity())
                    .remainingQuantity(remaining.max(BigDecimal.ZERO)).unit(b.getQuantityUnit()).build());
            try {
                TraceabilityDTO.ImpactResponse r = lineageGraphAssembler.assembleImpact(
                        factoryId, LineageEdgeRecorder.NODE_MATERIAL_BATCH, b.getId(),
                        LineageGraphAssembler.MAX_DEPTH_CAP);
                collect(r.getProductionBatches(), prod);
                collect(r.getFinishedBatches(), fin);
                collect(r.getShipments(), ship);
                if (r.getCompleteness() != null && r.getCompleteness().getGaps() != null
                        && !r.getCompleteness().getGaps().isEmpty()) {
                    lineageComplete = false;
                    gaps.add(gap("LINEAGE_INCOMPLETE", "批次 " + b.getBatchNumber() + " 的下游追溯不完整: "
                            + r.getCompleteness().getSummary()));
                }
            } catch (RuntimeException e) {
                lineageComplete = false;
                log.warn("[supply-impact] 批次下游追溯失败 factory={} batch={}", factoryId, b.getId(), e);
                gaps.add(gap("TRACE_FAILED", "批次 " + b.getBatchNumber() + " 下游追溯失败, 该批影响面未计入: " + e.getMessage()));
            }
        }
        List<CustomerExposure> customers = customers(ship.values(), gaps);
        Set<String> discoveredFinishedBatchIds = fin.values().stream()
                .map(DownstreamNode::getNodeId).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> discoveredShipmentIds = ship.values().stream()
                .map(DownstreamNode::getNodeId).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        SupplyImpactBoxExposure boxExposure = boxExposureService.evaluate(
                factoryId, discoveredFinishedBatchIds, discoveredShipmentIds, lineageComplete);
        gaps.addAll(boxExposure.getGaps());

        Set<String> productIds = new LinkedHashSet<>();
        for (String mid : scope.materialIds) {
            bomRecipeItemRepository.findCurrentByMaterial(factoryId, mid)
                    .forEach(i -> productIds.add(i.getRecipe().getProductTypeId()));
        }
        List<OrderImpact> unshipped = openOrdersForProducts(factoryId, productIds, "PRODUCT");

        return QualityView.builder().subject(scope.subject).from(from).to(to).sourceBatches(refs)
                .productionBatches(new ArrayList<>(prod.values())).finishedBatches(new ArrayList<>(fin.values()))
                .shipments(new ArrayList<>(ship.values())).customers(customers).unshippedOrders(unshipped)
                .boxExposure(boxExposure)
                .gaps(gaps).build();
    }

    // ───────────────────────── 3. 涨价 ─────────────────────────

    @Transactional(readOnly = true)
    public PriceView price(String factoryId, String materialTypeId, BigDecimal percent) {
        if (materialTypeId == null || materialTypeId.isBlank()) {
            throw new BusinessException(400, "涨价分析必须指定原料品种");
        }
        if (percent == null) {
            throw new BusinessException(400, "必须给出涨价百分比");
        }
        Scope scope = resolveScope(factoryId, null, materialTypeId);
        List<Gap> gaps = new ArrayList<>(scope.gaps);
        RawMaterialType material = materialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId).orElseThrow();
        BigDecimal archivePrice = material.getUnitPrice();
        if (archivePrice == null) {
            gaps.add(gap("MATERIAL_NO_UNIT_PRICE", "物料档案没有单价, 将用各计划开计划时的快照单价; 快照也没有的项算不出"));
        }

        List<BomRecipeItem> items = bomRecipeItemRepository.findCurrentByMaterial(factoryId, materialTypeId);
        List<RecipeDependency> recipes = items.stream()
                .map(i -> dependency(factoryId, i, Set.of(materialTypeId))).toList();

        List<ProductCostChange> products = new ArrayList<>();
        List<ProductionPlan> affected = new ArrayList<>();
        for (ProductionPlan plan : planRepository.findByFactoryIdAndStatusIn(factoryId, OPEN_PLAN)) {
            List<PlanStandardCostLine> lines = costLineRepository.findByFactoryIdAndPlanIdOrderByIdAsc(factoryId, plan.getId());
            if (lines.isEmpty()) {
                boolean uses = !PlanBomItems.pinnedOnly(factoryId, plan, bomRecipeItemRepository).stream()
                        .noneMatch(i -> materialTypeId.equals(i.getMaterialTypeId()));
                if (uses) {
                    affected.add(plan);
                    products.add(ProductCostChange.builder().productTypeId(plan.getProductTypeId())
                            .planNumber(plan.getPlanNumber()).plannedUnit(plan.getPlannedUnit())
                            .reason("这张计划用了该原料但没有标准成本快照, 单位成本变化无法量化").build());
                }
                continue;
            }
            for (PlanStandardCostLine line : lines) {
                if (!PlanStandardCostLine.TYPE_MATERIAL.equals(line.getLineType())
                        || !materialTypeId.equals(line.getMaterialTypeId())) {
                    continue;
                }
                affected.add(plan);
                products.add(productCost(plan, lines, line, archivePrice, percent));
            }
        }
        if (recipes.stream().anyMatch(r -> products.stream().noneMatch(p -> Objects.equals(p.getProductTypeId(), r.getProductTypeId())))) {
            gaps.add(gap("RECIPE_WITHOUT_OPEN_PLAN", "部分配方用了该原料但目前没有未完成计划, 只列依赖, 不估算单位成本"));
        }

        Set<String> productIds = recipes.stream().map(RecipeDependency::getProductTypeId).collect(Collectors.toSet());
        List<OrderImpact> orders = orders(factoryId, affected, productIds, gaps);
        return PriceView.builder().subject(scope.subject).percent(percent).currentUnitPrice(archivePrice)
                .recipes(recipes).products(products).orders(orders).gaps(gaps).build();
    }

    private ProductCostChange productCost(ProductionPlan plan, List<PlanStandardCostLine> lines,
                                          PlanStandardCostLine line, BigDecimal archivePrice, BigDecimal percent) {
        ProductCostChange.ProductCostChangeBuilder b = ProductCostChange.builder()
                .productTypeId(plan.getProductTypeId()).planNumber(plan.getPlanNumber())
                .plannedUnit(plan.getPlannedUnit()).quantityUnit(line.getPriceUnit());
        BigDecimal planned = plan.getPlannedQuantity();
        if (planned == null || planned.signum() <= 0) {
            return b.reason("计划数量为空, 算不出单位成本变化").build();
        }
        BigDecimal outputKg = unitConversionService.toKg(planned, plan.getPlannedUnit());
        PlanCostVarianceCalculator.PlanQty q = PlanCostVarianceCalculator.planStandardQuantity(
                lines, line, outputKg, planned, PlanBomItems.isMultiOutput(plan), this::convert);
        if (q.quantity() == null) {
            return b.reason(q.reason()).build();
        }
        BigDecimal price = archivePrice != null ? archivePrice : line.getUnitPrice();
        if (price == null) {
            return b.reason("物料档案和开计划快照都没有单价, 算不出金额").build();
        }
        BigDecimal planDelta = q.quantity().multiply(price).multiply(percent)
                .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        return b.materialQuantityPerPlanUnit(q.quantity().divide(planned, 6, RoundingMode.HALF_UP))
                .unitCostBefore(q.quantity().multiply(price).divide(planned, 4, RoundingMode.HALF_UP))
                .unitCostDelta(planDelta.divide(planned, 4, RoundingMode.HALF_UP))
                .planCostDelta(planDelta.setScale(2, RoundingMode.HALF_UP)).build();
    }

    // ───────────────────────── 共用 ─────────────────────────

    private static final class Scope {
        Subject subject;
        String supplierId;
        Set<String> materialIds = new LinkedHashSet<>();
        List<Gap> gaps = new ArrayList<>();

        List<MaterialBatch> batches(MaterialBatchRepository repo, String factoryId) {
            if (supplierId != null) {
                return repo.findByFactoryIdAndSupplierId(factoryId, supplierId);
            }
            List<MaterialBatch> all = new ArrayList<>();
            for (String mid : materialIds) {
                all.addAll(repo.findByFactoryIdAndMaterialTypeId(factoryId, mid));
            }
            return all;
        }
    }

    private Scope resolveScope(String factoryId, String supplierId, String materialTypeId) {
        boolean hasSupplier = supplierId != null && !supplierId.isBlank();
        boolean hasMaterial = materialTypeId != null && !materialTypeId.isBlank();
        if (hasSupplier == hasMaterial) {
            throw new BusinessException(400, "supplierId 与 materialTypeId 必须二选一");
        }
        Scope s = new Scope();
        if (hasSupplier) {
            Supplier sup = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                    .orElseThrow(() -> new BusinessException(404, "供应商不存在或不属于当前工厂"));
            s.supplierId = sup.getId();
            s.subject = Subject.builder().kind("SUPPLIER").id(sup.getId()).name(sup.getName()).build();
            supplierMaterialRepository.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(factoryId, supplierId)
                    .stream().filter(sm -> !Boolean.FALSE.equals(sm.getActive()))
                    .forEach(sm -> s.materialIds.add(sm.getMaterialTypeId()));
            materialBatchRepository.findByFactoryIdAndSupplierId(factoryId, supplierId)
                    .forEach(b -> {
                        if (b.getMaterialTypeId() != null) {
                            s.materialIds.add(b.getMaterialTypeId());
                        }
                    });
            if (s.materialIds.isEmpty()) {
                s.gaps.add(gap("SUPPLIER_NO_MATERIAL", "该供应商没有登记供货物料, 也没有入库批次, 无法判断它供什么料"));
            }
        } else {
            RawMaterialType m = materialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId)
                    .orElseThrow(() -> new BusinessException(404, "原料品种不存在或不属于当前工厂"));
            s.subject = Subject.builder().kind("MATERIAL").id(m.getId()).name(m.getName()).build();
            s.materialIds.add(m.getId());
        }
        return s;
    }

    private List<MaterialRef> materialRefs(String factoryId, Scope scope, List<Gap> gaps) {
        List<MaterialRef> out = new ArrayList<>();
        for (String mid : scope.materialIds) {
            RawMaterialType m = materialTypeRepository.findByIdAndFactoryId(mid, factoryId).orElse(null);
            List<String> others = supplierMaterialRepository
                    .findByFactoryIdAndMaterialTypeIdOrderByActiveDescPreferredDescCreatedAtDesc(factoryId, mid).stream()
                    .filter(sm -> !Boolean.FALSE.equals(sm.getActive()))
                    .map(SupplierMaterial::getSupplierId)
                    .filter(id -> !id.equals(scope.supplierId))
                    .distinct()
                    .map(id -> supplierName(factoryId, id, new LinkedHashMap<>()))
                    .toList();
            String name = m == null ? mid : m.getName();
            if (m == null) {
                gaps.add(gap("MATERIAL_NOT_FOUND", "物料 " + mid + " 在物料档案里查不到"));
            }
            if (others.isEmpty()) {
                gaps.add(gap("NO_ALTERNATIVE_SUPPLIER", name + " 没有登记其它在供供应商, 停供即无备选来源"));
            }
            out.add(MaterialRef.builder().materialTypeId(mid).name(name).unit(m == null ? null : m.getUnit())
                    .otherSuppliers(others).build());
        }
        return out;
    }

    private RecipeDependency dependency(String factoryId, BomRecipeItem it, Set<String> disrupted) {
        List<String> group = new ArrayList<>();
        if (it.getSubstituteGroup() != null && !it.getSubstituteGroup().isBlank()) {
            bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc(it.getRecipeId()).stream()
                    .filter(o -> !Objects.equals(o.getId(), it.getId()))
                    .filter(o -> it.getSubstituteGroup().equals(o.getSubstituteGroup()))
                    .filter(o -> !disrupted.contains(o.getMaterialTypeId()))
                    .forEach(o -> group.add(o.getMaterialName() != null ? o.getMaterialName() : o.getMaterialTypeId()));
        }
        List<String> subs = bomItemSubstituteRepository
                .findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(
                        factoryId, it.getRecipeId(), BomItemSubstitute.ParentKind.RECIPE_ITEM, it.getId()).stream()
                .filter(s -> !disrupted.contains(s.getSubstituteMaterialTypeId()))
                .map(s -> s.getSubstituteMaterialNameSnapshot() != null
                        ? s.getSubstituteMaterialNameSnapshot() : s.getSubstituteMaterialTypeId())
                .toList();
        boolean optional = Boolean.TRUE.equals(it.getIsOptional());
        return RecipeDependency.builder().recipeId(it.getRecipeId())
                .productTypeId(it.getRecipe().getProductTypeId()).productName(it.getRecipe().getProductName())
                .version(it.getRecipe().getVersion()).materialTypeId(it.getMaterialTypeId())
                .materialName(it.getMaterialName()).optional(optional).groupAlternatives(group)
                .registeredSubstitutes(subs).hasAlternative(optional || !group.isEmpty() || !subs.isEmpty()).build();
    }

    private List<PlanImpact> planImpacts(String factoryId, ProductionPlan plan, Set<String> materialIds,
                                         Map<String, List<BomRecipeItem>> itemsByMaterial, List<Gap> gaps) {
        List<PlanStandardCostLine> lines = costLineRepository.findByFactoryIdAndPlanIdOrderByIdAsc(factoryId, plan.getId());
        List<PlanImpact> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (!lines.isEmpty()) {
            BigDecimal planned = plan.getPlannedQuantity();
            BigDecimal outputKg = planned == null ? null : unitConversionService.toKg(planned, plan.getPlannedUnit());
            for (PlanStandardCostLine line : lines) {
                if (!PlanStandardCostLine.TYPE_MATERIAL.equals(line.getLineType())
                        || !materialIds.contains(line.getMaterialTypeId())) {
                    continue;
                }
                seen.add(line.getMaterialTypeId());
                PlanCostVarianceCalculator.PlanQty q = planned == null
                        ? new PlanCostVarianceCalculator.PlanQty(null, "计划数量为空")
                        : PlanCostVarianceCalculator.planStandardQuantity(lines, line, outputKg, planned,
                                PlanBomItems.isMultiOutput(plan), this::convert);
                out.add(planImpact(plan, line.getMaterialTypeId(), line.getMaterialName(), q.quantity(),
                        line.getPriceUnit(), q.reason()));
            }
            return out;
        }
        // 没有快照: 用计划钉住的配方判断"用没用", 用量如实说算不出
        for (BomRecipeItem it : PlanBomItems.pinnedOnly(factoryId, plan, bomRecipeItemRepository)) {
            if (materialIds.contains(it.getMaterialTypeId()) && seen.add(it.getMaterialTypeId())) {
                out.add(planImpact(plan, it.getMaterialTypeId(), it.getMaterialName(), null, it.getUnit(),
                        "这张计划没有标准成本快照, 用量算不出 (只确认配方用到该原料)"));
            }
        }
        if (out.isEmpty() && PlanBomItems.pinnedRecipeIds(plan).isEmpty()
                && itemsByMaterial.values().stream().flatMap(List::stream)
                .anyMatch(i -> Objects.equals(i.getRecipe().getProductTypeId(), plan.getProductTypeId()))) {
            out.add(planImpact(plan, null, null, null, null,
                    "计划没有锁定配方版本也没有快照, 但该产品当前配方用到了这批原料, 用量算不出"));
        }
        if (out.isEmpty() && lines.isEmpty() && PlanBomItems.pinnedRecipeIds(plan).isEmpty()) {
            gaps.add(gap("PLAN_NO_RECIPE", "计划 " + plan.getPlanNumber() + " 没有绑定配方, 无法判断是否用到该原料"));
        }
        return out;
    }

    private PlanImpact planImpact(ProductionPlan plan, String mid, String mname, BigDecimal qty, String unit, String reason) {
        List<String> orderIds = sourceOrderIds(plan);
        return PlanImpact.builder().planId(plan.getId()).planNumber(plan.getPlanNumber())
                .productTypeId(plan.getProductTypeId()).status(plan.getStatus().name())
                .plannedDate(plan.getPlannedDate()).plannedQuantity(plan.getPlannedQuantity())
                .plannedUnit(plan.getPlannedUnit()).materialTypeId(mid).materialName(mname)
                .requiredQuantity(qty).quantityUnit(unit).quantityReason(qty == null ? reason : null)
                .sourceOrderIds(orderIds).build();
    }

    private StockCover stockCover(String factoryId, String mid, Scope scope, List<PlanImpact> plans, int horizon,
                                  List<Gap> gaps) {
        RawMaterialType m = materialTypeRepository.findByIdAndFactoryId(mid, factoryId).orElse(null);
        String unit = m == null ? null : m.getUnit();
        BigDecimal onHand = BigDecimal.ZERO;
        boolean unitIssue = false;
        for (MaterialBatch b : materialBatchRepository.findByFactoryIdAndMaterialTypeId(factoryId, mid)) {
            if (b.getStatus() == null || !STOCK_STATUS.contains(b.getStatus())) {
                continue;
            }
            BigDecimal rem = nz(b.getReceiptQuantity()).subtract(nz(b.getUsedQuantity())).max(BigDecimal.ZERO);
            BigDecimal conv = unit == null || b.getQuantityUnit() == null ? rem : convert(rem, b.getQuantityUnit(), unit);
            if (conv == null) {
                unitIssue = true;
            } else {
                onHand = onHand.add(conv);
            }
        }
        BigDecimal fromSubject = BigDecimal.ZERO;
        BigDecimal fromOthers = BigDecimal.ZERO;
        List<PurchaseOrder> pos = purchaseOrderRepository.findByFactoryIdAndStatusIn(factoryId, OPEN_PO);
        if (!pos.isEmpty()) {
            Map<String, PurchaseOrder> byId = pos.stream().collect(Collectors.toMap(PurchaseOrder::getId, p -> p, (a, b) -> a));
            for (PurchaseOrderItem it : purchaseOrderItemRepository.findByPurchaseOrderIdIn(new ArrayList<>(byId.keySet()))) {
                if (!mid.equals(it.getMaterialTypeId())) {
                    continue;
                }
                BigDecimal open = nz(it.getQuantity()).subtract(nz(it.getReceivedQuantity())).max(BigDecimal.ZERO);
                BigDecimal conv = unit == null || it.getUnit() == null ? open : convert(open, it.getUnit(), unit);
                if (conv == null) {
                    unitIssue = true;
                    continue;
                }
                PurchaseOrder po = byId.get(it.getPurchaseOrderId());
                if (scope.supplierId != null && scope.supplierId.equals(po.getSupplierId())) {
                    fromSubject = fromSubject.add(conv);
                } else {
                    fromOthers = fromOthers.add(conv);
                }
            }
        }
        BigDecimal demand = BigDecimal.ZERO;
        boolean anyQuantified = false;
        boolean anyUnquantified = false;
        for (PlanImpact p : plans) {
            if (!mid.equals(p.getMaterialTypeId())) {
                continue;
            }
            if (p.getRequiredQuantity() == null) {
                anyUnquantified = true;
                continue;
            }
            BigDecimal conv = unit == null || p.getQuantityUnit() == null ? p.getRequiredQuantity()
                    : convert(p.getRequiredQuantity(), p.getQuantityUnit(), unit);
            if (conv == null) {
                anyUnquantified = true;
            } else {
                demand = demand.add(conv);
                anyQuantified = true;
            }
        }
        BigDecimal cover = null;
        String note = null;
        if (anyQuantified && demand.signum() > 0) {
            cover = onHand.multiply(new BigDecimal(horizon)).divide(demand, 1, RoundingMode.HALF_UP);
            note = "按窗口内计划总用量 " + demand.stripTrailingZeros().toPlainString() + (unit == null ? "" : unit)
                    + " 平摊到 " + horizon + " 天估算";
        } else {
            note = "窗口内没有可量化的计划用量, 撑几天算不出";
        }
        if (anyUnquantified) {
            note += "; 另有计划用量算不出, 实际撑的天数会更短";
        }
        if (unitIssue) {
            note += "; 部分库存/在途单位换不到物料单位, 未计入";
            gaps.add(gap("UNIT_NOT_CONVERTIBLE", (m == null ? mid : m.getName()) + " 有库存/在途记录单位换算失败, 已排除"));
        }
        return StockCover.builder().materialTypeId(mid).materialName(m == null ? mid : m.getName()).unit(unit)
                .onHand(onHand).inTransitFromSubject(fromSubject).inTransitFromOthers(fromOthers)
                .horizonDemand(anyQuantified ? demand : null).coverDays(cover).note(note).build();
    }

    /** 计划关联订单 (确切 id) + 同产品尚未发完的订单 (无计划关联, 标 PRODUCT)。 */
    private List<OrderImpact> orders(String factoryId, List<ProductionPlan> plans, Set<String> productIds, List<Gap> gaps) {
        Map<String, OrderImpact> out = new LinkedHashMap<>();
        Map<String, String> productByPlanOrder = new LinkedHashMap<>();
        for (ProductionPlan p : plans) {
            for (String oid : sourceOrderIds(p)) {
                productByPlanOrder.putIfAbsent(oid, p.getProductTypeId());
            }
        }
        for (String oid : productByPlanOrder.keySet()) {
            SalesOrder so = salesOrderRepository.findByIdAndFactoryId(oid, factoryId).orElse(null);
            if (so == null || !OPEN_ORDER.contains(so.getStatus())) {
                continue;
            }
            for (SalesOrderItem item : salesOrderItemRepository.findBySalesOrderId(so.getId())) {
                if (Objects.equals(item.getProductTypeId(), productByPlanOrder.get(oid))) {
                    out.putIfAbsent(so.getId() + "|" + item.getProductName(), orderImpact(so, item, "PLAN"));
                }
            }
        }
        for (OrderImpact o : openOrdersForProducts(factoryId, productIds, "PRODUCT")) {
            out.putIfAbsent(o.getOrderId() + "|" + o.getProductName(), o);
        }
        if (plans.stream().anyMatch(p -> sourceOrderIds(p).isEmpty())) {
            gaps.add(gap("PLAN_NO_SOURCE_ORDER", "部分受影响计划不是订单生产 (没有来源订单), 只能按同产品未发订单列出"));
        }
        return new ArrayList<>(out.values());
    }

    private List<OrderImpact> openOrdersForProducts(String factoryId, Set<String> productIds, String linkedBy) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        List<OrderImpact> out = new ArrayList<>();
        for (SalesOrder so : salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(factoryId, OPEN_ORDER, org.springframework.data.domain.PageRequest.of(0, 2000))) {
            for (SalesOrderItem item : salesOrderItemRepository.findBySalesOrderId(so.getId())) {
                if (productIds.contains(item.getProductTypeId())
                        && nz(item.getQuantity()).subtract(nz(item.getDeliveredQuantity())).signum() > 0) {
                    out.add(orderImpact(so, item, linkedBy));
                }
            }
        }
        return out;
    }

    private OrderImpact orderImpact(SalesOrder so, SalesOrderItem item, String linkedBy) {
        return OrderImpact.builder().orderId(so.getId()).orderNumber(so.getOrderNumber())
                .customerId(so.getCustomerId()).customerName(so.getCustomerName())
                .status(so.getStatus().name()).requiredDeliveryDate(so.getRequiredDeliveryDate())
                .productName(item.getProductName())
                .undeliveredQuantity(nz(item.getQuantity()).subtract(nz(item.getDeliveredQuantity())).max(BigDecimal.ZERO))
                .unit(item.getUnit()).linkedBy(linkedBy).build();
    }

    private static List<String> sourceOrderIds(ProductionPlan p) {
        Set<String> ids = new LinkedHashSet<>();
        if (p.getSourceOrderId() != null && !p.getSourceOrderId().isBlank()) {
            ids.add(p.getSourceOrderId());
        }
        if (p.getSourceOrderIds() != null) {
            p.getSourceOrderIds().stream().filter(Objects::nonNull).forEach(ids::add);
        }
        return new ArrayList<>(ids);
    }

    private static void collect(List<TraceabilityDTO.LineageNode> nodes, Map<String, DownstreamNode> into) {
        if (nodes == null) {
            return;
        }
        for (TraceabilityDTO.LineageNode n : nodes) {
            into.putIfAbsent(n.getNodeType() + "#" + n.getNodeId(), DownstreamNode.builder()
                    .nodeType(n.getNodeType()).nodeId(n.getNodeId()).batchNumber(n.getBatchNumber())
                    .name(n.getName()).quantity(n.getQuantity()).unit(n.getUnit()).build());
        }
    }

    private static List<CustomerExposure> customers(java.util.Collection<DownstreamNode> shipments, List<Gap> gaps) {
        Map<String, List<DownstreamNode>> byCustomer = new LinkedHashMap<>();
        boolean unnamed = false;
        boolean noQty = false;
        for (DownstreamNode s : shipments) {
            if (s.getName() == null) {
                unnamed = true;
            }
            if (s.getQuantity() == null) {
                noQty = true;
            }
            byCustomer.computeIfAbsent(s.getName() == null ? "(客户名查不到)" : s.getName(), k -> new ArrayList<>()).add(s);
        }
        if (unnamed) {
            gaps.add(gap("SHIPMENT_CUSTOMER_UNRESOLVED", "有发货单查不到客户名, 归入「客户名查不到」"));
        }
        if (noQty) {
            gaps.add(gap("SHIPMENT_QTY_UNKNOWN", "有发货关系没有可信数量 (多条边单位不一致), 该条数量不计入汇总"));
        }
        List<CustomerExposure> out = new ArrayList<>();
        byCustomer.forEach((name, list) -> {
            Map<String, Double> perUnit = new LinkedHashMap<>();
            list.stream().filter(s -> s.getQuantity() != null)
                    .forEach(s -> perUnit.merge(s.getUnit() == null ? "?" : s.getUnit(), s.getQuantity(), Double::sum));
            out.add(CustomerExposure.builder().customerName(name).shipmentCount(list.size())
                    .quantities(perUnit.entrySet().stream().map(e -> e.getValue() + e.getKey()).toList()).build());
        });
        return out;
    }

    private BigDecimal convert(BigDecimal q, String from, String to) {
        if (q == null || from == null || to == null) {
            return null;
        }
        if (from.equalsIgnoreCase(to)) {
            return q;
        }
        return unitConversionService.convert(q, from, to);
    }

    private String supplierName(String factoryId, String id, Map<String, String> cache) {
        if (id == null) {
            return null;
        }
        return cache.computeIfAbsent(id, k -> supplierRepository.findByIdAndFactoryId(k, factoryId)
                .map(Supplier::getName).orElse(k));
    }

    private String materialName(String factoryId, String id, Map<String, String> cache) {
        if (id == null) {
            return null;
        }
        return cache.computeIfAbsent(id, k -> materialTypeRepository.findByIdAndFactoryId(k, factoryId)
                .map(RawMaterialType::getName).orElse(k));
    }

    private static int clampHorizon(Integer d) {
        if (d == null || d <= 0) {
            return 14;
        }
        return Math.min(d, MAX_HORIZON_DAYS);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Gap gap(String code, String message) {
        return Gap.builder().code(code).message(message).build();
    }
}
