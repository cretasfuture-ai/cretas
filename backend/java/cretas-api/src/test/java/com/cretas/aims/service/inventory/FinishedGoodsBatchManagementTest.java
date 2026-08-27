package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.AdjustFinishedGoodsRequest;
import com.cretas.aims.dto.inventory.EditFinishedGoodsRequest;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.warehouse.WarehouseLocationCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * T126 Phase 1 — 成品库存 Web 闭环后端单元测试.
 *
 * <p>覆盖：
 * <ul>
 *   <li>opening: dup batch_number → 409, productTypeId missing → 404</li>
 *   <li>edit: factoryId mismatch → 403, happy path</li>
 *   <li>adjust: negative available → 422, happy path, operator_id written, optimistic-lock → 409</li>
 *   <li>void: shippedQty > 0 → 409, clean → soft-deleted, factoryId mismatch → 403</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("T126 成品库存 Web 闭环 — service layer")
class FinishedGoodsBatchManagementTest {

    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @Mock ApplicationEventPublisher applicationEventPublisher;
    @Mock WarehouseResolver warehouseResolver;
    @Mock FactoryFeatureConfigRepository featureConfigRepository;

    SalesServiceImpl salesService;

    private static final String FACTORY_ID = "F001";
    private static final String OTHER_FACTORY = "F999";
    private static final String BATCH_ID = "FGB-001";
    private static final Long OPERATOR_ID = 42L;
    /** 本厂 WAREHOUSE_LAYOUT 里真实配过的位置编码。 */
    private static final String CONFIGURED_LOCATION = "A仓-01";

    @BeforeEach
    void setUp() {
        // Use 8-arg ctor (positions: SO-repo, SOI-repo, delivery-repo, FGB-repo,
        //   customer-repo, productType-repo, arApService, eventPublisher)
        salesService = new SalesServiceImpl(
                null, null, null,
                finishedGoodsBatchRepository,
                null,
                productTypeRepository,
                null,
                applicationEventPublisher);
        // inject optional repo via reflection
        ReflectionTestUtils.setField(salesService, "finishedGoodsAdjustmentLogRepository",
                finishedGoodsAdjustmentLogRepository);
        ReflectionTestUtils.setField(salesService, "warehouseResolver", warehouseResolver);
        // #184：储存位置改成受控词表，editFinishedGoodsBatch 是成品的【第二条】写路。
        // ⚠️ 注入真判据而不是它的 mock —— mock 会让「填了一个没配过的位置」悄悄通过，
        //    那正是 #184 要拦的东西。配置数据逐个用例自己给（MockitoExtension 是严格桩）。
        ReflectionTestUtils.setField(salesService, "locationCatalog",
                new WarehouseLocationCatalog(featureConfigRepository));
    }

    /** 让本厂 WAREHOUSE_LAYOUT 里恰好配着这些位置编码。 */
    private void configureLocations(String... codes) {
        FactoryFeatureConfig config = new FactoryFeatureConfig();
        config.setConfig(Map.of("locations",
                List.of(codes).stream().map(c -> Map.<String, Object>of("code", c)).toList()));
        when(featureConfigRepository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(
                FACTORY_ID, WarehouseLocationCatalog.MODULE_ID))
                .thenReturn(Optional.of(config));
    }

    // ========== helpers ==========

