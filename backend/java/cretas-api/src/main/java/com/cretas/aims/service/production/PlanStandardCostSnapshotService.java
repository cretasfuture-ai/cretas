package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomItemSubstitute;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomItemSubstituteRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.service.bom.PlanBomItems;
import com.cretas.aims.service.workflow.PlanInputShareResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 开计划时写标准成本快照 (食品 DAG 缺口 09, Steve 2026-11-03 裁定)。
 *
 * <p>标准用量规则全部取自<b>计划钉住的配方版本</b> ({@link PlanBomItems#pinnedOnly}) 与钉住的工艺修订
 * (主料份额), 单价取<b>此刻</b>物料档案的未税单价 {@code raw_material_types.unit_price}。写一次, 之后不随价格变。</p>
 *
 * <ul>
 *   <li>⛔ 不维护标准价表, 不用近期采购均价 ({@code moving_avg_price} 不读)。</li>
 *   <li>⛔ 档案没单价就记 null, 不猜 —— 报表那一项说「无法比较」。</li>
 *   <li>快照失败不拦开计划 (报表会如实说「这张计划没有标准成本快照」), 但打 WARN。</li>
 * </ul>
 */
@Slf4j
@Service
public class PlanStandardCostSnapshotService {

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final PlanStandardCostLineRepository lineRepository;
    private final BomRecipeItemRepository bomRecipeItemRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ProductionPlanRepository planRepository;
    private final PlatformTransactionManager transactionManager;

    @Autowired(required = false)
    private BomSeasoningItemRepository bomSeasoningItemRepository;
    @Autowired(required = false)
    private BomItemSubstituteRepository bomItemSubstituteRepository;
    @Autowired(required = false)
    private QuotationTaskRepository quotationTaskRepository;
    @Autowired(required = false)
    private PlanInputShareResolver planInputShareResolver;

    public PlanStandardCostSnapshotService(PlanStandardCostLineRepository lineRepository,
                                           BomRecipeItemRepository bomRecipeItemRepository,
                                           RawMaterialTypeRepository rawMaterialTypeRepository,
                                           ProductionPlanRepository planRepository,
                                           PlatformTransactionManager transactionManager) {
        this.lineRepository = lineRepository;
        this.bomRecipeItemRepository = bomRecipeItemRepository;
        this.rawMaterialTypeRepository = rawMaterialTypeRepository;
        this.planRepository = planRepository;
        this.transactionManager = transactionManager;
    }

    /**
     * 开计划的事务<b>提交之后</b>再写快照 (没有事务时当场写)。这样:
     * 快照失败不会把开计划回滚; 开计划回滚了也不会留下孤儿快照。
     */
    public void captureAfterCommit(String factoryId, ProductionPlan plan) {
        if (factoryId == null || plan == null || plan.getId() == null) {
            return;
        }
        String planId = plan.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    captureIfAbsent(factoryId, planId);
                }
            });
        } else {
            captureIfAbsent(factoryId, planId);
        }
    }

    /**
     * 这张计划还没有快照就写一份。已有快照不动 —— 「开计划那一刻」只有一个。
     * 独立事务, 失败只 WARN 不往外抛 (报表会如实说「这张计划没有标准成本快照」)。
     */
    public List<PlanStandardCostLine> captureIfAbsent(String factoryId, String planId) {
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx.execute(status -> {
                if (lineRepository.existsByFactoryIdAndPlanId(factoryId, planId)) {
                    return lineRepository.findByFactoryIdAndPlanIdOrderByIdAsc(factoryId, planId);
                }
                ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId).orElse(null);
                if (plan == null) {
                    return List.<PlanStandardCostLine>of();
                }
                List<PlanStandardCostLine> lines = buildLines(factoryId, plan, LocalDateTime.now());
                if (lines.isEmpty()) {
                    log.info("[std-cost] 计划 {} 没有钉住的配方明细, 不写标准成本快照", planId);
                    return List.<PlanStandardCostLine>of();
                }
                return lineRepository.saveAll(lines);
            });
        } catch (RuntimeException e) {
            log.warn("[std-cost] 计划 {} 写标准成本快照失败 (开计划不受影响, 报表将显示无快照): {}",
                    planId, e.getMessage(), e);
            return List.of();
        }
    }

    List<PlanStandardCostLine> buildLines(String factoryId, ProductionPlan plan, LocalDateTime now) {
        List<BomRecipeItem> items = PlanBomItems.pinnedOnly(factoryId, plan, bomRecipeItemRepository);
        if (items.isEmpty()) {
            return List.of();
        }
        Map<String, BigDecimal> inputShares = planInputShareResolver == null
                ? Map.of() : planInputShareResolver.resolve(factoryId, plan);
        List<PlanStandardCostLine> lines = new ArrayList<>();
        Set<String> materials = new HashSet<>();
        Set<String> recipeIds = new LinkedHashSet<>();
        for (BomRecipeItem item : items) {
            BomRecipe recipe = item.getRecipe();
            if (recipe != null && recipe.getId() != null) {
                recipeIds.add(recipe.getId());
            }
            // 回料(半成品)行与副产声明不是原料仓投入, 本期不单列 (报表如实说明)
            if (BomRecipeItem.isSemiFinishedRow(item) || BomRecipeItem.isByproductCategory(item.getMaterialCategory())
                    || item.getMaterialTypeId() == null || !materials.add(item.getMaterialTypeId())) {
                continue;
            }
            String category = item.getMaterialCategory() == null ? "RAW" : item.getMaterialCategory().toUpperCase();
            PlanStandardCostLine.PlanStandardCostLineBuilder line = base(factoryId, plan, recipe, now)
                    .lineType(PlanStandardCostLine.TYPE_MATERIAL)
                    .materialTypeId(item.getMaterialTypeId())
                    .materialName(item.getMaterialName())
                    .materialCategory(category)
                    .workflowInputPortId(item.getWorkflowInputPortId());
            BigDecimal perUnit = item.calculateActualQuantity();
            if ("RAW".equals(category)) {
                // 主料: BOM 只登记配方资格, 标准投入 = 成品 kg ÷ 出成率, 按工艺投入端口份额分 (与领料推算同源)
                line.standardBasis(PlanStandardCostLine.BASIS_RAW_INPUT_SHARE)
                        .standardRate(item.getWorkflowInputPortId() == null ? null : inputShares.get(item.getWorkflowInputPortId()))
                        .standardUnit("kg")
                        .rawYieldRate(recipe == null ? null : recipe.getOverallYieldRate());
            } else if (perUnit != null && perUnit.signum() > 0) {
                line.standardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT)
                        .standardRate(perUnit)
                        .standardUnit(item.getUnit());
            } else {
                line.standardBasis(PlanStandardCostLine.BASIS_NONE).standardUnit(item.getUnit());
            }
            lines.add(withPrice(factoryId, line, item.getMaterialTypeId()).build());
        }
        for (String recipeId : recipeIds) {
            appendSeasoning(factoryId, plan, recipeId, items, materials, lines, now);
        }
        for (String recipeId : recipeIds) {
            appendSubstitutes(factoryId, plan, recipeId, materials, lines, now);
        }
        appendLabor(factoryId, plan, lines, now);
        return lines;
    }

    private void appendSeasoning(String factoryId, ProductionPlan plan, String recipeId, List<BomRecipeItem> items,
                                 Set<String> materials, List<PlanStandardCostLine> lines, LocalDateTime now) {
        if (bomSeasoningItemRepository == null) {
            return;
        }
        BomRecipe recipe = items.stream().map(BomRecipeItem::getRecipe)
                .filter(r -> r != null && recipeId.equals(r.getId())).findFirst().orElse(null);
        for (BomSeasoningItem seasoning : bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc(recipeId)) {
            if (seasoning.getMaterialTypeId() == null || !materials.add(seasoning.getMaterialTypeId())) {
                continue;
            }
            BigDecimal dosage = seasoning.getDosagePerKgG();
            PlanStandardCostLine.PlanStandardCostLineBuilder line = base(factoryId, plan, recipe, now)
                    .lineType(PlanStandardCostLine.TYPE_MATERIAL)
                    .materialTypeId(seasoning.getMaterialTypeId())
                    .materialName(seasoning.getName())
                    .materialCategory("SEASONING")
                    .standardUnit("kg");
            if (dosage != null && dosage.signum() > 0) {
                line.standardBasis(PlanStandardCostLine.BASIS_PER_RAW_INPUT_KG)
                        .standardRate(dosage.divide(THOUSAND, 8, RoundingMode.HALF_UP))
                        .rawYieldRate(recipe == null ? null : recipe.getOverallYieldRate());
            } else {
                line.standardBasis(PlanStandardCostLine.BASIS_NONE);
            }
            lines.add(withPrice(factoryId, line, seasoning.getMaterialTypeId()).build());
        }
    }

    private void appendSubstitutes(String factoryId, ProductionPlan plan, String recipeId,
                                   Set<String> materials, List<PlanStandardCostLine> lines, LocalDateTime now) {
        if (bomItemSubstituteRepository == null) {
            return;
        }
        for (BomItemSubstitute substitute : bomItemSubstituteRepository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(
                factoryId, recipeId)) {
            String materialTypeId = substitute.getSubstituteMaterialTypeId();
            if (materialTypeId == null || substitute.getParentMaterialTypeIdSnapshot() == null
                    || !materials.add(materialTypeId)) {
                continue;
            }
            PlanStandardCostLine.PlanStandardCostLineBuilder line = PlanStandardCostLine.builder()
                    .factoryId(factoryId).planId(plan.getId()).capturedAt(now)
                    .bomRecipeId(recipeId)
                    .lineType(PlanStandardCostLine.TYPE_SUBSTITUTE)
                    .materialTypeId(materialTypeId)
                    .materialName(substitute.getSubstituteMaterialNameSnapshot())
                    .materialCategory(substitute.getMaterialCategorySnapshot())
                    .parentMaterialTypeId(substitute.getParentMaterialTypeIdSnapshot())
                    .standardBasis(PlanStandardCostLine.BASIS_NONE);
            lines.add(withPrice(factoryId, line, materialTypeId).build());
        }
    }

    /** 标准人工: 研发报价任务的 元/kg 成品。没有就不写 (报表说人工无法比较)。 */
    private void appendLabor(String factoryId, ProductionPlan plan, List<PlanStandardCostLine> lines, LocalDateTime now) {
        if (quotationTaskRepository == null || plan.getProductTypeId() == null || PlanBomItems.isMultiOutput(plan)) {
            return;
        }
        quotationTaskRepository.findFirstByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, plan.getProductTypeId())
                .map(task -> task.getLaborPerKg())
                .filter(rate -> rate != null && rate.signum() > 0)
                .ifPresent(rate -> lines.add(PlanStandardCostLine.builder()
                        .factoryId(factoryId).planId(plan.getId()).capturedAt(now)
                        .productTypeId(plan.getProductTypeId())
                        .lineType(PlanStandardCostLine.TYPE_LABOR)
                        .materialName("人工")
                        .standardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_KG)
                        .standardRate(rate)
                        .unitPrice(rate)
                        .priceUnit("kg")
                        .priceSource(PlanStandardCostLine.PRICE_SOURCE_QUOTATION_LABOR)
                        .build()));
    }

    private static PlanStandardCostLine.PlanStandardCostLineBuilder base(String factoryId, ProductionPlan plan,
                                                                         BomRecipe recipe, LocalDateTime now) {
        return PlanStandardCostLine.builder()
                .factoryId(factoryId)
                .planId(plan.getId())
                .capturedAt(now)
                .productTypeId(recipe == null ? plan.getProductTypeId() : recipe.getProductTypeId())
                .bomRecipeId(recipe == null ? null : recipe.getId())
                .bomVersion(recipe == null ? null : recipe.getVersion());
    }

    private PlanStandardCostLine.PlanStandardCostLineBuilder withPrice(
            String factoryId, PlanStandardCostLine.PlanStandardCostLineBuilder line, String materialTypeId) {
        Optional<RawMaterialType> material = rawMaterialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId);
        BigDecimal price = material.map(RawMaterialType::getUnitPrice).filter(p -> p.signum() > 0).orElse(null);
        line.unitPrice(price)
                .priceUnit(material.map(RawMaterialType::getUnit).orElse(null))
                .priceSource(price == null ? null : PlanStandardCostLine.PRICE_SOURCE_MATERIAL_UNIT_PRICE);
        // 档案名优先 (BOM 上的是冗余快照); 档案读不到就保留 BOM 上的名字
        material.map(RawMaterialType::getName).filter(name -> !name.isBlank()).ifPresent(line::materialName);
        return line;
    }
}
