package com.cretas.aims.service.gate;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import com.cretas.aims.service.sales.FinishedGoodsBatchSaleabilityGuard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出库 / 领料 / 投料被拦时的结构化诊断: 每道闸拒绝时带「闸名 + 对象 + 缺多少 + 解开方式」。
 * 闸数 = 已接诊断 + 登记不接 (写理由); 诊断必须来自那道闸 (归因: 断言 gate id 与对象, 不只断言「有诊断」)。
 */
class GateDiagnosisContractTest {

    private static GateDiagnosis only(BusinessException e) {
        assertEquals(1, e.getDiagnoses().size(), "一处拒绝应带且只带这道闸的诊断");
        return e.getDiagnoses().get(0);
    }

    // ---------- 账本: 闸数 = 已接 + 登记不接 ----------

    @Test
    void catalogCountsBalance() {
        long diagnosed = Arrays.stream(GateId.values()).filter(GateId::diagnosed).count();
        long exempt = Arrays.stream(GateId.values()).filter(g -> !g.diagnosed()).count();
        assertEquals(GateId.values().length, diagnosed + exempt);
        assertEquals(16, diagnosed, "已接诊断的闸数");
        assertEquals(3, exempt, "登记不接的闸数");
        for (GateId g : GateId.values()) {
            if (!g.diagnosed()) {
                assertFalse(g.exemptReason().isBlank(), g + " 登记不接必须写理由");
            }
        }
    }

    // ---------- 待检 / 质检放行 ----------

