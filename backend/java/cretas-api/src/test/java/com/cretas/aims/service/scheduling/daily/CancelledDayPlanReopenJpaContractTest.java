package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.dto.scheduling.CreateSchedulingPlanRequest;
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
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缺口 18 后续 (统一验收 F006 复现): 某天的调度计划取消之后, 这一天再「自动排程」或手动新建一律 409,
 * 因为唯一约束是 (工厂, 日期) 而取消只改状态 —— 这一天永远排不了。
 *
 * <p>现在: 已取消的那天在落库时重开为草稿, 名下没开工的旧排程作废 (⛔ 不复活, 不再占产线),
 * 已开工的保留; 草稿 / 已确认 / 进行中 / 已完成仍然 409。真 JPA (独立 H2 库) 跑生成器与 CRUD 两个真实入口。
 * 并发重复生成在 {@code CancelledDayPlanReopenPostgresContractTest} (真 PostgreSQL)。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:aps_cancelled_day_reopen;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE"
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
        SchedulingResourceWriteLock.class
})
class CancelledDayPlanReopenJpaContractTest {

    private static final String F = "F-REOPEN";
    private static final String OTHER = "F-REOPEN-OTHER";
    private static final LocalDate DAY = LocalDate.of(2026, 12, 20);

    @MockBean private NotificationService notificationService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private WorkerAssignmentService workerAssignmentService;
    @MockBean private SchedulingAIService schedulingAIService;

    @Autowired private DailyCapacityScheduleGenerator generator;
    @Autowired private SchedulingPlanCrudServiceImpl crud;
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

    // ==================== 正向: 取消之后这一天还能排 ====================

    @Test
    void autoScheduleReopensACancelledDayAndTheOldRowsDoNotComeBack() {
        SchedulingPlanDTO first = generator.generate(F, request("PP-A"), 7L);
        String oldRowId = first.getLineSchedules().get(0).getId();
        assertThat(days(first)).containsExactly(DAY + "=100", DAY.plusDays(1) + "=100", DAY.plusDays(2) + "=50");
        crud.cancelPlan(F, first.getId(), "这一天不排了");
        flushClear();

        SchedulingPlanDTO again = generator.generate(F, request("PP-B"), 9L);

        System.out.println("REOPEN_AUTO id=" + again.getId() + " status=" + again.getStatus()
                + " warnings=" + again.getWarnings());
        assertThat(again.getId()).as("同一行 (唯一约束 工厂+日期), 不是新建").isEqualTo(first.getId());
        assertThat(again.getStatus()).isEqualTo("draft");
        assertThat(again.getWarnings().get(0)).contains("原来的调度计划已取消").contains("重开为草稿")
                .contains("1 条排程已作废");
        // 产品乙从第一天就排上: 已作废的产品甲旧排程不再占这条线
        assertThat(again.getLineSchedules()).filteredOn(ls -> "pending".equals(ls.getStatus()))
                .extracting(LineScheduleDTO::getProductionPlanId).containsExactly("PP-B");
        assertThat(days(again.getLineSchedules().stream()
                .filter(ls -> "PP-B".equals(ls.getProductionPlanId())).findFirst().orElseThrow()))
                .containsExactly(DAY + "=80");

        flushClear();
        LineSchedule oldRow = lineScheduleRepository.findById(oldRowId).orElseThrow();
        assertThat(oldRow.getStatus()).isEqualTo(LineSchedule.ScheduleStatus.cancelled);
        assertThat(oldRow.getAdjustmentReason()).isEqualTo(SchedulingPlanCrudServiceImpl.REOPEN_VOIDED_ROW_REASON);
        SchedulingPlan stored = schedulingPlanRepository.findById(first.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SchedulingPlan.PlanStatus.draft);
        assertThat(stored.getTotalBatches()).isEqualTo(1);
        assertThat(stored.getConfirmedBy()).isNull();
        assertThat(stored.getNotes()).contains("取消原因: 这一天不排了")
                .contains("由自动排程重开为草稿").contains("操作人 9").contains("作废没开工的旧排程 1 条");
        // 活的占用里只有新排的产品乙: 旧排程没有复活
        assertThat(constraintQueries.findLiveEndingFrom(F, DAY.atStartOfDay()))
                .extracting(LineSchedule::getProductionPlanId).containsExactly("PP-B");
    }

