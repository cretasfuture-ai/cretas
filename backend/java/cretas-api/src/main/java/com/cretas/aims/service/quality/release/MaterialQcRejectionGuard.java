package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitService;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 「判了不合格的批次, 别的入口不能把它改回能用」的<b>唯一判定</b> —— 原料收货批次 (D-S411-04 审查 M2) 与
 * 在制批次 (sourceDocType = PRODUCTION_BATCH, 交接单 2026-11-11 第二节第 1 条) 共用。
 *
 * <p>Steve 2026-11-04 质量裁定: 不合格批次只能走处置和复核改状态, 成品同步。成品同类防线见
 * {@code QualityDispositionInventoryService#rejectedAtQcRelease(String, FinishedGoodsBatch)}。
 *
 * <p>两种「判了不合格」来源都算:
 * <ol>
 *   <li>质检放行判不合格: {@link QcReleaseRecord} 最近一条为 REJECTED。目标类型按批次形态取 —— 在制批次查
 *       {@link QcReleaseRecord.TargetType#WIP_MATERIAL_BATCH}, 原料查 {@link QcReleaseRecord.TargetType#RAW_MATERIAL_BATCH}
 *       (#1571 只查原料类型, 在制批次判不合格后一条都查不到, 照样能被改回可用 —— 本次修复的缺陷)。</li>
 *   <li>工序时限超时、复检判不合格 ({@code ProcessTimeLimitService.writeOff} 写 DEFECTIVE): 本批有 REJECTED 的
 *       时限事件, 且之后没有新的质检放行 RELEASED 记录。</li>
 * </ol>
 * 目标状态属于能用的状态 (可用 / 库存中 / 鲜品 / 冻品 / 预留 / 生产预留) 才拦。改成质检中 (送回复检)、报废、
 * 不良品照常允许 —— 复检后在「质检放行」里放行会写一条更新的 RELEASED 记录, 最近一条结论就不再是不合格。
 * {@code QcReleaseService} 自己是放行的权威入口, 不调本闸。
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

    /** 在制批次 (生产产出) 还是原料收货批次。 */
    public static boolean isWipBatch(MaterialBatch batch) {
        return batch != null && QcInspectionGuard.WIP_SOURCE_DOC_TYPE.equals(batch.getSourceDocType());
    }

    /** 这批在质检放行记录里的目标类型。 */
    static String qcTargetType(MaterialBatch batch) {
        return isWipBatch(batch) ? QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH
                : QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH;
    }

    /**
     * 这批最近的结论是不是「不合格」(质检放行 + 工序时限两种来源)。仓储没注入不当「没判过」放过去。
     */
    public static boolean rejected(QcReleaseRecordRepository qcRepo, ProcessTimeLimitEventRepository timeLimitRepo,
                                   MaterialBatch batch) {
        if (qcRepo == null) {
            throw new IllegalStateException("质检放行记录仓储未注入，无法判断批次是否已被质检判定不合格");
        }
        if (timeLimitRepo == null) {
            throw new IllegalStateException("工序时限处置记录仓储未注入，无法判断批次是否已因超时判废");
        }
        if (batch == null || batch.getId() == null || batch.getFactoryId() == null) {
            return false;
        }
        List<QcReleaseRecord> records = qcRepo.findByTarget(batch.getFactoryId(), qcTargetType(batch), batch.getId());
        QcReleaseRecord latest = records.isEmpty() ? null : records.get(0);
        if (latest != null && QcReleaseRecord.Decision.REJECTED.equals(latest.getDecision())) {
            return true;
        }
        LocalDateTime timeLimitRejectedAt = latestTimeLimitRejection(timeLimitRepo, batch);
        if (timeLimitRejectedAt == null) {
            return false;
        }
        // 超时判废之后在质检放行里复检放行过 (更新的 RELEASED 记录) 才算复核通过
        return latest == null || latest.getReleasedAt() == null || !latest.getReleasedAt().isAfter(timeLimitRejectedAt);
    }

    /** 本批最近一次超时判废时刻; 没有返回 null。事件两种批次目标类型都查 (事件的类型在登记时定, 不信批次当前形态)。 */
    private static LocalDateTime latestTimeLimitRejection(ProcessTimeLimitEventRepository repo, MaterialBatch batch) {
        LocalDateTime latest = null;
        boolean any = false;
        for (String type : List.of(ProcessTimeLimitService.TargetType.WIP_MATERIAL_BATCH,
                ProcessTimeLimitService.TargetType.RAW_MATERIAL_BATCH)) {
            for (ProcessTimeLimitEvent e : repo.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(
                    batch.getFactoryId(), type, batch.getId(), ProcessTimeLimitEvent.Status.REJECTED)) {
                any = true;
                LocalDateTime at = e.getDecidedAt() != null ? e.getDecidedAt() : e.getBlockedAt();
                if (at == null) {
                    // 判废时刻缺失: 当作最新, 不让一条更早的放行记录把它盖掉
                    return LocalDateTime.MAX;
                }
                if (latest == null || at.isAfter(latest)) {
                    latest = at;
                }
            }
        }
        return any ? latest : null;
    }

    /** 这次改状态会不会把判了不合格的批次改回能用 (不查库的那一半先判, 不是目标状态就不查)。 */
    public static boolean blocks(QcReleaseRecordRepository qcRepo, ProcessTimeLimitEventRepository timeLimitRepo,
                                 MaterialBatch batch, MaterialBatchStatus target) {
        return target != null && USABLE.contains(target) && rejected(qcRepo, timeLimitRepo, batch);
    }

    /**
     * 数量恢复 (预留释放 / 退料 / 盘点盘盈 / 报工撤回 / 中途结算撤回) 把「已用完 / 已耗尽」翻回能用时该翻成什么。
     *
     * <p>两步洗白 (交接单 11-11 二.1 清点时发现): 判了不合格的批次先被预留满 / 出库到 0 变成 DEPLETED / USED_UP,
     * 原来的「不合格」就被数量状态盖掉了; 之后任何一处数量恢复都把它翻成 AVAILABLE, 没有一处问过它判过什么。
     * 最近结论是不合格 ⇒ 恢复成 DEFECTIVE (数量回来了, 结论不变); 否则 AVAILABLE (原行为)。
     */
    public static MaterialBatchStatus statusWhenRestored(QcReleaseRecordRepository qcRepo,
                                                         ProcessTimeLimitEventRepository timeLimitRepo,
                                                         MaterialBatch batch) {
        return rejected(qcRepo, timeLimitRepo, batch) ? MaterialBatchStatus.DEFECTIVE : MaterialBatchStatus.AVAILABLE;
    }

    /** {@link #blocks} 成立就 409, 写明去哪里处理。 */
    public static void assertMayBecome(QcReleaseRecordRepository qcRepo, ProcessTimeLimitEventRepository timeLimitRepo,
                                       MaterialBatch batch, MaterialBatchStatus target) {
        if (blocks(qcRepo, timeLimitRepo, batch, target)) {
            throw rejected(batch);
        }
    }

    public static String message(MaterialBatch batch) {
        return (isWipBatch(batch) ? "在制批次 " : "原料批次 ") + batch.getBatchNumber()
                + " 已判定不合格（质检放行或工序超时复检），不能改回可用";
    }

    public static final String HINT =
            "如需复检：先把批次改回「质检中」，再到「质量管理 → 质检放行」重新放行（会留下新的放行记录）；"
                    + "不再使用的走报废处置";

    private static BusinessException rejected(MaterialBatch batch) {
        return new BusinessException(409, message(batch))
                .withCode(CODE)
                .withHint(HINT)
                .withSeverity("BLOCKING");
    }
}