    @Test
    void semiPendingReportsQuantitiesAndReleasePage() {
        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .intermediateBatchNo("SF-001").unit("kg")
                .producedQuantity(new BigDecimal("100")).consumedQuantity(BigDecimal.ZERO)
                .inspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING)
                .inspectionPendingQuantity(new BigDecimal("60")).build();
        BusinessException e = assertThrows(BusinessException.class,
                () -> QcInspectionGuard.assertSemiDrawable(sfi, new BigDecimal("50"), "x"));
        GateDiagnosis d = only(e);
        assertEquals(GateId.SEMI_QC_PENDING, d.gate());
        assertEquals("SF-001", d.subjectRef());
        assertEquals(0, new BigDecimal("50").compareTo(d.required()));
        assertEquals(0, new BigDecimal("40").compareTo(d.available()));
        assertEquals(0, new BigDecimal("10").compareTo(d.shortBy()));
        assertEquals(GateRoutes.QC_RELEASE, d.resolveRoute());
        assertTrue(d.howToResolve().contains("SF-001"));
    }

    @Test
    void semiRejectedAndWipAndFgPending() {
        SemiFinishedInventory rej = SemiFinishedInventory.builder().intermediateBatchNo("SF-R")
                .inspectionStatus(SemiFinishedInventory.InspectionStatus.REJECTED).build();
        assertEquals(GateId.SEMI_QC_REJECTED,
                only(assertThrows(BusinessException.class, () -> QcInspectionGuard.assertSemiDrawable(rej, "x"))).gate());

        MaterialBatch wip = new MaterialBatch();
        wip.setSourceDocType(QcInspectionGuard.WIP_SOURCE_DOC_TYPE);
        wip.setStatus(MaterialBatchStatus.INSPECTING);
        GateDiagnosis w = only(assertThrows(BusinessException.class,
                () -> QcInspectionGuard.assertWipBatchDrawable(wip, "WIP-1", "x")));
        assertEquals(GateId.WIP_QC, w.gate());
        assertEquals("WIP-1", w.subjectRef());
        assertEquals(GateRoutes.QC_RELEASE, w.resolveRoute());

        wip.setStatus(MaterialBatchStatus.DEFECTIVE);
        assertEquals(QcInspectionGuard.WIP_REJECTED, only(assertThrows(BusinessException.class,
                () -> QcInspectionGuard.assertWipBatchDrawable(wip, "WIP-1", "x"))).code());

        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setBatchNumber("FG-P");
        fg.setStatus(FinishedGoodsBatch.Status.PENDING_INSPECTION);
        GateDiagnosis f = only(assertThrows(BusinessException.class,
                () -> QcInspectionGuard.assertFinishedGoodsFeedable(fg, "x")));
        assertEquals(GateId.FG_QC_PENDING, f.gate());
        assertEquals("FG-P", f.subjectRef());
    }

    // ---------- 过期 / 状态不可售: 归因 (过期批不能被诊成状态不可售, 反之亦然) ----------

    @Test
    void expiredFinishedGoodsIsAttributedToExpiryGateNotStatusGate() {
        LocalDate today = LocalDate.of(2026, 11, 3);
        FinishedGoodsBatch expired = new FinishedGoodsBatch();
        expired.setBatchNumber("FG-E");
        expired.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        expired.setExpireDate(today.minusDays(1));
        GateDiagnosis d = only(assertThrows(BusinessException.class,
                () -> FinishedGoodsBatchSaleabilityGuard.assertSaleable(expired, "x", today)));
        assertEquals(GateId.FG_EXPIRED, d.gate());
        assertEquals("FG-E", d.subjectRef());
        assertTrue(d.howToResolve().contains("过期"));

        FinishedGoodsBatch frozen = new FinishedGoodsBatch();
        frozen.setBatchNumber("FG-F");
        frozen.setStatus(FinishedGoodsBatch.Status.FROZEN);
        frozen.setExpireDate(today.minusDays(1));
        assertEquals(GateId.FG_NOT_SALEABLE, only(assertThrows(BusinessException.class,
                () -> FinishedGoodsBatchSaleabilityGuard.assertSaleable(frozen, "x", today))).gate());
    }

    @Test
    void materialBatchStateDiagnosisFollowsStatus() {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber("RM-1");
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        assertTrue(MaterialBatchStateGate.diagnose(mb).isEmpty(), "放行的批次不能有诊断");
        mb.setStatus(MaterialBatchStatus.INSPECTING);
        assertEquals(GateRoutes.QC_RELEASE, MaterialBatchStateGate.diagnose(mb).orElseThrow().resolveRoute());
        mb.setStatus(MaterialBatchStatus.EXPIRED);
        GateDiagnosis d = MaterialBatchStateGate.diagnose(mb).orElseThrow();
        assertEquals("MATERIAL_BATCH_EXPIRED", d.code());
        assertTrue(d.howToResolve().contains("过期"));
    }

    // ---------- 其余闸: 每个拒绝点 (withCode) 都必须带诊断, 且用的是本闸的 GateId ----------

    private record Wiring(String file, GateId gate, int sites) {}

    @Test
    void everyRejectionSiteOfTheRemainingGatesCarriesItsOwnDiagnosis() throws Exception {
        Path root = Path.of("src/main/java/com/cretas/aims/service");
        List<Wiring> wirings = List.of(
                new Wiring("processentry/IntermediateOwnershipGuard.java", GateId.INTERMEDIATE_OWNERSHIP, 1),
                new Wiring("processentry/ProductionInventoryOwnershipGuard.java", GateId.MATERIAL_OWNERSHIP, 2),
                new Wiring("sales/SalesFinishedGoodsOwnershipGuard.java", GateId.SALES_FG_OWNERSHIP, 2),
                new Wiring("workprocess/WorkflowStepUnlockGuard.java", GateId.WORKFLOW_UPSTREAM, 1),
                new Wiring("shortage/ShortageStartGate.java", GateId.SHORTAGE_START, 1),
                new Wiring("workflow/BlendRatioCheck.java", GateId.BLEND_RATIO, 2),
                new Wiring("wip/impl/WipInventoryServiceImpl.java", GateId.INSUFFICIENT_BALANCE, 2),
                new Wiring("inventory/impl/FinishedGoodsFeedServiceImpl.java", GateId.INSUFFICIENT_BALANCE, 1),
                new Wiring("workflow/ReworkRatioCheck.java", GateId.REWORK_RATIO, 2),
                new Wiring("foodsafety/timelimit/ProcessTimeLimitService.java", GateId.TIME_LIMIT, 2));
        Pattern p = Pattern.compile(
                "withDiagnosis\\(\\s*(?:[\\w.]*\\.)?GateDiagnosis\\.of\\(\\s*(?:[\\w.]*\\.)?GateId\\.(\\w+)");
        for (Wiring w : wirings) {
            String src = Files.readString(root.resolve(w.file()));
            Matcher m = p.matcher(src);
            int n = 0;
            while (m.find()) {
                assertEquals(w.gate().name(), m.group(1), w.file() + " 的诊断必须是本闸的 id");
                n++;
            }
            assertEquals(w.sites(), n, w.file() + " 拒绝点数 = 带诊断的点数");
        }
    }
}
