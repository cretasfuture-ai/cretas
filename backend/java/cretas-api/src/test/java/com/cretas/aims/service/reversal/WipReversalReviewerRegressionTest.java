package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reviewer regressions for WIP reversal provenance and compensation multiset handling. */
@ExtendWith(MockitoExtension.class)
class WipReversalReviewerRegressionTest {
    @Mock private com.cretas.aims.repository.ReportReversalLogRepository log;
    @Mock private com.cretas.aims.repository.ProductionBatchRepository batch;
    @Mock private com.cretas.aims.repository.ProductionReportRepository report;
    @Mock private com.cretas.aims.repository.SemiFinishedInventoryRepository wip;
    @Mock private com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository txn;
    @Mock private com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository fgb;
    @Mock private com.cretas.aims.repository.UserRepository users;
    @Mock private com.cretas.aims.repository.workprocess.WorkProcessTaskRepository tasks;
    @Mock private com.cretas.aims.service.NotificationService notifications;
    @InjectMocks private ReportReversalServiceImpl service;

    private SemiFinishedInventoryTransaction reverse(String quantity, String sourceType, Long reportId) {
        return SemiFinishedInventoryTransaction.builder().id((long) quantity.hashCode())
                .semiFinishedId(7L).reportId(reportId).txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(sourceType).quantity(new BigDecimal(quantity)).build();
    }

    @Test
    void splitCompensationExactlyConsumesMultiset() {
        var candidates = new ArrayList<>(List.of(reverse("1", "REVERSAL", 9L), reverse("2", "REVERSAL", 9L)));
        var original = SemiFinishedInventoryTransaction.builder().semiFinishedId(7L).reportId(9L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT).quantity(new BigDecimal("-3")).build();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "consumeMatchingReverse", candidates,
                original, 9L, new BigDecimal("3"))).isTrue();
        assertThat(candidates).isEmpty();
    }

    @Test
    void partialAndOverCompensationFailClosed() {
        var original = SemiFinishedInventoryTransaction.builder().semiFinishedId(7L).reportId(9L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT).quantity(new BigDecimal("-3")).build();
        for (List<SemiFinishedInventoryTransaction> rows : List.of(
                new ArrayList<>(List.of(reverse("2", "REVERSAL", 9L))),
                new ArrayList<>(List.of(reverse("4", "REVERSAL", 9L))))) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "consumeMatchingReverse", rows,
                    original, 9L, new BigDecimal("3"))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void nonReversalSourceCannotCompensate() {
        var candidates = new ArrayList<>(List.of(reverse("3", "STOCKTAKE", 9L)));
        var original = SemiFinishedInventoryTransaction.builder().semiFinishedId(7L).reportId(9L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT).quantity(new BigDecimal("-3")).build();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "consumeMatchingReverse", candidates,
                original, 9L, new BigDecimal("3"))).isFalse();
        assertThat(candidates).hasSize(1);
    }
}
