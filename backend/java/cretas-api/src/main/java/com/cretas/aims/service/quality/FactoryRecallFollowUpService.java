package com.cretas.aims.service.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.cretas.aims.dto.quality.FactoryRecallDTO;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only projection for the work remaining after a recall is closed.
 * It intentionally exposes progress facts only; financial amounts and balances
 * stay in the finance APIs.
 */
@Service
@RequiredArgsConstructor
public class FactoryRecallFollowUpService {

    private static final String FREEZE_INVENTORY = "FREEZE_INVENTORY";
    private static final String RETURN_ORDER_SOURCE = "RETURN_ORDER";

    private final FactoryRecallService recallService;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final DisposalRecordRepository disposalRecordRepository;
    private final ArApTransactionRepository arApTransactionRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private UnitContractService unitContractService;

    @Transactional(readOnly = true)
    public FactoryRecallDTO.FollowUpView get(String factoryId, Long eventId) {
        RecallEvent event = recallService.followUpEvent(factoryId, eventId);
        ProductionBatch batch = recallService.followUpBatch(factoryId, event);
        String unit = batch.getUnit();
        FinishedGoodsBatch original = finishedGoodsBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batch.getBatchNumber()).orElse(null);
        List<FactoryRecallDTO.FollowUpIssue> issues = new ArrayList<>();
        if (original == null || !Objects.equals(factoryId, original.getFactoryId())
                || !Objects.equals(batch.getProductTypeId(), original.getProductTypeId())) {
            issues.add(issue("ORIGINAL_STOCK_MISSING", "找不到同厂同 SKU 的原成品库存批次"));
        } else if (original.getUnit() != null) {
            unit = original.getUnit();
        }

        BigDecimal freezeBaseline = freezeBaseline(event, batch.getId(), issues);
        List<DisposalRecord> disposals = disposalRecordRepository
                .findByFactoryId(factoryId, Pageable.unpaged()).getContent();
        List<FactoryRecallDTO.FollowUpStock> stock = new ArrayList<>();
        boolean inventoryResolved = true;
        if (original != null && Objects.equals(factoryId, original.getFactoryId())
                && Objects.equals(batch.getProductTypeId(), original.getProductTypeId())) {
            StockEvidence originalEvidence = stockEvidence(factoryId, original, freezeBaseline,
                    true, disposals, issues);
            stock.add(toStock(original, "ORIGINAL", originalEvidence, disposals, factoryId));
            inventoryResolved &= originalEvidence.resolved();
        } else {
            inventoryResolved = false;
        }

        List<ReturnOrder> orders = recallService.followUpReturnOrders(factoryId, event, batch);
        Set<String> seenReturnIds = new HashSet<>();
        boolean receivableResolved = true;
        List<FactoryRecallDTO.FollowUpReturn> returns = new ArrayList<>();
        int completedReturnCount = 0;
        for (ReturnOrder order : orders) {
            if (order == null || order.getId() == null || order.getId().isBlank()
                    || !seenReturnIds.add(order.getId())) continue;
            BigDecimal quantity = recallService.followUpQuantityForBatch(order, batch);
            if (quantity.signum() <= 0) continue;
            ReceivableEvidence receivable = receivableEvidence(factoryId, order);
            returns.add(FactoryRecallDTO.FollowUpReturn.builder()
                    .returnOrderId(order.getId()).returnNumber(order.getReturnNumber())
                    .status(order.getStatus() == null ? null : order.getStatus().name())
                    .quantity(quantity).receivableStatus(receivable.status())
                    .adjustments(receivable.adjustments()).build());
            receivableResolved &= "APPROVED".equals(receivable.status())
                    || "NOT_REQUIRED".equals(receivable.status());
            if (order.getStatus() == ReturnOrderStatus.COMPLETED) {
                completedReturnCount++;
                StockResolution returned = resolveReturnedStock(factoryId, batch, order, quantity,
                        unit, disposals, stock, issues);
                inventoryResolved &= returned.resolved();
            }
        }
        if (completedReturnCount == 0) {
            inventoryResolved = false;
            issues.add(issue("RETURN_GOODS_MISSING", "没有可核验的已完成实物退货"));
        }

