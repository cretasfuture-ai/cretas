package com.cretas.aims.service.sales.impl;

import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import com.cretas.aims.service.quality.release.QcInspectionPolicy;
import com.cretas.aims.service.quality.release.QcReleaseService;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 质检放行 —— 成品: 待检批次不能分配、不能发货; 放行后可以; 没开需检的批次完全照旧。
 *
 * <p>两个产品真实入口: 发货行分配批次 {@code SalesDeliveryBatchAllocationServiceImpl#allocateBatches}
 * 与发货扣减 {@code SalesServiceImpl#deductFinishedGoodsInventory} (按已分配批次)。
 * 放行走真的 {@link QcReleaseService}; 入库扣留走真的 {@link QcInspectionPolicy}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检放行 · 成品分配 / 发货闸 + 入库扣留")
class QcReleaseFinishedGoodsContractTest {

    private static final String FID = "F-QC-FG";
    private static final String ITEM_ID = "42";
    private static final String WH = "wh-wks-qc";
    private static final String PRODUCT = "PT-FG-QC";

    @Mock SalesDeliveryItemBatchAllocationRepository allocationRepository;
    @Mock SalesDeliveryItemRepository deliveryItemRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock WarehouseResolver warehouseResolver;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock SalesOrderRepository salesOrderRepository;
    @Mock SemiFinishedInventoryRepository semiRepo;
    @Mock MaterialBatchRepository materialBatchRepo;
    @Mock QcReleaseRecordRepository recordRepo;
    @Mock SalesDeliveryBatchAllocationService batchAllocationService;

    @InjectMocks SalesDeliveryBatchAllocationServiceImpl allocationService;

    private QcReleaseService releaseService;
    private SalesServiceImpl salesService;

    @BeforeEach
    void setUp() {
        when(deliveryItemRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> deliveryItemRepository.findById(inv.getArgument(0)));
        when(finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate(any(), eq(FID)))
                .thenAnswer(inv -> finishedGoodsBatchRepository.findById(inv.getArgument(0)));
        when(recordRepo.save(any(QcReleaseRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        releaseService = new QcReleaseService(semiRepo, materialBatchRepo, finishedGoodsBatchRepository,
                productTypeRepository, recordRepo);
        salesService = new SalesServiceImpl(null, null, null, finishedGoodsBatchRepository,
                null, null, null, null);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(salesService, "batchAllocationService", batchAllocationService);

        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(42L);
        item.setSourceWarehouseCode("WH-WKS");
        item.setDeliveredQuantity(new BigDecimal("10"));
        item.setUnit("kg");
        item.setProductTypeId(PRODUCT);
        when(deliveryItemRepository.findById(42L)).thenReturn(Optional.of(item));
        when(warehouseResolver.resolveId(FID, "WH-WKS")).thenReturn(WH);
    }

    private FinishedGoodsBatch batch(String id, String status) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setFactoryId(FID);
        b.setBatchNumber("FG-" + id);
        b.setWarehouseId(WH);
        b.setProducedQuantity(new BigDecimal("50"));
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setProductTypeId(PRODUCT);
        b.setUnit("kg");
        b.setStatus(status);
        when(finishedGoodsBatchRepository.findById(id)).thenReturn(Optional.of(b));
        return b;
    }

    private static BatchAllocationDTO dto(String batchId) {
        BatchAllocationDTO dto = new BatchAllocationDTO();
        dto.setFinishedGoodsBatchId(batchId);
        dto.setAllocatedQty(new BigDecimal("10"));
        return dto;
    }

    @Nested
    @DisplayName("分配批次 (allocateBatches)")
    class Allocate {

        @Test
        @DisplayName("待检 → 409 FINISHED_GOODS_PENDING_INSPECTION, 不落分配")
        void pendingIsRejected() {
            batch("b-pend", FinishedGoodsBatch.Status.PENDING_INSPECTION);
            assertThatThrownBy(() -> allocationService.allocateBatches(FID, ITEM_ID, List.of(dto("b-pend"))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.FG_PENDING);
                        assertThat(e.getMessage()).contains("FG-b-pend");
                    });
            verify(allocationRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("放行 (QcReleaseService) 之后 → 可以分配; 放行记录带结论/放行人/时间")
        void releasedIsAllowed() {
            FinishedGoodsBatch b = batch("b-rel", FinishedGoodsBatch.Status.PENDING_INSPECTION);
            QcReleaseRecord rec = releaseService.decide(FID, new QcReleaseService.DecisionCommand(
                    QcReleaseRecord.TargetType.FINISHED_GOODS, "b-rel", "RELEASED", "出厂检验合格"), 7L, "质检员乙");
            assertThat(b.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
            assertThat(rec.getDecision()).isEqualTo("RELEASED");
            assertThat(rec.getReleasedBy()).isEqualTo(7L);
            assertThat(rec.getReleasedByName()).isEqualTo("质检员乙");
            assertThat(rec.getReleasedAt()).isNotNull();
            assertThatCode(() -> allocationService.allocateBatches(FID, ITEM_ID, List.of(dto("b-rel"))))
                    .doesNotThrowAnyException();
            verify(allocationRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("阴性对照: 没开需检的批次 (一直 AVAILABLE) → 照常分配")
        void plainIsUnaffected() {
            batch("b-plain", FinishedGoodsBatch.Status.AVAILABLE);
            assertThatCode(() -> allocationService.allocateBatches(FID, ITEM_ID, List.of(dto("b-plain"))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("判不合格 → DEFECTIVE, 走既有的不可售错误码")
        void rejectedBecomesDefective() {
            FinishedGoodsBatch b = batch("b-rej", FinishedGoodsBatch.Status.PENDING_INSPECTION);
            releaseService.decide(FID, new QcReleaseService.DecisionCommand(
                    QcReleaseRecord.TargetType.FINISHED_GOODS, "b-rej", "REJECTED", "微生物超标"), 7L, null);
            assertThat(b.getStatus()).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
            assertThatThrownBy(() -> allocationService.allocateBatches(FID, ITEM_ID, List.of(dto("b-rej"))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("FINISHED_GOODS_BATCH_NOT_SALEABLE"));
        }
    }

    @Nested
    @DisplayName("发货扣减 (deductFinishedGoodsInventory, 按已分配批次)")
    class Ship {

        private void allocateFor(FinishedGoodsBatch b) {
            SalesDeliveryItemBatchAllocation a = new SalesDeliveryItemBatchAllocation();
            a.setFactoryId(FID);
            a.setDeliveryItemId(ITEM_ID);
            a.setFinishedGoodsBatchId(b.getId());
            a.setBatchNumber(b.getBatchNumber());
            a.setAllocatedQty(new BigDecimal("10"));
            a.setUnit("kg");
            when(batchAllocationService.listByDeliveryItem(FID, ITEM_ID)).thenReturn(List.of(a));
        }

        private void deduct() throws Exception {
            SalesDeliveryItem item = deliveryItemRepository.findById(42L).orElseThrow();
            Method m = SalesServiceImpl.class.getDeclaredMethod(
                    "deductFinishedGoodsInventory", String.class, String.class, SalesDeliveryItem.class, Long.class);
            m.setAccessible(true);
            try {
                m.invoke(salesService, FID, (String) null, item, 7L);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
        }

        @Test
        @DisplayName("待检 → 拒绝发货, 一件不扣")
        void pendingIsRejected() {
            FinishedGoodsBatch b = batch("s-pend", FinishedGoodsBatch.Status.PENDING_INSPECTION);
            allocateFor(b);
            assertThatThrownBy(this::deduct)
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.FG_PENDING));
            assertThat(b.getShippedQuantity()).isEqualByComparingTo("0");
            verify(finishedGoodsBatchRepository, never()).save(b);
        }

        @Test
        @DisplayName("放行后 → 照常发货")
        void releasedShips() throws Exception {
            FinishedGoodsBatch b = batch("s-rel", FinishedGoodsBatch.Status.PENDING_INSPECTION);
            releaseService.decide(FID, new QcReleaseService.DecisionCommand(
                    QcReleaseRecord.TargetType.FINISHED_GOODS, "s-rel", "RELEASED", "合格"), 7L, null);
            allocateFor(b);
            deduct();
            assertThat(b.getShippedQuantity()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("阴性对照: 没开需检 → 照常发货")
        void plainShips() throws Exception {
            FinishedGoodsBatch b = batch("s-plain", FinishedGoodsBatch.Status.AVAILABLE);
            allocateFor(b);
            deduct();
            assertThat(b.getShippedQuantity()).isEqualByComparingTo("10");
        }
    }

    @Nested
    @DisplayName("成品入库扣留 (QcInspectionPolicy, 四条成品入库路径共用)")
    class OutputHold {

        private ProductType product(String id, boolean required) {
            ProductType pt = new ProductType();
            pt.setId(id);
            pt.setFactoryId(FID);
            pt.setProductCategory(ProductCategory.FINISHED_PRODUCT);
            pt.setInspectionRequired(required);
            return pt;
        }

        @Test
        @DisplayName("需检成品入库 → PENDING_INSPECTION; 未需检 → AVAILABLE; 已隔离的 DEFECTIVE 不被改")
        void finishedGoodsStatusFollowsSwitch() {
            when(productTypeRepository.findByIdAndFactoryId("PT-REQ", FID)).thenReturn(Optional.of(product("PT-REQ", true)));
            when(productTypeRepository.findByIdAndFactoryId("PT-OFF", FID)).thenReturn(Optional.of(product("PT-OFF", false)));
            QcInspectionPolicy policy = new QcInspectionPolicy(productTypeRepository);
            String held = policy.finishedGoodsStatusForOutput(FID, "PT-REQ", "PLAN-1",
                    FinishedGoodsBatch.Status.AVAILABLE, "CLK-B-1");
            System.out.println("FG_STATUS_FOR_REQUIRED=" + held);
            assertThat(held).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION);
            assertThat(policy.finishedGoodsStatusForOutput(FID, "PT-OFF", "PLAN-1",
                    FinishedGoodsBatch.Status.AVAILABLE, "CLK-B-1")).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
            assertThat(policy.finishedGoodsStatusForOutput(FID, "PT-REQ", "PLAN-1",
                    FinishedGoodsBatch.Status.DEFECTIVE, "CLK-B-1")).isEqualTo(FinishedGoodsBatch.Status.DEFECTIVE);
        }
    }
}
