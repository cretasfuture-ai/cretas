package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.math.BigDecimal;

/**
 * Fails closed when a customer-supplied order plan reaches settlement having consumed a raw
 * material that this sales order never had a customer-supply requirement for, or having pushed
 * cumulative consumption of a listed material (across every settled plan of this order) past
 * what the customer actually delivered.
 *
 * <p>缺陷 48（2026-09）：本闸原来的方向是反的 —— 它要求<b>这一个批次</b>把整张 SO 客供清单的
 * <b>全部</b>数量在<b>这一次</b>报工里报完，哪怕这批的 BOM 根本不含那种料（来料加工按批分次
 * 生产是正常形态，一张 SO 的客供料由多个批次陆续领用天经地义）。现在只守两件事：</p>
 * <ol>
 *   <li>这批实际用掉的每一种料，必须在这张 SO 的客供清单里 —— 不许拿别的订单/别人的料结这单；</li>
 *   <li>同一种料，跨这张 SO 已结单的所有批次累计用量，不能超过客户实际已收的数量。</li>
 * </ol>
 * <p>SO 清单里这批没用到的料、或只用了一部分的料 —— 都不产生任何提示。这两条判据在
 * {@link #audit} (核对结单弹窗) 和 {@link #mergeMissingManualRawConsumptions} (真正结单的写路径)
 * 里必须是同一份口径，见 {@link #evaluateAgainstLedger}。</p>
 *
 * <p>This guard is deliberately narrow: it does not infer quantities, mutate reports,
 * or post inventory. It only turns the existing settlement exception form on so a
 * production manager can add the omitted, already-received customer batches.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerSuppliedSettlementCoverageGuard {

    /** 本批用了一种不在这张 SO 客供清单里的料 —— 「拿别人的料做这单」。 */
    public static final String ISSUE_CODE_MATERIAL_NOT_LISTED = "CUSTOMER_SUPPLIED_MATERIAL_NOT_ON_ORDER";
    /** 同一种客供料, 跨这张 SO 已结单的所有批次累计用量超过了客户实际已收的数量。 */
    public static final String ISSUE_CODE_MATERIAL_OVER_RECEIVED = "CUSTOMER_SUPPLIED_MATERIAL_OVER_RECEIVED";

    private final ProductionPlanRepository productionPlanRepository;
    private final SalesOrderSuppliedMaterialRequirementRepository requirementRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final ProductionSettlementConsumptionRepository settlementConsumptionRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    /**
     * Workflow settlement keeps submitted process reports authoritative, but a
     * customer-supplied order may legitimately need the clerk to add batches that
     * the process reports omitted. Merge only those missing requirements; echoed
     * report rows are ignored only when their quantity is unchanged.
     *
     * <p>After the merge, the <b>final</b> set of raw-material consumption lines (auto-derived
     * + manually added) is evaluated against the same two-rule coverage predicate the settlement
     * dialog uses (see {@link #evaluateAgainstLedger}) — this is the write-path half of that shared
     * predicate; a plan cannot settle with a coverage violation even if nobody opened the
     * one-click dialog first.</p>
     */
    public void mergeMissingManualRawConsumptions(String factoryId,
                                                  ProductionPlan plan,
                                                  ProductionSettlementRequest derived,
                                                  ProductionSettlementRequest submitted) {
        if (plan == null || derived == null || submitted == null
                || plan.getMaterialSupplyMode() != MaterialSupplyMode.CUSTOMER_SUPPLIED
                || isBlank(plan.getSourceOrderId())) {
            return;
        }
        List<SalesOrderSuppliedMaterialRequirement> requirements =
                resolveRequirements(factoryId, plan.getSourceOrderId());
        if (requirements.isEmpty()) {
            return;
        }
        Map<String, UnitQuantity> receivedByType = receivedByMaterialType(requirements);
        Map<String, UnitQuantity> consumedElsewhere =
                consumedByOtherSettledPlans(factoryId, plan.getSourceOrderId(), plan.getId());

        List<ProductionSettlementRequest.ConsumptionLine> merged = new ArrayList<>(
                derived.getRawMaterialConsumptions() == null
                        ? List.of() : derived.getRawMaterialConsumptions());
        Map<String, ProductionSettlementRequest.ConsumptionLine> derivedByBatch = merged.stream()
                .filter(line -> line != null && !isBlank(line.getMaterialBatchId()))
                .collect(Collectors.toMap(ProductionSettlementRequest.ConsumptionLine::getMaterialBatchId,
                        line -> line, (left, right) -> left, LinkedHashMap::new));
        Set<String> addedManualBatchIds = new LinkedHashSet<>();
        // 本批内部已经算入的量（从 derived 起算，随手工补录逐步增长）——
        // 用来判断「再加这一笔会不会把这张 SO 这种料的累计用量顶穿已收数量」。
        Map<String, UnitQuantity> coveredWithinThisPlan = reportedQuantitiesByType(merged);

        List<ProductionSettlementRequest.ConsumptionLine> submittedLines =
                submitted.getRawMaterialConsumptions() == null
                        ? List.of() : submitted.getRawMaterialConsumptions();
        for (ProductionSettlementRequest.ConsumptionLine candidate : submittedLines) {
            if (candidate == null || isBlank(candidate.getMaterialBatchId())) {
                throw mergeFailure("客供补录必须选择已入库批次", "CUSTOMER_SUPPLIED_BATCH_REQUIRED");
            }
            ProductionSettlementRequest.ConsumptionLine serverLine =
                    derivedByBatch.get(candidate.getMaterialBatchId());
            if (serverLine != null) {
                if (candidate.getQuantity() == null || serverLine.getQuantity() == null
                        || candidate.getQuantity().compareTo(serverLine.getQuantity()) != 0) {
                    throw mergeFailure("逐道报工已带入的原料数量不能在结单页覆盖",
                            "CUSTOMER_SUPPLIED_REPORTED_FACT_OVERRIDE");
                }
                continue;
            }
            if (!addedManualBatchIds.add(candidate.getMaterialBatchId())) {
                throw mergeFailure("同一个客供批次不能重复补录",
                        "CUSTOMER_SUPPLIED_BATCH_DUPLICATED");
            }
            MaterialBatch batch = materialBatchRepository
                    .findByIdAndFactoryId(candidate.getMaterialBatchId(), factoryId)
                    .orElseThrow(() -> mergeFailure("客供补录批次不存在或不属于当前工厂",
                            "CUSTOMER_SUPPLIED_BATCH_NOT_FOUND"));
            String materialTypeId = batch.getMaterialTypeId();
            UnitQuantity received = receivedByType.get(materialTypeId);
            if (received == null) {
                throw mergeFailure("所选批次不属于当前订单的客供物料清单",
                        ISSUE_CODE_MATERIAL_NOT_LISTED);
            }
            BigDecimal quantity = candidate.getQuantity();
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw mergeFailure("客供补录数量必须大于 0",
                        "CUSTOMER_SUPPLIED_MANUAL_QUANTITY_INVALID");
            }
            String batchUnit = batch.getQuantityUnit();
            if (!unitsComparable(received.unit(), batchUnit)) {
                // 单位口径不一致（例如已收按 kg 记, 这个批次按「个/只/箱」记）——不能用数值
                // 直接减出「缺口」, 按硬约束4 当「没量到」处理：不拦这一笔手工补录, 也不在这里
                // 判定用完, 交给下面 evaluateAgainstLedger 同样跳过, 留 warn 让人核对口径。
                log.warn("客供结单累计校验跳过（单位不一致，视为未量到）: factoryId={} materialTypeId={} "
                                + "已收单位={} 本次补录批次单位={}",
                        factoryId, materialTypeId, received.unit(), batchUnit);
            } else {
                BigDecimal alreadyAccounted =
                        quantityOf(coveredWithinThisPlan.get(materialTypeId))
                                .add(quantityOf(consumedElsewhere.get(materialTypeId)));
                BigDecimal remaining = received.quantity().subtract(alreadyAccounted);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    throw mergeFailure("所选批次的客供数量已在本订单其它已结单批次用完",
                            ISSUE_CODE_MATERIAL_NOT_LISTED);
                }
                if (quantity.compareTo(remaining) > 0) {
                    throw mergeFailure("客供补录数量超过当前订单缺口 "
                                    + remaining.stripTrailingZeros().toPlainString() + batchUnit,
                            "CUSTOMER_SUPPLIED_MANUAL_QUANTITY_EXCEEDS_GAP");
                }
            }
            ProductionSettlementRequest.ConsumptionLine authoritative =
                    ProductionSettlementRequest.ConsumptionLine.builder()
                            .materialBatchId(batch.getId())
                            .materialTypeId(materialTypeId)
                            .batchNumber(batch.getBatchNumber())
                            .quantity(quantity)
                            .unit(batchUnit)
                            .warehouseId(batch.getWarehouseId())
                            .note(candidate.getNote())
                            .build();
            merged.add(authoritative);
            coveredWithinThisPlan.merge(materialTypeId, new UnitQuantity(quantity, batchUnit),
                    CustomerSuppliedSettlementCoverageGuard::mergeUnitQuantities);
        }

        // 最终整批校验：auto-derived 行本身也可能违反口径（例如逐道报工引用了一个不在
        // 客供清单里的批次），不能只查手工补录的那几行。
        CoverageEvaluation evaluation = evaluateAgainstLedger(
                requirements, receivedByType, consumedElsewhere, coveredWithinThisPlan);
        if (!evaluation.foreignMaterialTypeIds().isEmpty()) {
            throw mergeFailure("本批使用了客供清单之外的料：" + describeForeign(evaluation),
                    ISSUE_CODE_MATERIAL_NOT_LISTED);
        }
        if (!evaluation.overages().isEmpty()) {
            throw mergeFailure("客供原料累计用量超过已收数量：" + describeOverages(evaluation),
                    ISSUE_CODE_MATERIAL_OVER_RECEIVED);
        }
        derived.setRawMaterialConsumptions(merged);
    }

    public ProductionSettlementPrefillResponse audit(String factoryId,
                                                      String planId,
                                                      ProductionSettlementPrefillResponse response) {
        if (response == null || response.getPrefill() == null) {
            return response;
        }
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElse(null);
        if (plan == null
                || plan.getMaterialSupplyMode() != MaterialSupplyMode.CUSTOMER_SUPPLIED
                || isBlank(plan.getSourceOrderId())) {
            return response;
        }

        List<SalesOrderSuppliedMaterialRequirement> requirements =
                resolveRequirements(factoryId, plan.getSourceOrderId());
        if (requirements.isEmpty()) {
            return response;
        }
        Map<String, UnitQuantity> receivedByType = receivedByMaterialType(requirements);
        Map<String, UnitQuantity> consumedElsewhere =
                consumedByOtherSettledPlans(factoryId, plan.getSourceOrderId(), planId);
        Map<String, UnitQuantity> reportedByType =
                reportedQuantitiesByType(response.getPrefill().getRawMaterialConsumptions());

        CoverageEvaluation evaluation = evaluateAgainstLedger(
                requirements, receivedByType, consumedElsewhere, reportedByType);
        if (evaluation.foreignMaterialTypeIds().isEmpty() && evaluation.overages().isEmpty()) {
            return response;
        }

        ProductionSettlementPrefillResponse.Audit audit = response.getAudit();
        if (audit == null) {
            audit = ProductionSettlementPrefillResponse.Audit.builder().build();
            response.setAudit(audit);
        }
        List<ProductionSettlementPrefillResponse.Issue> issues = audit.getIssues();
        if (issues == null) {
            issues = new ArrayList<>();
            audit.setIssues(issues);
        }
        if (!evaluation.foreignMaterialTypeIds().isEmpty()
                && issues.stream().noneMatch(i -> i != null
                        && ISSUE_CODE_MATERIAL_NOT_LISTED.equals(i.getCode()))) {
            issues.add(ProductionSettlementPrefillResponse.Issue.builder()
                    .code(ISSUE_CODE_MATERIAL_NOT_LISTED)
                    .severity(ProductionSettlementPrefillResponse.Severity.BLOCKER)
                    .field("rawMaterialConsumptions")
                    .message("本批使用了客供清单之外的料：" + describeForeign(evaluation)
                            + "。请确认领用批次，或改从客供物料清单里补录。")
                    .build());
        }
        if (!evaluation.overages().isEmpty()
                && issues.stream().noneMatch(i -> i != null
                        && ISSUE_CODE_MATERIAL_OVER_RECEIVED.equals(i.getCode()))) {
            issues.add(ProductionSettlementPrefillResponse.Issue.builder()
                    .code(ISSUE_CODE_MATERIAL_OVER_RECEIVED)
                    .severity(ProductionSettlementPrefillResponse.Severity.BLOCKER)
                    .field("rawMaterialConsumptions")
                    .message("客供原料累计用量超过已收数量：" + describeOverages(evaluation) + "。请核对领用数量。")
                    .build());
        }
        audit.setClean(false);
        return response;
    }

    public void requireCleanForOneClick(ProductionSettlementPrefillResponse response) {
        if (response != null && response.getAudit() != null && !response.getAudit().isClean()) {
            String reasons = response.getAudit().getIssues() == null ? ""
                    : response.getAudit().getIssues().stream()
                            .filter(issue -> issue != null
                                    && issue.getSeverity() == ProductionSettlementPrefillResponse.Severity.BLOCKER)
                            .map(ProductionSettlementPrefillResponse.Issue::getMessage)
                            .filter(message -> !isBlank(message))
                            .collect(Collectors.joining("；"));
            throw new BusinessException(409, isBlank(reasons)
                    ? "结单自动带入不完整，请先在核对页补齐。" : reasons)
                    .withCode(ISSUE_CODE_MATERIAL_NOT_LISTED)
                    .withHint("请返回核对结单页，补齐或修正客供原料/辅料批次")
                    .withHintTarget("rawMaterialConsumptions");
        }
    }

    // ------------------------------------------------------------------
    // Shared coverage predicate — dialog (audit) 和写路径 (mergeMissingManualRawConsumptions)
    // 必须共用同一份判定, 见类注释「形态 D」。
    // ------------------------------------------------------------------

    /**
     * @param reportedByType 本批（audit 场景：核对结单弹窗预填；merge 场景：合并后的最终领用集合）
     *                       按 materialTypeId 汇总后的用量（各自带着自己的单位, 见 {@link UnitQuantity}）
     */
    private CoverageEvaluation evaluateAgainstLedger(
            List<SalesOrderSuppliedMaterialRequirement> requirements,
            Map<String, UnitQuantity> receivedByType,
            Map<String, UnitQuantity> consumedElsewhere,
            Map<String, UnitQuantity> reportedByType) {
        Map<String, SalesOrderSuppliedMaterialRequirement> representative = new LinkedHashMap<>();
        for (SalesOrderSuppliedMaterialRequirement requirement : requirements) {
            representative.putIfAbsent(requirement.getMaterialTypeId(), requirement);
        }

        List<String> foreign = new ArrayList<>();
        Map<String, Overage> overages = new LinkedHashMap<>();
        for (Map.Entry<String, UnitQuantity> entry : reportedByType.entrySet()) {
            String materialTypeId = entry.getKey();
            UnitQuantity thisPlan = entry.getValue();
            UnitQuantity received = receivedByType.get(materialTypeId);
            if (received == null) {
                // 规则①（在不在清单）只认 materialTypeId, 与单位无关 —— 不动。
                foreign.add(materialTypeId);
                continue;
            }
            UnitQuantity elsewhere = consumedElsewhere.getOrDefault(materialTypeId,
                    new UnitQuantity(BigDecimal.ZERO, received.unit()));
            // 规则②（累计 vs 已收）要求三边单位一致才能直接相减比较 —— kg 和 个/只/箱
            // 数值上相加毫无意义, 双向都错(小单位数值大就假拦, 大单位数值小就放过超收)。
            // 单位不一致时按硬约束4「没量到」处理：不拦, 只记 warn, 不当「没超」也不当「超了」。
            if (!unitsComparable(received.unit(), thisPlan.unit())
                    || !unitsComparable(received.unit(), elsewhere.unit())) {
                log.warn("客供结单累计校验跳过（单位不一致，视为未量到）: materialTypeId={} "
                                + "已收单位={} 本批消耗单位={} 其它已结单批次单位={}",
                        materialTypeId, received.unit(), thisPlan.unit(), elsewhere.unit());
                continue;
            }
            BigDecimal cumulative = elsewhere.quantity().add(thisPlan.quantity());
            if (cumulative.compareTo(received.quantity()) > 0) {
                SalesOrderSuppliedMaterialRequirement requirement = representative.get(materialTypeId);
                overages.put(materialTypeId, new Overage(requirement, cumulative, received.quantity()));
            }
        }
        return new CoverageEvaluation(foreign, overages);
    }

    private String describeForeign(CoverageEvaluation evaluation) {
        Map<String, String> names = resolveForeignMaterialNames(evaluation.foreignMaterialTypeIds());
        return evaluation.foreignMaterialTypeIds().stream()
                .map(typeId -> names.getOrDefault(typeId, typeId))
                .collect(Collectors.joining("、"));
    }

    private Map<String, String> resolveForeignMaterialNames(List<String> materialTypeIds) {
        if (materialTypeIds.isEmpty() || rawMaterialTypeRepository == null) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (RawMaterialType type : rawMaterialTypeRepository.findByIdIn(new LinkedHashSet<>(materialTypeIds))) {
            if (type != null && type.getId() != null && !isBlank(type.getName())) {
                names.put(type.getId(), type.getName());
            }
        }
        return names;
    }

    private String describeOverages(CoverageEvaluation evaluation) {
        return evaluation.overages().values().stream()
                .map(this::displayOverage)
                .collect(Collectors.joining("、"));
    }

    private String displayOverage(Overage overage) {
        SalesOrderSuppliedMaterialRequirement requirement = overage.requirement();
        String name = requirement != null && !isBlank(requirement.getMaterialName())
                ? requirement.getMaterialName()
                : (requirement != null ? requirement.getMaterialTypeId() : "?");
        String unit = requirement != null && !isBlank(requirement.getUnit()) ? requirement.getUnit() : "";
        return name + " 累计已用 " + overage.cumulative().stripTrailingZeros().toPlainString() + unit
                + "，超过已收 " + overage.received().stripTrailingZeros().toPlainString() + unit;
    }

    // ------------------------------------------------------------------
    // Data access helpers
    // ------------------------------------------------------------------

    private List<SalesOrderSuppliedMaterialRequirement> resolveRequirements(
            String factoryId, String sourceOrderId) {
        return requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(sourceOrderId)
                .stream()
                .filter(requirement -> requirement != null && factoryId.equals(requirement.getFactoryId()))
                .toList();
    }

    private static Map<String, UnitQuantity> receivedByMaterialType(
            List<SalesOrderSuppliedMaterialRequirement> requirements) {
        Map<String, UnitQuantity> received = new LinkedHashMap<>();
        for (SalesOrderSuppliedMaterialRequirement requirement : requirements) {
            BigDecimal quantity = requirement.getReceivedQuantity() == null
                    ? BigDecimal.ZERO : requirement.getReceivedQuantity();
            received.merge(requirement.getMaterialTypeId(),
                    new UnitQuantity(quantity, requirement.getUnit()),
                    CustomerSuppliedSettlementCoverageGuard::mergeUnitQuantities);
        }
        return received;
    }

    /**
     * 同一张 SO 下【其它已结单计划】按 materialTypeId 汇总的客供原料领用量（各自带单位）。
     *
     * <p>只读 {@code production_settlement_consumptions}（结单成功后才写入的账本），天然只覆盖
     * 「已结单」的计划 —— 草稿/进行中的其它计划在这张表里没有行，不会被误算进累计用量。</p>
     */
    private Map<String, UnitQuantity> consumedByOtherSettledPlans(
            String factoryId, String sourceOrderId, String excludePlanId) {
        List<String> siblingPlanIds = productionPlanRepository
                .findByFactoryIdAndSourceOrderId(factoryId, sourceOrderId).stream()
                .map(ProductionPlan::getId)
                .filter(id -> id != null && !id.equals(excludePlanId))
                .toList();
        if (siblingPlanIds.isEmpty()) {
            return Map.of();
        }
        List<ProductionSettlementConsumption> rows = settlementConsumptionRepository
                .findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull(factoryId, siblingPlanIds);
        Map<String, UnitQuantity> consumed = new LinkedHashMap<>();
        for (ProductionSettlementConsumption row : rows) {
            if (row == null || !"RAW_MATERIAL".equals(row.getSourceType())
                    || isBlank(row.getMaterialTypeId()) || row.getQuantity() == null
                    || isBlank(row.getUnit())) {
                continue;
            }
            consumed.merge(row.getMaterialTypeId(), new UnitQuantity(row.getQuantity(), row.getUnit()),
                    CustomerSuppliedSettlementCoverageGuard::mergeUnitQuantities);
        }
        return consumed;
    }

    private static Map<String, UnitQuantity> reportedQuantitiesByType(
            List<ProductionSettlementRequest.ConsumptionLine> lines) {
        Map<String, UnitQuantity> reported = new LinkedHashMap<>();
        if (lines == null) {
            return reported;
        }
        for (ProductionSettlementRequest.ConsumptionLine line : lines) {
            if (line == null || isBlank(line.getMaterialTypeId()) || line.getQuantity() == null
                    || isBlank(line.getUnit())) {
                continue;
            }
            reported.merge(line.getMaterialTypeId(), new UnitQuantity(line.getQuantity(), line.getUnit()),
                    CustomerSuppliedSettlementCoverageGuard::mergeUnitQuantities);
        }
        return reported;
    }

    private static BusinessException mergeFailure(String message, String code) {
        return new BusinessException(409, message)
                .withCode(code)
                .withHint("请只补齐当前销售订单缺少的已入库客供批次，报工已带入行不可修改")
                .withHintTarget("rawMaterialConsumptions");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 两个单位是否可以直接相加/相减比较 —— 都非空且（忽略大小写）相同。 */
    private static boolean unitsComparable(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static BigDecimal quantityOf(UnitQuantity value) {
        return value == null ? BigDecimal.ZERO : value.quantity();
    }

    /**
     * 同一 materialTypeId 出现多行时的合并策略：单位一致才把数值加起来；
     * 单位不一致（同一种料被两种单位分别记账，理论上不该发生但不假设不会）时
     * 把 unit 落成 null——{@link #unitsComparable} 对 null 恒为 false，
     * 于是这一条汇总结果在后续任何比较里都会被判「口径不一致」而跳过，
     * 不会把两种单位的数值直接加在一起产生一个假的合计。
     */
    private static UnitQuantity mergeUnitQuantities(UnitQuantity a, UnitQuantity b) {
        BigDecimal sum = quantityOf(a).add(quantityOf(b));
        String unit = unitsComparable(a.unit(), b.unit()) ? a.unit() : null;
        return new UnitQuantity(sum, unit);
    }

    private record Overage(SalesOrderSuppliedMaterialRequirement requirement,
                            BigDecimal cumulative, BigDecimal received) {
    }

    private record CoverageEvaluation(List<String> foreignMaterialTypeIds,
                                       Map<String, Overage> overages) {
    }

    /** 一个 materialTypeId 汇总后的数量, 连同它的单位——单位不一致时不能直接数值相加/比较。 */
    private record UnitQuantity(BigDecimal quantity, String unit) {
    }
}
