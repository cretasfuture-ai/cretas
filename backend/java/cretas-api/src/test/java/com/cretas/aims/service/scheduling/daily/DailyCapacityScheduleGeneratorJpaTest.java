package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.dto.scheduling.DailyCapacityOverride;
import com.cretas.aims.dto.scheduling.DailyCapacityRequiredDTO;
import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.LineScheduleDTO;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * APS v2 服务层: 真 JPA (独立 H2 库) 跑 生成器 → 人工 / 经验日产能判定 → 按天分配 → 调度计划落库 整条链。
 * 产品、成品入库 (经验值与已入库量的来源)、计划、批次、产线、调度计划、line_schedules (含 jsonb daily_allocations)
 * 全是真库, 没有桩。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        // create-drop 必须独占自己的库: 上下文被缓存驱逐时不能 drop 别的测试上下文的 schema。
        // JSONB 映成 VARCHAR: H2 原生 JSON 读回是双重编码字符串, hypersistence 反序列化会炸
        // (与 LineageEdgeRecorderTransactionContractTest 同一处理); daily_allocations 要真往返, 不能置空绕过。
        "spring.datasource.url=jdbc:h2:mem:aps_daily_capacity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@Import({
        DailyCapacityScheduleGenerator.class,
        ExperienceCapacityService.class,
        ProductDailyCapacityService.class,
        SchedulingPlanCrudServiceImpl.class
})
class DailyCapacityScheduleGeneratorJpaTest {

