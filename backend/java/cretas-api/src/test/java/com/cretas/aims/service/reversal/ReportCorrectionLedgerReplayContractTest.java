package com.cretas.aims.service.reversal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报工数量更正流水的消费者契约：只承认专用来源，不能把普通盘点 ADJUST
 * 偷渡进撤回或成本回放。
 */
class ReportCorrectionLedgerReplayContractTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java");

    private static String source() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    void replayRecognizesOnlyDedicatedCorrectionAdjustAndKeepsCostSeparate() throws IOException {
        String source = source();
        assertThat(source).contains("TxnType.ADJUST", "WipLedgerEntries.REPORT_CORRECTION_SOURCE_TYPE",
                "producedQty = producedQty.add(q)", "ledgerInTotal = ledgerInTotal.add(q)",
                "correctionUnitCostSnapshot", "balanceCostAfter");
        assertThat(source.indexOf("WipLedgerEntries.REPORT_CORRECTION_SOURCE_TYPE"))
                .isLessThan(source.indexOf("producedQty = producedQty.add(q)"));
        assertThat(source).contains("原 IN/REVERSE 的总成本与未知成本标记保持原口径");
    }

    @Test
    void directReversalHasAnActionableCorrectionConflict() throws IOException {
        String source = source();
        assertThat(source).contains("WIP_CORRECTION_REVERSAL_CONFLICT",
                "请继续使用已审批的报工修正流程", ".withHintTarget(\"报工修正审批\")");
    }

    @Test
    void ordinaryAdjustStillUsesExistingMalformedLedgerBoundary() throws IOException {
        String source = source();
        int dedicated = source.indexOf("WipLedgerEntries.REPORT_CORRECTION_SOURCE_TYPE");
        int malformed = source.indexOf("报工关联 ADJUST 不属于撤回集合");
        assertThat(dedicated).isGreaterThan(0);
        assertThat(malformed).isGreaterThan(dedicated);
        assertThat(source).contains("WIP_LEDGER_INVALID");
    }

    @Test
    void precisionGuardAndWipCostSnapshotAreWiredBeforeMutation() throws IOException {
        String correction = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/service/impl/ProductionReportCorrectionServiceImpl.java"),
                StandardCharsets.UTF_8);
        String wip = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java"),
                StandardCharsets.UTF_8);
        String entries = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/service/wip/WipLedgerEntries.java"),
                StandardCharsets.UTF_8);
        assertThat(correction).contains("PRODUCTION_REPORT_CORRECTION_PRECISION_UNSUPPORTED",
                "系统不会静默四舍五入");
        assertThat(wip).contains("divide(produced, 4, RoundingMode.HALF_UP)",
                "WIP_CORRECTION_PRECISION_UNSUPPORTED");
        assertThat(entries).contains("balanceCostAfter(sfi.getUnitCost())");
    }
}
