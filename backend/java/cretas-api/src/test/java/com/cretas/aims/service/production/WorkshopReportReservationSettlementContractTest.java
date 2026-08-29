package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔒 钉住「报工提交即占用生产仓, 结单前必须先释放」这条<b>设计约束</b>的完整闭环。
 *
 * <h3>需求</h3>
 *
 * <p>生产仓可用量要诚实: {@code 可用 = 在手 − 已提交报工引用量}。
 * {@code material_batches.reserved_quantity} 正是为此存在的列 ——
 * {@link MaterialBatch#getCurrentQuantity()} 已经减它。
 * 2026-08-18 prod 实测 F006 全部 65 个在用批次该列都是 0 (同表 receipt&gt;0 的 65 行 /
 * used&gt;0 的 50 行作阳性对照, 证明读得到真值) ⇒ <b>没有任何代码在写它</b>,
 * 于是同一批料可以被别的计划重复领用/结算。
 *
 * <h3>难点: 占用会把自己挡在门外</h3>
 *
 * <p>结单路径<b>三处闸</b>读的是同一个 {@code getCurrentQuantity()}
 * ({@code deriveRawConsumptions} 预填 / {@code resolveAvailableBefore} 校验 /
 * {@code postMaterialBatchConsumption} 过账)。prod 上领料按报工所需量整批建生产仓批次 ⇒
 * <b>引用量恰好等于收货量</b> (2026-08-18 实测 16/16 全部 refQty == receipt == 20) ⇒
 * 一旦提交即预留, {@code available = 20 − 0 − 20 = 0}, <b>每一单</b>都结不掉。
 *
 * <p>反向也堵死: 「先扣后放」时 {@code used} 已加上去而 reserved 还挂着 ⇒
 * {@code used + reserved > receipt} ⇒ {@link MaterialBatch#assertConsumptionInvariant()} 当场炸。
 *
 * <p>⇒ 可行解<b>唯一</b>: 释放发生在结单三道闸之前, 即 {@code settleProduction} 内部。
 * 下面 G4/G5 两条把这个「唯一」证出来, G1~G3 钉住实现真的落在那个位置上。
 *
 * <h3>⚠️ 与 PR#2866 原版的差异 (为什么两条断言翻了面)</h3>
 *
 * <p>原版 G2/G3 断言的是<b>缺陷本身</b>「结单对自己预留的那一笔拒绝」和「拒绝理由说错了」。
 * 那两条不是恒真式、也不是变异没生效 —— 它们好好地守着一个<b>现在被判定为缺陷</b>的行为。
 * 需求实现之后它们理应变红, 于是<b>把守的东西从字面抬到性质</b>:
 * G2 改成守「本计划自己的占用必须在扣减前被释放掉」, G3 改成守
 * 「⛔ 任何情况下都不许把人支去找一个不存在的冲突计划」(阴性对照保留原来那句黑话字面)。
 *
 * @see WorkshopReportReservationService
 * @see MaterialBatch#getCurrentQuantity()
 */
@DisplayName("生产仓预留 × 结单扣减 契约")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkshopReportReservationSettlementContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PP-RESERVE-1";
    private static final String BATCH_ID = "MB-WKS-1";
    private static final String MATERIAL_TYPE_ID = "RM-1";
    private static final Long PRODUCTION_BATCH_ID = 10761L;
    private static final Long REPORT_ID = 23836L;

    /** prod 实测形状: 领料按报工所需量整批建仓, 收货量 == 报工引用量 == 20kg。 */
    private static final BigDecimal RECEIPT = new BigDecimal("20");
    private static final BigDecimal REPORTED_REF = new BigDecimal("20");

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private BomService bomService;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;
    @Mock private ProductionSettlementLaborRepository productionSettlementLaborRepository;
    @Mock private SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private ProductionReportRepository productionReportRepository;

    private ProductionPlanServiceImpl service;
    private WorkshopReportReservationService reservationService;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository",
                productionSettlementConsumptionRepository);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository",
                productionSettlementLaborRepository);
        ReflectionTestUtils.setField(service, "semiFinishedInventoryRepository", semiFinishedInventoryRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "bomRecipeRepository", bomRecipeRepository);
        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRecipeItemRepository);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        ReflectionTestUtils.setField(service, "productionReportRepository", productionReportRepository);

        // ⚠️ 与生产同一个 bean: 释放服务和被测 service 共用 materialBatchRepository /
        //    productionBatchRepository / productionReportRepository —— 三个 mock 只有一份,
        //    ⛔ 不给释放侧另外配一套 (那会让「结单看到的现实」和「释放看到的现实」是两个东西)。
        reservationService = new WorkshopReportReservationService(
                materialBatchRepository, productionBatchRepository, productionReportRepository);
        wireReservationService(reservationService);

        lenient().when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
    }

    private void wireReservationService(WorkshopReportReservationService candidate) {
        ReflectionTestUtils.setField(service, "workshopReservationService", candidate);
    }

    // ─────────────────────────────────────────────────────────────
    // G0 预填闸 —— 自己的预留不能把「核对结单」表单清空
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("G0 🔑 报工占满整批时, 结单预填把本计划自有预留加回并保留原料行")
    void settlementPrefillIncludesMaterialHeldByItsOwnReport() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        wireSettlement(batch);
        ProductionReport report = reportHolding(REPORTED_REF);
        report.setProcessOrder(1);
        report.setProductTypeId("PT-1");
        report.setOutputQuantity(REPORTED_REF);
        report.setOutputUnit("kg");
        wirePlanReports(report);

        ProductionSettlementPrefillResponse response =
                service.getSettlementPrefill(FACTORY_ID, PLAN_ID);

        assertThat(response.getPrefill().getRawMaterialConsumptions())
                .as("本计划自己的 20kg 预留会在正式结单前释放, 预填不能先把这一行丢掉")
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getMaterialBatchId()).isEqualTo(BATCH_ID);
                    assertThat(line.getQuantity()).isEqualByComparingTo(REPORTED_REF);
                });
        assertThat(response.getAudit().getIssues())
                .noneMatch(issue -> "RAW_BATCH_INSUFFICIENT".equals(issue.getCode()));
    }

    @Test
    @DisplayName("G0b ⛔ 反向保护: 无法证明是本计划的预留时, available=0 仍拒绝自动带入")
    void settlementPrefillDoesNotReclaimUnattributedReservation() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        wireSettlement(batch);
        ProductionReport report = reportHolding(REPORTED_REF);
        report.setProcessOrder(1);
        report.setProductTypeId("PT-1");
        report.setOutputQuantity(REPORTED_REF);
        report.setOutputUnit("kg");
        wirePlanReports(report);
        wireReservationService(null); // 不能证明 reserved 属于当前计划

        ProductionSettlementPrefillResponse response =
                service.getSettlementPrefill(FACTORY_ID, PLAN_ID);

        assertThat(response.getPrefill().getRawMaterialConsumptions()).isEmpty();
        assertThat(response.getAudit().getIssues())
                .anyMatch(issue -> "RAW_BATCH_INSUFFICIENT".equals(issue.getCode()));
    }

    // ─────────────────────────────────────────────────────────────
    // G1 阳性对照 —— 没有预留时, 同一笔量结得掉
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("G1 阳性对照: reserved=0 时 20kg 报工引用量结单扣得下去 (证明桩真的跑到了扣减)")
    void settleDeductsFullBatch_whenNothingReserved() {
        MaterialBatch batch = workshopBatch(BigDecimal.ZERO);
        wireSettlement(batch);
        wirePlanReports();   // 有报工, 但批次上没有挂占 —— 释放是 no-op

        service.settleProduction(FACTORY_ID, PLAN_ID, requestConsuming(REPORTED_REF), 10L);

        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(REPORTED_REF);
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(materialBatchRepository).save(batch);
    }

    // ─────────────────────────────────────────────────────────────
    // G2 接线断言 —— 真实入口 settleProduction 上, 先释放再扣减
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("G2 🔑 报工占了 20kg 时, 结单先释放自己的占用再扣 ⇒ 那一笔扣得下去 (跑在真实入口上)")
    void settleReleasesItsOwnReportHoldBeforeDeducting() {
        // 报工提交时把 materialBatchRefs 的 20kg 记进 reserved_quantity —— prod 的常态形状。
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        wireSettlement(batch);
        wirePlanReports(reportHolding(REPORTED_REF));

        assertThatCode(() ->
                service.settleProduction(FACTORY_ID, PLAN_ID, requestConsuming(REPORTED_REF), 10L))
                .doesNotThrowAnyException();

        // 释放必须发生在扣减【之前】: 这三条同时成立才排除「先扣后放」。
        assertThat(batch.getReservedQuantity())
                .as("占用已释放").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch.getUsedQuantity())
                .as("那一笔真的扣下去了").isEqualByComparingTo(REPORTED_REF);
        assertThatCode(batch::assertConsumptionInvariant)
                .as("used+reserved 没有双记 —— 先扣后放会在这里炸")
                .doesNotThrowAnyException();
        verify(productionSettlementConsumptionRepository).saveAll(anyList());
    }

    // ─────────────────────────────────────────────────────────────
    // G3 变异对照 —— 把释放接线摘掉, G2 必须翻回「结不掉」
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("G3 ⛔ 变异对照: 摘掉释放接线, 同一笔立刻结不掉 —— 证明 G2 绿是【释放】换来的")
    void withoutTheReleaseWiringTheSameSettlementIsRefusedAgain() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        wireSettlement(batch);
        wirePlanReports(reportHolding(REPORTED_REF));
        wireReservationService(null);   // ← 变异: 唯一的释放出口被摘掉

        BusinessException thrown = (BusinessException) org.assertj.core.api.Assertions.catchThrowable(() ->
                service.settleProduction(FACTORY_ID, PLAN_ID, requestConsuming(REPORTED_REF), 10L));

        // 变异真的到达了被测代码: 行为从「结得掉」变成「409 且一克都没扣」。
        assertThat(thrown).as("摘掉释放后必须结不掉, 否则 G2 的绿与释放无关").isNotNull();
        assertThat(thrown.getCode()).isEqualTo(409);
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(materialBatchRepository, never()).save(any(MaterialBatch.class));
        verify(productionSettlementConsumptionRepository, never()).saveAll(anyList());
    }

    // ─────────────────────────────────────────────────────────────
    // G3b 文案 —— 现场没有别的计划时, ⛔ 不许把人支去找一个不存在的计划
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("G3b 🔴 拒绝时不许编造冲突计划: 现场只有本计划一条, 文案必须说是【预留占用】不是【其他未结生产计划】")
    void theRefusalNeverBlamesANonexistentConflictingPlan() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        wireSettlement(batch);
        wirePlanReports(reportHolding(REPORTED_REF));
        wireReservationService(null);
        // 现场只有本计划一条: 没有任何一行属于别人的待结消耗 ⇒ conflict.quantity() == 0。
        when(materialConsumptionRepository.findByFactoryIdAndBatchId(FACTORY_ID, BATCH_ID))
                .thenReturn(new ArrayList<>());

        BusinessException thrown = (BusinessException) org.assertj.core.api.Assertions.catchThrowable(() ->
                service.settleProduction(FACTORY_ID, PLAN_ID, requestConsuming(REPORTED_REF), 10L));

        assertThat(thrown).isNotNull();
        // ⛔ 阴性对照: 那句黑话不许回来 —— 它把人支去找一个不存在的计划。
        assertThat(thrown.getMessage())
                .as("现场没有别的计划, 就不许说是别的计划占的")
                .doesNotContain("其他未结生产计划");
        assertThat(thrown.getActionHint())
                .as("行动指引也不许指向一个不存在的计划")
                .doesNotContain("结清冲突计划");
        // ✅ 正向: 说的是真正的占用方, 且给得出下一步。
        assertThat(thrown.getErrorCode()).isEqualTo("RAW_BATCH_RESERVED_ELSEWHERE");
        assertThat(thrown.getMessage()).contains("预留占用");
        assertThat(thrown.getActionHint()).isNotBlank();
    }

    @Test
    @DisplayName("G3c ✅ 反向保护: 真的有别的计划待结占用时, 那句话必须还在 (⛔ 别把保护一起删了)")
    void aGenuineCrossPlanConflictStillSaysSo() {
        MaterialBatch batch = workshopBatch(BigDecimal.ZERO);
        wireSettlement(batch);
        wirePlanReports();
        // 别的计划在这个批次上挂着 20kg 未结消耗 (productionPlanId 不是本计划)。
        com.cretas.aims.entity.MaterialConsumption other = new com.cretas.aims.entity.MaterialConsumption();
        other.setFactoryId(FACTORY_ID);
        other.setBatchId(BATCH_ID);
        other.setProductionPlanId("PP-OTHER-9");
        other.setQuantity(REPORTED_REF);
        when(materialConsumptionRepository.findByFactoryIdAndBatchId(FACTORY_ID, BATCH_ID))
                .thenReturn(new ArrayList<>(List.of(other)));

        BusinessException thrown = (BusinessException) org.assertj.core.api.Assertions.catchThrowable(() ->
                service.settleProduction(FACTORY_ID, PLAN_ID, requestConsuming(REPORTED_REF), 10L));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getErrorCode()).isEqualTo("RAW_BATCH_CROSS_PLAN_CONFLICT");
        assertThat(thrown.getMessage()).contains("待结占用");
        assertThat(thrown.getActionHint()).isNotBlank();
    }

    // ─────────────────────────────────────────────────────────────
    // G4/G5 —— 把可行解夹死的那两条 (说明为什么释放只能在那一个位置)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("G4 被预留卡死的批次上, assertConsumptionInvariant 一声不吭 (指望它兜底是空的)")
    void consumptionInvariantStaysSilentOnTheReservedButUnsettleableBatch() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);

        assertThatCode(batch::assertConsumptionInvariant).doesNotThrowAnyException();
        // 不变式满足, 可用量却是 0 —— 「合法」和「结得掉」是两件事。
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("G5 先扣后放 (挂在结单事件的 BEFORE_COMMIT 上) 同样不安全: used 与 reserved 双记 ⇒ 不变式当场炸")
    void releasingAfterTheDeductionBlowsTheConsumptionInvariant() {
        MaterialBatch batch = workshopBatch(REPORTED_REF);
        // postMaterialBatchConsumption 只做 used += qty, 不碰 reserved。
        batch.setUsedQuantity(batch.getUsedQuantity().add(REPORTED_REF));

        assertThatThrownBy(batch::assertConsumptionInvariant)
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown ->
                        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo("BATCH_OVER_CONSUMED"));
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    /** 生产仓 (WH-WKS) 原料批次, prod 形状: 收货 20kg, 未耗, 预留量由入参决定。 */
    private MaterialBatch workshopBatch(BigDecimal reserved) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("MT-20260817-8432-WKS-d8c08b6a");
        batch.setMaterialTypeId(MATERIAL_TYPE_ID);
        batch.setReceiptQuantity(RECEIPT);
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(reserved);
        batch.setQuantityUnit("kg");
        batch.setWarehouseId("WH-LOG-ID");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        return batch;
    }

    /**
     * 一份「还占着料」的报工。
     *
     * <p>⚠️ jsonb 形状照抄 2026-08-18 prod 实测: 元素恰好三个键,
     * {@code materialBatchId} 是 String UUID, {@code quantity} 是<b>整数</b>而不是 BigDecimal
     * —— 桩要给上游真会给出的形状, 否则解析路径根本没被测到。
     */
    private ProductionReport reportHolding(BigDecimal quantity) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("materialBatchId", BATCH_ID);
        ref.put("quantity", quantity.intValueExact());
        ref.put("unit", "kg");
        ProductionReport report = new ProductionReport();
        report.setId(REPORT_ID);
        report.setFactoryId(FACTORY_ID);
        report.setBatchId(PRODUCTION_BATCH_ID);
        report.setApprovalStatus("APPROVED");
        report.setMaterialBatchRefs(new ArrayList<>(List.of(ref)));
        return report;
    }

    /** 本计划下的生产批次 + 报工 —— 释放侧枚举「谁还占着料」走的就是这条路。 */
    private void wirePlanReports(ProductionReport... reports) {
        ProductionBatch productionBatch = new ProductionBatch();
        productionBatch.setId(PRODUCTION_BATCH_ID);
        productionBatch.setFactoryId(FACTORY_ID);
        productionBatch.setProductionPlanId(PLAN_ID);
        productionBatch.setBatchNumber("PB-RESERVE-1");
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(new ArrayList<>(List.of(productionBatch)));
        when(productionReportRepository.findYieldReportsByBatch(FACTORY_ID, PRODUCTION_BATCH_ID))
                .thenReturn(new ArrayList<>(List.of(reports)));
    }

    private ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("P-RESERVE-001");
        plan.setProductTypeId("PT-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    /** 结单请求: 单行原料领用 = 报工 materialBatchRefs 聚合出来的那一笔。 */
    private ProductionSettlementRequest requestConsuming(BigDecimal quantity) {
        return ProductionSettlementRequest.builder()
                .idempotencyKey("idem-reserve-1")
                .actualFinishedQuantity(new BigDecimal("90"))
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .rawMaterialConsumptions(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                        .materialBatchId(BATCH_ID)
                        .quantity(quantity)
                        .unit("kg")
                        .build()))
                .semiFinishedConsumptions(new ArrayList<>())
                .auxiliaryConsumptions(new ArrayList<>())
                .laborSegments(List.of(ProductionSettlementRequest.LaborSegment.builder()
                        .workerName("operator")
                        .minutes(120)
                        .headcount(2)
                        .build()))
                .build();
    }

    private void wireSettlement(MaterialBatch batch) {
        ProductionPlan plan = plan();
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
        when(productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                        FACTORY_ID, PLAN_ID, "idem-reserve-1")).thenReturn(Optional.empty());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, PLAN_ID)).thenReturn(Optional.empty());
        when(materialBatchRepository.findByIdAndFactoryId(BATCH_ID, FACTORY_ID)).thenReturn(Optional.of(batch));
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate(BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(batch));
        when(warehouseResolver.resolveLogisticsId(FACTORY_ID)).thenReturn("WH-LOG-ID");
        stubCurrentBom();
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId(FACTORY_ID, PLAN_ID))
                .thenReturn(new ArrayList<>());
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementRepository.save(any(ProductionSettlement.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(productionPlanRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementConsumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(productionSettlementLaborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubCurrentBom() {
        BomRecipe recipe = BomRecipe.builder()
                .id("bom-reserve-1")
                .factoryId(FACTORY_ID)
                .recipeCode("BOM-PT-1")
                .productTypeId("PT-1")
                .productName("Product")
                .outputQuantityPerUnit(BigDecimal.ONE)
                .build();
        BomRecipeItem item = BomRecipeItem.builder()
                .recipeId("bom-reserve-1")
                .factoryId(FACTORY_ID)
                .materialTypeId(MATERIAL_TYPE_ID)
                .standardQuantity(BigDecimal.ONE)
                .unit("kg")
                .build();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY_ID, "PT-1"))
                .thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findByRecipeIdOrderBySortOrderAsc("bom-reserve-1"))
                .thenReturn(List.of(item));
    }
}
