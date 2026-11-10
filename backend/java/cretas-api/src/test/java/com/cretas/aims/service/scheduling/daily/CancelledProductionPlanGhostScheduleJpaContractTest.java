package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.LineScheduleDTO;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository;
import com.cretas.aims.repository.scheduling.CancelledPlanScheduleRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.CancelledPlanScheduleCascade;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import com.cretas.aims.service.scheduling.impl.LineScheduleServiceImpl;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-S4-CANCEL-GHOST-DAILY (F006 2026-11-10 线上复现): 生产计划取消后, 它的排程任务仍是 pending, 继续占线 ——
 * 之后再排程 / 插单报「产线这天已被「计划 …」排满」。
 *
 * <p>第 1 层 (占线判定): 真实入口是自动排程生成器 ({@link DailyCapacityScheduleGenerator}), 占用读自
 * {@link ApsConstraintQueryRepository#findLiveEndingFrom} (插单也读它)。这里的「已取消」直接改计划状态,
 * 模拟级联之前留下的存量幽灵排程 —— 不靠第 2 层也必须不占线。
 * <p>第 2 层 (取消级联): {@link CancelledPlanScheduleCascade} 真 JPA 跑 UPDATE; 挂到取消入口上的接线见
 * {@code CancelledPlanScheduleCascadeAspectTest}。真 PostgreSQL 上的同一组语句见
 * {@code CancelledPlanScheduleRepositoryQueryValidationTest}。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:aps_cancelled_production_plan_ghost;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE"
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
        ScheduleConstraintReader.class,
        SchedulingPlanCrudServiceImpl.class,
        LineScheduleServiceImpl.class,
        SchedulingResourceWriteLock.class,
        CancelledPlanScheduleCascade.class
})
class CancelledProductionPlanGhostScheduleJpaContractTest {

    private static final String F = "F-GHOST";
    private static final String OTHER = "F-GHOST-OTHER";
    private static final LocalDate DAY = LocalDate.of(2026, 12, 10);

    @MockBean private NotificationService notificationService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private WorkerAssignmentService workerAssignmentService;
    @MockBean private SchedulingAIService schedulingAIService;

    @Autowired private DailyCapacityScheduleGenerator generator;
    @Autowired private CancelledPlanScheduleCascade cascade;
    @Autowired private EntityManager em;
    @Autowired private ProductionPlanRepository planRepository;
    @Autowired private ProductionLineRepository lineRepository;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private SchedulingPlanRepository schedulingPlanRepository;
    @Autowired private LineScheduleRepository lineScheduleRepository;
    @Autowired private ApsConstraintQueryRepository constraintQueries;

    private String lineId;

    @BeforeEach
    void setUp() {
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        lineId = line(F).getId();
        product(F, "PT-A", "产品甲", "100");
        product(F, "PT-B", "产品乙", "100");
        plan(F, "PP-A", "PLAN-A", "PT-A", "250");
        plan(F, "PP-B", "PLAN-B", "PT-B", "80");
    }

    // ==================== 第 1 层: 占线判定 ====================

    @Test
    void aCancelledProductionPlansPendingRowNoLongerFillsTheLine() {
        // PP-A 占 12-10 / 12-11 满, 12-12 一半
        generator.generate(F, request(DAY, "PP-A"), 7L);
        flushClear();
        cancelPlanStatusOnly("PP-A");   // 存量幽灵: 计划已取消, 排程还是 pending (级联之前的线上状态)

        SchedulingPlanDTO next = generator.generate(F, request(DAY.plusDays(1), "PP-B"), 7L);

        List<String> b = days(rowOf(next, "PP-B"));
        System.out.println("GHOST_APS cancelled PP-B days=" + b + " warnings=" + next.getWarnings());
        assertThat(b).as("PP-A 已取消, 它的 pending 排程不占线, PP-B 从第一天排上").containsExactly(DAY.plusDays(1) + "=80");
        assertThat(next.getWarnings()).noneSatisfy(w -> assertThat(w).contains("排满"));
        assertThat(constraintQueries.findLiveEndingFrom(F, DAY.atStartOfDay()))
                .extracting(LineSchedule::getProductionPlanId).containsExactly("PP-B");
    }

    @Test
    void negativeControlAPendingRowOfALivePlanStillFillsTheLine() {
        generator.generate(F, request(DAY, "PP-A"), 7L);
        flushClear();

        SchedulingPlanDTO next = generator.generate(F, request(DAY.plusDays(1), "PP-B"), 7L);

        List<String> b = days(rowOf(next, "PP-B"));
        System.out.println("GHOST_APS live PP-B days=" + b + " warnings=" + next.getWarnings());
        assertThat(b).as("PP-A 没取消, 照样占 12-11 整天、12-12 一半").containsExactly(
                DAY.plusDays(2) + "=50", DAY.plusDays(3) + "=30");
        assertThat(next.getWarnings()).anySatisfy(w -> assertThat(w).contains("产线这天已被「计划 PLAN-A」排满"));
    }

