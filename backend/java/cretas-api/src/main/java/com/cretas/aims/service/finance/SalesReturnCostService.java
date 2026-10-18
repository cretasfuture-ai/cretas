package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.service.inventory.ReturnedGoodsCostResolver;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the cost restored by completed, physical sales returns.
 *
 * <p>The quantity is deliberately taken from the completed return line.  A
 * later disposal or re-sale changes the returned child batch's live balance,
 * but cannot erase a cost recovery which already happened.</p>
 */
@Service
@RequiredArgsConstructor
public class SalesReturnCostService {

    private final ReturnOrderRepository returnOrderRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final ReturnedGoodsCostResolver returnedGoodsCostResolver;
    private final VoucherRepository voucherRepository;
    private final FinishedGoodsAdjustmentLogRepository adjustmentLogRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private UnitContractService unitContractService;

    /** Returns the unrounded total cost of physical sales returns in the date range. */
    public BigDecimal returnedCost(String factoryId, LocalDate start, LocalDate end) {
        if (factoryId == null || factoryId.isBlank() || start == null || end == null || start.isAfter(end)) {
            throw conflict("退货成本汇总日期或工厂参数无效");
        }
        List<ReturnOrder> orders = returnOrderRepository
                .findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                        factoryId, ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, start, end);
        BigDecimal total = BigDecimal.ZERO;
        Set<String> countedBatchIds = new HashSet<>();
        for (ReturnOrder order : orders) {
            if (order == null || !factoryId.equals(order.getFactoryId())
                    || order.getReturnType() != ReturnType.SALES_RETURN
                    || order.getStatus() != ReturnOrderStatus.COMPLETED
                    || !Boolean.TRUE.equals(order.getWithGoods())) {
                continue;
            }
            validatePostedReturnVoucher(factoryId, order);
            if (order.getItems() == null) {
                throw conflict("退货单 " + order.getReturnNumber() + " 缺少退货明细，无法恢复成本");
            }
            if (order.getItems().isEmpty()) {
                throw conflict("退货单 " + order.getReturnNumber() + " 退货明细为空，无法恢复成本");
            }
            for (int line = 0; line < order.getItems().size(); line++) {
                ReturnOrderItem item = order.getItems().get(line);
                if (item == null || item.getQuantity() == null || item.getQuantity().signum() <= 0) {
                    throw conflict("退货单 " + order.getReturnNumber() + " 含无效退货数量，无法恢复成本");
                }
                FinishedGoodsBatch returnedBatch = locateInbound(factoryId, order, item, line + 1);
                if (!countedBatchIds.add(returnedBatch.getId())) {
                    throw conflict("退货批次 " + returnedBatch.getBatchNumber() + " 被重复入库，无法恢复成本");
                }
                validateReturnedBatch(factoryId, order, item, returnedBatch);
                FinishedGoodsBatch source = locateSource(factoryId, item);
                validateSource(factoryId, item, returnedBatch, source);
                validateReturnedQuantity(factoryId, item, returnedBatch);
                BigDecimal unitCost = resolveUnitCost(factoryId, item, returnedBatch, source);
                total = total.add(item.getQuantity().multiply(unitCost));
            }
        }
        return total;
    }

    private void validatePostedReturnVoucher(String factoryId, ReturnOrder order) {
        if (isBlank(order.getId())) {
            throw conflict("已完成退货单缺少单据ID，无法核对收入凭证");
        }
        Voucher voucher = voucherRepository
                .findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_ORDER", order.getId())
                .orElseThrow(() -> conflict("退货单 " + order.getReturnNumber() + " 缺少已过账收入凭证，已阻断成本恢复"));
        if (!factoryId.equals(voucher.getFactoryId())
                || !Objects.equals(order.getId(), voucher.getSourceBusinessId())
                || !Objects.equals(order.getReturnDate(), voucher.getVoucherDate())
                || !Objects.equals(voucher.getSourceBusinessType(), "RETURN_ORDER")
                || voucher.getVoucherType() != VoucherType.RETURN
                || voucher.getStatus() != VoucherStatus.POSTED) {
            throw conflict("退货单 " + order.getReturnNumber() + " 的收入凭证未按原退货期间正确过账");
        }
    }

    private FinishedGoodsBatch locateInbound(String factoryId, ReturnOrder order, ReturnOrderItem item, int lineIndex) {
        if (item.getId() != null) {
            List<FinishedGoodsBatch> candidates = finishedGoodsBatchRepository
                    .findByFactoryIdAndReturnOrderItemId(factoryId, item.getId());
            if (candidates.size() != 1) {
                if (!candidates.isEmpty()) {
                    throw conflict("退货行 " + item.getId() + " 的回库批次证据重复，无法恢复成本");
                }
                return legacyInbound(factoryId, order, item, lineIndex);
            }
            if (order.getReturnNumber() != null) {
                String legacyBatchNumber = "RTN-" + order.getReturnNumber() + "-" + lineIndex;
                FinishedGoodsBatch legacy = finishedGoodsBatchRepository
                        .findByFactoryIdAndBatchNumber(factoryId, legacyBatchNumber).orElse(null);
                if (legacy != null && legacy.getReturnOrderItemId() == null) {
                    throw conflict("退货行 " + item.getId() + " 同时存在稳定和旧批次回库证据，无法恢复成本");
                }
            }
            validateStableBatchReference(order, candidates.get(0));
            return candidates.get(0);
        }
        return legacyInbound(factoryId, order, item, lineIndex);
    }

    private FinishedGoodsBatch legacyInbound(String factoryId, ReturnOrder order, ReturnOrderItem item, int lineIndex) {
        if (order.getItems() == null || order.getItems().size() != 1 || lineIndex != 1
                || order.getReturnNumber() == null || isBlank(item.getBatchNumber())) {
            throw conflict("历史退货行缺少稳定回库链路，无法恢复成本");
        }
        String expected = "RTN-" + order.getReturnNumber() + "-" + lineIndex;
        FinishedGoodsBatch batch = finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(factoryId, expected)
                .orElseThrow(() -> conflict("退货批次 " + expected + " 缺少回库证据，无法恢复成本"));
        if (batch.getReturnOrderItemId() != null
                || returnedGoodsCostResolver.resolve(factoryId, batch).isEmpty()) {
            throw conflict("历史退货批次 " + expected + " 缺少唯一可验证的旧回库链路");
        }
        return batch;
    }

    private void validateStableBatchReference(ReturnOrder order, FinishedGoodsBatch returned) {
        String prefix = "RTN-" + order.getReturnNumber() + "-";
        if (isBlank(returned.getBatchNumber()) || !returned.getBatchNumber().startsWith(prefix)) {
            throw conflict("退货批次未匹配退货单文档编号，无法恢复成本");
        }
    }

    private FinishedGoodsBatch locateSource(String factoryId, ReturnOrderItem item) {
        if (!isBlank(item.getSourceFinishedGoodsBatchId())) {
            return finishedGoodsBatchRepository.findById(item.getSourceFinishedGoodsBatchId())
                    .orElseThrow(() -> conflict("退货行 " + item.getId() + " 的源成品批次不存在"));
        }
        if (isBlank(item.getBatchNumber())) {
            throw conflict("退货行 " + item.getId() + " 缺少源批次，无法恢复成本");
        }
        return finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(factoryId, item.getBatchNumber().trim())
                .orElseThrow(() -> conflict("退货行 " + item.getId() + " 的源成品批次不存在"));
    }

    private void validateReturnedBatch(String factoryId, ReturnOrder order, ReturnOrderItem item,
                                       FinishedGoodsBatch returnedBatch) {
        if (!factoryId.equals(returnedBatch.getFactoryId())
                || !isCompanyOwned(returnedBatch.getOwnership())
                || isBlank(returnedBatch.getProductTypeId())
                || isBlank(returnedBatch.getUnit())
                || (returnedBatch.getReturnOrderItemId() != null && item.getId() != null
                    && !Objects.equals(item.getId(), returnedBatch.getReturnOrderItemId()))) {
            throw conflict("退货批次 " + returnedBatch.getBatchNumber() + " 的工厂、归属或回库链路不匹配");
        }
        if (order.getReturnNumber() == null || isBlank(returnedBatch.getBatchNumber())) {
            throw conflict("退货单缺少可验证的回库批次号");
        }
    }

    private void validateSource(String factoryId, ReturnOrderItem item, FinishedGoodsBatch returnedBatch,
                                FinishedGoodsBatch source) {
        if (!factoryId.equals(source.getFactoryId()) || !isCompanyOwned(source.getOwnership())
                || !Objects.equals(item.getProductTypeId(), source.getProductTypeId())
                || !Objects.equals(item.getBatchNumber(), source.getBatchNumber())
                || !Objects.equals(returnedBatch.getProductTypeId(), source.getProductTypeId())
                || !sameUnit(factoryId, returnedBatch.getUnit(), source.getUnit())) {
            throw conflict("退货行的源批次、SKU、单位或工厂不匹配");
        }
        if (source.getUnitCost() == null || source.getUnitCost().signum() < 0) {
            throw conflict("退货行源批次缺少合法库存成本，无法恢复成本");
        }
    }

    private BigDecimal resolveUnitCost(String factoryId, ReturnOrderItem item,
                                       FinishedGoodsBatch returned, FinishedGoodsBatch source) {
        BigDecimal snapshot = returned.getUnitCost();
        if (snapshot != null && snapshot.compareTo(source.getUnitCost()) != 0) {
            throw conflict("退货批次 " + returned.getBatchNumber() + " 成本快照与源批次成本不一致，请核对");
        }
        if (snapshot != null) return snapshot;
        if (returned.getReturnOrderItemId() != null) return source.getUnitCost();
        return returnedGoodsCostResolver.resolve(factoryId, returned)
                .map(ReturnedGoodsCostResolver.ResolvedCost::unitCost)
                .filter(cost -> cost.compareTo(source.getUnitCost()) == 0 && cost.signum() >= 0)
                .orElseThrow(() -> conflict("退货批次 " + returned.getBatchNumber()
                        + " 缺少与源批次一致的可验证库存成本，已阻断成本恢复"));
    }

    private void validateReturnedQuantity(String factoryId, ReturnOrderItem item,
                                          FinishedGoodsBatch returned) {
        if (returned.getProducedQuantity() == null || returned.getProducedQuantity().signum() < 0
                || returned.getId() == null) {
            throw conflict("退货批次 " + returned.getBatchNumber() + " 缺少完整数量证据");
        }
        if (returned.getProducedQuantity().compareTo(item.getQuantity()) == 0) return;
        List<FinishedGoodsAdjustmentLog> logs = adjustmentLogRepository
                .findByBatchIdOrderByCreatedAtDesc(returned.getId());
        BigDecimal adjustedOriginal = returned.getProducedQuantity();
        for (FinishedGoodsAdjustmentLog log : logs) {
            if (log == null || !factoryId.equals(log.getFactoryId())
                    || !returned.getId().equals(log.getBatchId())
                    || log.getAdjustmentQuantity() == null) {
                throw conflict("退货批次数量调整日志跨工厂或批次不匹配");
            }
            adjustedOriginal = adjustedOriginal.subtract(log.getAdjustmentQuantity());
        }
        if (logs.isEmpty() || adjustedOriginal.compareTo(item.getQuantity()) != 0) {
            throw conflict("退货批次 " + returned.getBatchNumber() + " 缺少可验证的原始退回数量");
        }
    }

    private static boolean isCompanyOwned(InventoryOwnership ownership) {
        return ownership == null || ownership == InventoryOwnership.COMPANY_OWNED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(409, message)
                .withHint("请核对已完成实物退货、回库批次和源批次单位成本后重试");
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
}
