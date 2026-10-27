package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.factory.SemiFinishedStocktake;
import com.cretas.aims.entity.factory.SemiFinishedStocktakeItem;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeItemRepository;
import com.cretas.aims.repository.factory.SemiFinishedStocktakeRepository;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 第二轮审查 (疑似项: 盘盈) —— 有待检数量的半成品行盘盈, 多出来的量来历不明, 按保守口径一并进待检,
 * 不让它绕过质检放行直接可领。真实入口 {@link SemiFinishedStocktakeServiceImpl#apply}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("半成品盘盈: 有待检的行, 盘盈的量也进待检")
class SemiStocktakeGainQcContractTest {

    private static final String F = "F001";
    private static final String NO = "SEMI-ST-1";

    @Mock private SemiFinishedStocktakeRepository stocktakeRepo;
    @Mock private SemiFinishedStocktakeItemRepository stocktakeItemRepo;
    @Mock private SemiFinishedInventoryRepository sfiRepo;
    @Mock private SemiFinishedInventoryTransactionRepository sfiTxnRepo;
    @Mock private VoucherService voucherService;
    @InjectMocks private SemiFinishedStocktakeServiceImpl service;

    private SemiFinishedInventory gain10(String inspection, String pending) {
        SemiFinishedStocktakeItem item = new SemiFinishedStocktakeItem();
        item.setId("item-1");
        item.setSemiFinishedId(100L);
        item.setIntermediateBatchNo(NO);
        item.setSystemQty(new BigDecimal("40"));
        item.setActualQty(new BigDecimal("50"));
        item.setDifferenceQty(new BigDecimal("10"));
        item.setDifferenceType(SemiFinishedStocktakeItem.DifferenceType.SURPLUS);
        SemiFinishedStocktake st = new SemiFinishedStocktake();
        st.setId("st-1");
        st.setFactoryId(F);
        st.setStatus(SemiFinishedStocktake.Status.APPROVED);
        item.setStocktake(st);
        st.setItems(List.of(item));
        when(stocktakeRepo.findById("st-1")).thenReturn(Optional.of(st));
        when(stocktakeRepo.save(any())).thenReturn(st);

        SemiFinishedInventory sfi = SemiFinishedInventory.builder()
                .id(100L).factoryId(F).intermediateBatchNo(NO)
                .producedQuantity(new BigDecimal("40")).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("40")).adjustmentQuantity(BigDecimal.ZERO)
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .inspectionStatus(inspection)
                .inspectionPendingQuantity(pending == null ? null : new BigDecimal(pending))
                .build();
        when(sfiRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, NO)).thenReturn(Optional.of(sfi));
        when(sfiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sfiTxnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.apply("st-1", F, 9L);
        return sfi;
    }

    @Test
    @DisplayName("整行待检 40, 盘盈 10 → 余 50 全部待检, 可领 0")
    void gainOnPendingRowGoesToPending() {
        SemiFinishedInventory sfi = gain10(SemiFinishedInventory.InspectionStatus.PENDING, "40");
        System.out.println("GAIN balance=" + sfi.computeBalance() + " pending=" + sfi.getInspectionPendingQuantity());
        assertThat(sfi.computeBalance()).isEqualByComparingTo("50");
        assertThat(sfi.getInspectionPendingQuantity()).isEqualByComparingTo("50");
        assertThat(sfi.drawableBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("阴性对照: 没有待检的行盘盈 10 → 50 全部可领 (零回归)")
    void gainOnFreeRowStaysFree() {
        SemiFinishedInventory sfi = gain10(null, null);
        assertThat(sfi.getInspectionStatus()).isNull();
        assertThat(sfi.drawableBalance()).isEqualByComparingTo("50");
    }
}
