package com.cretas.aims.service.sales;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;

/** Shared fail-closed saleability gate for finished-goods allocation and shipment. */
public final class FinishedGoodsBatchSaleabilityGuard {

    private FinishedGoodsBatchSaleabilityGuard() {
    }

    public static void assertSaleable(FinishedGoodsBatch batch, String hintTarget) {
        String status = batch.getStatus();
        if (FinishedGoodsBatch.Status.AVAILABLE.equals(status)) {
            return;
        }

        String displayStatus = status == null || status.isBlank() ? "未知" : status;
        throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                + " 当前状态为 " + displayStatus + "，不可用于销售分配或仓库发货")
                .withCode("FINISHED_GOODS_BATCH_NOT_SALEABLE")
                .withHint("只有状态为 AVAILABLE（可售）的成品批次可以分配和发货；请先完成质检放行，或选择其他可售批次")
                .withSeverity("BLOCKING")
                .withHintTarget(hintTarget);
    }
}
