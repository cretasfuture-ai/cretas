package com.cretas.aims.service.yield;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纪律排查 A10: 报工超收「强制提交」必须写原因并留痕。端到端 (经 YieldReportServiceImpl.submitReport) 的用例在
 * YieldReportServiceImplTest.submitReport_overLimit_force*; 这里钉住判定本身 (PR 快速门禁会跑)。
 */
class OverReceiptForceReasonContractTest {

    private static final BigDecimal TARGET = new BigDecimal("100");
    private static final BigDecimal MAX = new BigDecimal("130");
    private static final BigDecimal CUMULATIVE = new BigDecimal("131");

    @Test
    void noReasonOrBlankReasonIsRefusedWithACodeTheAppRecognises() {
        for (String blank : new String[]{null, "", "   ", "　"}) {
            assertThatThrownBy(() -> OverReceiptForceReason.auditOrRefuse(blank, TARGET, MAX, CUMULATIVE, "kg"))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getErrorCode()).isEqualTo(OverReceiptForceReason.REASON_REQUIRED);
                        assertThat(e.getActionHint()).contains("原因");
                    });
        }
    }

    @Test
    void aGivenReasonIsKeptWithTheNumbersItExplains() {
        Map<String, Object> audit = OverReceiptForceReason.auditOrRefuse(" 原料出成偏高 ", TARGET, MAX, CUMULATIVE, "kg");

        assertThat(audit).containsEntry("reason", "原料出成偏高").containsEntry("unit", "kg");
        assertThat((BigDecimal) audit.get("cumulative")).isEqualByComparingTo("131");
        assertThat((BigDecimal) audit.get("maxAllowed")).isEqualByComparingTo("130");
        assertThat((BigDecimal) audit.get("target")).isEqualByComparingTo("100");
    }

    @Test
    void anOverlongReasonIsCutNotRejected() {
        String longReason = "超".repeat(OverReceiptForceReason.MAX_REASON_LENGTH + 20);
        assertThat((String) OverReceiptForceReason.auditOrRefuse(longReason, TARGET, MAX, CUMULATIVE, "kg").get("reason"))
                .hasSize(OverReceiptForceReason.MAX_REASON_LENGTH);
    }
}
