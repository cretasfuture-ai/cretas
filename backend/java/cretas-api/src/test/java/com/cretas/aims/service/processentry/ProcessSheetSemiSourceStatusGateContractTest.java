package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ResolvedEdge;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 🔴 跨计划投料的<b>早期</b>校验 —— 半成品来源必须是还活着的库存行。
 *
 * <h2>补的是哪一段</h2>
 *
 * <p><b>存在性早就在管</b>（{@code resolveEdges} 里那句 {@code orElseThrow} → {@code SFI_NOT_FOUND}，
 * 覆盖 create / re-save / 多产出三条路）。<b>没在管的是状态</b>：一个已经被别的计划领空的批次
 * （{@code DEPLETED}）或已退回总仓的批次（{@code RETURNED}）在保存这一刻照样过，一路留到
 * 「小结 / 结单」才撞 {@code SFI_INSUFFICIENT} —— 那时活已经干完了，操作员面对的是一个改不动的 409。
 * 跨计划共用半成品之后，这个窗口从"少见"变成"日常"。
 *
 * <h2>⛔ 刻意不做的两件事</h2>
 *
 * <ul>
 *   <li><b>不判数量</b>：逐道录入的扣减是刻意延迟的。在保存这一刻拿 {@code availableQuantity}
 *       比投料量，只是把 409 从结算挪到保存，两个人同时保存照样都过 —— 那是伪占用。
 *       真正的收敛在下拉那一侧（预扣，见 {@code WipPickerPendingFeedVisibilityTest}）。</li>
 *   <li><b>不动回料</b>：回料在正式报工事务内就被严格扣减，扣完当场 {@code DEPLETED} ——
 *       拿 AVAILABLE 卡它，"提交完再重存一次"这种正常动作会 409。</li>
 * </ul>
 *
 * <p>⚠️ 这个 409 走 {@code BusinessException409HintCoverageContractTest} 的零容忍集合
 * （本文件在那张名单上），少一个 {@code .withHint(...)} 会把 CI 弄红。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("投料来源状态早校验 — 领空/退库的半成品在保存时就拦下")
class ProcessSheetSemiSourceStatusGateContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "PLAN-CROSS-SEMI";
    private static final String BATCH = "IB-CROSS-1";

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private ProductionPlanRepository productionPlanRepo;
    @Mock private ProcessSheetRowChangeLogRepository changeLogRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository processRepo;
    @Mock private ProductWorkProcessRepository productWorkProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;

    private ProcessSheetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                consumptionRepo, reportRepo, productionPlanRepo, changeLogRepo,
                new ObjectMapper(), wipRepo, taskRepo, processRepo,
                productWorkProcessRepo, productTypeRepo, finishedGoodsBatchRepo,
                wipInventoryService, finishedGoodsFeedService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "intermediateOwnershipGuard",
                org.mockito.Mockito.mock(com.cretas.aims.service.processentry.IntermediateOwnershipGuard.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<ResolvedEdge> resolve(ProcessSheetRowRequest request) throws Throwable {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "resolveEdges", String.class, String.class, ProcessSheetRowRequest.class);
        method.setAccessible(true);
        try {
            return (List<ResolvedEdge>) method.invoke(service, FACTORY, PLAN, request);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private ProcessSheetRowRequest semiFeedRequest() {
        ProcessSheetRowRequest.UpstreamRef source = new ProcessSheetRowRequest.UpstreamRef();
        source.setSourceBatchNumber(BATCH);
        source.setFeedQuantityKg(new BigDecimal("5"));
        source.setSemiFinished(true);
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setProcessCode("qidiao");
        request.setClientRowId("row-1");
        request.setInputUnit("kg");
        request.setUpstreamSources(List.of(source));
        return request;
    }

    private void sourceRow(String status, boolean rework) {
        SemiFinishedInventory sfi = new SemiFinishedInventory();
        sfi.setId(7L);
        sfi.setFactoryId(FACTORY);
        sfi.setIntermediateBatchNo(BATCH);
        sfi.setProductTypeId(null);          // 不比 SKU: 本测试只谈状态这一道闸
        sfi.setStatus(status);
        sfi.setIsRework(rework);
        sfi.setUnit("kg");
        sfi.setAvailableQuantity(status.equals(SemiFinishedInventory.Status.AVAILABLE)
                ? new BigDecimal("100") : BigDecimal.ZERO);
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, BATCH))
                .thenReturn(Optional.of(sfi));
    }

    // ── ① 状态闸本身 ────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 来源已被别的计划领空 (DEPLETED) → 保存即 409 SFI_SOURCE_NOT_AVAILABLE, 带 hint")
    void depletedSource_failsClosedAtSave() {
        sourceRow(SemiFinishedInventory.Status.DEPLETED, false);

        assertThatThrownBy(() -> resolve(semiFeedRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("SFI_SOURCE_NOT_AVAILABLE");
                    assertThat(be.getActionHint())
                            .as("零容忍集合里的 409 必须带 hint (BusinessException409HintCoverageContractTest); "
                                    + "而且 hint 要说【下一步做什么】, 不是复述错误")
                            .isNotBlank()
                            .contains("刷新");
                    assertThat(be.getMessage()).contains(BATCH).contains("已被领用完");
                });
    }

    @Test
    @DisplayName("🔴 来源已退回总仓 (RETURNED) → 同样拦下, 且措辞与「领用完」不同 (两种情况操作员要做的事不同)")
    void returnedSource_failsClosedWithItsOwnWording() {
        sourceRow(SemiFinishedInventory.Status.RETURNED, false);

        assertThatThrownBy(() -> resolve(semiFeedRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo("SFI_SOURCE_NOT_AVAILABLE");
                    assertThat(e.getMessage()).contains("已退回总仓");
                });
    }

    @Test
    @DisplayName("阴性对照: AVAILABLE 来源照常放行 (证明这道闸不是恒拦)")
    void availableSource_passes() throws Throwable {
        sourceRow(SemiFinishedInventory.Status.AVAILABLE, false);

        assertThat(resolve(semiFeedRequest()))
                .as("SFI 投料不解析成 in-plan WIP 边, 所以边集合为空 —— 关键是【没抛】")
                .isEmpty();
    }

    @Test
    @DisplayName("回归: 回料来源不走这道闸 —— 它在报工时就被扣空了, 卡 AVAILABLE 会把重存弄红")
    void reworkSource_isNotGatedOnStatus() {
        sourceRow(SemiFinishedInventory.Status.DEPLETED, true);

        assertThatCode(() -> resolve(semiFeedRequest()))
                .as("同一个 DEPLETED 行, 只因为 isRework=true 就该放行 —— 与上面第一条构成对照")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("回归: 来源根本不存在仍是原来的 SFI_NOT_FOUND (新闸没有顶掉旧闸)")
    void missingSource_stillReportsNotFound() {
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, BATCH))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolve(semiFeedRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("SFI_NOT_FOUND"));
    }

    // ── ② 接上没有 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 接线: 走【真实入口 saveRow】也拦得住 (不是只有反射直调 resolveEdges 才拦)")
    void gateIsReachedFromTheRealSaveRowEntryPoint() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN);
        plan.setFactoryId(FACTORY);
        when(productionPlanRepo.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan));
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(FACTORY, PLAN, "qidiao", "row-1"))
                .thenReturn(Optional.empty());
        sourceRow(SemiFinishedInventory.Status.DEPLETED, false);

        // 单位走 legacy 单字段口径 (inputUnit/outputUnit 都不给), 免得先撞
        // PROCESS_SHEET_PROCESS_NOT_CONFIGURED —— 那会让这条断言测不到本闸 (变异没到达被测行为)。
        ProcessSheetRowRequest req = semiFeedRequest();
        req.setInputUnit(null);
        req.setUnit("kg");

        assertThatThrownBy(() -> service.saveRow(FACTORY, PLAN, req, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .as("闸写在 resolveEdges 里, 而生产上是 saveRow 调它 —— 这条断言证明那条路真的经过它")
                        .isEqualTo("SFI_SOURCE_NOT_AVAILABLE"));
    }
}
