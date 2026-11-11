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
