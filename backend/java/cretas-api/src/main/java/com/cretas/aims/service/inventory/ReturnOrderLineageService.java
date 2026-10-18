package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.ReturnOrderLineageDTO;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Factory-scoped read model for return-line source and returned FG stock. */
@Service
@RequiredArgsConstructor
public class ReturnOrderLineageService {

    private static final String VERIFIED = "VERIFIED";
    private static final String LEGACY_SINGLE_LINE = "LEGACY_SINGLE_LINE";
    private static final String UNKNOWN = "UNKNOWN";

    private final ReturnOrderRepository returnOrderRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private UnitContractService unitContractService;

    @Transactional(readOnly = true)
    public ReturnOrderLineageDTO get(String factoryId, String returnOrderId) {
        ReturnOrder order = returnOrderRepository.findById(returnOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("退货单不存在"));
        if (!Objects.equals(factoryId, order.getFactoryId())) {
            throw new BusinessException(403, "无权访问该退货单")
                    .withHint("当前退货单不属于该工厂, 无法访问");
        }

        List<ReturnOrderItem> items = order.getItems() == null ? List.of() : order.getItems();
        List<ReturnOrderLineageDTO.Line> lines = new ArrayList<>();
        for (ReturnOrderItem item : items) {
            if (item == null) continue;
            Source source = resolveSource(factoryId, order, item);
            List<FinishedGoodsBatch> returned = resolveReturnedStock(factoryId, order, item, source);
            String status = source.status();
            if (source.stable() && returned.size() > 1) status = UNKNOWN;
            lines.add(ReturnOrderLineageDTO.Line.builder()
                    .returnOrderItemId(item.getId())
                    .sourceFinishedGoodsBatchId(source.id())
                    .sourceBatchNumber(source.batchNumber() != null
                            ? source.batchNumber() : item.getBatchNumber())
                    .productTypeId(item.getProductTypeId())
                    .unit(source.unit())
                    .quantity(item.getQuantity())
                    .sourceStatus(status)
                    .returnedStock(returned.stream().map(this::toReturnedStock).toList())
                    .build());
        }
        return ReturnOrderLineageDTO.builder()
                .returnOrderId(order.getId())
                .returnNumber(order.getReturnNumber())
                .lines(lines)
                .build();
    }

    private Source resolveSource(String factoryId, ReturnOrder order, ReturnOrderItem item) {
        FinishedGoodsBatch source = null;
        boolean stable = item.getSourceFinishedGoodsBatchId() != null
                && !item.getSourceFinishedGoodsBatchId().isBlank();
        if (stable) {
            source = finishedGoodsBatchRepository.findById(item.getSourceFinishedGoodsBatchId()).orElse(null);
        } else if (item.getBatchNumber() != null && !item.getBatchNumber().isBlank()) {
            source = finishedGoodsBatchRepository
                    .findByFactoryIdAndBatchNumber(factoryId, item.getBatchNumber().trim())
                    .orElse(null);
        }
        boolean sameFactory = source != null && Objects.equals(factoryId, source.getFactoryId());
        if (source == null || !sameFactory
                || !Objects.equals(item.getProductTypeId(), source.getProductTypeId())
                || item.getBatchNumber() == null
                || !item.getBatchNumber().trim().equals(source.getBatchNumber())
                || source.getUnit() == null || source.getUnit().isBlank()) {
            return new Source(stable ? item.getSourceFinishedGoodsBatchId() : null,
                    sameFactory ? source.getBatchNumber() : null,
                    sameFactory ? source.getUnit() : null,
                    UNKNOWN, stable);
        }
        return new Source(source.getId(), source.getBatchNumber(), source.getUnit(),
                stable ? VERIFIED : (order.getItems() != null && order.getItems().size() == 1
                        ? LEGACY_SINGLE_LINE : UNKNOWN), stable);
    }

    private List<FinishedGoodsBatch> resolveReturnedStock(String factoryId, ReturnOrder order,
                                                           ReturnOrderItem item, Source source) {
        List<FinishedGoodsBatch> candidates;
        if (item.getId() != null && source.stable()) {
            candidates = finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(
                    factoryId, item.getId());
        } else if (order.getItems() != null && order.getItems().size() == 1
                && order.getReturnNumber() != null) {
            candidates = finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(
                    factoryId, "RTN-" + order.getReturnNumber() + "-1")
                    .map(batch -> List.of(batch)).orElseGet(List::of);
        } else {
            candidates = List.of();
        }
        return candidates == null ? List.of() : candidates.stream()
                .filter(batch -> batch != null
                        && Objects.equals(factoryId, batch.getFactoryId())
                        && Objects.equals(item.getProductTypeId(), batch.getProductTypeId())
                        && (source.unit() == null || sameUnit(factoryId, source.unit(), batch.getUnit())))
                .toList();
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

    private ReturnOrderLineageDTO.ReturnedStock toReturnedStock(FinishedGoodsBatch batch) {
        BigDecimal remaining = batch.getProducedQuantity() == null ? BigDecimal.ZERO
                : batch.getProducedQuantity()
                .subtract(batch.getShippedQuantity() == null ? BigDecimal.ZERO : batch.getShippedQuantity())
                .subtract(batch.getReservedQuantity() == null ? BigDecimal.ZERO : batch.getReservedQuantity());
        return ReturnOrderLineageDTO.ReturnedStock.builder()
                .id(batch.getId()).batchNumber(batch.getBatchNumber()).unit(batch.getUnit())
                .remainingQuantity(remaining).status(batch.getStatus()).build();
    }

    private record Source(String id, String batchNumber, String unit, String status, boolean stable) {
    }
}
