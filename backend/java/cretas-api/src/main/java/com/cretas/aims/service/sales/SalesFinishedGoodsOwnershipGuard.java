package com.cretas.aims.service.sales;

import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.enums.CustomerStockFulfillmentMode;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.gate.GateDiagnosis;
import com.cretas.aims.service.gate.GateId;
import com.cretas.aims.service.gate.GateRoutes;

import java.util.Objects;

/** 推荐、分配与发货共用的库存所有权 fail-closed 门禁。 */
public final class SalesFinishedGoodsOwnershipGuard {

    private SalesFinishedGoodsOwnershipGuard() {
    }

    public static boolean requiresCustomerOwnedStock(SalesOrder order) {
        return order != null && order.getProcessingMode() == SalesProcessingMode.TOLL_PROCESSING;
    }

    public static void assertBatchAllowed(SalesOrder order,
                                          FinishedGoodsBatch batch,
                                          String hintTarget) {
        assertBatchAllowed(order, batch, false, hintTarget);
    }

    public static void assertBatchAllowed(SalesOrder order,
                                          FinishedGoodsBatch batch,
                                          boolean activelyReservedForOrder,
                                          String hintTarget) {
        if (batch == null) {
            throw new BusinessException(409, "成品批次缺少库存所有权信息")
                    .withCode("FINISHED_GOODS_OWNERSHIP_CONTEXT_REQUIRED")
                    .withHintTarget(hintTarget);
        }
        if (requiresCustomerOwnedStock(order)) {
            if (isBlank(order.getCustomerId()) || isBlank(order.getId())) {
                throw new BusinessException(409, "代加工销售订单缺少客户或来源订单信息")
                        .withCode("CUSTOMER_OWNED_SALES_LINEAGE_INCOMPLETE")
                        .withHintTarget(hintTarget);
            }
            boolean prestocked = order.getCustomerStockFulfillmentMode()
                    == CustomerStockFulfillmentMode.PRESTOCKED;
            boolean lineageMatches = prestocked
                    ? batch.getSourceSalesOrderId() == null && activelyReservedForOrder
                    : Objects.equals(order.getId(), batch.getSourceSalesOrderId());
            if (batch.getOwnership() != InventoryOwnership.CUSTOMER_OWNED
                    || !Objects.equals(order.getCustomerId(), batch.getOwnerCustomerId())
                    || !lineageMatches) {
                throw new BusinessException(409, "代加工订单只能发出属于同一客户、同一销售订单的成品库存")
                        .withCode("CUSTOMER_OWNED_FINISHED_GOODS_SCOPE_MISMATCH")
                        .withHint(prestocked
                                ? "只能选择已由本订单预留的客户已有库存"
                                : "请选择该客户订单生产并入库的成品批次")
                        .withHintTarget(hintTarget)
                    .withDiagnosis(GateDiagnosis.of(GateId.SALES_FG_OWNERSHIP, "CUSTOMER_OWNED_FINISHED_GOODS_SCOPE_MISMATCH", "FG_BATCH", batch.getBatchNumber()).resolve(prestocked ? "只能选择已由本订单预留的客户已有库存" : "改选该客户订单生产并入库的成品批次", null).build());
            }
            return;
        }

        if (batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) {
            throw new BusinessException(409, "普通销售订单不能发出客户所有的成品库存")
                    .withCode("CUSTOMER_OWNED_FINISHED_GOODS_FORBIDDEN")
                    .withHintTarget(hintTarget)
                    .withDiagnosis(GateDiagnosis.of(GateId.SALES_FG_OWNERSHIP, "CUSTOMER_OWNED_FINISHED_GOODS_FORBIDDEN", "FG_BATCH", batch.getBatchNumber()).resolve("批次 " + batch.getBatchNumber() + " 是客户所有的成品, 普通销售订单不能发; 改选公司自有成品批次", null).build());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
