package com.cretas.aims.service.gate;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;

import java.util.Optional;

/** 原料批次状态闸的诊断 (写路径 assertMaterialBatchUsable 与 AI 只读诊断共用同一份)。 */
public final class MaterialBatchStateGate {
    private MaterialBatchStateGate() {}

    /**
     * 写路径的硬拦 (唯一一份): 批次状态本身禁止投产 (待检 / 过期 / 报废 / 不良品) 就 409。
     * 领料 / 投料 / 消耗记录 / AI 出库都调这一个 (排查 2026-11-10 A4/A5: 两个领料接口和 AI 出库原来只比数量)。
     */
    /**
     * 事后报工 (工序表 / 逐道录入) 用的口径: 拦待检 / 报废 / 不良品, <b>不拦过期</b>。
     * 生产仓批次继承来源效期, 凌晨 02:00 的过期任务会把「昨天已经领出、已经用掉」的批次翻成已过期 ——
     * 第二天补录这笔已经发生的消耗不该被拦 (拦了消耗就记不上, 结单少扣、关单当成没用退回)。
     * 过期料的食品安全闸在领料 / 划出那一步 (独立审查 M2)。
     */
    public static void assertReportable(MaterialBatch batch) {
        if (batch != null && batch.getStatus() == MaterialBatchStatus.EXPIRED) {
            return;
        }
        assertUsable(batch);
    }

    public static void assertUsable(MaterialBatch batch) {
        MaterialBatchStatus st = batch == null ? null : batch.getStatus();
        if (st != null && st.blocksProduction()) {
            throw new com.cretas.aims.exception.BusinessException(409,
                    (batch.getBatchNumber() != null ? batch.getBatchNumber() + " " : "") + st.productionBlockedMessage())
                    .withCode("MATERIAL_BATCH_" + st.name())
                    .withHint(st.productionBlockedHint())
                    .withDiagnosis(diagnose(batch).orElseThrow());
        }
    }

    public static Optional<GateDiagnosis> diagnose(MaterialBatch batch) {
        MaterialBatchStatus st = batch == null ? null : batch.getStatus();
        if (st == null || !st.blocksProduction()) {
            return Optional.empty();
        }
        boolean inspecting = st == MaterialBatchStatus.INSPECTING;
        boolean expired = st == MaterialBatchStatus.EXPIRED;
        return Optional.of(GateDiagnosis.of(GateId.MATERIAL_BATCH_STATE, "MATERIAL_BATCH_" + st.name(),
                        "BATCH", batch.getBatchNumber())
                .cause(st.getDisplayName())
                .resolve(inspecting ? "去「质检放行」页放行原料批次 " + batch.getBatchNumber()
                                : expired ? "该批已过期需处置 (报废/退货), 改选未过期批次"
                                : "该批" + st.getDisplayName() + ", 改选可用批次",
                        inspecting ? GateRoutes.QC_RELEASE : null)
                .build());
    }
}