    @Test
    void theSamePlanCanBeRescheduledOnTheReopenedDay() {
        SchedulingPlanDTO first = generator.generate(F, request("PP-A"), 7L);
        crud.cancelPlan(F, first.getId(), "重排");
        flushClear();

        SchedulingPlanDTO again = generator.generate(F, request("PP-A"), 7L);

        List<LineScheduleDTO> live = again.getLineSchedules().stream()
                .filter(ls -> "pending".equals(ls.getStatus())).toList();
        assertThat(live).hasSize(1);
        // 自己被作废的旧排程不当占用: 还是从第一天排, 不是被挤到第四天
        assertThat(days(live.get(0))).containsExactly(DAY + "=100", DAY.plusDays(1) + "=100", DAY.plusDays(2) + "=50");
    }

    @Test
    void startedRowsUnderTheCancelledPlanAreKeptNotVoided() {
        SchedulingPlanDTO first = generator.generate(F, request("PP-A"), 7L);
        LineSchedule started = lineScheduleRepository.findById(first.getLineSchedules().get(0).getId()).orElseThrow();
        started.setStatus(LineSchedule.ScheduleStatus.in_progress);
        started.setActualStartTime(DAY.atTime(8, 0));
        lineScheduleRepository.saveAndFlush(started);
        crud.cancelPlan(F, first.getId(), "取消");
        flushClear();

        SchedulingPlanDTO again = generator.generate(F, request("PP-B"), 7L);

        assertThat(again.getWarnings().get(0)).contains("0 条排程已作废").contains("已开工的 1 条保留");
        flushClear();
        assertThat(lineScheduleRepository.findById(started.getId()).orElseThrow().getStatus())
                .isEqualTo(LineSchedule.ScheduleStatus.in_progress);
        assertThat(schedulingPlanRepository.findById(first.getId()).orElseThrow().getTotalBatches()).isEqualTo(2);
    }

    @Test
    void manualCreateAlsoReopensACancelledDay() {
        SchedulingPlanDTO first = generator.generate(F, request("PP-A"), 7L);
        String oldRowId = first.getLineSchedules().get(0).getId();
        crud.cancelPlan(F, first.getId(), "取消");
        flushClear();

        CreateSchedulingPlanRequest req = new CreateSchedulingPlanRequest();
        req.setPlanDate(DAY);
        req.setPlanName("手工重建");
        SchedulingPlanDTO created = crud.createPlan(F, req, 11L);

        assertThat(created.getId()).isEqualTo(first.getId());
        assertThat(created.getStatus()).isEqualTo("draft");
        assertThat(created.getPlanName()).isEqualTo("手工重建");
        assertThat(created.getWarnings()).singleElement().asString().contains("1 条排程已作废");
        flushClear();
        assertThat(lineScheduleRepository.findById(oldRowId).orElseThrow().getStatus())
                .isEqualTo(LineSchedule.ScheduleStatus.cancelled);
        SchedulingPlan stored = schedulingPlanRepository.findById(first.getId()).orElseThrow();
        assertThat(stored.getNotes()).contains("由手动新建重开为草稿").contains("操作人 11");
        assertThat(stored.getTotalBatches()).isZero();
    }

    @Test
    void aDayCanBeCancelledAndReopenedRepeatedly() {
        SchedulingPlanDTO first = generator.generate(F, request("PP-A"), 7L);
        crud.cancelPlan(F, first.getId(), "第一次取消");
        flushClear();
        SchedulingPlanDTO second = generator.generate(F, request("PP-B"), 7L);
        crud.cancelPlan(F, second.getId(), "第二次取消");
        flushClear();

        SchedulingPlanDTO third = generator.generate(F, request("PP-A"), 7L);

        assertThat(third.getId()).isEqualTo(first.getId());
        assertThat(constraintQueries.findLiveEndingFrom(F, DAY.atStartOfDay()))
                .extracting(LineSchedule::getProductionPlanId).containsExactly("PP-A");
        assertThat(lineScheduleRepository.findByPlanIdOrderBySequenceOrder(first.getId()))
                .filteredOn(ls -> ls.getStatus() == LineSchedule.ScheduleStatus.cancelled).hasSize(2);
    }

