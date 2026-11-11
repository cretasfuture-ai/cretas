package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.shortage.ShortageStartGate;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 纪律排查 A8: 批次级开工 / 完工与计划级同口径。
 * <ul>
 *   <li>开工: 批次挂在没开工的计划上 ⇒ 过同一道缺料闸 (真实 {@link ShortageStartGate}, 工厂开关开 / 关两侧);</li>
 *   <li>完工: 实际产量超过批次计划量 (同单位) ⇒ 必须带超产原因, 原因留痕。</li>
 * </ul>
 * 可达性: 手工 / AI 建批次 (POST /processing/batches 带 productionPlanId) 出来的是 PLANNED 批次,
 * App 签到 / 班组报工页对它调 /batches/{id}/start —— 修复前这条路完全不看缺料开关。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchStartCompleteDisciplineContractTest {

    private static final String F = "F006";

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;

    @InjectMocks private ProcessingServiceImpl service;

    // 真实缺料闸的依赖
    private final FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseLineRepository caseLines = mock(ShortageCaseLineRepository.class);
    private final ShortageCaseEventRepository caseEvents = mock(ShortageCaseEventRepository.class);

    private ProductionBatch batch;
    private ProductionPlan plan;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "shortageStartGate",
                new ShortageStartGate(settings, cases, caseLines, caseEvents));

        plan = new ProductionPlan();
        plan.setId("PP-1");
        plan.setFactoryId(F);
        plan.setPlanNumber("PP-QA-1");
        plan.setSourceOrderId("SO-1");
        plan.setStatus(ProductionPlanStatus.PENDING);
        when(productionPlanRepository.findById("PP-1")).thenReturn(Optional.of(plan));

        batch = new ProductionBatch();
        batch.setId(1885L);
        batch.setFactoryId(F);
        batch.setBatchNumber("PB-QA-1885");
        batch.setProductionPlanId("PP-1");
        batch.setStatus(ProductionBatchStatus.PLANNED);
        batch.setPlannedQuantity(new BigDecimal("100"));
        batch.setUnit("kg");
        batch.setPlannedUnit("kg");
        when(productionBatchRepository.findByIdAndFactoryId(1885L, F)).thenReturn(Optional.of(batch));
        when(productionBatchRepository.findByIdAndFactoryIdForUpdate(1885L, F)).thenReturn(Optional.of(batch));
        when(productionBatchRepository.save(any(ProductionBatch.class))).thenAnswer(i -> i.getArgument(0));

        ShortageCase open = new ShortageCase();
        open.setId("CASE-1");
        open.setFactoryId(F);
        open.setStage(ShortageCase.Stage.DETECTED);
        when(cases.findOpenForUpdate(F, ShortageCase.SourceType.SALES_ORDER, "SO-1")).thenReturn(Optional.of(open));
        ShortageCaseLine gap = new ShortageCaseLine();
        gap.setMaterialName("猪大骨");
        gap.setGapQty(new BigDecimal("6"));
        gap.setUnit("kg");
        when(caseLines.findByCaseIdOrderByIdAsc("CASE-1")).thenReturn(List.of(gap));
    }

    private void blockStartOnShortage(boolean on) {
        when(settings.findBlockStartOnOpenShortageByFactoryId(F)).thenReturn(on);
    }

    // ------------------------------------------------------------------ 开工

    @Test
    void switchOnBatchOfAnUnstartedPlanWithOpenShortageIsBlockedLikeThePlan() {
        blockStartOnShortage(true);

        assertThatThrownBy(() -> service.startProduction(F, "1885", 7))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo(ShortageStartGate.CODE);
                    assertThat(e.getMessage()).contains("猪大骨");
                });
        assertThat(batch.getStatus()).isEqualTo(ProductionBatchStatus.PLANNED);
        verify(productionBatchRepository, never()).save(any());
    }

    @Test
    void switchOnForcedStartWithReasonStartsAndLeavesTheReasonOnTheShortageCase() {
        blockStartOnShortage(true);

        ProductionBatch started = service.startProduction(F, "1885", 7, "客户急单，缺的料下午到");

        assertThat(started.getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        ArgumentCaptor<ShortageCaseEvent> event = ArgumentCaptor.forClass(ShortageCaseEvent.class);
        verify(caseEvents).save(event.capture());
        assertThat(event.getValue().getTriggerReason()).isEqualTo("FORCED_START");
        assertThat(event.getValue().getDetail()).contains("客户急单，缺的料下午到");
    }

    @Test
    void switchOffBatchStartsAsBefore() {
        blockStartOnShortage(false);

        assertThat(service.startProduction(F, "1885", 7).getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        verify(caseEvents, never()).save(any());
    }

    @Test
    void aPlanAlreadyStartedIsNotAskedAgainForEachBatch() {
        blockStartOnShortage(true);
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);   // 计划开工时已过闸 / 已强制并留痕

        assertThat(service.startProduction(F, "1885", 7).getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
    }

    @Test
    void aBatchOnADraftPlanIsGatedToo() {
        // 草稿 / 待审批同样「还没开工」, 不能借批次绕过缺料闸 (独立审查 #1596)
        blockStartOnShortage(true);
        plan.setStatus(ProductionPlanStatus.PREPARED);
        assertThatThrownBy(() -> service.startProduction(F, "1885", 7))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ShortageStartGate.CODE));
    }

    @Test
    void aBatchWithoutAPlanHasNoShortageToCheck() {
        blockStartOnShortage(true);
        batch.setProductionPlanId(null);

        assertThat(service.startProduction(F, "1885", 7).getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        verify(productionPlanRepository, never()).findById(any());
    }

    // ------------------------------------------------------------------ 完工

    private void inProgress() {
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
    }

    @Test
    void overPlanCompletionWithoutReasonIsRefusedAndNothingIsCompleted() {
        inProgress();

        assertThatThrownBy(() -> service.completeProduction(F, "1885",
                new BigDecimal("120"), new BigDecimal("120"), BigDecimal.ZERO))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("PRODUCTION_OVER_PLAN_REASON_REQUIRED");
                    assertThat(e.getActionHint()).contains("计划 100").contains("实际 120");
                });
        assertThat(batch.getStatus()).isEqualTo(ProductionBatchStatus.IN_PROGRESS);
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void overPlanCompletionWithReasonCompletesAndRecordsTheReason() {
        inProgress();

        ProductionBatch done = service.completeProductionWithReason(F, "1885",
                new BigDecimal("120"), new BigDecimal("118"), new BigDecimal("2"), "原料出成偏高");

        assertThat(done.getStatus()).isEqualTo(ProductionBatchStatus.COMPLETED);
        assertThat(done.getNotes()).contains("超产原因: 原料出成偏高");
        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) done.getCustomFields().get("overPlan");
        assertThat(audit).containsEntry("reason", "原料出成偏高");
        assertThat((BigDecimal) audit.get("actualQuantity")).isEqualByComparingTo("120");
    }

    @Test
    void withinPlanCompletionNeedsNoReason() {
        inProgress();

        ProductionBatch done = service.completeProduction(F, "1885",
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO);

        assertThat(done.getStatus()).isEqualTo(ProductionBatchStatus.COMPLETED);
        assertThat(done.getCustomFields()).doesNotContainKey("overPlan");
    }

    @Test
    void unitAliasesAreTheSameUnitSoAnOverPlanStillNeedsAReason() {
        // 计划按「公斤」、产出按「kg」: 同一个单位 (单位契约归一码), 超了照样要原因 —— 不能因为字面不同就放过
        inProgress();
        batch.setPlannedUnit("公斤");
        batch.setUnit("kg");
        assertThatThrownBy(() -> service.completeProduction(F, "1885",
                new BigDecimal("120"), new BigDecimal("120"), BigDecimal.ZERO))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRODUCTION_OVER_PLAN_REASON_REQUIRED"));
    }

    @Test
    void differentUnitsAreNotComparedSoNoReasonIsDemanded() {
        inProgress();
        batch.setPlannedUnit("kg");
        batch.setUnit("盒");   // 计划按 kg、产出按盒: 不比 (不猜换算)

        assertThat(service.completeProduction(F, "1885", new BigDecimal("500"), new BigDecimal("500"), BigDecimal.ZERO)
                .getStatus()).isEqualTo(ProductionBatchStatus.COMPLETED);
    }

    @Test
    void aBatchThatCannotBeCompletedStillGetsTheStatusMessageFirst() {
        // PLANNED 的批次: 说的是「状态不允许完成」, 不是「要超产原因」
        assertThatThrownBy(() -> service.completeProduction(F, "1885",
                new BigDecimal("120"), new BigDecimal("120"), BigDecimal.ZERO))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("批次状态不允许完成生产"));
    }
}
