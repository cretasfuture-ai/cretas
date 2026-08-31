package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
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

        Long sourceOrderItemId = parseLong(plan.getSourceOrderItemId());
        List<SalesOrderSuppliedMaterialRequirement> requirements = requirementRepository
                .findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(plan.getSourceOrderId())
                .stream()
                .filter(requirement -> requirement != null
                        && factoryId.equals(requirement.getFactoryId()))
                .filter(requirement -> sourceOrderItemId == null
                        || sourceOrderItemId.equals(requirement.getSalesOrderItemId()))
                .toList();
        if (requirements.isEmpty()) {
            return response;
        }
        BigDecimal planShare = resolvePlanShare(factoryId, plan, sourceOrderItemId);

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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal resolvePlanShare(String factoryId, ProductionPlan plan, Long sourceOrderItemId) {
        if (sourceOrderItemId == null) {
            return BigDecimal.ONE;
        }
        SalesOrderItem orderItem = salesOrderItemRepository
                .findBySalesOrderIdAndSalesOrderFactoryId(plan.getSourceOrderId(), factoryId).stream()
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
}