    @Test
    void aStartedRowOfACancelledProductionPlanStillOccupiesTheLine() {
        SchedulingPlanDTO first = generator.generate(F, request(DAY, "PP-A"), 7L);
        LineSchedule row = lineScheduleRepository.findById(rowOf(first, "PP-A").getId()).orElseThrow();
        row.setStatus(LineSchedule.ScheduleStatus.in_progress);
        row.setActualStartTime(DAY.atTime(8, 0));
        lineScheduleRepository.saveAndFlush(row);
        flushClear();
        cancelPlanStatusOnly("PP-A");

        SchedulingPlanDTO next = generator.generate(F, request(DAY.plusDays(1), "PP-B"), 7L);

        assertThat(days(rowOf(next, "PP-B"))).as("已开工的排程人和设备真在线上, 取消计划不让出来")
                .containsExactly(DAY.plusDays(2) + "=50", DAY.plusDays(3) + "=30");
    }

    // ==================== 第 2 层: 取消级联 ====================

    @Test
    void cascadeCancelsNotStartedRowsKeepsStartedOnesAndLeavesOthersAlone() {
        SchedulingPlan sp = schedulingPlan(F, DAY);
        LineSchedule pendingA = row(sp, "PP-A", LineSchedule.ScheduleStatus.pending, false);
        LineSchedule startedA = row(sp, "PP-A", LineSchedule.ScheduleStatus.in_progress, true);
        LineSchedule pendingButStartedA = row(sp, "PP-A", LineSchedule.ScheduleStatus.pending, true);
        LineSchedule completedA = row(sp, "PP-A", LineSchedule.ScheduleStatus.completed, true);
        LineSchedule pendingB = row(sp, "PP-B", LineSchedule.ScheduleStatus.pending, false);
        // 别的厂有一条挂着同名计划 id 的 pending 排程 —— 不许被这家厂的取消碰到
        LineSchedule foreign = row(schedulingPlan(OTHER, DAY), "PP-A", LineSchedule.ScheduleStatus.pending, false);
        flushClear();

        // 计划还没取消: 级联什么都不动 (条件在 UPDATE 里判)
        CancelledPlanScheduleCascade.Outcome notYet = cascade.cascade(F, "PP-A");
        assertThat(notYet.cancelledSchedules()).isZero();
        flushClear();
        assertThat(status(pendingA)).isEqualTo(LineSchedule.ScheduleStatus.pending);

        cancelPlanStatusOnly("PP-A");
        CancelledPlanScheduleCascade.Outcome out = cascade.cascade(F, "PP-A");
        flushClear();
        System.out.println("GHOST_CASCADE out=" + out + " pendingA=" + status(pendingA) + " startedA=" + status(startedA)
                + " pendingButStartedA=" + status(pendingButStartedA) + " pendingB=" + status(pendingB)
                + " foreign=" + status(foreign));

        assertThat(out.cancelledSchedules()).isEqualTo(1);
        assertThat(out.keptStartedSchedules()).as("执行中 1 条 + 状态 pending 但已有实际开始 1 条").isEqualTo(2);
        LineSchedule voided = lineScheduleRepository.findById(pendingA.getId()).orElseThrow();
        assertThat(voided.getStatus()).isEqualTo(LineSchedule.ScheduleStatus.cancelled);
        assertThat(voided.getAdjustmentReason()).isEqualTo(CancelledPlanScheduleRepository.CASCADE_REASON);
        assertThat(voided.getLastAdjustmentTime()).isNotNull();
        assertThat(status(startedA)).isEqualTo(LineSchedule.ScheduleStatus.in_progress);
        assertThat(status(pendingButStartedA)).isEqualTo(LineSchedule.ScheduleStatus.pending);
        assertThat(status(completedA)).isEqualTo(LineSchedule.ScheduleStatus.completed);
        assertThat(status(pendingB)).as("阴性对照: 别的计划不动").isEqualTo(LineSchedule.ScheduleStatus.pending);
        assertThat(status(foreign)).as("阴性对照: 别的厂不动").isEqualTo(LineSchedule.ScheduleStatus.pending);
        assertThat(lineScheduleRepository.count()).as("⛔ 不删行").isEqualTo(6);

        // 重复级联: 0 条; 给用户的结果现数, 不依赖那一刻的返回值
        assertThat(cascade.cascade(F, "PP-A").cancelledSchedules()).isZero();
        CancelledPlanScheduleCascade.Outcome summary = cascade.summarize(F, "PP-A");
        assertThat(summary).isEqualTo(new CancelledPlanScheduleCascade.Outcome(1, 2));
        assertThat(summary.describe("生产计划已取消"))
                .isEqualTo("生产计划已取消；1 条未开工的排程任务已一并取消；2 条排程任务已开工，保留未动，请到调度计划里收尾");
        assertThat(new CancelledPlanScheduleCascade.Outcome(0, 0).describe("生产计划已取消")).isEqualTo("生产计划已取消");
    }

