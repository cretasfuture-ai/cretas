package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.event.ProductionCompletedEvent;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「生产完成」这条链上的「谁干的」必须一路传下去，⛔ 不许在半路写死一个数字。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>{@link ProductionCompletedEvent} 原来<b>不带任何操作人</b>。它的两个监听器都需要一个
 * actor 来标注自己做的事，于是<b>各自发明了一个</b> {@code private static final SYSTEM_USER_ID = 0L}：
 *
 * <ul>
 *   <li>{@link ReverseTransferService} → {@code TransferService.createTransfer(…, 0L)}
 *       → {@code internal_transfers.requested_by = 0}</li>
 *   <li>{@link ProductionPlanTransferLifecycleListener} →
 *       {@code closeOpenTransfersForProductionPlan(…, 0L, …)}
 *       → {@code workflowEngine.cancel(…, 0L, …)} → {@code approval_history.actor_id = 0}</li>
 * </ul>
 *
 * <h2>prod 实测（2026-08-19，cretas_prod_db 只读）</h2>
 *
 * <p>{@code users} 表 79 行，{@code min(id) = 1309}，{@code id <= 10} 的行数是 <b>0</b> ——
 * 那个 0 从来不指向任何人，它只是「没人填」伪装成了一个用户 id。
 *
 * <p>⚠️ 它<b>不炸</b>，所以九个月没人发现：全库 147 个 actor 列扫下来，
 * {@code internal_transfers.requested_by} 与 {@code approval_history.actor_id}
 * <b>都可空、都没有外键指向 users</b>。落进去的 0 是一条悬空引用，不是一次违约。
 * 实际落库的有 <b>2 行</b>：{@code TRF-20260816-5192} / {@code TRF-20260817-9377}，
 * 都是 F006、都是 DRAFT、都来自 D1 自动反向调拨（{@code remark} 含 {@code PR #309 A3=A.}）。
 *
 * <p>用户看到的症状：{@code TransferServiceImpl#resolveActorNames} 拿 0 去
 * {@code findAllById} 查不到人 → {@code requestedByName} 为 null →
 * <b>调拨详情页「申请人」是空的</b>。而 fool-proof-design Rule 2 正是 2026-07-04 为了
 * F006 反馈「申请人显示成裸 userId」才加的 —— 同一个工厂、同一栏，换了个形态又回来了。
 *
 * <h2>⛔ 为什么不兜底、也不抛异常</h2>
 *
 * <p>两个下游列都<b>可空且无外键</b>，null 是合法值。所以：
 *
 * <ul>
 *   <li>⛔ 不兜底一个假 id —— 形态 A¹⁰：兜底会把「不知道是谁」写成「就是那个人」，
 *       而且下次那个人被删掉时又是一次静默失败。</li>
 *   <li>⛔ 也不像 {@code production_plans} / {@code purchase_orders} 那样抛异常 ——
 *       那两处的列是 {@code NOT NULL} + 外键，不抛就是静默丢数据；这里不同，
 *       为了「归属不明」就拒绝建调拨草稿，会把「余料还在车间仓」这件事丢掉，
 *       比归属为空糟得多。逐字同 {@code SupplyChainOrchestrator} 里
 *       {@code fg.setCreatedBy} 那处 2026-08-19 的判断。</li>
 * </ul>
 *
 * <p>⇒ <b>有就如实传，没有就如实留空。</b>
 *
 * @see HardcodedActorIdContractTestNote 该族的棘轮闸只扫 setter 名，看不见本文件守的这种
 *      「常量当位置参数传给 actor 形参」的形状 —— 这也是它当初漏掉这两处的原因。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("生产完成链路: 「谁干的」必须一路传到底, 不许写死 0L")
class ProductionCompletedActorPropagationTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "plan-uuid-1";
    private static final String PLAN_NUMBER = "PLAN-1786724332978-6013FEBE";
    private static final String PRODUCT_TYPE_ID = "prod-uuid-1";
    private static final String WKS_WAREHOUSE_ID = "wh-wks-uuid";
    private static final String LOG_WAREHOUSE_ID = "wh-log-uuid";

    /** prod 实测 {@code users.min(id)}，用它当「一个真实的人」。 */
    private static final Long SUPERVISOR_ID = 1309L;
    private static final Long BATCH_CREATOR_ID = 1310L;

    /**
     * ⛔ 这个值是被守的对象本身，不是一个可以随手换的常量。
     * 它出现在断言里只为了说明「这个数不许再出现」。
     */
    private static final Long NOBODY = 0L;

    // ============================================================
    // ① 发布端: 事件必须带上「谁干的」
    // ============================================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("① 发布端 SupplyChainOrchestrator")
    class Publisher {

        @Mock private InventoryMatchingService inventoryMatchingService;
        @Mock private BomExpansionService bomExpansionService;
        @Mock private ProcurementSuggestionService procurementSuggestionService;
        @Mock private ProductionPlanRepository productionPlanRepository;
        @Mock private ProductionBatchRepository productionBatchRepository;
        @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
        @Mock private ApplicationEventPublisher applicationEventPublisher;
        @Mock private QualityInspectionService qualityInspectionService;
        @Mock private BatchConsumptionService batchConsumptionService;
        @Mock private ProductTypeRepository productTypeRepository;
        @Mock private WarehouseResolver warehouseResolver;
        @Mock private FactorySettingsRepository factorySettingsRepository;
        @Mock private LinkArrayService linkArrayService;

        @InjectMocks
        private SupplyChainOrchestrator orchestrator;

        private ProductionBatch driveBatchCompletion(Long supervisorId, Long createdBy) {
            ReflectionTestUtils.setField(orchestrator, "warehouseResolver", warehouseResolver);
            ReflectionTestUtils.setField(orchestrator, "factorySettingsRepository", factorySettingsRepository);

            ProductionBatch batch = new ProductionBatch();
            batch.setId(1L);
            batch.setFactoryId(FACTORY_ID);
            batch.setProductTypeId(PRODUCT_TYPE_ID);
            batch.setProductName("猪舌120g");
            batch.setGoodQuantity(new BigDecimal("100"));
            batch.setUnit("kg");
            batch.setIsTrial(false);
            batch.setStatus(ProductionBatchStatus.COMPLETED);
            batch.setProductionPlanId(PLAN_ID);
            batch.setSupervisorId(supervisorId);
            batch.setCreatedBy(createdBy);

            ProductionPlan plan = new ProductionPlan();
            plan.setId(PLAN_ID);
            plan.setFactoryId(FACTORY_ID);
            plan.setPlanNumber(PLAN_NUMBER);
            plan.setProductTypeId(PRODUCT_TYPE_ID);
            plan.setActualQuantity(BigDecimal.ZERO);

            when(factorySettingsRepository.findSkipProcessReportingDefaultByFactoryId(FACTORY_ID))
                    .thenReturn(false);
            when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(productTypeRepository.findById(anyString())).thenReturn(Optional.empty());
            when(finishedGoodsBatchRepository.save(any(FinishedGoodsBatch.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(warehouseResolver.resolveWorkshopId(anyString())).thenReturn(WKS_WAREHOUSE_ID);
            when(productionPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
            when(productionPlanRepository.save(any(ProductionPlan.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            // 0 个未完成批次 ⇒ 计划整体完成 ⇒ 发布 ProductionCompletedEvent
            when(productionBatchRepository.countByProductionPlanIdAndStatusNot(any(), any()))
                    .thenReturn(0L);

            orchestrator.onBatchCompleted(new BatchCompletedEvent(this, batch));
            return batch;
        }

        private ProductionCompletedEvent capturePublishedEvent() {
            // ⚠️ 必须是 ApplicationEvent.class 而不是 Object.class：
            //    publishEvent 是**重载**的 (publishEvent(Object) / publishEvent(ApplicationEvent))，
            //    而 ProductionCompletedEvent extends ApplicationEvent ⇒ 实际调的是后者。
            //    用 Object 捕获会去 verify **另一个方法**，报「Wanted but not invoked」，
            //    长得像「事件根本没发」—— 而它其实发了。
            ArgumentCaptor<org.springframework.context.ApplicationEvent> cap =
                    ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
            verify(applicationEventPublisher, org.mockito.Mockito.atLeastOnce())
                    .publishEvent(cap.capture());
            return cap.getAllValues().stream()
                    .filter(ProductionCompletedEvent.class::isInstance)
                    .map(ProductionCompletedEvent.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "没有发布 ProductionCompletedEvent —— 前置条件没搭好, "
                                    + "后面关于 completedBy 的断言无论红绿都没有意义"));
        }

        @Test
        @DisplayName("有生产负责人 → 事件带的就是他")
        void supervisorBecomesTheActor() {
            driveBatchCompletion(SUPERVISOR_ID, BATCH_CREATOR_ID);

            assertThat(capturePublishedEvent().getCompletedBy())
                    .as("生产负责人在场时, 这批货就归他 —— 与同文件 fg.setCreatedBy 同一口径")
                    .isEqualTo(SUPERVISOR_ID);
        }

        @Test
        @DisplayName("没有生产负责人 → 退到批次建单人")
        void fallsBackToBatchCreator() {
            driveBatchCompletion(null, BATCH_CREATOR_ID);

            assertThat(capturePublishedEvent().getCompletedBy())
                    .as("没有负责人时退到建单人, 而不是退到一个写死的数字")
                    .isEqualTo(BATCH_CREATOR_ID);
        }

        @Test
        @DisplayName("两个都没有 → 如实留空, ⛔ 不是 0")
        void unknownActorStaysNull() {
            driveBatchCompletion(null, null);

            assertThat(capturePublishedEvent().getCompletedBy())
                    .as("归属不明就如实留空 —— 兜底一个 0 会把「不知道是谁」写成「就是那个人」")
                    .isNull();
        }
    }

    // ============================================================
    // ② 消费端 A: 生产计划完成 → 关闭未完成的备料调拨
    // ============================================================

    @Nested
    @DisplayName("② ProductionPlanTransferLifecycleListener")
    class CloseTransfersListener {

        private final TransferService transferService = org.mockito.Mockito.mock(TransferService.class);
        private final ProductionPlanTransferLifecycleListener listener =
                new ProductionPlanTransferLifecycleListener(transferService);

        private static final String REASON = "生产计划已正式完成，关闭未完成的备料调拨关联";

        @Test
        @DisplayName("事件带人 → 原样传给 closeOpenTransfersForProductionPlan")
        void forwardsTheEventActor() {
            listener.onProductionCompleted(newEvent(SUPERVISOR_ID));

            verify(transferService).closeOpenTransfersForProductionPlan(
                    FACTORY_ID, PLAN_ID, SUPERVISOR_ID, REASON);
        }

        @Test
        @DisplayName("事件没带人 → 传 null, ⛔ 不许再传 0L")
        void forwardsNullRatherThanNobody() {
            listener.onProductionCompleted(newEvent(null));

            // ⚠️ isNull() 而不是 anyLong(): anyLong() 不匹配 null (本仓 2026-08-15 实测过,
            //    那次一条阴性断言因此两条路都成立)。
            verify(transferService).closeOpenTransfersForProductionPlan(
                    eq(FACTORY_ID), eq(PLAN_ID), isNull(), eq(REASON));
            verify(transferService, never()).closeOpenTransfersForProductionPlan(
                    any(), any(), eq(NOBODY), any());
        }
    }

    // ============================================================
    // ③ 消费端 B: D1 自动反向调拨草稿 —— 就是它在 prod 写出了那 2 行
    // ============================================================

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("③ ReverseTransferService (prod 上落了 2 行 requested_by=0 的那一处)")
    class ReverseTransferDraft {

        @Mock private MaterialBatchRepository materialBatchRepository;
        @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
        @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
        @Mock private WarehouseResolver warehouseResolver;
        @Mock private TransferService transferService;

        @InjectMocks
        private ReverseTransferService service;

        private void stubDraftCreation() {
            when(warehouseResolver.resolveWorkshopId(FACTORY_ID)).thenReturn(WKS_WAREHOUSE_ID);
            when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn(LOG_WAREHOUSE_ID);

            MaterialBatch leftover = new MaterialBatch();
            leftover.setId("mb-1");
            leftover.setFactoryId(FACTORY_ID);
            leftover.setMaterialTypeId("mat-A");
            leftover.setWarehouseId(WKS_WAREHOUSE_ID);
            leftover.setStatus(MaterialBatchStatus.AVAILABLE);
            leftover.setReceiptQuantity(new BigDecimal("100"));
            leftover.setUsedQuantity(new BigDecimal("20"));
            leftover.setReservedQuantity(BigDecimal.ZERO);
            when(materialBatchRepository.findAllAvailableInWarehouse(FACTORY_ID, WKS_WAREHOUSE_ID))
                    .thenReturn(List.of(leftover));

            RawMaterialType type = new RawMaterialType();
            type.setId("mat-A");
            type.setName("面粉");
            type.setUnit("kg");
            when(rawMaterialTypeRepository.findById("mat-A")).thenReturn(Optional.of(type));
            when(finishedGoodsBatchRepository
                    .findAvailableByPlanAndWarehouse(FACTORY_ID, PLAN_ID, WKS_WAREHOUSE_ID))
                    .thenReturn(List.of());

            InternalTransfer created = new InternalTransfer();
            created.setId("xfer-1");
            created.setTransferNumber("TRF-20260816-5192");
            created.setTargetFactoryId(FACTORY_ID);
            created.setStatus(TransferStatus.DRAFT);
            // ⚠️ any() 而不是 anyLong() —— null actor 是本测试的主场景, anyLong() 不匹配它,
            //    桩会落空, 返回 null 后 NPE, 长得像「业务没执行」。
            when(transferService.createTransfer(anyString(), any(CreateTransferRequest.class), any()))
                    .thenReturn(created);
        }

        @Test
        @DisplayName("事件带人 → 草稿的申请人就是他")
        void draftIsRequestedByTheEventActor() {
            stubDraftCreation();

            service.onProductionCompleted(newEvent(SUPERVISOR_ID));

            verify(transferService).createTransfer(
                    eq(FACTORY_ID), any(CreateTransferRequest.class), eq(SUPERVISOR_ID));
        }

        @Test
        @DisplayName("事件没带人 → 申请人留空, ⛔ 不许再写 0L")
        void draftRequesterStaysNullRatherThanNobody() {
            stubDraftCreation();

            service.onProductionCompleted(newEvent(null));

            verify(transferService).createTransfer(
                    eq(FACTORY_ID), any(CreateTransferRequest.class), isNull());
            verify(transferService, never()).createTransfer(
                    anyString(), any(CreateTransferRequest.class), eq(NOBODY));
        }
    }

    // ---------- shared ----------

    private static ProductionCompletedEvent newEvent(Long completedBy) {
        return new ProductionCompletedEvent(
                ProductionCompletedActorPropagationTest.class, FACTORY_ID, PLAN_ID,
                PLAN_NUMBER, PRODUCT_TYPE_ID, new BigDecimal("100"), completedBy);
    }

    /** 仅供上面 javadoc 的 {@code @see} 引用，指向那道只扫 setter 名的棘轮闸。 */
    private interface HardcodedActorIdContractTestNote { }
}
