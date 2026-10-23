package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WipOutputCorrectionContractTest {
    @Mock SemiFinishedInventoryRepository wipRepo;
    @Mock SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock ProductionReportRepository reportRepo;
    @Mock BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock WorkProcessTaskRepository taskRepo;
    @Mock WorkProcessRepository workProcessRepo;
    @Mock ProductTypeRepository productTypeRepo;
    @Mock ProductProcessWorkflowRepository productProcessWorkflowRepo;
    @Mock ProductionWorkflowInstanceRepository workflowInstanceRepo;
    @Mock WorkflowTaskPortRepository workflowTaskPortRepo;
    @Mock ProductFamilyResolver productFamilyResolver;
    @Mock ApplicationEventPublisher eventPublisher;

    private WipInventoryServiceImpl service;
    private ProductionReport report;
    private WorkProcessTask task;
    private SemiFinishedInventory wip;
    private SemiFinishedInventoryTransaction originalIn;

    @BeforeEach
    void setUp() {
        service = new WipInventoryServiceImpl(wipRepo, txnRepo, reportRepo, lineageEdgeRepo, taskRepo,
                workProcessRepo, productTypeRepo, workflowInstanceRepo, workflowTaskPortRepo,
                productFamilyResolver, eventPublisher);
        report = ProductionReport.builder().id(23989L).factoryId("F006").outputQuantity(new BigDecimal("0.81"))
                .outputUnit("kg").reportKind("OUTPUT").outputKind(null).semiOutputs(null).build();
        task = new WorkProcessTask();
        task.setId(1989L);
        task.setFactoryId("F006");
        task.setProductTypeId("SKU-1");
        task.setProductionBatchId(2398L);
        task.setProcessOrder(1);
        task.setPlannedUnit("kg");
        wip = SemiFinishedInventory.builder().id(383L).factoryId("F006")
                .intermediateBatchNo("SKU-1-B2398-S1-1989").sourceWorkProcessTaskId(1989L)
                .producedQuantity(new BigDecimal("0.81")).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("0.81")).adjustmentQuantity(BigDecimal.ZERO)
                .unit("kg").unitCost(new BigDecimal("12.50"))
                .accumulatedCost(new BigDecimal("10.125"))
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        originalIn = SemiFinishedInventoryTransaction.builder().id(7001L).factoryId("F006")
                .semiFinishedId(383L).txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .sourceRef(wip.getIntermediateBatchNo()).quantity(new BigDecimal("0.81"))
                .reportId(23989L).build();
        lenient().when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                "F006", wip.getIntermediateBatchNo())).thenReturn(Optional.of(wip));
        lenient().when(txnRepo.findByFactoryIdAndReportId("F006", 23989L)).thenReturn(List.of(originalIn));
        lenient().when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(383L)).thenReturn(List.of(originalIn));
        lenient().when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(txnRepo.save(any(SemiFinishedInventoryTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void safeIncreaseAndDecreaseAdjustProducedAvailableAndAppendSignedCorrection() {
        service.correctApprovedOutputQuantity("F006", report, task,
                new BigDecimal("0.81"), new BigDecimal("0.82"), "COR-UP", 7L);

        assertThat(wip.getProducedQuantity()).isEqualByComparingTo("0.82");
        assertThat(wip.getAvailableQuantity()).isEqualByComparingTo("0.82");
        assertThat(wip.getUnitCost()).isNull();
        verify(txnRepo).save(org.mockito.ArgumentMatchers.argThat(txn ->
                WipLedgerEntries.REPORT_CORRECTION_SOURCE_TYPE.equals(txn.getSourceType())
                        && txn.getQuantity().compareTo(new BigDecimal("0.01")) == 0
                        && txn.getSourceRef().contains("COR-UP")
                        && txn.getUnitCostAtTxn() == null
                        && txn.getReportId().equals(23989L)));

        wip.setProducedQuantity(new BigDecimal("0.81"));
        wip.setAvailableQuantity(new BigDecimal("0.81"));
        wip.setUnitCost(new BigDecimal("12.50"));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(383L)).thenReturn(List.of(originalIn));
        service.correctApprovedOutputQuantity("F006", report, task,
                new BigDecimal("0.81"), new BigDecimal("0.80"), "COR-DOWN", 7L);

        assertThat(wip.getProducedQuantity()).isEqualByComparingTo("0.80");
        assertThat(wip.getAvailableQuantity()).isEqualByComparingTo("0.80");
        verify(txnRepo).save(org.mockito.ArgumentMatchers.argThat(txn ->
                txn.getQuantity().compareTo(new BigDecimal("-0.01")) == 0
                        && txn.getSourceRef().contains("COR-DOWN")));
    }

    @Test
    void consumedWipIsRejectedBeforeAnySave() {
        wip.setConsumedQuantity(new BigDecimal("0.80"));
        wip.setAvailableQuantity(new BigDecimal("0.01"));

        assertThatThrownBy(() -> service.correctApprovedOutputQuantity("F006", report, task,
                new BigDecimal("0.81"), new BigDecimal("0.80"), "COR-CONSUMED", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("下游消费");
        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    void missingUnitMultiOutputAndMissingWipFailClosed() {
        wip.setUnit("box");
        assertThatThrownBy(() -> service.validateApprovedOutputQuantityCorrection("F006", report, task,
                new BigDecimal("0.81"), new BigDecimal("0.80"), "COR-UNIT"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("单位不一致");

        report.setOutputUnit("kg");
        report.setSemiOutputs(List.of(Map.of("semiCode", "BY", "quantity", new BigDecimal("0.1"))));
        assertThatThrownBy(() -> service.validateApprovedOutputQuantityCorrection("F006", report, task,
                new BigDecimal("0.81"), new BigDecimal("0.80"), "COR-MULTI"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("多产出");

        report.setSemiOutputs(null);
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                "F006", wip.getIntermediateBatchNo())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validateApprovedOutputQuantityCorrection("F006", report, task,
                new BigDecimal("0.81"), new BigDecimal("0.80"), "COR-MISSING"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("找不到");
    }

    @Test
    void notesOnlyAndRepeatedCorrectionAreNoOps() {
        service.correctApprovedOutputQuantity("F006", null, null,
                new BigDecimal("0.81"), new BigDecimal("0.81"), "COR-NOTES", 7L);
        verify(wipRepo, never()).findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(any(), any());

        SemiFinishedInventoryTransaction existing = WipLedgerEntries.correctionRow(
                wip, new BigDecimal("0.01"), wip.getUnitCost(), report, "COR-REPEAT", 7L);
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(383L)).thenReturn(List.of(originalIn, existing));
        service.correctApprovedOutputQuantity("F006", report, task,
                new BigDecimal("0.81"), new BigDecimal("0.82"), "COR-REPEAT", 7L);
        assertThat(wip.getProducedQuantity()).isEqualByComparingTo("0.81");
        verify(txnRepo, never()).save(existing);
    }
}
