package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the cost of an old sales-return inventory batch whose unitCost was
 * not copied when the return was completed.
 *
 * <p>The RTN batch number is only a candidate pointer.  It becomes usable
 * evidence after the factory-scoped, completed return document, exact item
 * index, source batch, SKU, native unit, and quantity all agree.  A sale price
 * is deliberately never used as a cost fallback.</p>
 */
@Service
@RequiredArgsConstructor
public class ReturnedGoodsCostResolver {

    private static final Pattern RETURN_BATCH = Pattern.compile("^RTN-(.+)-(\\d+)$");

    private final ReturnOrderRepository returnOrderRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Autowired
    private UnitContractService unitContractService;

    @Autowired(required = false)
    private ReturnOrderItemRepository returnOrderItemRepository;

    public Optional<ResolvedCost> resolve(String factoryId, FinishedGoodsBatch returnedBatch) {
        if (factoryId == null || returnedBatch == null
                || !factoryId.equals(returnedBatch.getFactoryId())
                || isBlank(returnedBatch.getBatchNumber())
                || isBlank(returnedBatch.getProductTypeId())
                || isBlank(returnedBatch.getUnit())) {
            return Optional.empty();
        }
        Matcher matcher = RETURN_BATCH.matcher(returnedBatch.getBatchNumber().trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        int itemIndex;
        try {
            itemIndex = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (itemIndex <= 0) return Optional.empty();

        ReturnOrder order = returnOrderRepository
                .findByFactoryIdAndReturnNumber(factoryId, matcher.group(1))
                .orElse(null);
        if (order == null || !factoryId.equals(order.getFactoryId())
                || order.getStatus() != ReturnOrderStatus.COMPLETED
                || order.getReturnType() != ReturnType.SALES_RETURN
                || !Boolean.TRUE.equals(order.getWithGoods())) {
            return Optional.empty();
        }
        List<ReturnOrderItem> items = order.getItems();
        ReturnOrderItem item;
        boolean stableLineLink = returnedBatch.getReturnOrderItemId() != null;
        if (stableLineLink) {
            if (returnOrderItemRepository == null) return Optional.empty();
            item = returnOrderItemRepository.findById(returnedBatch.getReturnOrderItemId()).orElse(null);
            if (item == null || !Objects.equals(order.getId(), item.getReturnOrderId())) return Optional.empty();
        } else {
            // Historical rows do not persist a stable item identity beyond the
            // RTN suffix.  Only a single-line return with suffix -1 is safe to
            // recover automatically; multi-line evidence must be reviewed by a
            // human rather than guessing list order.
            if (items == null || items.size() != 1 || itemIndex != 1) return Optional.empty();
            item = items.get(0);
        }
        if (item == null || item.getQuantity() == null || item.getQuantity().signum() <= 0
                || isBlank(item.getBatchNumber())
                || !returnedBatch.getProductTypeId().equals(item.getProductTypeId())) {
            return Optional.empty();
        }
        if (!returnedBatch.getBatchNumber().startsWith("RTN-" + order.getReturnNumber() + "-")) {
            return Optional.empty();
        }
        if (!stableLineLink) {
            String expectedBatchNumber = "RTN-" + order.getReturnNumber() + "-" + itemIndex;
            if (!expectedBatchNumber.equals(returnedBatch.getBatchNumber())) return Optional.empty();
        }

        FinishedGoodsBatch source;
        if (!isBlank(item.getSourceFinishedGoodsBatchId())) {
            source = finishedGoodsBatchRepository.findById(item.getSourceFinishedGoodsBatchId()).orElse(null);
        } else {
            source = finishedGoodsBatchRepository
                    .findByFactoryIdAndBatchNumber(factoryId, item.getBatchNumber().trim())
                    .orElse(null);
        }
        if (source == null || !factoryId.equals(source.getFactoryId())
                || !returnedBatch.getProductTypeId().equals(source.getProductTypeId())
                || !item.getBatchNumber().trim().equals(source.getBatchNumber())
                || !sameUnit(factoryId, returnedBatch.getUnit(), source.getUnit())
                || source.getUnitCost() == null || source.getUnitCost().signum() < 0) {
            return Optional.empty();
        }

        // A returned child is never a second outbound shipment.  The source
        // item quantity may exceed the current child quantity after a partial
        // disposal, but it must still cover the physical quantity being held.
        BigDecimal produced = returnedBatch.getProducedQuantity();
        BigDecimal shipped = returnedBatch.getShippedQuantity();
        BigDecimal reserved = returnedBatch.getReservedQuantity();
        if (produced == null || produced.signum() < 0
                || shipped == null || shipped.signum() != 0
                || reserved == null || reserved.signum() != 0
                || item.getQuantity().compareTo(produced) < 0) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedCost(source.getUnitCost(), source.getId(),
                source.getBatchNumber(), order.getReturnNumber(), itemIndex, source.getUnit()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    public record ResolvedCost(BigDecimal unitCost, String sourceBatchId, String sourceBatchNumber,
                               String returnNumber, int itemIndex, String unit) {
    }
}
