package com.cretas.aims.service.sales;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.util.InventoryExpiry;

import java.time.LocalDate;
import java.util.List;

/**
 * Shared fail-closed saleability gate for finished-goods allocation and shipment.
 *
 * <p>Two independent conditions, both checked at the moment of the action:
 * <ol>
 *   <li>status is AVAILABLE (QC released, not frozen/defective/reversed/expired-marked);</li>
 *   <li>the batch is not past its expire date on the business calendar day
 *       ({@link InventoryExpiry}: expire date itself is still sellable, the day after is not).
 *       Status alone is not enough: nothing guarantees the EXPIRED status flip has run yet.</li>
 * </ol>
 * A batch with no expire date is judged by status only (unchanged legacy behaviour; this is
 * "not blocked by date", not "shelf life is sufficient").
 */
public final class FinishedGoodsBatchSaleabilityGuard {

    private FinishedGoodsBatchSaleabilityGuard() {
    }

    public static void assertSaleable(FinishedGoodsBatch batch, String hintTarget) {
        assertSaleable(batch, hintTarget, InventoryExpiry.today());
    }

    public static void assertSaleable(FinishedGoodsBatch batch, String hintTarget, LocalDate today) {
        String status = batch.getStatus();
        // 质检放行 (缺口 11): 待检单独给错误码和出路 —— 「请先完成质检放行」在通用分支里只是一句泛话,
        // 待检批次的出路是明确的 (质检员放行), 不能和冻结/不良品混成同一个 FINISHED_GOODS_BATCH_NOT_SALEABLE。
        if (FinishedGoodsBatch.Status.PENDING_INSPECTION.equals(status)) {
            throw com.cretas.aims.service.quality.release.QcInspectionGuard
                    .pendingFinishedGoods(batch, hintTarget, "分配或发货");
        }
        if (!FinishedGoodsBatch.Status.AVAILABLE.equals(status)) {
            String displayStatus = status == null || status.isBlank() ? "未知" : status;
            throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                    + " 当前状态为 " + displayStatus + "，不可用于销售分配或仓库发货")
                    .withCode("FINISHED_GOODS_BATCH_NOT_SALEABLE")
                    .withHint("只有状态为 AVAILABLE（可售）的成品批次可以分配和发货；请先完成质检放行，或选择其他可售批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget(hintTarget);
        }
        if (isExpired(batch, today)) {
            throw new BusinessException(409, "成品批次 " + batch.getBatchNumber()
                    + " 已过期（到期日 " + batch.getExpireDate() + "），不可用于销售分配或仓库发货")
                    .withCode("FINISHED_GOODS_BATCH_EXPIRED")
                    .withHint("到期日当天仍可发货，次日起不可再分配或发货；请改选未过期的批次，过期批次请走报损处理")
                    .withSeverity("BLOCKING")
                    .withHintTarget(hintTarget);
        }
    }

    /** Expire date strictly before {@code today}; null expire date is never expired. */
    public static boolean isExpired(FinishedGoodsBatch batch, LocalDate today) {
        return InventoryExpiry.isExpired(batch.getExpireDate(), today);
    }

    /**
     * Candidate lists for recommendation / automatic FEFO deduction: drop expired batches so
     * they can neither be offered to the operator nor auto-picked. Order is preserved.
     */
    public static List<FinishedGoodsBatch> excludeExpired(List<FinishedGoodsBatch> batches, LocalDate today) {
        if (batches == null || batches.isEmpty()) {
            return batches;
        }
        return batches.stream().filter(batch -> !isExpired(batch, today)).toList();
    }
}
