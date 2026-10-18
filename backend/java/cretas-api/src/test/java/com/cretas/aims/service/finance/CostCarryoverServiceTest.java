package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.CostCarryoverSummary;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.service.finance.impl.CostCarryoverServiceImpl;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CostCarryoverServiceTest {

    @Test void springDataNestedAggregateRowCannotBecomeFalseZeroCost() {
        when(deliveryItemRepo.aggregateShippedCogs(eq("F006"), any(), any()))
                .thenReturn(new Object[]{new Object[]{new BigDecimal("265.9517"),new BigDecimal("29"),4L}});
        when(deliveryItemRepo.aggregateMissingCost(eq("F006"), any(), any()))
                .thenReturn(new Object[]{new Object[]{BigDecimal.ZERO,0L}});
        var summary=service.carryCost("F006",2026,10,2L);
        assertEquals(0,new BigDecimal("265.95").compareTo(summary.totalCogs()));
        verify(voucherService).createManual(eq("F006"),eq(VoucherType.COST_CARRYOVER),any(),anyList(),any(),any(),any(),eq(2L));
    }

    @Test void unknownProjectionFailsInsteadOfClosingAtZero() {
        when(deliveryItemRepo.aggregateShippedCogs(eq("F006"), any(), any())).thenReturn(new Object[]{"invalid",0,0});
        assertThrows(IllegalStateException.class,()->service.carryCost("F006",2026,10,2L));
        verifyNoInteractions(voucherService);
    }

    @Mock SalesDeliveryItemRepository deliveryItemRepo;
    @Mock VoucherService voucherService;
    @Mock VoucherRepository voucherRepo;
    @Mock SalesReturnCostService salesReturnCosts;
    @Mock jakarta.persistence.EntityManager entityManager;
    @InjectMocks CostCarryoverServiceImpl service;

    @org.junit.jupiter.api.BeforeEach void returnCostsAndFactoryLock() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
        jakarta.persistence.Query query=mock(jakarta.persistence.Query.class,RETURNS_SELF);
        lenient().when(entityManager.createNativeQuery(startsWith("SELECT pg_advisory_xact_lock"))).thenReturn(query);
        lenient().when(salesReturnCosts.returnedCost(anyString(),any(),any())).thenReturn(BigDecimal.ZERO);
    }

    @Test void physicalReturnRestoresNativeCostBeforeFinalRounding() {
        when(deliveryItemRepo.aggregateShippedCogs(anyString(),any(),any())).thenReturn(costed("353.5315","40",7));
        when(deliveryItemRepo.aggregateMissingCost(anyString(),any(),any())).thenReturn(missing("0",0));
        when(salesReturnCosts.returnedCost(anyString(),any(),any())).thenReturn(new BigDecimal("7.4165"));
        assertEquals(0,new BigDecimal("346.12").compareTo(service.carryCost("F006",2026,10,2L).totalCogs()));
        verify(voucherService).createManual(any(),any(),any(),entriesCap.capture(),any(),any(),any(),any());
        assertEquals(0,new BigDecimal("346.12").compareTo(entriesCap.getValue().get(0).debit()));
    }

    @Test void priorMonthReturnWithoutNewShipmentsCreditsCogsInsteadOfDisappearing() {
        when(deliveryItemRepo.aggregateShippedCogs(anyString(),any(),any())).thenReturn(costed("0","0",0));
        when(deliveryItemRepo.aggregateMissingCost(anyString(),any(),any())).thenReturn(missing("0",0));
        when(salesReturnCosts.returnedCost(anyString(),any(),any())).thenReturn(new BigDecimal("7.4165"));
        assertEquals(0,new BigDecimal("-7.42").compareTo(service.carryCost("F006",2026,10,2L).totalCogs()));
        verify(voucherService).createManual(any(),any(),any(),entriesCap.capture(),any(),any(),any(),any());
        var entries=entriesCap.getValue();
        assertEquals("1405",entries.get(0).subjectCode());assertEquals(new BigDecimal("7.42"),entries.get(0).debit());
        assertEquals("6401",entries.get(1).subjectCode());assertEquals(new BigDecimal("7.42"),entries.get(1).credit());
    }

    @Test void missingPhysicalReturnCostCannotGeneratePartialClosing() {
        when(deliveryItemRepo.aggregateShippedCogs(anyString(),any(),any())).thenReturn(costed("50","1",1));
        when(salesReturnCosts.returnedCost(anyString(),any(),any())).thenThrow(new com.cretas.aims.exception.BusinessException(409,"退回成本证据缺失"));
        assertThrows(com.cretas.aims.exception.BusinessException.class,()->service.carryCost("F006",2026,10,2L));
        verifyNoInteractions(voucherService,voucherRepo);
    }

    @Captor ArgumentCaptor<List<VoucherEntrySpec>> entriesCap;

    private Object[] costed(String cogs, String qty, long count) {
        return new Object[]{ new BigDecimal(cogs), new BigDecimal(qty), count };
    }
    private Object[] missing(String qty, long count) {
        return new Object[]{ new BigDecimal(qty), count };
    }

    @Test
    void carryCost_postsCogsVoucher_borrow6401_credit1405_balanced() {
        when(deliveryItemRepo.aggregateShippedCogs(eq("F006"), any(), any()))
                .thenReturn(costed("1234.56", "100", 3L));
        when(deliveryItemRepo.aggregateMissingCost(eq("F006"), any(), any()))
                .thenReturn(missing("0", 0L));
        when(voucherRepo.countCostCarryoverBatches(eq("F006"), anyString())).thenReturn(0L);
        when(voucherService.createManual(any(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Voucher.builder().id("cc1").build());

        CostCarryoverSummary s = service.carryCost("F006", 2026, 5, 1309L);

        verify(voucherService).createManual(eq("F006"), eq(VoucherType.COST_CARRYOVER),
                eq(LocalDate.of(2026, 5, 31)), entriesCap.capture(),
                eq("COST_CARRYOVER"), contains("cost-F006-2026-5-cogs-r0"), any(), eq(1309L));
        List<VoucherEntrySpec> es = entriesCap.getValue();
        // 借 6401 = 贷 1405 = 1234.56
        BigDecimal d6401 = es.stream().filter(e -> e.subjectCode().equals("6401"))
                .map(e -> e.debit() == null ? BigDecimal.ZERO : e.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal c1405 = es.stream().filter(e -> e.subjectCode().equals("1405"))
                .map(e -> e.credit() == null ? BigDecimal.ZERO : e.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1234.56").compareTo(d6401), "借 6401 = COGS");
        assertEquals(0, new BigDecimal("1234.56").compareTo(c1405), "贷 1405 = COGS");
        // 借贷平
        BigDecimal d = es.stream().map(e -> e.debit()==null?BigDecimal.ZERO:e.debit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal c = es.stream().map(e -> e.credit()==null?BigDecimal.ZERO:e.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, d.compareTo(c), "借贷平");
        assertEquals(0, new BigDecimal("1234.56").compareTo(s.totalCogs()));
        assertEquals(0L, s.missingItemCount());
    }

    @Test
    void carryCost_honestNull_excludesPricelessAndSurfacesCount() {
        when(deliveryItemRepo.aggregateShippedCogs(eq("F006"), any(), any())).thenReturn(costed("500.00", "50", 1L));
        when(deliveryItemRepo.aggregateMissingCost(eq("F006"), any(), any())).thenReturn(missing("30", 1L));
        assertThrows(com.cretas.aims.exception.BusinessException.class, () -> service.carryCost("F006",2026,5,1309L));
        verifyNoInteractions(voucherService, voucherRepo);
    }

    @Test
    void carryCost_noCogs_noOp() {
        when(deliveryItemRepo.aggregateShippedCogs(eq("F006"), any(), any()))
                .thenReturn(costed("0", "0", 0L));
        when(deliveryItemRepo.aggregateMissingCost(eq("F006"), any(), any()))
                .thenReturn(missing("0", 0L));

        CostCarryoverSummary s = service.carryCost("F006", 2026, 5, 1309L);

        verify(voucherService, never()).createManual(any(), any(), any(), anyList(), any(), any(), any(), any());
        assertEquals(0, BigDecimal.ZERO.compareTo(s.totalCogs()));
    }

    @Test
    void carryCost_noOp_stillSurfacesMissingCount() {
        when(deliveryItemRepo.aggregateShippedCogs(eq("F006"), any(), any())).thenReturn(costed("0", "50", 1L));
        when(deliveryItemRepo.aggregateMissingCost(eq("F006"), any(), any())).thenReturn(missing("120", 5L));
        assertThrows(com.cretas.aims.exception.BusinessException.class, () -> service.carryCost("F006",2026,5,1309L));
        verifyNoInteractions(voucherService, voucherRepo);
    }

    @Test
    void carryCost_rev_incrementsSourceId() {
        when(deliveryItemRepo.aggregateShippedCogs(eq("F006"), any(), any()))
                .thenReturn(costed("100.00", "10", 1L));
        when(deliveryItemRepo.aggregateMissingCost(eq("F006"), any(), any()))
                .thenReturn(missing("0", 0L));
        when(voucherRepo.countCostCarryoverBatches(eq("F006"), anyString())).thenReturn(2L);
        when(voucherService.createManual(any(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Voucher.builder().id("cc1").build());

        service.carryCost("F006", 2026, 5, 1309L);

        // rev2 (已有 2 批) → source id 用 r2, 避免撞 uk_voucher_source_business
        verify(voucherService).createManual(any(), any(), any(), anyList(), any(),
                contains("cost-F006-2026-5-cogs-r2"), any(), any());
    }

    @Test
    void reverseCostCarryover_redReversesActive() {
        Voucher active = Voucher.builder().id("cc1").factoryId("F006").build();
        when(voucherRepo.findActiveCostCarryoverVouchers(eq("F006"), contains("cost-F006-2026-5-cogs-r")))
                .thenReturn(List.of(active));

        service.reverseCostCarryover("F006", 2026, 5, 1309L);

        verify(voucherService).voidVoucher("F006", "cc1", "反结账自动红冲(结转成本)", 1309L);
    }

    @Test
    void reverseCostCarryover_noActive_noOp() {
        when(voucherRepo.findActiveCostCarryoverVouchers(eq("F006"), anyString())).thenReturn(List.of());
        service.reverseCostCarryover("F006", 2026, 5, 1309L);
        verifyNoInteractions(voucherService);
    }
}
