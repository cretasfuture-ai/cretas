package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 「质检放行判了不合格的原料, 别的入口不能把它改回能用」的<b>唯一判定</b> (D-S411-04 审查 M2)。
 *
 * <p>质检放行判不合格写的是 DEFECTIVE, 而 {@code QualityDispositionInventoryService.evaluateMaterialFlip} 允许
 * DEFECTIVE ↔ AVAILABLE 互转 (退货回库的不良品要能改回来)。不加这一道, 放行决策工具 / 质检处置放行 / 仓库页改状态
 * 都能把这批改回可用, 而放行记录上仍是「不合格」。成品同类防线见
 * {@code QualityDispositionInventoryService#rejectedAtQcRelease(String, FinishedGoodsBatch)}。
 *
 * <p>判据: 这批原料<b>最近一条</b>质检放行记录 ({@link QcReleaseRecord.TargetType#RAW_MATERIAL_BATCH}) 是 REJECTED,
 * 且目标状态是能用的状态 (可用 / 库存中 / 鲜品 / 冻品 / 预留)。改成质检中 (送回复检)、报废、不良品照常允许 ——
 * 复检之后在「质检放行」里放行会写一条新的 RELEASED 记录, 最近一条就不再是 REJECTED。
 * {@code QcReleaseService} 自己是放行的权威入口, 不调本闸。
 *
 * <p>本次只管原料收货批次; 在制批次 (WIP_MATERIAL_BATCH) 同类缺口登记为后续。
 */
public final class MaterialQcRejectionGuard {

    public static final String CODE = "MATERIAL_BATCH_QC_REJECTED";

    /** 判了不合格之后不许进入的状态: 这些状态下批次能被领用 / 预留 / 投产。 */
    static final Set<MaterialBatchStatus> USABLE = EnumSet.of(
            MaterialBatchStatus.AVAILABLE,
            MaterialBatchStatus.IN_STOCK,
            MaterialBatchStatus.FRESH,
            MaterialBatchStatus.FROZEN,
            MaterialBatchStatus.RESERVED,
            MaterialBatchStatus.PRODUCING_RESERVED);

    private MaterialQcRejectionGuard() {
    }

    /** 这批原料最近一条质检放行结论是不是「不合格」。仓储没注入不当「没判过」放过去。 */
    public static boolean rejectedAtQcRelease(QcReleaseRecordRepository repo, MaterialBatch batch) {
        if (repo == null) {
            throw new IllegalStateException("质检放行记录仓储未注入，无法判断原料是否已被质检判定不合格");
        }
        if (batch == null || batch.getId() == null || batch.getFactoryId() == null) {
            return false;
        }
        List<QcReleaseRecord> records = repo.findByTarget(batch.getFactoryId(),
                QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, batch.getId());
        return !records.isEmpty() && QcReleaseRecord.Decision.REJECTED.equals(records.get(0).getDecision());
    }

    /** 这次改状态会不会把质检判了不合格的原料改回能用 (不查库的那一半先判, 不是目标状态就不查)。 */
    public static boolean blocks(QcReleaseRecordRepository repo, MaterialBatch batch, MaterialBatchStatus target) {
        return target != null && USABLE.contains(target) && rejectedAtQcRelease(repo, batch);
    }

    /** {@link #blocks} 成立就 409, 写明去哪里处理。 */
    public static void assertMayBecome(QcReleaseRecordRepository repo, MaterialBatch batch, MaterialBatchStatus target) {
        if (blocks(repo, batch, target)) {
            throw rejected(batch);
        }
    }

    public static String message(MaterialBatch batch) {
        return "原料批次 " + batch.getBatchNumber() + " 已在「质检放行」判定不合格，不能改回可用";
    }

    public static final String HINT =
            "如需复检：先把批次改回「质检中」，再到「质量管理 → 质检放行」重新放行（会留下新的放行记录）";

    private static BusinessException rejected(MaterialBatch batch) {
        return new BusinessException(409, message(batch))
                .withCode(CODE)
                .withHint(HINT)
                .withSeverity("BLOCKING");
    }
}
