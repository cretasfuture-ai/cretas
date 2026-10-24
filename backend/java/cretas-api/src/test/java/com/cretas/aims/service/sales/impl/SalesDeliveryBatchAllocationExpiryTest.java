package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.util.InventoryExpiry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产品真实入口: 发货行分配批次 ({@code allocateBatches}) 与分配对话框的批次推荐 ({@code recommendFifo})。
 * 「今天」取东八区业务日, 样本日期全部相对它构造。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("发货批次分配 — 过期成品选不到、分不进")
class SalesDeliveryBatchAllocationExpiryTest {

    private static final String FID = "F001";
    private static final String ITEM_ID = "42";
    private static final String WH_WKS_ID = "wh-wks-uuid-001";
    private static final String PRODUCT_ID = "p-1";

    @Mock SalesDeliveryItemBatchAllocationRepository allocationRepository;
    @Mock SalesDeliveryItemRepository deliveryItemRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock WarehouseResolver warehouseResolver;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock SalesOrderRepository salesOrderRepository;

    @InjectMocks SalesDeliveryBatchAllocationServiceImpl service;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = InventoryExpiry.today();
        lenient().when(deliveryItemRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> deliveryItemRepository.findById(invocation.getArgument(0)));
        lenient().when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate(any(), eq(FID)))
                .thenAnswer(invocation -> finishedGoodsBatchRepository.findById(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("分配昨天到期的批次 → 409 过期, 批号+到期日; 旧分配不被替换")
    void allocatingBatchExpiredYesterdayIsRejected() {
        stubItem();
        FinishedGoodsBatch expired = batch("b-old", today.minusDays(1));
        when(finishedGoodsBatchRepository.findById("b-old")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.allocateBatches(FID, ITEM_ID, List.of(dto("b-old", "10"))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("FINISHED_GOODS_BATCH_EXPIRED");
                    assertThat(error.getMessage()).contains("BATCH-b-old", today.minusDays(1).toString());
                });
        verify(allocationRepository, never()).deleteByFactoryIdAndDeliveryItemId(FID, ITEM_ID);
        verify(allocationRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("分配今天到期的批次 → 放行 (到期日当天仍可发)")
    void allocatingBatchExpiringTodayIsAccepted() {
        stubItem();
        when(finishedGoodsBatchRepository.findById("b-today"))
                .thenReturn(Optional.of(batch("b-today", today)));

        assertThatCode(() -> service.allocateBatches(FID, ITEM_ID, List.of(dto("b-today", "10"))))
                .doesNotThrowAnyException();
        verify(allocationRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("分配无到期日的批次 → 维持现状 (放行)")
    void allocatingBatchWithoutExpireDateKeepsLegacyBehaviour() {
        stubItem();
        when(finishedGoodsBatchRepository.findById("b-nodate"))
                .thenReturn(Optional.of(batch("b-nodate", null)));

        assertThatCode(() -> service.allocateBatches(FID, ITEM_ID, List.of(dto("b-nodate", "10"))))
                .doesNotThrowAnyException();
        verify(allocationRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("推荐列表不含过期批次 (即便 FEFO 把它排在最前); 今天到期 / 无到期日照常推荐")
    void recommendationSkipsExpiredBatches() {
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(FID, PRODUCT_ID, "WH-RD"))
                .thenReturn(List.of(
                        batch("b-old", today.minusDays(1)),
                        batch("b-today", today),
                        batch("b-nodate", null)));

        List<Map<String, Object>> result =
                service.recommendFifo(FID, PRODUCT_ID, new BigDecimal("30"), "kg", null);

        assertThat(result).extracting(row -> row.get("batchId"))
                .containsExactly("b-today", "b-nodate");
    }

    private void stubItem() {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(42L);
        item.setSourceWarehouseCode("WH-WKS");
        item.setDeliveredQuantity(new BigDecimal("10"));
        item.setUnit("kg");
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveId(FID, "WH-WKS")).thenReturn(WH_WKS_ID);
    }

    private static BatchAllocationDTO dto(String batchId, String qty) {
        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId(batchId);
        dto.setAllocatedQty(new BigDecimal(qty));
        return dto;
    }

    private static FinishedGoodsBatch batch(String id, LocalDate expireDate) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setFactoryId(FID);
        b.setBatchNumber("BATCH-" + id);
        b.setWarehouseId(WH_WKS_ID);
        b.setProducedQuantity(new BigDecimal("10"));
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setProductTypeId(PRODUCT_ID);
        b.setUnit("kg");
        b.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        b.setExpireDate(expireDate);
        return b;
    }
}
