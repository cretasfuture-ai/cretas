package com.cretas.aims.service.gate;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;

import java.util.Optional;

/** 原料批次状态闸的诊断 (写路径 assertMaterialBatchUsable 与 AI 只读诊断共用同一份)。 */
public final class MaterialBatchStateGate {
    private MaterialBatchStateGate() {}

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
