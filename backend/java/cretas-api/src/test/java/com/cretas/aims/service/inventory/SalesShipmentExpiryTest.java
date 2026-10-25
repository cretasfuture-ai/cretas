package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import com.cretas.aims.util.InventoryExpiry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发货扣减的两条路 (按分配扣 / 无分配时 FEFO 回落) 与可售批次查询, 在发货当下比较到期日。
 * 调用方式沿用 {@link SalesDeliveryHonorBatchAllocationTest}: null 构造 + 反射注入 + 反射调私有扣减方法。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("销售发货 — 过期成品不能出货")
class SalesShipmentExpiryTest {

    private static final String FACTORY = "F001";
    private static final String PRODUCT = "PT-001";
    private static final Long ITEM_ID = 777L;
    private static final String ITEM_ID_STR = "777";
    private static final Long OPERATOR_ID = 7L;

    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private SalesDeliveryBatchAllocationService batchAllocationService;

    private SalesServiceImpl salesService;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = InventoryExpiry.today();
        salesService = new SalesServiceImpl(null, null, null, finishedGoodsBatchRepository,
                null, null, null, null);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "batchAllocationService", batchAllocationService);
    }

    @Test
    @DisplayName("按分配发货: 分配的批次昨天到期 → 拒绝, 不扣库存")
    void shippingAllocatedBatchExpiredYesterdayIsRejected() {
        FinishedGoodsBatch expired = batch("id-old", "FG-OLD", today.minusDays(1), "50");
        allocate(expired, "10");

        assertThatThrownBy(() -> deduct(item("10")))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo("FINISHED_GOODS_BATCH_EXPIRED");
                    assertThat(error.getMessage()).contains("FG-OLD", today.minusDays(1).toString());
                });
        assertThat(expired.getShippedQuantity()).isEqualByComparingTo("0");
        verify(finishedGoodsBatchRepository, never()).save(expired);
    }

    @Test
    @DisplayName("按分配发货: 今天到期 → 照常发货")
    void shippingAllocatedBatchExpiringTodaySucceeds() throws Exception {
        FinishedGoodsBatch todayBatch = batch("id-today", "FG-TODAY", today, "50");
        allocate(todayBatch, "10");

        deduct(item("10"));

        assertThat(todayBatch.getShippedQuantity()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("按分配发货: 无到期日 → 维持现状, 照常发货")
    void shippingAllocatedBatchWithoutExpireDateSucceeds() throws Exception {
        FinishedGoodsBatch noDate = batch("id-nodate", "FG-NODATE", null, "50");
        allocate(noDate, "10");

        deduct(item("10"));

        assertThat(noDate.getShippedQuantity()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("无分配回落 FEFO: 过期批排在最前也被跳过, 从未过期的批次扣")
    void fefoFallbackSkipsExpiredBatch() throws Exception {
        FinishedGoodsBatch expired = batch("id-old", "FG-OLD", today.minusDays(1), "50");
        FinishedGoodsBatch fresh = batch("id-fresh", "FG-FRESH", today.plusDays(5), "50");
        when(batchAllocationService.listByDeliveryItem(FACTORY, ITEM_ID_STR)).thenReturn(List.of());
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                eq(FACTORY), eq(PRODUCT), anyString())).thenReturn(List.of(expired, fresh));

        deduct(item("10"));

        assertThat(expired.getShippedQuantity()).isEqualByComparingTo("0");
        assertThat(fresh.getShippedQuantity()).isEqualByComparingTo("10");
        verify(finishedGoodsBatchRepository, never()).save(expired);
    }

    @Test
    @DisplayName("无分配回落 FEFO: 只剩过期批 → 库存不足, 并说明有过期批次未计入")
    void fefoFallbackWithOnlyExpiredStockFailsLoudly() {
        FinishedGoodsBatch expired = batch("id-old", "FG-OLD", today.minusDays(1), "50");
        when(batchAllocationService.listByDeliveryItem(FACTORY, ITEM_ID_STR)).thenReturn(List.of());
        when(finishedGoodsBatchRepository.findShippableBatchesAllWarehousesExcluding(
                eq(FACTORY), eq(PRODUCT), anyString())).thenReturn(List.of(expired));

        assertThatThrownBy(() -> deduct(item("10")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成品库存不足")
                .hasMessageContaining("1 个已过期批次未计入");
        assertThat(expired.getShippedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("可售批次查询 (前端选批): 过期批次不返回")
    void availableBatchListExcludesExpired() {
        when(finishedGoodsBatchRepository.findAvailableBatchesFefoAllWarehousesExcluding(FACTORY, PRODUCT, "WH-RD"))
                .thenReturn(List.of(
                        batch("id-old", "FG-OLD", today.minusDays(1), "5"),
                        batch("id-today", "FG-TODAY", today, "5"),
                        batch("id-nodate", "FG-NODATE", null, "5")));

        assertThat(salesService.getAvailableBatches(FACTORY, PRODUCT, null))
                .extracting(FinishedGoodsBatch::getBatchNumber)
                .containsExactly("FG-TODAY", "FG-NODATE");
    }

    private void allocate(FinishedGoodsBatch batch, String qty) {
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate(batch.getId(), FACTORY))
                .thenReturn(Optional.of(batch));
        SalesDeliveryItemBatchAllocation a = new SalesDeliveryItemBatchAllocation();
        a.setFactoryId(FACTORY);
        a.setDeliveryItemId(ITEM_ID_STR);
        a.setFinishedGoodsBatchId(batch.getId());
        a.setBatchNumber(batch.getBatchNumber());
        a.setAllocatedQty(new BigDecimal(qty));
        a.setUnit("件");
        when(batchAllocationService.listByDeliveryItem(FACTORY, ITEM_ID_STR)).thenReturn(List.of(a));
    }

    private void deduct(SalesDeliveryItem item) throws Exception {
        Method m = SalesServiceImpl.class.getDeclaredMethod(
                "deductFinishedGoodsInventory", String.class, String.class, SalesDeliveryItem.class, Long.class);
        m.setAccessible(true);
        try {
            m.invoke(salesService, FACTORY, (String) null, item, OPERATOR_ID);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private static SalesDeliveryItem item(String qty) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(ITEM_ID);
        item.setProductTypeId(PRODUCT);
        item.setDeliveredQuantity(new BigDecimal(qty));
        item.setUnit("件");
        return item;
    }

    private static FinishedGoodsBatch batch(String id, String batchNumber, LocalDate expireDate, String produced) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setBatchNumber(batchNumber);
        b.setProductTypeId(PRODUCT);
        b.setWarehouseId("wh-log-f001");
        b.setStatus("AVAILABLE");
        b.setUnit("件");
        b.setProducedQuantity(new BigDecimal(produced));
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setExpireDate(expireDate);
        return b;
    }
}