    // ==================== 阴性: 活的和已完成的照旧 409 ====================

    @Test
    void liveOrCompletedDaysAreStillA409OnBothEntrances() {
        SchedulingPlanDTO first = generator.generate(F, request("PP-A"), 7L);
        for (SchedulingPlan.PlanStatus status : List.of(SchedulingPlan.PlanStatus.draft,
                SchedulingPlan.PlanStatus.confirmed, SchedulingPlan.PlanStatus.in_progress,
                SchedulingPlan.PlanStatus.completed)) {
            SchedulingPlan p = schedulingPlanRepository.findById(first.getId()).orElseThrow();
            p.setStatus(status);
            schedulingPlanRepository.saveAndFlush(p);
            flushClear();

            assertThatThrownBy(() -> generator.generate(F, request("PP-B"), 7L)).as(status.name())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
            CreateSchedulingPlanRequest req = new CreateSchedulingPlanRequest();
            req.setPlanDate(DAY);
            assertThatThrownBy(() -> crud.createPlan(F, req, 7L)).as(status.name())
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
            flushClear();
            SchedulingPlan after = schedulingPlanRepository.findById(first.getId()).orElseThrow();
            assertThat(after.getStatus()).as(status.name()).isEqualTo(status);
            assertThat(after.getNotes()).as(status.name()).doesNotContain("重开");
            assertThat(lineScheduleRepository.findByPlanIdOrderBySequenceOrder(first.getId()))
                    .as(status.name()).singleElement()
                    .satisfies(ls -> assertThat(ls.getStatus()).isEqualTo(LineSchedule.ScheduleStatus.pending));
        }
    }

    @Test
    void anotherFactorysCancelledPlanOnTheSameDayIsUntouched() {
        SchedulingPlan other = new SchedulingPlan();
        other.setFactoryId(OTHER);
        other.setPlanDate(DAY);
        other.setPlanName("别的厂");
        other.setStatus(SchedulingPlan.PlanStatus.cancelled);
        other.setNotes("取消原因: 别的厂");
        other = schedulingPlanRepository.saveAndFlush(other);
        String otherLine = line(OTHER).getId();
        LineSchedule otherRow = new LineSchedule();
        otherRow.setPlan(other);
        otherRow.setProductionLineId(otherLine);
        otherRow.setProductionPlanId("PP-OTHER");
        otherRow.setPlannedStartTime(DAY.atStartOfDay());
        otherRow.setPlannedEndTime(DAY.atTime(23, 59));
        otherRow.setPlannedQuantity(5);
        otherRow.setStatus(LineSchedule.ScheduleStatus.pending);
        lineScheduleRepository.saveAndFlush(otherRow);
        flushClear();

        SchedulingPlanDTO mine = generator.generate(F, request("PP-A"), 7L);

        assertThat(mine.getId()).isNotEqualTo(other.getId());
        assertThat(mine.getWarnings()).noneSatisfy(w -> assertThat(w).contains("重开"));
        flushClear();
        SchedulingPlan otherAfter = schedulingPlanRepository.findById(other.getId()).orElseThrow();
        assertThat(otherAfter.getStatus()).isEqualTo(SchedulingPlan.PlanStatus.cancelled);
        assertThat(otherAfter.getNotes()).isEqualTo("取消原因: 别的厂");
        assertThat(lineScheduleRepository.findById(otherRow.getId()).orElseThrow().getStatus())
                .isEqualTo(LineSchedule.ScheduleStatus.pending);
    }

    // ==================== fixtures ====================

    private void flushClear() {
        em.flush();
        em.clear();
    }

    private static GenerateScheduleRequest request(String... planIds) {
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(DAY);
        req.setPlanIds(new ArrayList<>(List.of(planIds)));
        return req;
    }

    @SuppressWarnings("unchecked")
    private static List<String> days(SchedulingPlanDTO dto) {
        return days(dto.getLineSchedules().get(0));
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