    private static final String F = "F-APS";
    private static final String OTHER = "F-APS-OTHER";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);
    /** 经验值窗口相对「今天」: 生成器用 LocalDate.now(), 历史数据放在昨天往前。 */
    private static final LocalDate TODAY = LocalDate.now();

    @MockBean private NotificationService notificationService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private WorkerAssignmentService workerAssignmentService;
    @MockBean private SchedulingAIService schedulingAIService;

    @Autowired private DailyCapacityScheduleGenerator generator;
    @Autowired private EntityManager em;
    @Autowired private ProductionPlanRepository planRepository;
    @Autowired private ProductionBatchRepository batchRepository;
    @Autowired private ProductionLineRepository lineRepository;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Autowired private SchedulingPlanRepository schedulingPlanRepository;
    @Autowired private LineScheduleRepository lineScheduleRepository;
    @Autowired private MaterialBatchRepository materialBatchRepository;
    @Autowired private MaterialConsumptionRepository materialConsumptionRepository;

    private String lineId;
    private int fgSeq;

    @BeforeEach
    void setUp() {
        // 本测不验外键 (工厂 / 用户 / 仓库主数据与排程无关), 只验排程读写。
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        lineId = line(F, "一号线").getId();
    }

    // ==================== 人工优先 / 经验参考 ====================

    @Test
    void manualCapacityWinsOverHistoryAndPlanSpillsAcrossDays() {
        product(F, "PT-BEEF", "酱牛肉", "300", "袋");
        history(F, "PT-BEEF", "袋", "100", "100", "100"); // 经验 100 袋/天, 与人工 300 相差 200%
        plan(F, "PP-A", "PLAN-A", "PT-BEEF", "700", "袋");

        SchedulingPlanDTO dto = generator.generate(F, request("PP-A"), 7L);

        System.out.println("APS_V2_MANUAL " + dto.getLineSchedules().get(0).getDailyAllocations()
                + " WARN " + dto.getWarnings());
        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_SCHEDULED);
        assertThat(dto.getId()).isNotNull();
        LineScheduleDTO ls = dto.getLineSchedules().get(0);
        assertThat(ls.getProductionPlanId()).isEqualTo("PP-A");
        assertThat(ls.getProductionLineId()).isEqualTo(lineId);
        assertThat(ls.getPlannedQuantity()).isEqualTo(700);
        assertThat(ls.getPlannedStartTime()).isEqualTo(DAY.atStartOfDay());
        assertThat(ls.getPlannedEndTime()).isEqualTo(DAY.plusDays(2).atTime(23, 59));
        // 两者相差超过 30%: 提示, 仍用人工
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w)
                .contains("酱牛肉").contains("300").contains("100").contains("相差超过 30%").contains("按人工"));

        // 真库往返: 清掉一级缓存再读, daily_allocations 走 jsonb 列回来
        em.flush();
        em.clear();
        LineSchedule row = lineScheduleRepository.findByPlanIdOrderBySequenceOrder(dto.getId()).get(0);
        Map<String, Object> alloc = row.getDailyAllocations();
        assertThat(alloc.get("capacitySource")).isEqualTo("MANUAL");
        assertThat(alloc.get("capacitySourceLabel")).isEqualTo("人工填写");
        assertThat(dec(alloc.get("capacityPerDay"))).isEqualByComparingTo("300");
        assertThat(dec(alloc.get("experiencePerDay"))).isEqualByComparingTo("100");
        assertThat(alloc.get("unit")).isEqualTo("袋");
        assertThat(days(alloc)).containsExactly(
                DAY + "=300", DAY.plusDays(1) + "=300", DAY.plusDays(2) + "=100");
        assertThat(row.getTaskTimeline()).isNull();
    }

    @Test
    void manualCloseToHistoryGivesNoDifferenceWarning() {
        product(F, "PT-BEEF", "酱牛肉", "120", "袋");
        history(F, "PT-BEEF", "袋", "100", "100", "100"); // 相差 20%
        plan(F, "PP-A", "PLAN-A", "PT-BEEF", "100", "袋");

        SchedulingPlanDTO dto = generator.generate(F, request("PP-A"), 7L);

        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_SCHEDULED);
        assertThat(dto.getWarnings()).noneSatisfy(w -> assertThat(w).contains("相差超过"));
    }

    @Test
    void withoutManualCapacityHistoryIsUsedAndLabelled() {
        product(F, "PT-CHICKEN", "香煎鸡胸", null, null);
        history(F, "PT-CHICKEN", "袋", "100", "200", "150"); // 中位 150
        // 阴性: 别的产品 / 别的厂 / 不挂计划的入库不算进来
        fg(OTHER, "PT-CHICKEN", "袋", "9999", TODAY.minusDays(1), "OTHER-PLAN");
        fg(F, "PT-OTHER", "袋", "9999", TODAY.minusDays(1), "SOME-PLAN");
        fg(F, "PT-CHICKEN", "袋", "9999", TODAY.minusDays(2), null);
        plan(F, "PP-C", "PLAN-C", "PT-CHICKEN", "300", "袋");

        SchedulingPlanDTO dto = generator.generate(F, request("PP-C"), 7L);

        Map<String, Object> alloc = dto.getLineSchedules().get(0).getDailyAllocations();
        System.out.println("APS_V2_EXPERIENCE " + alloc);
        assertThat(alloc.get("capacitySource")).isEqualTo("EXPERIENCE");
        assertThat(alloc.get("capacitySourceLabel")).isEqualTo("按历史估算");
        assertThat(dec(alloc.get("capacityPerDay"))).isEqualByComparingTo("150");
        assertThat(days(alloc)).containsExactly(DAY + "=150", DAY.plusDays(1) + "=150");
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w).contains("香煎鸡胸").contains("按历史估算"));
    }

    @Test
    void plansShareADayOfTheLineInOrder() {
        product(F, "PT-A", "产品甲", "100", "袋");
        product(F, "PT-B", "产品乙", "200", "kg");
        plan(F, "PP-A", "PLAN-A", "PT-A", "40", "袋");
        plan(F, "PP-B", "PLAN-B", "PT-B", "300", "kg");

        SchedulingPlanDTO dto = generator.generate(F, request("PP-A", "PP-B"), 7L);

        assertThat(dto.getLineSchedules()).extracting(LineScheduleDTO::getProductionPlanId)
                .containsExactly("PP-A", "PP-B");
        assertThat(days(dto.getLineSchedules().get(0).getDailyAllocations())).containsExactly(DAY + "=40");
        // 第一天只剩 60% × 200 = 120 kg, 第二天 180 kg
        assertThat(days(dto.getLineSchedules().get(1).getDailyAllocations()))
                .containsExactly(DAY + "=120", DAY.plusDays(1) + "=180");
    }

    // ==================== 两样都没有: 返回需补清单, 不落库; 补了就排 ====================

    @Test
    void whenNeitherManualNorHistoryExistsTheProductIsListedAndNothingIsWritten() {
        product(F, "PT-NEW", "新品卤蛋", null, null);
        history(F, "PT-NEW", "袋", "50", "60"); // 只有 2 天, 算不出
        product(F, "PT-OK", "酱牛肉", "300", "袋");
        plan(F, "PP-OK", "PLAN-OK", "PT-OK", "100", "袋");
        plan(F, "PP-NEW", "PLAN-NEW", "PT-NEW", "100", "袋");

        SchedulingPlanDTO dto = generator.generate(F, request("PP-OK", "PP-NEW"), 7L);

        System.out.println("APS_V2_REQUIRED outcome=" + dto.getOutcome() + " list=" + dto.getCapacityRequired());
        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_CAPACITY_REQUIRED);
        assertThat(dto.getId()).isNull();
        assertThat(dto.getCapacityRequired()).hasSize(1);
        DailyCapacityRequiredDTO need = dto.getCapacityRequired().get(0);
        assertThat(need.getProductTypeId()).isEqualTo("PT-NEW");
        assertThat(need.getProductName()).isEqualTo("新品卤蛋");
        assertThat(need.getUnit()).isEqualTo("袋");
        assertThat(need.getReason()).isEqualTo("MISSING");
        assertThat(need.isJointPlan()).isFalse();
        assertThat(need.getPlanNumbers()).containsExactly("PLAN-NEW");
        assertThat(need.getExperienceNote()).contains("不足 3 天");
        assertThat(need.getQuestion()).contains("新品卤蛋").contains("一天能做多少").contains("袋");
        // 不足 3 天: 把那两天的实际量带出来 (日期升序)
        assertThat(samples(need)).containsExactly(TODAY.minusDays(2) + "=60袋", TODAY.minusDays(1) + "=50袋");
        // 什么都没落: 同一天补完再提交不会撞 409
        assertThat(schedulingPlanRepository.count()).isZero();
        assertThat(lineScheduleRepository.count()).isZero();
    }

    @Test
    void overridesAreSavedToTheProductFirstThenScheduled() {
        product(F, "PT-NEW", "新品卤蛋", null, null);
        plan(F, "PP-NEW", "PLAN-NEW", "PT-NEW", "250", "袋");
        assertThat(generator.generate(F, request("PP-NEW"), 7L).getOutcome())
                .isEqualTo(SchedulingPlanDTO.OUTCOME_CAPACITY_REQUIRED);

        GenerateScheduleRequest req = request("PP-NEW");
        req.setDailyCapacityOverrides(List.of(new DailyCapacityOverride("PT-NEW", new BigDecimal("200"), "袋")));
        SchedulingPlanDTO dto = generator.generate(F, req, 7L);

        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_SCHEDULED);
        assertThat(days(dto.getLineSchedules().get(0).getDailyAllocations()))
                .containsExactly(DAY + "=200", DAY.plusDays(1) + "=50");
        em.flush();
        em.clear();
        ProductType saved = productTypeRepository.findByIdAndFactoryId("PT-NEW", F).orElseThrow();
        assertThat(saved.getDailyCapacity()).isEqualByComparingTo("200");
        assertThat(saved.getDailyCapacityUnit()).isEqualTo("袋");
        assertThat(saved.getDailyCapacityUpdatedBy()).isEqualTo(7L);
        assertThat(saved.getDailyCapacityUpdatedAt()).isNotNull();
    }

    @Test
    void foreignProductOverrideIsRejectedAndNothingIsWritten() {
        product(F, "PT-NEW", "新品卤蛋", null, null);
        product(OTHER, "PT-FOREIGN", "别厂产品", null, null);
        plan(F, "PP-NEW", "PLAN-NEW", "PT-NEW", "100", "袋");
        GenerateScheduleRequest req = request("PP-NEW");
        req.setDailyCapacityOverrides(List.of(
                new DailyCapacityOverride("PT-NEW", new BigDecimal("200"), "袋"),
                new DailyCapacityOverride("PT-FOREIGN", new BigDecimal("999"), "袋")));

        assertThatThrownBy(() -> generator.generate(F, req, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));

        em.flush();
        em.clear();
        // 整批校验在写之前: 本厂那一条也没写, 别厂的更没动
        assertThat(productTypeRepository.findByIdAndFactoryId("PT-NEW", F).orElseThrow().getDailyCapacity()).isNull();
        assertThat(productTypeRepository.findByIdAndFactoryId("PT-FOREIGN", OTHER).orElseThrow().getDailyCapacity())
                .isNull();
        assertThat(schedulingPlanRepository.count()).isZero();
    }

    @Test
    void overrideForAProductOutsideThisRunOrInTheWrongUnitIsA400AndNotSaved() {
        product(F, "PT-NEW", "新品卤蛋", null, null);
        product(F, "PT-UNRELATED", "不相干的产品", null, null);
        plan(F, "PP-NEW", "PLAN-NEW", "PT-NEW", "100", "袋");

        GenerateScheduleRequest unrelated = request("PP-NEW");
        unrelated.setDailyCapacityOverrides(List.of(
                new DailyCapacityOverride("PT-UNRELATED", new BigDecimal("50"), "袋")));
        assertThatThrownBy(() -> generator.generate(F, unrelated, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在本次要排的计划里")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        GenerateScheduleRequest wrongUnit = request("PP-NEW");
        wrongUnit.setDailyCapacityOverrides(List.of(new DailyCapacityOverride("PT-NEW", new BigDecimal("50"), "kg")));
        assertThatThrownBy(() -> generator.generate(F, wrongUnit, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("袋")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        em.flush();
        em.clear();
        assertThat(productTypeRepository.findByIdAndFactoryId("PT-UNRELATED", F).orElseThrow().getDailyCapacity())
                .isNull();
        assertThat(productTypeRepository.findByIdAndFactoryId("PT-NEW", F).orElseThrow().getDailyCapacity()).isNull();
    }

    @Test
    void manualCapacityInAnUnconvertibleUnitIsReportedAsUnitMismatch() {
        product(F, "PT-BEEF", "酱牛肉", "80", "kg"); // 计划按袋, 填的是 kg: 换不了
        plan(F, "PP-A", "PLAN-A", "PT-BEEF", "100", "袋");

        SchedulingPlanDTO dto = generator.generate(F, request("PP-A"), 7L);

        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_CAPACITY_REQUIRED);
        DailyCapacityRequiredDTO need = dto.getCapacityRequired().get(0);
        assertThat(need.getReason()).isEqualTo("UNIT_MISMATCH");
        assertThat(need.getCurrentQuantityPerDay()).isEqualByComparingTo("80");
        assertThat(need.getCurrentUnit()).isEqualTo("kg");
        assertThat(need.getUnit()).isEqualTo("袋");
    }

    // ==================== 已开工: 只排剩余量 ====================

    @Test
    void startedPlanOnlySchedulesWhatIsLeftAfterFinishedGoodsReceipts() {
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        ProductionPlan p = plan(F, "PP-S", "PLAN-S", "PT-BEEF", "500", "袋");
        p.setStatus(ProductionPlanStatus.IN_PROGRESS);
        planRepository.saveAndFlush(p);
        ProductionBatch open = batch(F, "PP-S", "B-S-1", ProductionBatchStatus.IN_PROGRESS);
        fg(F, "PT-BEEF", "袋", "120", TODAY, "PP-S");
        fg(F, "PT-BEEF", "袋", "80", TODAY, "PP-S");
        // 阴性: 撤回的、退货入库的、别的计划的不算
        FinishedGoodsBatch reversed = fg(F, "PT-BEEF", "袋", "1000", TODAY, "PP-S");
        reversed.setStatus(FinishedGoodsBatch.Status.REVERSED);
        finishedGoodsBatchRepository.saveAndFlush(reversed);
        FinishedGoodsBatch returned = fg(F, "PT-BEEF", "袋", "1000", TODAY, "PP-S");
        returned.setReturnOrderItemId(42L);
        finishedGoodsBatchRepository.saveAndFlush(returned);
        fg(F, "PT-BEEF", "袋", "1000", TODAY, "PP-ELSE");

        SchedulingPlanDTO dto = generator.generate(F, request("PP-S"), 7L);

        LineScheduleDTO ls = dto.getLineSchedules().get(0);
        Map<String, Object> alloc = ls.getDailyAllocations();
        System.out.println("APS_V2_REMAINING " + alloc);
        assertThat(dec(alloc.get("reportedQuantity"))).isEqualByComparingTo("200");
        assertThat(dec(alloc.get("remainingQuantity"))).isEqualByComparingTo("300");
        assertThat(ls.getPlannedQuantity()).isEqualTo(300);
        assertThat(ls.getBatchId()).isEqualTo(open.getId());
        assertThat(days(alloc)).containsExactly(DAY + "=100", DAY.plusDays(1) + "=100", DAY.plusDays(2) + "=100");
    }

    @Test
    void completedPlansAreSkippedAndNothingSchedulableIsA422() {
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        ProductionPlan p = plan(F, "PP-DONE", "PLAN-DONE", "PT-BEEF", "100", "袋");
        p.setStatus(ProductionPlanStatus.COMPLETED);
        planRepository.saveAndFlush(p);
        plan(F, "PP-FULL", "PLAN-FULL", "PT-BEEF", "100", "袋");
        fg(F, "PT-BEEF", "袋", "100", TODAY, "PP-FULL");

        assertThatThrownBy(() -> generator.generate(F, request("PP-DONE", "PP-FULL"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PLAN-DONE").hasMessageContaining("PLAN-FULL")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(422));
        assertThat(schedulingPlanRepository.count()).isZero();
    }

    // ==================== 联产计划: 问「这个计划一天能做多少」, 存在锚点上 ====================

    @Test
    void jointPlanAsksForThePlanCapacityOnItsAnchorAndIgnoresSingleProductHistory() {
        ProductType anchor = product(F, "RAW-CHICKEN", "__RAW_WORKFLOW_OWNER__RAW-CHICKEN", null, null);
        anchor.setCategory("RAW_MATERIAL");
        productTypeRepository.saveAndFlush(anchor);
        rawMaterial(F, "RAW-CHICKEN", "西装鸡");
        product(F, "FG-BREAST", "鸡胸", null, null);
        product(F, "FG-WING", "鸡翅", null, null);
        history(F, "FG-BREAST", "盒", "100", "100", "100"); // 单个成品的历史: 联产计划不拿它
        ProductionPlan p = plan(F, "PP-J", "PLAN-J", "RAW-CHICKEN", "300", "kg");
        p.setTargetFinishedGoodIds(new ArrayList<>(List.of("FG-BREAST", "FG-WING")));
        planRepository.saveAndFlush(p);
        fg(F, "FG-BREAST", "盒", "40", TODAY, "PP-J"); // 已入库 40 盒: 折不成 kg

        SchedulingPlanDTO first = generator.generate(F, request("PP-J"), 7L);

        assertThat(first.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_CAPACITY_REQUIRED);
        DailyCapacityRequiredDTO need = first.getCapacityRequired().get(0);
        assertThat(need.getProductTypeId()).isEqualTo("RAW-CHICKEN");
        assertThat(need.getProductName()).isEqualTo("西装鸡");
        assertThat(need.isJointPlan()).isTrue();
        assertThat(need.getUnit()).isEqualTo("kg");
        assertThat(need.getQuestion()).contains("联产计划").contains("kg");

        GenerateScheduleRequest req = request("PP-J");
        req.setDailyCapacityOverrides(List.of(new DailyCapacityOverride("RAW-CHICKEN", new BigDecimal("200"), "kg")));
        SchedulingPlanDTO dto = generator.generate(F, req, 7L);

        Map<String, Object> alloc = dto.getLineSchedules().get(0).getDailyAllocations();
        assertThat(alloc.get("jointPlan")).isEqualTo(Boolean.TRUE);
        assertThat(days(alloc)).containsExactly(DAY + "=200", DAY.plusDays(1) + "=100");
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w).contains("40 盒").contains("没有从计划量里扣"));
        em.flush();
        em.clear();
        assertThat(productTypeRepository.findByIdAndFactoryId("RAW-CHICKEN", F).orElseThrow().getDailyCapacity())
                .isEqualByComparingTo("200");
    }

    // ==================== 联产计划: 按原料投入给建议值, 只作建议 (Steve 2026-10-29) ====================

    @Test
    void jointPlanWithEnoughInputHistoryStillAsksAndSuggestsTheMedianOfDailyInput() {
        jointChickenPlan("PP-J", "PLAN-J", "600");
        String chickenKg = materialBatch(F, "RAW-CHICKEN", "kg");
        String chickenJin = materialBatch(F, "RAW-CHICKEN", "斤");
        Long pb = batch(F, "HIST-PLAN", "B-HIST", ProductionBatchStatus.COMPLETED).getId();
        // 昨天 100 kg; 前天 300 − 100 (差异调整退回) = 200 kg; 3 天前 480 斤 = 240 kg → 中位 200
        consume(F, chickenKg, pb, "100", TODAY.minusDays(1), false);
        consume(F, chickenKg, pb, "300", TODAY.minusDays(2), false);
        consume(F, chickenKg, pb, "-100", TODAY.minusDays(2), false);
        consume(F, chickenJin, pb, "480", TODAY.minusDays(3), false);
        // 阴性: 别的原料 / 不挂生产批 (调拨、领料出库) / 撤回软删 / 今天 / 别的厂 —— 哪条算进来中位数都会变
        rawMaterial(F, "RAW-DUCK", "鸭");
        consume(F, materialBatch(F, "RAW-DUCK", "kg"), pb, "9999", TODAY.minusDays(1), false);
        consume(F, chickenKg, null, "9999", TODAY.minusDays(1), false);
        consume(F, chickenKg, pb, "9999", TODAY.minusDays(5), true);
        consume(F, chickenKg, pb, "9999", TODAY, false);
        Long otherPb = batch(OTHER, "OTHER-PLAN", "B-OTHER", ProductionBatchStatus.COMPLETED).getId();
        consume(OTHER, materialBatch(OTHER, "RAW-CHICKEN", "kg"), otherPb, "9999", TODAY.minusDays(6), false);

        SchedulingPlanDTO dto = generator.generate(F, request("PP-J"), 7L);

        System.out.println("APS_V3_JOINT_SUGGEST outcome=" + dto.getOutcome() + " list=" + dto.getCapacityRequired());
        // ⛔ 不自动用: 仍然问, 什么都不落
        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_CAPACITY_REQUIRED);
        assertThat(dto.getId()).isNull();
        assertThat(schedulingPlanRepository.count()).isZero();
        assertThat(lineScheduleRepository.count()).isZero();
        DailyCapacityRequiredDTO need = dto.getCapacityRequired().get(0);
        assertThat(need.getProductTypeId()).isEqualTo("RAW-CHICKEN");
        assertThat(need.isJointPlan()).isTrue();
        assertThat(need.getUnit()).isEqualTo("kg");
        assertThat(need.getSuggestedPerDay()).isEqualByComparingTo("200");
        assertThat(need.getExperienceNote()).contains("实际投入西装鸡").contains("中位数").contains("3 天");
        assertThat(need.getHistoryBasis()).isEqualTo("RAW_INPUT");
        assertThat(need.getHistoryWindowDays()).isEqualTo(90);
        assertThat(need.getSampleDays()).isEmpty();
        assertThat(dto.getWarnings()).noneSatisfy(w -> assertThat(w).contains("按历史估算"));
        em.flush();
        em.clear();
        assertThat(productTypeRepository.findByIdAndFactoryId("RAW-CHICKEN", F).orElseThrow().getDailyCapacity())
                .isNull();

        // 人把预填的 200 改成 180 再提交: 按 180 排, 并保存为人工值
        GenerateScheduleRequest req = request("PP-J");
        req.setDailyCapacityOverrides(List.of(new DailyCapacityOverride("RAW-CHICKEN", new BigDecimal("180"), "kg")));
        SchedulingPlanDTO scheduled = generator.generate(F, req, 7L);

        assertThat(scheduled.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_SCHEDULED);
        Map<String, Object> alloc = scheduled.getLineSchedules().get(0).getDailyAllocations();
        assertThat(alloc.get("capacitySource")).isEqualTo("MANUAL");
        assertThat(dec(alloc.get("capacityPerDay"))).isEqualByComparingTo("180");
        assertThat(days(alloc)).containsExactly(
                DAY + "=180", DAY.plusDays(1) + "=180", DAY.plusDays(2) + "=180", DAY.plusDays(3) + "=60");
        em.flush();
        em.clear();
        ProductType saved = productTypeRepository.findByIdAndFactoryId("RAW-CHICKEN", F).orElseThrow();
        assertThat(saved.getDailyCapacity()).isEqualByComparingTo("180");
        assertThat(saved.getDailyCapacityUnit()).isEqualTo("kg");
    }

    @Test
    void jointPlanWithFewerThanThreeInputDaysHasNoSuggestionButShowsThoseDays() {
        jointChickenPlan("PP-J", "PLAN-J", "300");
        Long pb = batch(F, "HIST-PLAN", "B-HIST", ProductionBatchStatus.COMPLETED).getId();
        consume(F, materialBatch(F, "RAW-CHICKEN", "斤"), pb, "160", TODAY.minusDays(2), false); // 80 kg

        SchedulingPlanDTO dto = generator.generate(F, request("PP-J"), 7L);

        DailyCapacityRequiredDTO need = dto.getCapacityRequired().get(0);
        System.out.println("APS_V3_JOINT_SAMPLES " + need.getSampleDays() + " note=" + need.getExperienceNote());
        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_CAPACITY_REQUIRED);
        assertThat(need.getSuggestedPerDay()).isNull();
        assertThat(need.getExperienceNote()).contains("只有 1 天").contains("西装鸡");
        assertThat(samples(need)).containsExactly(TODAY.minusDays(2) + "=80kg");
    }

    @Test
    void ordinaryProductWithOneDayOfHistoryIsAskedAndShowsThatDay() {
        product(F, "PT-NEW", "新品卤蛋", null, null);
        fg(F, "PT-NEW", "盒", "40", TODAY.minusDays(2), "HIST-PLAN");
        plan(F, "PP-NEW", "PLAN-NEW", "PT-NEW", "100", "盒");

        SchedulingPlanDTO dto = generator.generate(F, request("PP-NEW"), 7L);

        DailyCapacityRequiredDTO need = dto.getCapacityRequired().get(0);
        System.out.println("APS_V3_SAMPLES " + need.getSampleDays());
        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_CAPACITY_REQUIRED);
        assertThat(dto.getId()).isNull();
        assertThat(need.isJointPlan()).isFalse();
        assertThat(need.getSuggestedPerDay()).isNull(); // 普通产品不给建议值: 够 3 天就自动用了
        assertThat(need.getHistoryBasis()).isEqualTo("FINISHED_OUTPUT");
        assertThat(need.getExperienceNote()).contains("只有 1 天");
        assertThat(samples(need)).containsExactly(TODAY.minusDays(2) + "=40盒");
    }

    @Test
    void ordinaryProductWithNoHistoryHasAnEmptySampleList() {
        product(F, "PT-NEW", "新品卤蛋", null, null);
        plan(F, "PP-NEW", "PLAN-NEW", "PT-NEW", "100", "盒");

        DailyCapacityRequiredDTO need = generator.generate(F, request("PP-NEW"), 7L).getCapacityRequired().get(0);

        assertThat(need.getSampleDays()).isNotNull().isEmpty();
        assertThat(need.getExperienceNote()).contains("只有 0 天");
    }

    // ==================== 租户 / 入参 (沿用 v1) ====================

    @Test
    void planFromAnotherFactoryIsRejectedAndNothingIsWritten() {
        product(F, "PT-A", "产品甲", "100", "袋");
        plan(OTHER, "PP-FOREIGN", "PLAN-FOREIGN", "PT-A", "10", "袋");
        plan(F, "PP-A", "PLAN-A", "PT-A", "10", "袋");

        assertThatThrownBy(() -> generator.generate(F, request("PP-A", "PP-FOREIGN"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThat(schedulingPlanRepository.count()).isZero();

        // 阳性对照: 本厂计划能排
        assertThat(generator.generate(F, request("PP-A"), 7L).getLineSchedules()).hasSize(1);
    }

    @Test
    void multipleActiveLinesWithoutLineIdIsA400AndChoosingOneWorks() {
        product(F, "PT-A", "产品甲", "100", "袋");
        plan(F, "PP-A", "PLAN-A", "PT-A", "10", "袋");
        ProductionLine second = line(F, "二号线");

        assertThatThrownBy(() -> generator.generate(F, request("PP-A"), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 条产线")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        GenerateScheduleRequest req = request("PP-A");
        req.setProductionLineId(second.getId());
        assertThat(generator.generate(F, req, 7L).getLineSchedules().get(0).getProductionLineId())
                .isEqualTo(second.getId());
    }

    @Test
    void sameDayExistingSchedulingPlanIsA409EvenBeforeAskingForCapacity() {
        product(F, "PT-A", "产品甲", "100", "袋");
        product(F, "PT-NEW", "新品", null, null);
        plan(F, "PP-A", "PLAN-A", "PT-A", "10", "袋");
        plan(F, "PP-NEW", "PLAN-NEW", "PT-NEW", "10", "袋");
        generator.generate(F, request("PP-A"), 7L);

        assertThatThrownBy(() -> generator.generate(F, request("PP-A"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        // 缺日产能的计划也先报 409, 不让人白填
        assertThatThrownBy(() -> generator.generate(F, request("PP-NEW"), 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    void batchIdsAreMappedToTheirPlanAndEmptySelectionIsA400() {
        product(F, "PT-A", "产品甲", "100", "袋");
        plan(F, "PP-A", "PLAN-A", "PT-A", "10", "袋");
        ProductionBatch b = batch(F, "PP-A", "B-A-1", ProductionBatchStatus.PLANNED);
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        req.setBatchIds(List.of(b.getId()));

        assertThat(generator.generate(F, req, 7L).getLineSchedules())
                .extracting(LineScheduleDTO::getProductionPlanId).containsExactly("PP-A");

        GenerateScheduleRequest empty = new GenerateScheduleRequest();
        empty.setPlanDate(DAY.plusDays(10));
        assertThatThrownBy(() -> generator.generate(F, empty, 7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    // ==================== fixtures ====================

    private static GenerateScheduleRequest request(String... planIds) {
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        req.setPlanIds(new ArrayList<>(List.of(planIds)));
        return req;
    }

    private static BigDecimal dec(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    /** daily_allocations.days → ["2026-10-29=300", ...] (数量去掉尾零)。 */
    @SuppressWarnings("unchecked")
    private static List<String> days(Map<String, Object> alloc) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) alloc.get("days")) {
            out.add(d.get("date") + "=" + dec(d.get("quantity")).stripTrailingZeros().toPlainString());
        }
        return out;
    }

    private static List<String> samples(DailyCapacityRequiredDTO need) {
        return need.getSampleDays().stream()
                .map(s -> s.getDate() + "=" + s.getQuantity().stripTrailingZeros().toPlainString() + s.getUnit())
                .toList();
    }

    /** 联产计划: 主产品是原料「西装鸡」的归属锚点, 出鸡胸、鸡翅两个成品, 计划单位 kg。 */
    private void jointChickenPlan(String id, String number, String quantity) {
        ProductType anchor = product(F, "RAW-CHICKEN", "__RAW_WORKFLOW_OWNER__RAW-CHICKEN", null, null);
        anchor.setCategory("RAW_MATERIAL");
        productTypeRepository.saveAndFlush(anchor);
        rawMaterial(F, "RAW-CHICKEN", "西装鸡");
        product(F, "FG-BREAST", "鸡胸", null, null);
        product(F, "FG-WING", "鸡翅", null, null);
        ProductionPlan p = plan(F, id, number, "RAW-CHICKEN", quantity, "kg");
        p.setTargetFinishedGoodIds(new ArrayList<>(List.of("FG-BREAST", "FG-WING")));
        planRepository.saveAndFlush(p);
    }

    /** 一个原料批次, 库存单位 unit。 */
    private String materialBatch(String factoryId, String materialTypeId, String unit) {
        MaterialBatch b = new MaterialBatch();
        b.setId(java.util.UUID.randomUUID().toString());
        b.setFactoryId(factoryId);
        b.setBatchNumber("MB-" + (++fgSeq));
        b.setMaterialTypeId(materialTypeId);
        b.setReceiptDate(TODAY.minusDays(30));
        b.setWarehouseId("WH-RAW");
        b.setReceiptQuantity(new BigDecimal("100000"));
        b.setQuantityUnit(unit);
        b.setCreatedBy(7L);
        return materialBatchRepository.saveAndFlush(b).getId();
    }

    /** 一笔原料消耗 (单位 = 来源批次的库存单位), 当天 10 点。 */
    private void consume(String factoryId, String materialBatchId, Long productionBatchId, String qty, LocalDate day,
                         boolean softDeleted) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(factoryId);
        c.setBatchId(materialBatchId);
        c.setProductionBatchId(productionBatchId);
        c.setQuantity(new BigDecimal(qty));
        c.setUnitPrice(BigDecimal.ONE);
        c.setTotalCost(new BigDecimal(qty));
        c.setConsumptionTime(day.atTime(10, 0));
        c.setRecordedBy(7L);
        c.setSourceType("PRODUCTION_ISSUE");
        if (softDeleted) {
            c.setDeletedAt(java.time.LocalDateTime.now());
        }
        materialConsumptionRepository.saveAndFlush(c);
    }

    private ProductType product(String factoryId, String id, String name, String capacity, String unit) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setCode("C-" + id);
        p.setName(name);
        p.setUnit("袋");
        if (capacity != null) {
            p.setDailyCapacity(new BigDecimal(capacity));
            p.setDailyCapacityUnit(unit);
        }
        return productTypeRepository.saveAndFlush(p);
    }

    private void rawMaterial(String factoryId, String id, String name) {
        RawMaterialType r = new RawMaterialType();
        r.setId(id);
        r.setFactoryId(factoryId);
        r.setCode("RM-" + id);
        r.setName(name);
        r.setUnit("kg");
        rawMaterialTypeRepository.saveAndFlush(r);
    }

    /** 最近几天每天一笔生产成品入库 (昨天、前天、…)。 */
    private void history(String factoryId, String productTypeId, String unit, String... dailyQuantities) {
        for (int i = 0; i < dailyQuantities.length; i++) {
            fg(factoryId, productTypeId, unit, dailyQuantities[i], TODAY.minusDays(i + 1L), "HIST-PLAN");
        }
    }

    private FinishedGoodsBatch fg(String factoryId, String productTypeId, String unit, String qty, LocalDate day,
                                  String planId) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setFactoryId(factoryId);
        b.setBatchNumber("FG-" + (++fgSeq));
        b.setProductTypeId(productTypeId);
        b.setProducedQuantity(new BigDecimal(qty));
        b.setUnit(unit);
        b.setProductionDate(day);
        b.setProductionPlanId(planId);
        b.setWarehouseId("WH-WKS");
        return finishedGoodsBatchRepository.saveAndFlush(b);
    }

    private ProductionLine line(String factoryId, String name) {
        ProductionLine line = new ProductionLine();
        line.setFactoryId(factoryId);
        line.setName(name);
        line.setStatus(ProductionLine.LineStatus.active);
        line.setHourlyCapacity(new BigDecimal("999")); // 不参与排程
        return lineRepository.saveAndFlush(line);
    }

    private ProductionPlan plan(String factoryId, String id, String number, String productTypeId,
                                String quantity, String unit) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setPlanNumber(number);
        p.setProductTypeId(productTypeId);
        p.setPlannedQuantity(new BigDecimal(quantity));
        p.setPlannedUnit(unit);
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setSourceType(PlanSourceType.MANUAL);
        p.setCreatedBy(7L);
        p.setIsLocked(false);
        p.setSkipProcessReporting(false);
        return planRepository.saveAndFlush(p);
    }

    private ProductionBatch batch(String factoryId, String planId, String number, ProductionBatchStatus status) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(factoryId);
        b.setBatchNumber(number);
        b.setProductTypeId("PT-S");
        b.setQuantity(new BigDecimal("8"));
        b.setUnit("袋");
        b.setStatus(status);
        b.setProductionPlanId(planId);
        return batchRepository.saveAndFlush(b);
    }
}
