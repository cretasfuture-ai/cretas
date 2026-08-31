package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fails closed when a customer-supplied order plan reaches settlement with fewer
 * reported material types than the exact sales-order line requires.
 *
 * <p>This guard is deliberately narrow: it does not infer quantities, mutate reports,
 * or post inventory. It only turns the existing settlement exception form on so a
 * production manager can add the omitted, already-received customer batches.</p>
 */
@Service
@RequiredArgsConstructor
public class CustomerSuppliedSettlementCoverageGuard {

    public static final String ISSUE_CODE = "CUSTOMER_SUPPLIED_MATERIAL_REPORT_MISSING";

    private final ProductionPlanRepository productionPlanRepository;
    private final SalesOrderSuppliedMaterialRequirementRepository requirementRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final MaterialBatchRepository materialBatchRepository;

    /**
     * Workflow settlement keeps submitted process reports authoritative, but a
     * customer-supplied order may legitimately need the clerk to add batches that
     * the process reports omitted. Merge only those missing requirements; echoed
     * report rows are ignored only when their quantity is unchanged.
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
        RequirementContext context = resolveRequirementContext(factoryId, plan);
        if (context.requirements().isEmpty()) {
            return;
        }

        List<ProductionSettlementRequest.ConsumptionLine> merged = new ArrayList<>(
                derived.getRawMaterialConsumptions() == null
                        ? List.of() : derived.getRawMaterialConsumptions());
        Map<String, ProductionSettlementRequest.ConsumptionLine> derivedByBatch = merged.stream()
                .filter(line -> line != null && !isBlank(line.getMaterialBatchId()))
                .collect(Collectors.toMap(ProductionSettlementRequest.ConsumptionLine::getMaterialBatchId,
                        line -> line, (left, right) -> left, LinkedHashMap::new));
        Set<String> addedManualBatchIds = new LinkedHashSet<>();
        Map<RequirementKey, BigDecimal> required = requiredQuantities(context.requirements(), context.planShare());
        Map<RequirementKey, BigDecimal> covered = reportedQuantities(merged);

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
            RequirementKey key = new RequirementKey(batch.getMaterialTypeId(), batch.getQuantityUnit());
            BigDecimal remaining = required.getOrDefault(key, BigDecimal.ZERO)
                    .subtract(covered.getOrDefault(key, BigDecimal.ZERO));
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                throw mergeFailure("所选批次不属于当前订单仍缺少的客供物料",
                        "CUSTOMER_SUPPLIED_MANUAL_LINE_NOT_REQUIRED");
            }
            BigDecimal quantity = candidate.getQuantity();
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw mergeFailure("客供补录数量必须大于 0",
                        "CUSTOMER_SUPPLIED_MANUAL_QUANTITY_INVALID");
            }
            if (quantity.compareTo(remaining) > 0) {
                throw mergeFailure("客供补录数量超过当前订单缺口 "
                                + remaining.stripTrailingZeros().toPlainString() + batch.getQuantityUnit(),
                        "CUSTOMER_SUPPLIED_MANUAL_QUANTITY_EXCEEDS_GAP");
            }
            ProductionSettlementRequest.ConsumptionLine authoritative =
                    ProductionSettlementRequest.ConsumptionLine.builder()
                            .materialBatchId(batch.getId())
                            .materialTypeId(batch.getMaterialTypeId())
                            .batchNumber(batch.getBatchNumber())
                            .quantity(quantity)
                            .unit(batch.getQuantityUnit())
                            .warehouseId(batch.getWarehouseId())
                            .note(candidate.getNote())
                            .build();
            merged.add(authoritative);
            covered.merge(key, quantity, BigDecimal::add);
        }

        List<String> remaining = required.entrySet().stream()
                .filter(entry -> covered.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                        .compareTo(entry.getValue()) < 0)
                .map(entry -> entry.getKey().materialTypeId() + " 尚缺 "
                        + entry.getValue().subtract(covered.getOrDefault(entry.getKey(), BigDecimal.ZERO))
                                .stripTrailingZeros().toPlainString() + entry.getKey().unit())
                .toList();
        if (!remaining.isEmpty()) {
            throw mergeFailure("客供原料仍未补齐：" + String.join("、", remaining), ISSUE_CODE);
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

        RequirementContext context = resolveRequirementContext(factoryId, plan);
        List<SalesOrderSuppliedMaterialRequirement> requirements = context.requirements();
        if (requirements.isEmpty()) {
            return response;
        }
        BigDecimal planShare = context.planShare();

        Map<RequirementKey, BigDecimal> reportedQuantity = new LinkedHashMap<>();
        if (response.getPrefill().getRawMaterialConsumptions() != null) {
            for (ProductionSettlementRequest.ConsumptionLine line
                    : response.getPrefill().getRawMaterialConsumptions()) {
                if (line == null || isBlank(line.getMaterialTypeId()) || isBlank(line.getUnit())
                        || line.getQuantity() == null) {
                    continue;
                }
                reportedQuantity.merge(new RequirementKey(line.getMaterialTypeId(), line.getUnit()),
                        line.getQuantity(), BigDecimal::add);
            }
        }
        Set<String> reportedTypes = reportedQuantity.keySet().stream()
                .map(RequirementKey::materialTypeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<RequirementKey, BigDecimal> requiredQuantity = new LinkedHashMap<>();
        Map<RequirementKey, SalesOrderSuppliedMaterialRequirement> requirementByKey = new LinkedHashMap<>();
        for (SalesOrderSuppliedMaterialRequirement requirement : requirements) {
            RequirementKey key = new RequirementKey(requirement.getMaterialTypeId(), requirement.getUnit());
            requiredQuantity.merge(key, requirement.getExpectedQuantity().multiply(planShare), BigDecimal::add);
            requirementByKey.putIfAbsent(key, requirement);
        }
        Map<RequirementKey, BigDecimal> missingOrShort = requiredQuantity.entrySet().stream()
                .filter(entry -> reportedQuantity.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                        .compareTo(entry.getValue()) < 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
        if (missingOrShort.isEmpty()) {
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
        boolean alreadyPresent = issues.stream()
                .anyMatch(issue -> issue != null && ISSUE_CODE.equals(issue.getCode()));
        if (!alreadyPresent) {
            String missingSummary = missingOrShort.entrySet().stream()
                    .map(entry -> displayRequirement(requirementByKey.get(entry.getKey()), entry.getValue(),
                            reportedQuantity.getOrDefault(entry.getKey(), BigDecimal.ZERO)))
                    .collect(Collectors.joining("、"));
            issues.add(ProductionSettlementPrefillResponse.Issue.builder()
                    .code(ISSUE_CODE)
                    .severity(ProductionSettlementPrefillResponse.Severity.BLOCKER)
                    .field("rawMaterialConsumptions")
                    .message("客供要求共 " + distinctTypeCount(requirements) + " 种，逐道报工仅带入 "
                            + reportedTypes.size() + " 种；还缺或数量不足 " + missingOrShort.size() + " 项："
                            + missingSummary + "。请在“原料/辅料实际领用”补齐已入库客供批次后再结单。")
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
                    .withCode(ISSUE_CODE)
                    .withHint("请返回核对结单页，补齐缺失的客供原料/辅料批次")
                    .withHintTarget("rawMaterialConsumptions");
        }
    }

    private static long distinctTypeCount(List<SalesOrderSuppliedMaterialRequirement> requirements) {
        return requirements.stream()
                .map(SalesOrderSuppliedMaterialRequirement::getMaterialTypeId)
                .filter(type -> !isBlank(type))
                .distinct()
                .count();
    }

    private static String displayRequirement(SalesOrderSuppliedMaterialRequirement requirement,
                                             BigDecimal expected,
                                             BigDecimal reported) {
        String name = isBlank(requirement.getMaterialName())
                ? requirement.getMaterialTypeId() : requirement.getMaterialName();
        return name + " 要求 " + expected.stripTrailingZeros().toPlainString() + requirement.getUnit()
                + "，已报 " + reported.stripTrailingZeros().toPlainString() + requirement.getUnit();
    }

    private static Long parseLong(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private RequirementContext resolveRequirementContext(String factoryId, ProductionPlan plan) {
        Long sourceOrderItemId = parseLong(plan.getSourceOrderItemId());
        List<SalesOrderItem> orderItems = sourceOrderItemId == null ? List.of()
                : salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId(
                        plan.getSourceOrderId(), factoryId);
        boolean isExactSingleLineOrder = sourceOrderItemId != null
                && orderItems.size() == 1
                && sourceOrderItemId.equals(orderItems.get(0).getId());
        List<SalesOrderSuppliedMaterialRequirement> requirements = requirementRepository
                .findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(plan.getSourceOrderId())
                .stream()
                .filter(requirement -> requirement != null
                        && factoryId.equals(requirement.getFactoryId()))
                .filter(requirement -> sourceOrderItemId == null
                        || sourceOrderItemId.equals(requirement.getSalesOrderItemId())
                        || (requirement.getSalesOrderItemId() == null && isExactSingleLineOrder))
                .toList();
        return new RequirementContext(requirements,
                resolvePlanShare(plan, sourceOrderItemId, orderItems));
    }

    private static Map<RequirementKey, BigDecimal> requiredQuantities(
            List<SalesOrderSuppliedMaterialRequirement> requirements, BigDecimal planShare) {
        Map<RequirementKey, BigDecimal> required = new LinkedHashMap<>();
        for (SalesOrderSuppliedMaterialRequirement requirement : requirements) {
            required.merge(new RequirementKey(requirement.getMaterialTypeId(), requirement.getUnit()),
                    requirement.getExpectedQuantity().multiply(planShare), BigDecimal::add);
        }
        return required;
    }

    private static Map<RequirementKey, BigDecimal> reportedQuantities(
            List<ProductionSettlementRequest.ConsumptionLine> lines) {
        Map<RequirementKey, BigDecimal> reported = new LinkedHashMap<>();
        if (lines == null) {
            return reported;
        }
        for (ProductionSettlementRequest.ConsumptionLine line : lines) {
            if (line == null || isBlank(line.getMaterialTypeId()) || isBlank(line.getUnit())
                    || line.getQuantity() == null) {
                continue;
            }
            reported.merge(new RequirementKey(line.getMaterialTypeId(), line.getUnit()),
                    line.getQuantity(), BigDecimal::add);
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

    private BigDecimal resolvePlanShare(ProductionPlan plan,
                                        Long sourceOrderItemId,
                                        List<SalesOrderItem> orderItems) {
        if (sourceOrderItemId == null) {
            return BigDecimal.ONE;
        }
        SalesOrderItem orderItem = orderItems.stream()
                .filter(item -> sourceOrderItemId.equals(item.getId()))
                .findFirst()
                .orElse(null);
        BigDecimal orderQuantity = orderItem == null ? null : orderItem.getQuantity();
        BigDecimal planQuantity = plan.getSourceDisplayQuantity() != null
                ? plan.getSourceDisplayQuantity() : plan.getPlannedQuantity();
        if (orderQuantity == null || orderQuantity.compareTo(BigDecimal.ZERO) <= 0
                || planQuantity == null || planQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return planQuantity.divide(orderQuantity, 8, RoundingMode.HALF_UP);
    }

    private record RequirementKey(String materialTypeId, String unit) {
    }

    private record RequirementContext(List<SalesOrderSuppliedMaterialRequirement> requirements,
                                      BigDecimal planShare) {
    }
}
