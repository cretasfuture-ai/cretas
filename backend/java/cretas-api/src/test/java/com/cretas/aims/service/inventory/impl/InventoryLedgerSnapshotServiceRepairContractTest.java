package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.InventoryLedgerLineDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.SnapshotType;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.inventory.InventoryLedgerSnapshot;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.inventory.InventoryLedgerSnapshotRepository;
import com.cretas.aims.service.inventory.InventoryLedgerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryLedgerSnapshotServiceRepairContractTest {

    private static final String FACTORY = "F006";
    private static final String PERIOD_ID = "06a465b6-9e2f-4f26-8eef-5913d12f6060";
    private static final String PREVIOUS_PERIOD_ID = "aeef462b-8288-4986-bc94-afe39feee64c";

    @Mock InventoryLedgerSnapshotRepository snapshotRepo;
    @Mock AccountingPeriodRepository periodRepo;
    @Mock RawMaterialTypeRepository materialTypeRepo;
    @Mock InventoryLedgerService ledgerService;
    @Mock EntityManager entityManager;

    private InventoryLedgerSnapshotServiceImpl service;
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        service = new InventoryLedgerSnapshotServiceImpl(
                snapshotRepo, periodRepo, materialTypeRepo, ledgerService, entityManager);
        period = new AccountingPeriod();
        period.setId(PERIOD_ID);
        period.setFactoryId(FACTORY);
        period.setYear(2026);
        period.setMonth(3);
        period.setStatus(AccountingPeriod.Status.CLOSED);
        org.mockito.Mockito.lenient().when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.of(period));
        org.mockito.Mockito.lenient().when(entityManager.find(AccountingPeriod.class, PERIOD_ID, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(period);
    }

    @Test
    void repairsMissingClosedPeriodAndReturnsCreatedMaterialIdsIncludingUnknownAmount() {
        InventoryLedgerLineDTO line = line("MAT-UNKNOWN", "10", null, null);
        stubValidPreviousState("MAT-UNKNOWN", "kg", "10");
        when(snapshotRepo.findLatestBeforePeriod(
                FACTORY, "MAT-UNKNOWN", SnapshotType.PERIOD_CLOSE, 202604))
                .thenReturn(List.of(snapshot("MAT-UNKNOWN", PREVIOUS_PERIOD_ID, "kg", "10")));
        when(ledgerService.getLedger(FACTORY, LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), null,
                InventoryLedgerService.LedgerReadMode.STRICT_REPAIR)).thenReturn(List.of(line));
        when(snapshotRepo.save(org.mockito.ArgumentMatchers.any(InventoryLedgerSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.existing()).isZero();
        assertThat(result.materialTypeIds()).containsExactly("MAT-UNKNOWN");
        var saved = org.mockito.ArgumentCaptor.forClass(InventoryLedgerSnapshot.class);
        verify(snapshotRepo).save(saved.capture());
        assertThat(saved.getValue().getMaterialTypeId()).isEqualTo("MAT-UNKNOWN");
        assertThat(saved.getValue().getClosingQty()).isEqualByComparingTo("10");
        assertThat(saved.getValue().getClosingAmount()).isNull();
        verify(entityManager).find(AccountingPeriod.class, PERIOD_ID, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void repairUsesCoherentRepeatableReadAndFreshPeriodLock() throws Exception {
        Transactional transactional = InventoryLedgerSnapshotServiceImpl.class
                .getMethod("repairMissingInventorySnapshots", String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        verify(entityManager, org.mockito.Mockito.never()).find(
                AccountingPeriod.class, PERIOD_ID, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void existingSnapshotsAreReturnedWithoutLedgerReadOrOverwrite() {
        InventoryLedgerSnapshot first = snapshot("MAT-1", PERIOD_ID, "kg", "1");
        InventoryLedgerSnapshot second = snapshot("MAT-2", PERIOD_ID, "kg", "2");
        stubPreviousPeriod();
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot("MAT-1", PREVIOUS_PERIOD_ID, "kg", "1"),
                        snapshot("MAT-2", PREVIOUS_PERIOD_ID, "kg", "2")));
        when(materialTypeRepo.findByFactoryId(FACTORY))
                .thenReturn(List.of(material("MAT-1", "kg"), material("MAT-2", "kg")));
        when(ledgerService.getLedger(eq(FACTORY), eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31)), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR)))
                .thenReturn(List.of(line("MAT-1", "1", null, null), line("MAT-2", "2", null, null)));
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PERIOD_ID, SnapshotType.PERIOD_CLOSE)).thenReturn(List.of(first, second));

        var result = service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID);

        assertThat(result.created()).isZero();
        assertThat(result.existing()).isEqualTo(2);
        assertThat(result.materialTypeIds()).containsExactly("MAT-1", "MAT-2");
        verify(entityManager).clear();
        verify(ledgerService).getLedger(eq(FACTORY), eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31)), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR));
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsPartialExistingTargetInsteadOfReportingIdempotentCompletion() {
        stubPreviousPeriod();
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot("MAT-1", PREVIOUS_PERIOD_ID, "kg", "1"),
                        snapshot("MAT-2", PREVIOUS_PERIOD_ID, "kg", "2")));
        when(materialTypeRepo.findByFactoryId(FACTORY))
                .thenReturn(List.of(material("MAT-1", "kg"), material("MAT-2", "kg")));
        when(ledgerService.getLedger(eq(FACTORY), eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31)), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR)))
                .thenReturn(List.of(line("MAT-1", "1", null, null), line("MAT-2", "2", null, null)));
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot("MAT-1", PERIOD_ID, "kg", "1")));

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("complete validated material set");
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsExistingClosingMismatchWithoutOverwritingIt() {
        stubPreviousPeriod();
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot("MAT-1", PREVIOUS_PERIOD_ID, "kg", "1")));
        when(materialTypeRepo.findByFactoryId(FACTORY)).thenReturn(List.of(material("MAT-1", "kg")));
        InventoryLedgerLineDTO mismatch = line("MAT-1", "2", null, null);
        mismatch.setOpeningQty(new BigDecimal("1"));
        when(ledgerService.getLedger(eq(FACTORY), eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31)), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR)))
                .thenReturn(List.of(mismatch));
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot("MAT-1", PERIOD_ID, "kg", "1")));

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the validated ledger");
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsOpenPeriodBeforeCheckingSnapshots() {
        period.setStatus(AccountingPeriod.Status.OPEN);

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOSED");
        verify(snapshotRepo, never()).findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingPeriodBeforeAttemptingToLock() {
        when(periodRepo.findById(PERIOD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        verify(entityManager, never()).find(org.mockito.ArgumentMatchers.eq(AccountingPeriod.class),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(LockModeType.class));
    }

    @Test
    void rejectsEmptyLedgerInsteadOfReportingSuccessfulRepair() {
        stubValidPreviousState("MAT-UNKNOWN", "kg", "10");
        when(ledgerService.getLedger(eq(FACTORY), eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31)), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR))).thenReturn(List.of());

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no rows");
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void propagatesLedgerSourceErrorWithoutCreatingSnapshots() {
        stubValidPreviousState("MAT-UNKNOWN", "kg", "10");
        when(ledgerService.getLedger(eq(FACTORY), eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31)), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR)))
                .thenThrow(new IllegalStateException("ledger source unavailable"));

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger source unavailable");
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingPreviousSnapshotSetBeforeReadingLedger() {
        stubPreviousPeriod();
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE)).thenReturn(List.of());
        when(materialTypeRepo.findByFactoryId(FACTORY)).thenReturn(List.of(material("MAT-1", "kg")));

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not match");
        verify(ledgerService, never()).getLedger(eq(FACTORY), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR));
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsPartialDuplicateAndIncompletePreviousRowsBeforeReadingLedger() {
        stubPreviousPeriod();
        when(materialTypeRepo.findByFactoryId(FACTORY))
                .thenReturn(List.of(material("MAT-1", "kg"), material("MAT-2", "kg")));

        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot("MAT-1", PREVIOUS_PERIOD_ID, "kg", "1")));
        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class);

        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot("MAT-1", PREVIOUS_PERIOD_ID, "kg", "1"),
                        snapshot("MAT-1", PREVIOUS_PERIOD_ID, "kg", "1")));
        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");

        InventoryLedgerSnapshot missingUnit = snapshot("MAT-1", PREVIOUS_PERIOD_ID, null, "1");
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE)).thenReturn(List.of(missingUnit));
        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");

        InventoryLedgerSnapshot missingQty = snapshot("MAT-1", PREVIOUS_PERIOD_ID, "kg", null);
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE)).thenReturn(List.of(missingQty));
        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
        verify(ledgerService, never()).getLedger(eq(FACTORY), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR));
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsLedgerOpeningMismatchBeforeCreatingTargetSnapshots() {
        stubValidPreviousState("MAT-UNKNOWN", "kg", "10");
        InventoryLedgerLineDTO mismatch = line("MAT-UNKNOWN", "9", null, null);
        when(ledgerService.getLedger(eq(FACTORY), eq(LocalDate.of(2026, 3, 1)),
                eq(LocalDate.of(2026, 3, 31)), isNull(),
                eq(InventoryLedgerService.LedgerReadMode.STRICT_REPAIR))).thenReturn(List.of(mismatch));

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("opening quantity");
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsNullClosingQuantityBeforeSavingAnyOfTheValidatedRows() {
        stubPreviousPeriod();
        when(materialTypeRepo.findByFactoryId(FACTORY))
                .thenReturn(List.of(material("MAT-1", "kg"), material("MAT-2", "kg")));
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot("MAT-1", PREVIOUS_PERIOD_ID, "kg", "5"),
                        snapshot("MAT-2", PREVIOUS_PERIOD_ID, "kg", "7")));
        InventoryLedgerLineDTO valid = line("MAT-1", "5", null, null);
        InventoryLedgerLineDTO incomplete = line("MAT-2", "7", null, null);
        incomplete.setClosingQty(null);
        when(ledgerService.getLedger(FACTORY, LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), null,
                InventoryLedgerService.LedgerReadMode.STRICT_REPAIR)).thenReturn(List.of(valid, incomplete));

        assertThatThrownBy(() -> service.repairMissingInventorySnapshots(FACTORY, PERIOD_ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
        verify(snapshotRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private void stubPreviousPeriod() {
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PERIOD_ID, SnapshotType.PERIOD_CLOSE)).thenReturn(List.of());
        AccountingPeriod previous = new AccountingPeriod();
        previous.setId(PREVIOUS_PERIOD_ID);
        previous.setFactoryId(FACTORY);
        previous.setYear(2026);
        previous.setMonth(2);
        previous.setStatus(AccountingPeriod.Status.CLOSED);
        when(periodRepo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, 2026, 2))
                .thenReturn(Optional.of(previous));
    }

    private void stubValidPreviousState(String materialTypeId, String unit, String closingQty) {
        stubPreviousPeriod();
        when(snapshotRepo.findByFactoryIdAndAccountingPeriodIdAndSnapshotTypeAndDeletedAtIsNull(
                FACTORY, PREVIOUS_PERIOD_ID, SnapshotType.PERIOD_CLOSE))
                .thenReturn(List.of(snapshot(materialTypeId, PREVIOUS_PERIOD_ID, unit, closingQty)));
        when(materialTypeRepo.findByFactoryId(FACTORY)).thenReturn(List.of(material(materialTypeId, unit)));
    }

    private static InventoryLedgerLineDTO line(String materialTypeId, String qty,
                                                String amount, String unitPrice) {
        InventoryLedgerLineDTO line = new InventoryLedgerLineDTO();
        line.setMaterialTypeId(materialTypeId);
        line.setMaterialCode("CODE-" + materialTypeId);
        line.setMaterialName("测试物料");
        line.setUnit("kg");
        line.setOpeningQty(new BigDecimal(qty));
        line.setClosingQty(new BigDecimal(qty));
        line.setClosingAmount(amount == null ? null : new BigDecimal(amount));
        line.setMovingAvgUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return line;
    }

    private static RawMaterialType material(String materialTypeId, String unit) {
        RawMaterialType material = new RawMaterialType();
        material.setId(materialTypeId);
        material.setFactoryId(FACTORY);
        material.setUnit(unit);
        return material;
    }

    private static InventoryLedgerSnapshot snapshot(String materialTypeId) {
        return snapshot(materialTypeId, PERIOD_ID, "kg", "10");
    }

    private static InventoryLedgerSnapshot snapshot(String materialTypeId, String periodId,
                                                     String unit, String closingQty) {
        InventoryLedgerSnapshot snapshot = new InventoryLedgerSnapshot();
        snapshot.setAccountingPeriodId(periodId);
        snapshot.setFactoryId(FACTORY);
        snapshot.setMaterialTypeId(materialTypeId);
        snapshot.setUnit(unit);
        snapshot.setClosingQty(closingQty == null ? null : new BigDecimal(closingQty));
        snapshot.setSnapshotType(SnapshotType.PERIOD_CLOSE);
        return snapshot;
    }
}
