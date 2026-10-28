package com.cretas.aims.service.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.quality.FactoryRecallDTO;
import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.unit.UnitContractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactoryRecallFollowUpServiceContractTest {

    private static final String FACTORY = "F006";
    private static final Long EVENT_ID = 912L;
    private static final Long BATCH_ID = 41L;

    @Mock private FactoryRecallService recallService;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private DisposalRecordRepository disposalRecordRepository;
    @Mock private ArApTransactionRepository arApTransactionRepository;
    @Mock private UnitContractService unitContractService;

    private FactoryRecallFollowUpService service;
    private RecallEvent event;
    private ProductionBatch batch;
    private FinishedGoodsBatch original;
    private FinishedGoodsBatch returned;
    private ReturnOrder order;

    @BeforeEach
    void setUp() {
        service = new FactoryRecallFollowUpService(recallService, finishedGoodsBatchRepository,
                disposalRecordRepository, arApTransactionRepository, new ObjectMapper());
        org.mockito.Mockito.lenient().when(unitContractService.storageUnit(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                        .crossLanguageCode(invocation.getArgument(1)));
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        event = RecallEvent.builder().id(EVENT_ID).factoryId(FACTORY).status("COMPLETED").build();
        batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("PB-9");
        batch.setProductTypeId("SKU-9");
        batch.setUnit("kg");
        original = fg("fg-original", "PB-9", "10");
        returned = fg("fg-returned", "RTN-RET-1", "10");
        order = order("return-1", "RET", new BigDecimal("10"));

        when(recallService.followUpEvent(FACTORY, EVENT_ID)).thenReturn(event);
        when(recallService.followUpBatch(FACTORY, event)).thenReturn(batch);
        when(recallService.followUpActions(EVENT_ID)).thenReturn(List.of(
                RecallAction.builder().recallEventId(EVENT_ID).actionType("FREEZE_INVENTORY")
                        .targetEntityId(BATCH_ID).status("COMPLETED")
                        .executedAt(LocalDateTime.of(2026, 10, 16, 10, 0))
                        .executionNotes("{\"remainingInventory\":10}").build()));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "PB-9"))
                .thenReturn(Optional.of(original));
        lenient().when(finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(eq(FACTORY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(original, returned)));
        lenient().when(disposalRecordRepository.findByFactoryId(eq(FACTORY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(recallService.followUpReturnOrders(FACTORY, event, batch)).thenReturn(List.of(order));
        when(recallService.followUpQuantityForBatch(order, batch)).thenReturn(new BigDecimal("10"));
        when(recallService.followUpPhysicalQuantity(original)).thenReturn(BigDecimal.ZERO);
        lenient().when(recallService.followUpPhysicalQuantity(returned)).thenReturn(BigDecimal.ZERO);
        when(recallService.followUpScrappedQuantity(FACTORY, original)).thenReturn(new BigDecimal("10"));
        lenient().when(recallService.followUpScrappedQuantity(FACTORY, returned))
                .thenReturn(new BigDecimal("10"));
        lenient().when(recallService.followUpStatusAllowsReceipt(FACTORY, original, new BigDecimal("10")))
                .thenReturn(true);
        lenient().when(recallService.followUpStatusAllowsReceipt(FACTORY, returned, new BigDecimal("10")))
                .thenReturn(true);
        // Unmatched approval states are empty ledger slices; malformed totals
        // intentionally short-circuit before any ledger read.
        lenient().when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        eq(FACTORY), eq("RETURN_ORDER"), eq(order.getId()), any(ArApApprovalStatus.class)))
                .thenReturn(List.of());
    }

    @Test
    void projectsCompletedProgressWithoutFinancialAmounts() {
        ArApTransaction approved = new ArApTransaction();
        approved.setId("ar-adjust-1");
        approved.setFactoryId(FACTORY);
        approved.setSourceType("RETURN_ORDER");
        approved.setSourceId(order.getId());
        approved.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        approved.setCounterpartyType(CounterpartyType.CUSTOMER);
        approved.setCounterpartyId(order.getCounterpartyId());
        approved.setAmount(new BigDecimal("-10"));
        approved.setApprovalStatus(ArApApprovalStatus.APPROVED);
        approved.setTransactionNumber("AR-ADJ-1");
        when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        FACTORY, "RETURN_ORDER", order.getId(), ArApApprovalStatus.APPROVED))
                .thenReturn(List.of(approved));

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getRecallCompleted()).isTrue();
        assertThat(result.getInventoryResolved()).isTrue();
        assertThat(result.getReceivableResolved()).isTrue();
        assertThat(result.getCompleted()).isTrue();
        assertThat(result.getStock()).extracting(FactoryRecallDTO.FollowUpStock::getOrigin)
                .containsExactly("ORIGINAL", "RETURNED");
        assertThat(result.getReturns()).singleElement().satisfies(item -> {
            assertThat(item.getReceivableStatus()).isEqualTo("APPROVED");
            assertThat(item.getAdjustments()).singleElement()
                    .extracting(FactoryRecallDTO.AdjustmentSummary::getTransactionNumber)
                    .isEqualTo("AR-ADJ-1");
        });
    }

    @Test
    void rejectedAndApprovedAdjustmentsResolveToApproved() {
        ArApTransaction rejected = transaction("-10", order.getCounterpartyId(),
                ArApApprovalStatus.REJECTED, "ar-rejected");
        ArApTransaction approved = transaction("-10", order.getCounterpartyId(),
                ArApApprovalStatus.APPROVED, "ar-approved");
        when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        FACTORY, "RETURN_ORDER", order.getId(), ArApApprovalStatus.REJECTED))
                .thenReturn(List.of(rejected));
        when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        FACTORY, "RETURN_ORDER", order.getId(), ArApApprovalStatus.APPROVED))
                .thenReturn(List.of(approved));

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getReceivableResolved()).isTrue();
        assertThat(result.getReturns()).singleElement()
                .extracting(FactoryRecallDTO.FollowUpReturn::getReceivableStatus)
                .isEqualTo("APPROVED");
    }

    @Test
    void rejectedAndPendingAdjustmentsRemainPendingWithoutInconsistency() {
        ArApTransaction rejected = transaction("-10", order.getCounterpartyId(),
                ArApApprovalStatus.REJECTED, "ar-rejected");
        ArApTransaction pending = transaction("-10", order.getCounterpartyId(),
                ArApApprovalStatus.PENDING, "ar-pending");
        when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        FACTORY, "RETURN_ORDER", order.getId(), ArApApprovalStatus.REJECTED))
                .thenReturn(List.of(rejected));
        when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        FACTORY, "RETURN_ORDER", order.getId(), ArApApprovalStatus.PENDING))
                .thenReturn(List.of(pending));

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getReceivableResolved()).isFalse();
        assertThat(result.getReturns()).singleElement()
                .extracting(FactoryRecallDTO.FollowUpReturn::getReceivableStatus)
                .isEqualTo("PENDING");
    }

    @Test
    void duplicateApprovedAdjustmentsAreInconsistent() {
        ArApTransaction first = transaction("-10", order.getCounterpartyId(),
                ArApApprovalStatus.APPROVED, "ar-approved-1");
        ArApTransaction second = transaction("-10", order.getCounterpartyId(),
                ArApApprovalStatus.APPROVED, "ar-approved-2");
        when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        FACTORY, "RETURN_ORDER", order.getId(), ArApApprovalStatus.APPROVED))
                .thenReturn(List.of(first, second));

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getReceivableResolved()).isFalse();
        assertThat(result.getReturns()).singleElement()
                .extracting(FactoryRecallDTO.FollowUpReturn::getReceivableStatus)
                .isEqualTo("INCONSISTENT");
    }

    @Test
    void refusesToMapHistoricalMultiLineReturnByListPosition() {
        ReturnOrderItem second = new ReturnOrderItem();
        second.setProductTypeId("SKU-9");
        second.setBatchNumber("PB-9");
        second.setQuantity(new BigDecimal("5"));
        order.getItems().add(second);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getCompleted()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("RETURN_SOURCE_AMBIGUOUS");
        assertThat(result.getStock()).noneMatch(stock -> "RETURNED".equals(stock.getOrigin()));
    }

    @Test
    void refusesToMapWhenOtherSkuMakesHistoricalIndexUnstable() {
        ReturnOrderItem second = new ReturnOrderItem();
        second.setProductTypeId("SKU-OTHER");
        second.setBatchNumber("PB-OTHER");
        second.setQuantity(new BigDecimal("5"));
        order.getItems().add(second);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("RETURN_SOURCE_AMBIGUOUS");
        assertThat(result.getStock()).noneMatch(stock -> "RETURNED".equals(stock.getOrigin()));
    }

    @Test
    void mapsMultiLineReturnInventoryThroughStableReturnItemId() {
        ReturnOrderItem stable = order.getItems().get(0);
        stable.setId(31L);
        stable.setReturnOrderId(order.getId());
        stable.setSourceFinishedGoodsBatchId(original.getId());
        ReturnOrderItem unrelated = new ReturnOrderItem();
        unrelated.setId(32L);
        unrelated.setProductTypeId("SKU-OTHER");
        unrelated.setBatchNumber("PB-OTHER");
        unrelated.setQuantity(new BigDecimal("4"));
        order.setItems(new java.util.ArrayList<>(List.of(stable, unrelated)));
        returned.setReturnOrderItemId(31L);
        when(finishedGoodsBatchRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 31L))
                .thenReturn(List.of(returned));

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .doesNotContain("RETURN_SOURCE_AMBIGUOUS");
        assertThat(result.getStock()).extracting(FactoryRecallDTO.FollowUpStock::getOrigin)
                .contains("RETURNED");
    }

    @Test
    void mapsAllReorderedSameBatchLinesThroughTheirStableReturnItemIds() {
        ReturnOrderItem first = returnItem(31L, "6");
        ReturnOrderItem second = returnItem(33L, "4");
        ReturnOrderItem unrelated = new ReturnOrderItem();
        unrelated.setId(32L);
        unrelated.setProductTypeId("SKU-OTHER");
        unrelated.setBatchNumber("PB-OTHER");
        unrelated.setQuantity(new BigDecimal("8"));
        order.setItems(new java.util.ArrayList<>(List.of(second, unrelated, first)));

        FinishedGoodsBatch firstReturned = fg("fg-returned-first", "RTN-RET-1-1", "6");
        firstReturned.setReturnOrderItemId(31L);
        FinishedGoodsBatch secondReturned = fg("fg-returned-second", "RTN-RET-1-3", "4");
        secondReturned.setReturnOrderItemId(33L);
        when(finishedGoodsBatchRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 31L))
                .thenReturn(List.of(firstReturned));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 33L))
                .thenReturn(List.of(secondReturned));
        when(recallService.followUpPhysicalQuantity(firstReturned)).thenReturn(BigDecimal.ZERO);
        when(recallService.followUpPhysicalQuantity(secondReturned)).thenReturn(BigDecimal.ZERO);
        when(recallService.followUpScrappedQuantity(FACTORY, firstReturned))
                .thenReturn(new BigDecimal("6"));
        when(recallService.followUpScrappedQuantity(FACTORY, secondReturned))
                .thenReturn(new BigDecimal("4"));
        when(recallService.followUpStatusAllowsReceipt(FACTORY, firstReturned, new BigDecimal("6")))
                .thenReturn(true);
        when(recallService.followUpStatusAllowsReceipt(FACTORY, secondReturned, new BigDecimal("4")))
                .thenReturn(true);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .doesNotContain("RETURN_SOURCE_AMBIGUOUS", "RETURN_STOCK_MISSING");
        assertThat(result.getInventoryResolved()).isTrue();
        assertThat(result.getStock()).extracting(FactoryRecallDTO.FollowUpStock::getFinishedGoodsBatchId)
                .contains("fg-returned-first", "fg-returned-second");
    }

    @Test
    void refusesSameBatchLinesWhenOneStableSourceLinkIsMissing() {
        ReturnOrderItem first = returnItem(31L, "6");
        ReturnOrderItem missing = new ReturnOrderItem();
        missing.setProductTypeId("SKU-9");
        missing.setBatchNumber("PB-9");
        missing.setQuantity(new BigDecimal("4"));
        order.setItems(new java.util.ArrayList<>(List.of(first, missing)));

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("RETURN_SOURCE_AMBIGUOUS");
        assertThat(result.getStock()).noneMatch(stock -> "RETURNED".equals(stock.getOrigin()));
    }

    @Test
    void refusesSameReturnedChildForTwoStableReturnLines() {
        ReturnOrderItem first = returnItem(31L, "6");
        ReturnOrderItem second = returnItem(33L, "4");
        order.setItems(new java.util.ArrayList<>(List.of(first, second)));

        FinishedGoodsBatch firstChild = fg("fg-same-returned", "RTN-RET-1-1", "6");
        firstChild.setReturnOrderItemId(31L);
        FinishedGoodsBatch duplicateChild = fg("fg-same-returned", "RTN-RET-1-2", "4");
        duplicateChild.setReturnOrderItemId(33L);
        when(finishedGoodsBatchRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 31L))
                .thenReturn(List.of(firstChild));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 33L))
                .thenReturn(List.of(duplicateChild));
        when(recallService.followUpPhysicalQuantity(firstChild)).thenReturn(BigDecimal.ZERO);
        when(recallService.followUpScrappedQuantity(FACTORY, firstChild)).thenReturn(new BigDecimal("6"));
        when(recallService.followUpStatusAllowsReceipt(FACTORY, firstChild, new BigDecimal("6")))
                .thenReturn(true);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("RETURN_SOURCE_AMBIGUOUS");
    }

    @Test
    void refusesReturnedBatchThatWasShippedAgain() {
        returned.setShippedQuantity(BigDecimal.ONE);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("RETURN_STOCK_MISSING");
    }

    @Test
    void refusesReturnedBatchThatWasReservedAgain() {
        returned.setReservedQuantity(BigDecimal.ONE);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("RETURN_STOCK_MISSING");
    }

    @Test
    void originalStockAlreadyShippedOutBeforeFreezeNeedsNoDisposal() {
        // 召回前原批次已全部发出: 冻结基准 0, 状态是发货留下的 DEPLETED, 没有报废证据 —— 没东西可处置
        when(recallService.followUpActions(EVENT_ID)).thenReturn(List.of(
                RecallAction.builder().recallEventId(EVENT_ID).actionType("FREEZE_INVENTORY")
                        .targetEntityId(BATCH_ID).status("COMPLETED")
                        .executedAt(LocalDateTime.of(2026, 10, 27, 23, 49))
                        .executionNotes("{\"remainingInventory\":0}").build()));
        when(recallService.followUpPhysicalQuantity(original)).thenReturn(BigDecimal.ZERO);
        when(recallService.followUpScrappedQuantity(FACTORY, original)).thenReturn(null);
        lenient().when(recallService.followUpStatusAllowsReceipt(eq(FACTORY), eq(original), any()))
                .thenReturn(false);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .doesNotContain("ORIGINAL_STOCK_UNRESOLVED");
        assertThat(result.getStock()).filteredOn(stock -> "ORIGINAL".equals(stock.getOrigin()))
                .singleElement().satisfies(stock -> {
                    assertThat(stock.getEvidenceComplete()).isTrue();
                    assertThat(stock.getDisposalQuantity()).isEqualByComparingTo("0");
                });
    }

    @Test
    void zeroFreezeBaselineWithStockReappearingIsStillUnresolved() {
        // 阴性对照: 冻结时 0, 现在却有货 —— 不能当成「没东西可处置」
        when(recallService.followUpActions(EVENT_ID)).thenReturn(List.of(
                RecallAction.builder().recallEventId(EVENT_ID).actionType("FREEZE_INVENTORY")
                        .targetEntityId(BATCH_ID).status("COMPLETED")
                        .executedAt(LocalDateTime.of(2026, 10, 27, 23, 49))
                        .executionNotes("{\"remainingInventory\":0}").build()));
        when(recallService.followUpPhysicalQuantity(original)).thenReturn(new BigDecimal("3"));
        when(recallService.followUpScrappedQuantity(FACTORY, original)).thenReturn(null);
        lenient().when(recallService.followUpStatusAllowsReceipt(eq(FACTORY), eq(original), any()))
                .thenReturn(false);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("ORIGINAL_STOCK_UNRESOLVED");
    }

    @Test
    void isolatedInventoryWithNoApprovedScrapRemainsPending() {
        when(recallService.followUpPhysicalQuantity(original)).thenReturn(new BigDecimal("10"));
        when(recallService.followUpPhysicalQuantity(returned)).thenReturn(new BigDecimal("10"));
        when(recallService.followUpScrappedQuantity(FACTORY, original)).thenReturn(null);
        when(recallService.followUpScrappedQuantity(FACTORY, returned)).thenReturn(null);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getCompleted()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("INVENTORY_DISPOSAL_PENDING");
        assertThat(result.getStock()).allMatch(stock -> Boolean.TRUE.equals(stock.getEvidenceComplete()));
    }

    @Test
    void pendingOrRejectedDisposalsDoNotResolveInventory() {
        DisposalRecord pending = disposal("fg-original", "PENDING");
        DisposalRecord rejected = disposal("fg-returned", "REJECTED");
        when(disposalRecordRepository.findByFactoryId(eq(FACTORY), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pending, rejected)));
        when(recallService.followUpPhysicalQuantity(original)).thenReturn(BigDecimal.ZERO);
        when(recallService.followUpPhysicalQuantity(returned)).thenReturn(BigDecimal.ZERO);
        when(recallService.followUpScrappedQuantity(FACTORY, original)).thenReturn(null);
        when(recallService.followUpScrappedQuantity(FACTORY, returned)).thenReturn(null);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getInventoryResolved()).isFalse();
        assertThat(result.getCompleted()).isFalse();
        assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                .contains("ORIGINAL_STOCK_UNRESOLVED", "RETURN_STOCK_UNRESOLVED");
    }

    @Test
    void rejectsPendingOrFailedFreezeActions() {
        for (String status : List.of("PENDING", "FAILED")) {
            RecallAction action = RecallAction.builder().recallEventId(EVENT_ID)
                    .actionType("FREEZE_INVENTORY").targetEntityId(BATCH_ID).status(status)
                    .executionNotes("{\"remainingInventory\":10}").build();
            when(recallService.followUpActions(EVENT_ID)).thenReturn(List.of(action));

            FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

            assertThat(result.getInventoryResolved()).isFalse();
            assertThat(result.getIssues()).extracting(FactoryRecallDTO.FollowUpIssue::getCode)
                    .contains("FREEZE_BASELINE_UNKNOWN");
        }
    }

    @Test
    void rejectsReceivableWithWrongAmount() {
        ArApTransaction transaction = transaction("-9", order.getCounterpartyId());
        when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        FACTORY, "RETURN_ORDER", order.getId(), ArApApprovalStatus.APPROVED))
                .thenReturn(List.of(transaction));

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getReceivableResolved()).isFalse();
        assertThat(result.getCompleted()).isFalse();
        assertThat(result.getReturns()).singleElement()
                .extracting(FactoryRecallDTO.FollowUpReturn::getReceivableStatus)
                .isEqualTo("INCONSISTENT");
    }

    @Test
    void rejectsReceivableWithWrongCustomer() {
        ArApTransaction transaction = transaction("-10", "customer-other");
        when(arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
                        FACTORY, "RETURN_ORDER", order.getId(), ArApApprovalStatus.APPROVED))
                .thenReturn(List.of(transaction));

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getReceivableResolved()).isFalse();
        assertThat(result.getReturns()).singleElement()
                .extracting(FactoryRecallDTO.FollowUpReturn::getReceivableStatus)
                .isEqualTo("INCONSISTENT");
    }

    @Test
    void rejectsReceivableWhenReturnTotalIsNull() {
        order.setTotalAmount(null);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getReceivableResolved()).isFalse();
        assertThat(result.getReturns()).singleElement()
                .extracting(FactoryRecallDTO.FollowUpReturn::getReceivableStatus)
                .isEqualTo("INCONSISTENT");
    }

    @Test
    void zeroValueReturnWithoutAdjustmentIsNotRequired() {
        order.setTotalAmount(BigDecimal.ZERO);

        FactoryRecallDTO.FollowUpView result = service.get(FACTORY, EVENT_ID);

        assertThat(result.getReceivableResolved()).isTrue();
        assertThat(result.getReturns()).singleElement()
                .extracting(FactoryRecallDTO.FollowUpReturn::getReceivableStatus)
                .isEqualTo("NOT_REQUIRED");
    }

    private FinishedGoodsBatch fg(String id, String batchNumber, String produced) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(id);
        fg.setFactoryId(FACTORY);
        fg.setBatchNumber(batchNumber);
        fg.setProductTypeId("SKU-9");
        fg.setUnit("kg");
        fg.setProducedQuantity(new BigDecimal(produced));
        fg.setShippedQuantity(BigDecimal.ZERO);
        fg.setReservedQuantity(BigDecimal.ZERO);
        fg.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        return fg;
    }

    private ReturnOrder order(String id, String returnNumber, BigDecimal quantity) {
        ReturnOrder value = new ReturnOrder();
        value.setId(id);
        value.setFactoryId(FACTORY);
        value.setReturnNumber(returnNumber);
        value.setCounterpartyId("customer-1");
        value.setTotalAmount(new BigDecimal("10"));
        value.setReturnType(ReturnType.SALES_RETURN);
        value.setStatus(ReturnOrderStatus.COMPLETED);
        value.setWithGoods(true);
        ReturnOrderItem item = new ReturnOrderItem();
        item.setProductTypeId("SKU-9");
        item.setBatchNumber("PB-9");
        item.setQuantity(quantity);
        value.setItems(new java.util.ArrayList<>(List.of(item)));
        return value;
    }

    private ReturnOrderItem returnItem(Long id, String quantity) {
        ReturnOrderItem item = new ReturnOrderItem();
        item.setId(id);
        item.setReturnOrderId(order.getId());
        item.setProductTypeId("SKU-9");
        item.setBatchNumber("PB-9");
        item.setQuantity(new BigDecimal(quantity));
        item.setSourceFinishedGoodsBatchId(original.getId());
        return item;
    }

    private ArApTransaction transaction(String amount, String counterpartyId) {
        return transaction(amount, counterpartyId, ArApApprovalStatus.APPROVED,
                "ar-adjust-" + amount + counterpartyId);
    }

    private ArApTransaction transaction(String amount, String counterpartyId,
                                        ArApApprovalStatus status, String id) {
        ArApTransaction value = new ArApTransaction();
        value.setId(id);
        value.setFactoryId(FACTORY);
        value.setSourceType("RETURN_ORDER");
        value.setSourceId(order.getId());
        value.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        value.setCounterpartyType(CounterpartyType.CUSTOMER);
        value.setCounterpartyId(counterpartyId);
        value.setAmount(new BigDecimal(amount));
        value.setApprovalStatus(status);
        return value;
    }

    private DisposalRecord disposal(String batchId, String status) {
        DisposalRecord value = new DisposalRecord();
        value.setId(status.hashCode() > 0 ? (long) status.hashCode() : 101L);
        value.setFactoryId(FACTORY);
        value.setFinishedGoodsBatchId(batchId);
        value.setApprovalStatus(status);
        value.setIsApproved("APPROVED".equals(status));
        value.setDisposalQuantity(new BigDecimal("10"));
        return value;
    }
}
