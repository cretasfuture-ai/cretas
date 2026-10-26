package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;

/**
 * 质检放行的「领用 / 投料」闸 (缺口 11, owner 2026-10-25)。纯函数, 只读行上的状态, 不查库。
 *
 * <p>三种存货形态各一道:
 * <ul>
 *   <li>常驻半成品库 {@link SemiFinishedInventory}: 看 {@code inspectionStatus} 列 (null = 不需检, 放行);</li>
 *   <li>逐道录入的在制半成品 {@link MaterialBatch} (WIP-CLK-W-…, {@code sourceDocType=PRODUCTION_BATCH}):
 *       复用原料的「质检中」状态 {@link MaterialBatchStatus#INSPECTING}, 不合格 = DEFECTIVE;</li>
 *   <li>成品 {@link FinishedGoodsBatch}: {@link FinishedGoodsBatch.Status#PENDING_INSPECTION}
 *       (分配/发货那道在 {@code FinishedGoodsBatchSaleabilityGuard}, 这里只管「成品作投料来源」)。</li>
 * </ul>
 *
 * <p>🔴 <b>零回归是硬要求</b>: 没开「需检」、也没有 CCP 偏离的行, 三列/状态都与以前一模一样 ⇒ 这里一律放行。
 * 每道闸都只对<b>本功能写出来的状态</b>说不, ⛔ 不顺手收紧任何既有状态 (比如原料 INSPECTING 的既有行为)。
 */
public final class QcInspectionGuard {

    public static final String SEMI_PENDING = "QC_SEMI_PENDING_INSPECTION";
    public static final String SEMI_REJECTED = "QC_SEMI_REJECTED";
    public static final String WIP_PENDING = "QC_WIP_PENDING_INSPECTION";
    public static final String WIP_REJECTED = "QC_WIP_REJECTED";
    public static final String FG_PENDING = "FINISHED_GOODS_PENDING_INSPECTION";

    /** 在制半成品 MaterialBatch 的来源标记 (ClerkProcessEntryServiceImpl#createWipMaterialBatch)。 */
    public static final String WIP_SOURCE_DOC_TYPE = "PRODUCTION_BATCH";

    private static final String RELEASE_PATH = "质量管理 → 质检放行";

    private QcInspectionGuard() {
    }

    /** 常驻半成品库存行能否被下道领用。null / RELEASED 放行。 */
    public static void assertSemiDrawable(SemiFinishedInventory sfi, String hintTarget) {
        if (sfi == null) {
            return;
        }
        String s = sfi.getInspectionStatus();
        if (SemiFinishedInventory.InspectionStatus.PENDING.equals(s)) {
            throw new BusinessException(409, "半成品批次 " + sfi.getIntermediateBatchNo()
                    + " 待检，质检放行前不能领用")
                    .withCode(SEMI_PENDING)
                    .withHint("这批半成品的产品设置了「需检」或有未处理的 CCP 偏离。请质检员在「"
                            + RELEASE_PATH + "」放行后再领用，或改选已放行的批次")
                    .withSeverity("BLOCKING")
                    .withHintTarget(hintTarget);
        }
        if (SemiFinishedInventory.InspectionStatus.REJECTED.equals(s)) {
            throw new BusinessException(409, "半成品批次 " + sfi.getIntermediateBatchNo()
                    + " 质检不合格，不能领用")
                    .withCode(SEMI_REJECTED)
                    .withHint("请改选其他合格批次；不合格品请按不良品流程处置")
                    .withSeverity("BLOCKING")
                    .withHintTarget(hintTarget);
        }
    }

    /**
     * 逐道录入的在制半成品批次 (WIP MaterialBatch) 能否被下道投料。
     *
     * <p>只对 {@code sourceDocType=PRODUCTION_BATCH} 的在制批次判; 原料批次的 INSPECTING 有自己的
     * 既有闸 ({@code MaterialBatchServiceImpl#assertMaterialBatchUsable}), ⛔ 这里不碰。
     */
    public static void assertWipBatchDrawable(MaterialBatch mb, String sourceBatchNumber, String hintTarget) {
        if (mb == null || !WIP_SOURCE_DOC_TYPE.equals(mb.getSourceDocType())) {
            return;
        }
        if (mb.getStatus() == MaterialBatchStatus.INSPECTING) {
            throw new BusinessException(409, "上游批次 " + sourceBatchNumber + " 待检，质检放行前不能投料")
                    .withCode(WIP_PENDING)
                    .withHint("上道产出的产品设置了「需检」或有未处理的 CCP 偏离。请质检员在「"
                            + RELEASE_PATH + "」放行后再投料，或先保存草稿")
                    .withSeverity("BLOCKING")
                    .withHintTarget(hintTarget);
        }
        if (mb.getStatus() == MaterialBatchStatus.DEFECTIVE) {
            throw new BusinessException(409, "上游批次 " + sourceBatchNumber + " 质检不合格，不能投料")
                    .withCode(WIP_REJECTED)
                    .withHint("请改选其他合格批次；不合格品请按不良品流程处置")
                    .withSeverity("BLOCKING")
                    .withHintTarget(hintTarget);
        }
    }

    /** 成品批次作投料来源 (成品再加工)。只拦待检; 其它状态维持既有行为。 */
    public static void assertFinishedGoodsFeedable(FinishedGoodsBatch fg, String hintTarget) {
        if (fg != null && FinishedGoodsBatch.Status.PENDING_INSPECTION.equals(fg.getStatus())) {
            throw pendingFinishedGoods(fg, hintTarget, "投料");
        }
    }

    /** 成品待检的统一错误 (分配 / 发货 / 投料 共用同一句, 只换动作)。 */
    public static BusinessException pendingFinishedGoods(FinishedGoodsBatch fg, String hintTarget, String action) {
        return new BusinessException(409, "成品批次 " + fg.getBatchNumber() + " 待检，质检放行前不能" + action)
                .withCode(FG_PENDING)
                .withHint("这批成品的产品设置了「需检」或有未处理的 CCP 偏离。请质检员在「"
                        + RELEASE_PATH + "」放行后再" + action + "，或改选已放行的批次")
                .withSeverity("BLOCKING")
                .withHintTarget(hintTarget);
    }
}
