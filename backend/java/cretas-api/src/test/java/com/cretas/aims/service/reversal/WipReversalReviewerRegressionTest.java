package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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

    private SemiFinishedInventoryTransaction movement(Long id, String factoryId, Long sfiId,
                                                       Long reportId, String txnType,
                                                       String sourceType, String quantity) {
        return SemiFinishedInventoryTransaction.builder().id(id).factoryId(factoryId)
                .semiFinishedId(sfiId).reportId(reportId).txnType(txnType)
                .sourceType(sourceType).quantity(new BigDecimal(quantity)).build();
    }

    @Test
    void splitCompensationIsRejectedBecauseOriginalLedgerRowsAreImmutable() {
        var candidates = List.of(reverse("1", "REVERSAL", 9L), reverse("2", "REVERSAL", 9L));
        var original = SemiFinishedInventoryTransaction.builder().id(19L).semiFinishedId(7L).reportId(9L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT).quantity(new BigDecimal("-3")).build();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "matchExistingCompensations",
                List.of(original), candidates))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("PARTIAL_COMPENSATION"));
    }

    @Test
    void partialAndOverCompensationFailClosed() {
        var original = SemiFinishedInventoryTransaction.builder().id(19L).semiFinishedId(7L).reportId(9L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT).quantity(new BigDecimal("-3")).build();
        for (List<SemiFinishedInventoryTransaction> rows : List.of(
                new ArrayList<>(List.of(reverse("2", "REVERSAL", 9L))),
                new ArrayList<>(List.of(reverse("4", "REVERSAL", 9L))))) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "matchExistingCompensations",
                    List.of(original), rows)).isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                            .isEqualTo("PARTIAL_COMPENSATION"));
        }
    }

    @Test
    void nonReversalSourceCannotCompensate() {
        var candidates = List.of(reverse("3", "STOCKTAKE", 9L));
        var original = SemiFinishedInventoryTransaction.builder().id(19L).semiFinishedId(7L).reportId(9L)
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT).quantity(new BigDecimal("-3")).build();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "matchExistingCompensations",
                List.of(original), candidates))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("PARTIAL_COMPENSATION"));
    }

    @Test
    void duplicateAndDifferentQuantityFullCompensationAreConsumedOneForOne() {
        var duplicate = List.of(
                reverse("2", "REVERSAL", 9L), reverse("2", "REVERSAL", 9L));
        var outTwo = movement(20L, "F1", 7L, 9L, "OUT", "SECONDARY_CONSUME", "-2");
        var outTwoDuplicate = movement(22L, "F1", 7L, 9L, "OUT", "SECONDARY_CONSUME", "-2");
        assertThat((java.util.Set<Long>) ReflectionTestUtils.invokeMethod(service,
                "matchExistingCompensations", List.of(outTwo, outTwoDuplicate), duplicate))
                .containsExactlyInAnyOrder(20L, 22L);

        var different = List.of(
                reverse("2", "REVERSAL", 9L), reverse("1", "REVERSAL", 9L));
        var outOne = movement(21L, "F1", 7L, 9L, "OUT", "SECONDARY_CONSUME", "-1");
        assertThat((java.util.Set<Long>) ReflectionTestUtils.invokeMethod(service,
                "matchExistingCompensations", List.of(outTwo, outOne), different))
                .containsExactlyInAnyOrder(20L, 21L);
    }

    @Test
    void nullCostOnLegitimateReversalDoesNotBreakIdempotency() {
        var reverseTwo = reverse("2", "REVERSAL", 9L);
        reverseTwo.setUnitCostAtTxn(null);
        var reverseThree = reverse("3", "REVERSAL", 9L);
        reverseThree.setUnitCostAtTxn(new BigDecimal("999.0000"));
        var originalThree = movement(20L, "F1", 7L, 9L, "OUT", "SECONDARY_CONSUME", "-3");
        originalThree.setUnitCostAtTxn(new BigDecimal("10.0000"));
        var originalTwo = movement(21L, "F1", 7L, 9L, "OUT", "SECONDARY_CONSUME", "-2");
        originalTwo.setUnitCostAtTxn(new BigDecimal("11.0000"));
        assertThat((java.util.Set<Long>) ReflectionTestUtils.invokeMethod(service,
                "matchExistingCompensations", List.of(originalThree, originalTwo),
                List.of(reverseTwo, reverseThree)))
                .containsExactlyInAnyOrder(20L, 21L);
    }

    @Test
    void unorderedSnapshotPassesButNullDuplicateAndChangedRowsFailClosed() {
        var one = movement(1L, "F1", 7L, 9L, "IN", "PRODUCTION_OUTPUT", "2");
        var two = movement(2L, "F1", 8L, 9L, "OUT", "SECONDARY_CONSUME", "-1");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "sameTransactionSnapshot",
                List.of(one, two), List.of(two, one))).isTrue();

        var nullId = movement(null, "F1", 7L, 9L, "IN", "PRODUCTION_OUTPUT", "2");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "sameTransactionSnapshot",
                List.of(nullId), List.of(nullId))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "sameTransactionSnapshot",
                List.of(one, one), List.of(one, one))).isFalse();

        var changed = movement(1L, "F1", 7L, 9L, "IN", "PRODUCTION_OUTPUT", "3");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "sameTransactionSnapshot",
                List.of(one), List.of(changed))).isFalse();
    }

    @Test
    void crossBatchSfiAndTaskNotOwnedByReportAreRejected() {
        var reportRow = ProductionReport.builder().id(9L).factoryId("F1").batchId(10L)
                .workProcessTaskId(30L).build();
        var foreignSfi = SemiFinishedInventory.builder().id(7L).factoryId("F1").batchId(11L).build();
        when(wip.findByIdForUpdate(7L)).thenReturn(Optional.of(foreignSfi));
        var in = movement(1L, "F1", 7L, 9L, "IN", "PRODUCTION_OUTPUT", "2");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service,
                "validateReportWipTransactions", "F1", 10L, List.of(reportRow), Map.of(9L, List.of(in))))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("WIP_SCOPE_INVALID"));

        var localSfi = SemiFinishedInventory.builder().id(7L).factoryId("F1").batchId(10L).build();
        when(wip.findByIdForUpdate(7L)).thenReturn(Optional.of(localSfi));
        when(tasks.findByFactoryIdAndId("F1", 31L)).thenReturn(Optional.of(
                WorkProcessTask.builder().id(31L).factoryId("F1").productionBatchId(10L).build()));
        var out = movement(2L, "F1", 7L, 9L, "OUT", "SECONDARY_CONSUME", "-1");
        out.setSourceRef("TASK-31");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service,
                "validateReportWipTransactions", "F1", 10L, List.of(reportRow), Map.of(9L, List.of(out))))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo("WIP_SCOPE_INVALID"));
    }

    @Test
    void unknownAndStocktakeReportRowsFailClosed() {
        var reportRow = ProductionReport.builder().id(9L).factoryId("F1").batchId(10L).build();
        var localSfi = SemiFinishedInventory.builder().id(7L).factoryId("F1").batchId(10L).build();
        when(wip.findByIdForUpdate(7L)).thenReturn(Optional.of(localSfi));
        for (SemiFinishedInventoryTransaction row : List.of(
                movement(1L, "F1", 7L, 9L, "REVERSE", "STOCKTAKE", "2"),
                movement(2L, "F1", 7L, 9L, "MYSTERY", "REVERSAL", "2"))) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service,
                    "validateReportWipTransactions", "F1", 10L, List.of(reportRow), Map.of(9L, List.of(row))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void residualWipAfterReplayCannotBeMarkedDone() {
        var residual = SemiFinishedInventory.builder().id(7L).factoryId("F1").batchId(10L)
                .producedQuantity(new BigDecimal("5")).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("5")).adjustmentQuantity(BigDecimal.ZERO)
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service,
                "validateFinalWipState", residual, true)).isInstanceOf(BusinessException.class);

        residual.setProducedQuantity(BigDecimal.ZERO);
        residual.setAvailableQuantity(BigDecimal.ZERO);
        residual.setStatus(SemiFinishedInventory.Status.DEPLETED);
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(service,
                "validateFinalWipState", residual, true)).doesNotThrowAnyException();
    }
}