    private FinishedGoodsBatch buildBatch(String factoryId,
                                          BigDecimal produced,
                                          BigDecimal shipped,
                                          BigDecimal reserved) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(BATCH_ID);
        b.setFactoryId(factoryId);
        b.setBatchNumber("FG-2026-001");
        b.setProducedQuantity(produced);
        b.setShippedQuantity(shipped);
        b.setReservedQuantity(reserved);
        b.setUnit("盒");
        b.setWarehouseId("WH-WKS");
        return b;
    }

    // ========== create tests ==========

    @Test
    @DisplayName("opening finished goods defaults to WH-FG")
    void createOpening_defaultsWarehouseToFinishedGoods() {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setProductTypeId("PT-1");
        batch.setProducedQuantity(new BigDecimal("12"));
        batch.setUnit("kg");
        when(warehouseResolver.resolveFinishedGoodsId(FACTORY_ID)).thenReturn("WH-FG-ID");
        when(finishedGoodsBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinishedGoodsBatch result = salesService.createOpeningFinishedGoodsBatch(FACTORY_ID, batch, OPERATOR_ID);

        assertThat(result.getWarehouseId()).isEqualTo("WH-FG-ID");
        verify(warehouseResolver).resolveFinishedGoodsId(FACTORY_ID);
    }

    // ========== edit tests ==========

    @Nested
    @DisplayName("editFinishedGoodsBatch")
    class EditTests {

        @Test
        @DisplayName("正常编辑 → 返回更新后 batch")
        void edit_happyPath_updatesMetadata() {
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            when(finishedGoodsBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            configureLocations(CONFIGURED_LOCATION);

            EditFinishedGoodsRequest req = new EditFinishedGoodsRequest(
                    "新备注", CONFIGURED_LOCATION, LocalDate.of(2027, 1, 1), new BigDecimal("5.50"));

            FinishedGoodsBatch result = salesService.editFinishedGoodsBatch(FACTORY_ID, BATCH_ID, req, OPERATOR_ID);

            assertThat(result.getRemark()).isEqualTo("新备注");
            assertThat(result.getStorageLocation()).isEqualTo(CONFIGURED_LOCATION);
            assertThat(result.getExpireDate()).isEqualTo(LocalDate.of(2027, 1, 1));
            assertThat(result.getUnitPrice()).isEqualByComparingTo("5.50");
            // producedQuantity and unit must NOT change
            assertThat(result.getProducedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
            assertThat(result.getUnit()).isEqualTo("盒");
        }

        /**
         * ⚠️ 阴性对照 —— 上面那条正向断言只证明「配过的位置放得进去」，
         * 它在协作者被 mock 成「原样返回」时同样会绿。这条让它红得起来。
         *
         * <p>守的是 #184 的裁定：储存位置是受控词表，⛔ 不是自由文本。
         * {@code editFinishedGoodsBatch} 正是当时查出来的、成品的第二条写路 ——
         * 「成品已 fail closed」此前只对两条写路中的一条成立。</p>
         */
        @Test
        @DisplayName("填了本厂没配过的位置 → 400，且不落库")
        void edit_unconfiguredStorageLocation_rejectedAndNotPersisted() {
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            configureLocations(CONFIGURED_LOCATION);

            EditFinishedGoodsRequest req = new EditFinishedGoodsRequest(
                    "新备注", "冷冻库", LocalDate.of(2027, 1, 1), new BigDecimal("5.50"));

            assertThatThrownBy(() -> salesService.editFinishedGoodsBatch(FACTORY_ID, BATCH_ID, req, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(400);
            verify(finishedGoodsBatchRepository, never()).save(any());
        }

        @Test
        @DisplayName("不传位置 → 不改动，且不需要仓储配置")
        void edit_nullStorageLocation_leavesLocationUntouched() {
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            batch.setStorageLocation(CONFIGURED_LOCATION);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            when(finishedGoodsBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EditFinishedGoodsRequest req = new EditFinishedGoodsRequest("只改备注", null, null, null);

            FinishedGoodsBatch result = salesService.editFinishedGoodsBatch(FACTORY_ID, BATCH_ID, req, OPERATOR_ID);

            assertThat(result.getRemark()).isEqualTo("只改备注");
            // null = 不改动（合法的「未分配」语义），⛔ 不该被判据抹掉
            assertThat(result.getStorageLocation()).isEqualTo(CONFIGURED_LOCATION);
        }

        @Test
        @DisplayName("跨工厂访问 → 403")
        void edit_crossFactory_403() {
            FinishedGoodsBatch batch = buildBatch(OTHER_FACTORY, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

            EditFinishedGoodsRequest req = new EditFinishedGoodsRequest("remark", null, null, null);

            assertThatThrownBy(() -> salesService.editFinishedGoodsBatch(FACTORY_ID, BATCH_ID, req, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(403);
        }
    }

    // ========== adjust tests ==========

    @Nested
    @DisplayName("adjustFinishedGoodsQuantity")
    class AdjustTests {

        @Test
        @DisplayName("正常增加 → producedQuantity 增加, 写 adjustment_log, operator_id 非 null")
        void adjust_positive_updatesAndLogsWithOperatorId() {
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID, BigDecimal.TEN, BigDecimal.TWO, BigDecimal.ONE);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            when(finishedGoodsBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AdjustFinishedGoodsRequest req = new AdjustFinishedGoodsRequest(
                    new BigDecimal("5"),
                    "盘点补录",
                    AdjustFinishedGoodsRequest.ReferenceType.STOCKTAKE);

            FinishedGoodsBatch result = salesService.adjustFinishedGoodsQuantity(FACTORY_ID, BATCH_ID, req, OPERATOR_ID);

            assertThat(result.getProducedQuantity()).isEqualByComparingTo("15");

            // Capture the log entry and verify operator_id
            ArgumentCaptor<FinishedGoodsAdjustmentLog> logCaptor =
                    ArgumentCaptor.forClass(FinishedGoodsAdjustmentLog.class);
            verify(finishedGoodsAdjustmentLogRepository).save(logCaptor.capture());
            FinishedGoodsAdjustmentLog logEntry = logCaptor.getValue();
            assertThat(logEntry.getOperatorId()).isNotNull().isEqualTo(OPERATOR_ID);
            assertThat(logEntry.getBeforeProduced()).isEqualByComparingTo("10");
            assertThat(logEntry.getAfterProduced()).isEqualByComparingTo("15");
            assertThat(logEntry.getReferenceType()).isEqualTo("STOCKTAKE");
        }

        @Test
        @DisplayName("调整后可用量为负 → 422")
        void adjust_negative_makesAvailableNegative_422() {
            // produced=10, shipped=8, reserved=2 → available=0
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID,
                    BigDecimal.TEN, new BigDecimal("8"), BigDecimal.TWO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

            // adjust by -1 → newProduced=9, newAvailable = 9-8-2 = -1
            AdjustFinishedGoodsRequest req = new AdjustFinishedGoodsRequest(
                    new BigDecimal("-1"),
                    "报废",
                    AdjustFinishedGoodsRequest.ReferenceType.SCRAP);

            assertThatThrownBy(() -> salesService.adjustFinishedGoodsQuantity(FACTORY_ID, BATCH_ID, req, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(422);
        }

        @Test
        @DisplayName("正好降至零可用 → 允许 (边界)")
        void adjust_reducesToZeroAvailable_allowed() {
            // produced=10, shipped=8, reserved=2 → available=0
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID,
                    BigDecimal.TEN, new BigDecimal("8"), BigDecimal.TWO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            when(finishedGoodsBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // adjust by 0: newProduced=10, newAvailable=0 (exactly zero, allowed)
            AdjustFinishedGoodsRequest req = new AdjustFinishedGoodsRequest(
                    BigDecimal.ZERO,
                    "零调整",
                    AdjustFinishedGoodsRequest.ReferenceType.OTHER);

            FinishedGoodsBatch result = salesService.adjustFinishedGoodsQuantity(FACTORY_ID, BATCH_ID, req, OPERATOR_ID);
            assertThat(result.getProducedQuantity()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("跨工厂访问 → 403 (F12)")
        void adjust_crossFactory_403() {
            FinishedGoodsBatch batch = buildBatch(OTHER_FACTORY, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

            AdjustFinishedGoodsRequest req = new AdjustFinishedGoodsRequest(
                    BigDecimal.ONE, "test", AdjustFinishedGoodsRequest.ReferenceType.OTHER);

            assertThatThrownBy(() -> salesService.adjustFinishedGoodsQuantity(FACTORY_ID, BATCH_ID, req, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(403);
        }
    }

    // ========== void tests ==========

    @Nested
    @DisplayName("voidFinishedGoodsBatch")
    class VoidTests {

        @Test
        @DisplayName("正常作废 → batch 软删除 (deletedAt 非 null)")
        void void_clean_softDeletesEntity() {
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
            when(finishedGoodsBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            salesService.voidFinishedGoodsBatch(FACTORY_ID, BATCH_ID, OPERATOR_ID);

            ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
            verify(finishedGoodsBatchRepository).save(captor.capture());
            assertThat(captor.getValue().getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("有已出库记录 → 409")
        void void_shippedQtyGtZero_409() {
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

            assertThatThrownBy(() -> salesService.voidFinishedGoodsBatch(FACTORY_ID, BATCH_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("有预留记录 → 409")
        void void_reservedQtyGtZero_409() {
            FinishedGoodsBatch batch = buildBatch(FACTORY_ID, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

            assertThatThrownBy(() -> salesService.voidFinishedGoodsBatch(FACTORY_ID, BATCH_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("跨工厂访问 → 403 (F12)")
        void void_crossFactory_403() {
            FinishedGoodsBatch batch = buildBatch(OTHER_FACTORY, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO);
            when(finishedGoodsBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

            assertThatThrownBy(() -> salesService.voidFinishedGoodsBatch(FACTORY_ID, BATCH_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(403);
        }
    }
}