    @Test
    void cascadeByPlanIdReadsTheFactoryFromThePlan() {
        SchedulingPlan sp = schedulingPlan(F, DAY);
        LineSchedule pendingA = row(sp, "PP-A", LineSchedule.ScheduleStatus.pending, false);
        flushClear();
        cancelPlanStatusOnly("PP-A");

        cascade.cascadeByPlanId("PP-A");
        cascade.cascadeByPlanId("NO-SUCH-PLAN");
        flushClear();

        assertThat(status(pendingA)).isEqualTo(LineSchedule.ScheduleStatus.cancelled);
    }

    // ==================== fixtures ====================

    private void flushClear() {
        em.flush();
        em.clear();
    }

    private void cancelPlanStatusOnly(String planId) {
        ProductionPlan p = planRepository.findById(planId).orElseThrow();
        p.setStatus(ProductionPlanStatus.CANCELLED);
        planRepository.saveAndFlush(p);
        flushClear();
    }

    private LineSchedule.ScheduleStatus status(LineSchedule row) {
        return lineScheduleRepository.findById(row.getId()).orElseThrow().getStatus();
    }

    private SchedulingPlan schedulingPlan(String factoryId, LocalDate day) {
        SchedulingPlan sp = new SchedulingPlan();
        sp.setFactoryId(factoryId);
        sp.setPlanDate(day);
        sp.setPlanName("调度 " + day);
        sp.setStatus(SchedulingPlan.PlanStatus.draft);
        return schedulingPlanRepository.saveAndFlush(sp);
    }

    private LineSchedule row(SchedulingPlan sp, String productionPlanId, LineSchedule.ScheduleStatus status,
                             boolean actuallyStarted) {
        LineSchedule ls = new LineSchedule();
        ls.setPlan(sp);
        ls.setProductionLineId(lineId);
        ls.setProductionPlanId(productionPlanId);
        ls.setPlannedStartTime(DAY.atTime(8, 0));
        ls.setPlannedEndTime(DAY.atTime(16, 0));
        ls.setPlannedQuantity(5);
        ls.setStatus(status);
        if (actuallyStarted) {
            ls.setActualStartTime(DAY.atTime(8, 5));
        }
        return lineScheduleRepository.saveAndFlush(ls);
    }

    private static LineScheduleDTO rowOf(SchedulingPlanDTO dto, String productionPlanId) {
        return dto.getLineSchedules().stream()
                .filter(ls -> productionPlanId.equals(ls.getProductionPlanId())).findFirst().orElseThrow();
    }

    private static GenerateScheduleRequest request(LocalDate day, String... planIds) {
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(day);
        req.setPlanIds(new ArrayList<>(List.of(planIds)));
        return req;
    }

    @SuppressWarnings("unchecked")
    private static List<String> days(LineScheduleDTO ls) {
        List<String> out = new ArrayList<>();
        for (java.util.Map<String, Object> d : (List<java.util.Map<String, Object>>) ls.getDailyAllocations().get("days")) {
            out.add(d.get("date") + "=" + new BigDecimal(d.get("quantity").toString()).stripTrailingZeros().toPlainString());
        }
        return out;
    }

    private ProductType product(String factoryId, String id, String name, String capacity) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setCode("C-" + id);
        p.setName(name);
        p.setUnit("袋");
        p.setDailyCapacity(new BigDecimal(capacity));
        p.setDailyCapacityUnit("袋");
        return productTypeRepository.saveAndFlush(p);
    }

    private ProductionLine line(String factoryId) {
        ProductionLine line = new ProductionLine();
        line.setFactoryId(factoryId);
        line.setName("一号线");
        line.setStatus(ProductionLine.LineStatus.active);
        line.setHourlyCapacity(new BigDecimal("999"));
        return lineRepository.saveAndFlush(line);
    }

    private ProductionPlan plan(String factoryId, String id, String number, String productTypeId, String quantity) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setPlanNumber(number);
        p.setProductTypeId(productTypeId);
        p.setPlannedQuantity(new BigDecimal(quantity));
        p.setPlannedUnit("袋");
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setSourceType(PlanSourceType.MANUAL);
        p.setCreatedBy(7L);
        p.setIsLocked(false);
        p.setSkipProcessReporting(false);
        return planRepository.saveAndFlush(p);
    }
}