        boolean recallCompleted = "COMPLETED".equals(event.getStatus());
        if (!recallCompleted) issues.add(issue("RECALL_NOT_COMPLETED", "召回事件尚未结案"));
        boolean completed = recallCompleted && inventoryResolved && receivableResolved;
        return FactoryRecallDTO.FollowUpView.builder()
                .recallId(event.getId()).batchId(batch.getId()).batchNumber(batch.getBatchNumber())
                .unit(unit).recallCompleted(recallCompleted)
                .inventoryResolved(inventoryResolved).receivableResolved(receivableResolved)
                .completed(completed).issues(issues).stock(stock).returns(returns).build();
    }

    private StockResolution resolveReturnedStock(String factoryId, ProductionBatch batch,
                                                  ReturnOrder order, BigDecimal quantity,
                                                  String unit,
                                                  List<DisposalRecord> disposals,
                                                  List<FactoryRecallDTO.FollowUpStock> stock,
                                                  List<FactoryRecallDTO.FollowUpIssue> issues) {
        List<ReturnOrderItem> items = order.getItems() == null ? List.of() : order.getItems();
        List<Integer> matching = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ReturnOrderItem item = items.get(i);
            if (item != null && Objects.equals(batch.getProductTypeId(), item.getProductTypeId())
                    && Objects.equals(batch.getBatchNumber(), item.getBatchNumber())
                    && item.getQuantity() != null && item.getQuantity().signum() > 0) {
                matching.add(i);
            }
        }
        if (matching.isEmpty()) {
            issues.add(issue("RETURN_SOURCE_AMBIGUOUS", "多行退货缺少稳定来源序号，无法映射退回库存"));
            return new StockResolution(false);
        }
        boolean allStable = matching.stream().allMatch(index -> {
            ReturnOrderItem item = items.get(index);
            return item.getId() != null && item.getSourceFinishedGoodsBatchId() != null
                    && !item.getSourceFinishedGoodsBatchId().isBlank();
        });
        // Legacy records can only be mapped when the whole order is a single line.
        // A mixed stable/legacy order is deliberately fail-closed.
        if (!allStable && (matching.size() != 1 || items.size() != 1)) {
            issues.add(issue("RETURN_SOURCE_AMBIGUOUS", "多行退货缺少稳定来源序号，无法映射退回库存"));
            return new StockResolution(false);
        }
        Set<Long> seenItemIds = new HashSet<>();
        BigDecimal totalBaseline = BigDecimal.ZERO;
        BigDecimal totalCurrent = BigDecimal.ZERO;
        BigDecimal totalDisposed = BigDecimal.ZERO;
        boolean allEvidenceComplete = true;
        boolean allResolved = true;
        Set<String> seenReturnedIds = new HashSet<>();

        for (Integer matchingIndex : matching) {
            ReturnOrderItem matchedItem = items.get(matchingIndex);
            boolean stableLineLink = allStable;
            if (stableLineLink && !seenItemIds.add(matchedItem.getId())) {
                issues.add(issue("RETURN_SOURCE_AMBIGUOUS", "退货行稳定来源序号重复，无法映射退回库存"));
                return new StockResolution(false);
            }
            if (stableLineLink) {
                FinishedGoodsBatch source = finishedGoodsBatchRepository
                        .findById(matchedItem.getSourceFinishedGoodsBatchId()).orElse(null);
                if (source == null || !Objects.equals(factoryId, source.getFactoryId())
                        || !Objects.equals(batch.getBatchNumber(), source.getBatchNumber())
                        || !Objects.equals(batch.getProductTypeId(), source.getProductTypeId())
                        || !sameUnit(factoryId, unit, source.getUnit())) {
                    issues.add(issue("RETURN_SOURCE_AMBIGUOUS", "退货行稳定来源与召回批次不一致，无法映射退回库存"));
                    return new StockResolution(false);
                }
            }
            String expectedBatchNumber = "RTN-" + order.getReturnNumber() + "-" + (matchingIndex + 1);
            List<FinishedGoodsBatch> returnedCandidates = stableLineLink
                    ? finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(
                            factoryId, matchedItem.getId())
                    : finishedGoodsBatchRepository
                    .findByFactoryIdOrderByCreatedAtDesc(factoryId, Pageable.unpaged()).getContent();
            List<FinishedGoodsBatch> candidates = returnedCandidates == null ? List.of() : returnedCandidates.stream()
                    .filter(candidate -> candidate != null
                            && Objects.equals(factoryId, candidate.getFactoryId())
                            && (stableLineLink || Objects.equals(expectedBatchNumber, candidate.getBatchNumber()))
                            && (!stableLineLink || Objects.equals(matchedItem.getId(), candidate.getReturnOrderItemId()))
                            && Objects.equals(batch.getProductTypeId(), candidate.getProductTypeId())
                            && unit != null && !unit.isBlank()
                            && sameUnit(factoryId, unit, candidate.getUnit())
                            && candidate.getShippedQuantity() != null
                            && candidate.getShippedQuantity().signum() == 0
                            && candidate.getReservedQuantity() != null
                            && candidate.getReservedQuantity().signum() == 0)
                    .toList();
            if (candidates.size() != 1) {
                issues.add(issue("RETURN_STOCK_MISSING", "已完成退货缺少唯一同厂同 SKU 的退回成品库存"));
                return new StockResolution(false);
            }
            FinishedGoodsBatch returned = candidates.get(0);
            if (returned.getId() == null || !seenReturnedIds.add(returned.getId())) {
                issues.add(issue("RETURN_SOURCE_AMBIGUOUS", "多个退货行映射到同一退回成品库存，无法核验数量"));
                return new StockResolution(false);
            }
            BigDecimal baseline = matchedItem.getQuantity();
            BigDecimal current = recallService.followUpPhysicalQuantity(returned);
            BigDecimal disposed = recallService.followUpScrappedQuantity(factoryId, returned);
            boolean statusAllows = baseline != null
                    && recallService.followUpStatusAllowsReceipt(factoryId, returned, baseline);
            boolean direct = statusAllows && current != null && current.compareTo(baseline) == 0
                    && FinishedGoodsBatch.Status.DEFECTIVE.equals(returned.getStatus());
            boolean evidenceComplete = direct || (statusAllows && current != null && disposed != null
                    && current.add(disposed).compareTo(baseline) == 0);
            boolean resolved = statusAllows && (baseline.signum() == 0
                    ? current != null && current.signum() == 0
                    : disposed != null && current != null && current.signum() == 0
                    && disposed.compareTo(baseline) == 0);
            if (!evidenceComplete) {
                issues.add(issue("RETURN_STOCK_UNRESOLVED", "退回成品库存数量与已完成退货数量不守恒"));
            } else if (!resolved) {
                issues.add(issue("INVENTORY_DISPOSAL_PENDING", "退回成品仍有隔离库存待完成报废"));
            }
            BigDecimal reportedDisposal = direct ? BigDecimal.ZERO : disposed;
            StockEvidence evidence = new StockEvidence(current, reportedDisposal, evidenceComplete, resolved);
            stock.add(toStock(returned, "RETURNED", evidence, disposals, factoryId));
            totalBaseline = totalBaseline.add(baseline);
            totalCurrent = totalCurrent.add(current == null ? BigDecimal.ZERO : current);
            totalDisposed = totalDisposed.add(direct || disposed == null ? BigDecimal.ZERO : disposed);
            allEvidenceComplete &= evidenceComplete;
            allResolved &= resolved;
        }
        if (totalBaseline.compareTo(quantity) != 0
                || (allEvidenceComplete && totalCurrent.add(totalDisposed).compareTo(totalBaseline) != 0)) {
            issues.add(issue("RETURN_STOCK_UNRESOLVED", "退回成品库存数量与已完成退货数量不守恒"));
            allResolved = false;
        }
        return new StockResolution(allEvidenceComplete && allResolved);
    }

    private StockEvidence stockEvidence(String factoryId, FinishedGoodsBatch batch,
                                        BigDecimal freezeBaseline, boolean original,
                                        List<DisposalRecord> disposals,
                                        List<FactoryRecallDTO.FollowUpIssue> issues) {
        BigDecimal current = recallService.followUpPhysicalQuantity(batch);
        BigDecimal disposed = recallService.followUpScrappedQuantity(factoryId, batch);
        boolean statusAllows = freezeBaseline != null
                && recallService.followUpStatusAllowsReceipt(factoryId, batch, freezeBaseline);
        boolean direct = original && statusAllows
                && current.compareTo(freezeBaseline) == 0
                && FinishedGoodsBatch.Status.DEFECTIVE.equals(batch.getStatus());
        boolean evidenceComplete = direct || (statusAllows && freezeBaseline != null && disposed != null
                && current.add(disposed).compareTo(freezeBaseline) == 0);
        boolean resolved = statusAllows && (freezeBaseline.signum() == 0
                ? current.signum() == 0
                : disposed != null && current.signum() == 0
                && disposed.compareTo(freezeBaseline) == 0);
        if (!evidenceComplete) {
            issues.add(issue("ORIGINAL_STOCK_UNRESOLVED", "原库存未按冻结事实完成隔离或处置"));
        } else if (!resolved) {
            issues.add(issue("INVENTORY_DISPOSAL_PENDING", "原库存仍有隔离库存待完成报废"));
        }
        BigDecimal reportedDisposal = direct ? BigDecimal.ZERO : disposed;
        return new StockEvidence(current, reportedDisposal, evidenceComplete, resolved);
    }

    private FactoryRecallDTO.FollowUpStock toStock(FinishedGoodsBatch batch, String origin,
                                                    StockEvidence evidence,
                                                    List<DisposalRecord> disposals,
                                                    String factoryId) {
        List<FactoryRecallDTO.DisposalSummary> summaries = disposals.stream()
                .filter(record -> record != null && Objects.equals(factoryId, record.getFactoryId())
                        && Objects.equals(batch.getId(), record.getFinishedGoodsBatchId()))
                .map(record -> FactoryRecallDTO.DisposalSummary.builder().id(record.getId())
                        .status(record.getStatus()).quantity(record.getDisposalQuantity()).build())
                .toList();
        return FactoryRecallDTO.FollowUpStock.builder().finishedGoodsBatchId(batch.getId())
                .batchNumber(batch.getBatchNumber()).origin(origin).unit(batch.getUnit())
                .remainingQuantity(evidence.remaining()).disposalQuantity(evidence.disposed())
                .evidenceComplete(evidence.evidenceComplete()).disposals(summaries).build();
    }

    private BigDecimal freezeBaseline(RecallEvent event, Long batchId,
                                      List<FactoryRecallDTO.FollowUpIssue> issues) {
        BigDecimal found = null;
        for (RecallAction action : recallService.followUpActions(event.getId())) {
            if (!FREEZE_INVENTORY.equals(action.getActionType())
                    || !Objects.equals(batchId, action.getTargetEntityId())
                    || action.getExecutionNotes() == null) continue;
            if (!"COMPLETED".equals(action.getStatus()) || action.getExecutedAt() == null) {
                return missingBaseline(issues);
            }
            try {
                JsonNode node = objectMapper.readTree(action.getExecutionNotes()).get("remainingInventory");
                if (node == null || !node.isNumber()) return missingBaseline(issues);
                BigDecimal candidate = node.decimalValue();
                if (candidate.signum() < 0 || (found != null && found.compareTo(candidate) != 0)) {
                    return missingBaseline(issues);
                }
                found = candidate;
            } catch (Exception ex) {
                return missingBaseline(issues);
            }
        }
        if (found == null) return missingBaseline(issues);
        return found;
    }

    private BigDecimal missingBaseline(List<FactoryRecallDTO.FollowUpIssue> issues) {
        issues.add(issue("FREEZE_BASELINE_UNKNOWN", "缺少可信的冻结库存基准"));
        return null;
    }

    private ReceivableEvidence receivableEvidence(String factoryId, ReturnOrder order) {
        if (order.getReturnType() != ReturnType.SALES_RETURN || Boolean.FALSE.equals(order.getWithGoods())) {
            return new ReceivableEvidence("NOT_REQUIRED", List.of());
        }
        if (order.getStatus() != ReturnOrderStatus.COMPLETED) {
            return new ReceivableEvidence("NOT_DUE", List.of());
        }
        BigDecimal total = order.getReceivableAmount();
        if (total == null || total.signum() < 0) {
            return new ReceivableEvidence("INCONSISTENT", List.of());
        }
        Map<String, ArApTransaction> rows = new LinkedHashMap<>();
        boolean invalidRow = false;
        for (ArApApprovalStatus status : ArApApprovalStatus.values()) {
            List<ArApTransaction> candidates = arApTransactionRepository
                    .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                            factoryId, RETURN_ORDER_SOURCE, order.getId(), status);
            for (ArApTransaction transaction : candidates == null ? List.<ArApTransaction>of() : candidates) {
                if (transaction == null || transaction.getId() == null) {
                    invalidRow = true;
                } else {
                    rows.put(transaction.getId(), transaction);
                    if (!validReceivableRow(factoryId, order, total, status, transaction)) {
                        invalidRow = true;
                    }
                }
            }
        }
        List<FactoryRecallDTO.AdjustmentSummary> adjustments = rows.values().stream()
                .map(transaction -> FactoryRecallDTO.AdjustmentSummary.builder().id(transaction.getId())
                        .transactionNumber(transaction.getTransactionNumber())
                        .approvalStatus(transaction.getApprovalStatus() == null ? null
                                : transaction.getApprovalStatus().name()).build())
                .toList();
        if (invalidRow) return new ReceivableEvidence("INCONSISTENT", adjustments);
        if (rows.isEmpty()) {
            if (total.signum() == 0) return new ReceivableEvidence("NOT_REQUIRED", adjustments);
            return new ReceivableEvidence("MISSING", adjustments);
        }
        long approved = rows.values().stream().filter(row -> row.getApprovalStatus() == ArApApprovalStatus.APPROVED).count();
        long pending = rows.values().stream().filter(row -> row.getApprovalStatus() == ArApApprovalStatus.PENDING).count();
        if (approved > 1 || pending > 1 || approved > 0 && pending > 0)
            return new ReceivableEvidence("INCONSISTENT", adjustments);
        if (approved == 1) return new ReceivableEvidence("APPROVED", adjustments);
        if (pending == 1) return new ReceivableEvidence("PENDING", adjustments);
        return new ReceivableEvidence("REJECTED", adjustments);
    }

    private boolean validReceivableRow(String factoryId, ReturnOrder order, BigDecimal total,
                                       ArApApprovalStatus queriedStatus,
                                       ArApTransaction transaction) {
        return Objects.equals(factoryId, transaction.getFactoryId())
                && Objects.equals(RETURN_ORDER_SOURCE, transaction.getSourceType())
                && Objects.equals(order.getId(), transaction.getSourceId())
                && transaction.getTransactionType() == ArApTransactionType.AR_ADJUSTMENT
                && transaction.getCounterpartyType()
                == com.cretas.aims.entity.enums.CounterpartyType.CUSTOMER
                && order.getCounterpartyId() != null
                && Objects.equals(order.getCounterpartyId(), transaction.getCounterpartyId())
                && transaction.getApprovalStatus() == queriedStatus
                && transaction.getApprovalStatus() != null
                && transaction.getAmount() != null
                && transaction.getAmount().compareTo(total.negate()) == 0;
    }

    private FactoryRecallDTO.FollowUpIssue issue(String code, String message) {
        return FactoryRecallDTO.FollowUpIssue.builder().code(code).message(message).build();
    }

    private boolean sameUnit(String factoryId, String left, String right) {
        if (left == null || right == null) return false;
        if (unitContractService == null) {
            throw new IllegalStateException("UnitContractService is required for unit identity checks");
        }
        String canonicalLeft = unitContractService.storageUnit(factoryId, left);
        String canonicalRight = unitContractService.storageUnit(factoryId, right);
        return canonicalLeft != null && !canonicalLeft.isBlank()
                && Objects.equals(canonicalLeft, canonicalRight);
    }

    private record StockEvidence(BigDecimal remaining, BigDecimal disposed,
                                 boolean evidenceComplete, boolean resolved) { }
    private record StockResolution(boolean resolved) { }
    private record ReceivableEvidence(String status, List<FactoryRecallDTO.AdjustmentSummary> adjustments) { }
}
