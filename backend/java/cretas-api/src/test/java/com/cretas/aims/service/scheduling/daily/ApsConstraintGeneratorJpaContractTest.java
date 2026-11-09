package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview;
import com.cretas.aims.dto.scheduling.DailyCapacityOverride;
import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.LineScheduleDTO;
import com.cretas.aims.dto.scheduling.SchedulePreviewRequest;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionPlanBatchUsage;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.security.AuthenticatedActorType;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 食品 DAG 缺口 18 服务层契约: 真 JPA (独立 H2 库) 跑 生成器 → 读约束 (设备配置 / 已落库排程 / 标准物料快照 /
 * 原料库存 / 在走采购单 / 钉住的配方) → 引擎 → 调度计划落库。全是真库, 没有桩。
 *
 * <p>对应统一验收清单三个场景: 同设备同日两个产品被错开 / 原料未到不排在到货前 / 缺数据照排并提示。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        // 独占自己的库 (create-drop 不能 drop 别的上下文的 schema); JSONB 映成 VARCHAR, daily_allocations 真往返
        "spring.datasource.url=jdbc:h2:mem:aps_constraints;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE"
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
class ApsConstraintGeneratorJpaContractTest {

    private static final String F = "F-APS18";
    private static final String OTHER = "F-APS18-OTHER";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @MockBean private NotificationService notificationService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private WorkerAssignmentService workerAssignmentService;
    @MockBean private SchedulingAIService schedulingAIService;

    @Autowired private DailyCapacityScheduleGenerator generator;
    @Autowired private EntityManager em;
    @Autowired private ProductionPlanRepository planRepository;
    @Autowired private ProductionLineRepository lineRepository;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private SchedulingPlanRepository schedulingPlanRepository;
    @Autowired private LineScheduleRepository lineScheduleRepository;

    private String lineId;
    private int seq;

    @BeforeEach
    void setUp() {
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        lineId = line(F, "一号线").getId();
    }

    // ==================== 场景 1: 同一设备两个产品同日 → 被错开 ====================

    @Test
    void twoProductsNeedingTheSameKettleAreStaggeredAndTheReasonIsStored() {
        Long kettle = equipment(F, "1号夹层锅", "GL-01", "idle", null);
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        needs(F, "PT-BEEF", kettle);
        needs(F, "PT-PORK", kettle);
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "40", "袋", null);
        plan(F, "PP-2", "PLAN-2", "PT-PORK", "60", "袋", null);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1", "PP-2"), 7L);

        Map<String, Map<String, Object>> alloc = allocByPlan(dto);
        System.out.println("APS18_EQUIPMENT " + alloc + " WARN " + dto.getWarnings());
        assertThat(days(alloc.get("PP-1"))).containsExactly(DAY + "=40");
        assertThat(days(alloc.get("PP-2"))).containsExactly(DAY.plusDays(1) + "=60");
        assertThat(delays(alloc.get("PP-2"))).singleElement().satisfies(d -> {
            assertThat(d.get("code")).isEqualTo("EQUIPMENT_BUSY");
            assertThat(d.get("from")).isEqualTo(DAY.toString());
            assertThat((String) d.get("text")).contains("1号夹层锅").contains("PLAN-1");
        });
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w).contains("PLAN-2").contains("1号夹层锅"));

        // 真库往返: 清缓存后再读, 推迟原因走 jsonb 列回来
        em.flush();
        em.clear();
        LineSchedule row = lineScheduleRepository.findByPlanIdOrderBySequenceOrder(dto.getId()).stream()
                .filter(ls -> "PP-2".equals(ls.getProductionPlanId())).findFirst().orElseThrow();
        assertThat(row.getPlannedStartTime()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(delays(row.getDailyAllocations())).hasSize(1);
    }

    @Test
    void negativeControlDifferentKettlesBothRunOnTheFirstDay() {
        Long k1 = equipment(F, "1号夹层锅", "GL-01", "idle", null);
        Long k2 = equipment(F, "2号夹层锅", "GL-02", "idle", null);
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        needs(F, "PT-BEEF", k1);
        needs(F, "PT-PORK", k2);
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "40", "袋", null);
        plan(F, "PP-2", "PLAN-2", "PT-PORK", "60", "袋", null);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1", "PP-2"), 7L);

        Map<String, Map<String, Object>> alloc = allocByPlan(dto);
        assertThat(days(alloc.get("PP-1"))).containsExactly(DAY + "=40");
        assertThat(days(alloc.get("PP-2"))).containsExactly(DAY + "=60");
        assertThat(delays(alloc.get("PP-2"))).isEmpty();
    }

    @Test
    void earlierDueDateGoesFirstOnTheSharedKettle() {
        Long kettle = equipment(F, "1号夹层锅", "GL-01", "idle", null);
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        needs(F, "PT-BEEF", kettle);
        needs(F, "PT-PORK", kettle);
        // 勾选顺序 PLAN-1 在前, 但 PLAN-2 交期早 → PLAN-2 先占锅
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "40", "袋", DAY.plusDays(9));
        plan(F, "PP-2", "PLAN-2", "PT-PORK", "60", "袋", DAY.plusDays(2));

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1", "PP-2"), 7L);

        Map<String, Map<String, Object>> alloc = allocByPlan(dto);
        assertThat(days(alloc.get("PP-2"))).containsExactly(DAY + "=60");
        assertThat(days(alloc.get("PP-1"))).containsExactly(DAY.plusDays(1) + "=40");
    }

    @Test
    void anEarlierSavedScheduleKeepsTheKettleAndANewRunGoesAroundIt() {
        Long kettle = equipment(F, "1号夹层锅", "GL-01", "idle", null);
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        needs(F, "PT-BEEF", kettle);
        needs(F, "PT-PORK", kettle);
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "300", "袋", null);
        plan(F, "PP-2", "PLAN-2", "PT-PORK", "60", "袋", null);

        // 先排的: 酱牛肉占锅 DAY..DAY+2
        generator.generate(F, request(DAY, "PP-1"), 7L);
        // 后排的从 DAY+1 开始, 锅被占到 DAY+2 ⇒ 卤猪蹄 DAY+3
        SchedulingPlanDTO second = generator.generate(F, request(DAY.plusDays(1), "PP-2"), 7L);

        Map<String, Object> alloc = allocByPlan(second).get("PP-2");
        assertThat(days(alloc)).containsExactly(DAY.plusDays(3) + "=60");
        assertThat(delays(alloc)).singleElement().satisfies(d -> {
            assertThat(d.get("code")).isEqualTo("EQUIPMENT_BUSY");
            assertThat(d.get("from")).isEqualTo(DAY.plusDays(1).toString());
            assertThat(d.get("to")).isEqualTo(DAY.plusDays(2).toString());
            assertThat((String) d.get("text")).contains("PLAN-1");
        });
    }

    @Test
    void equipmentPlannedMaintenanceDayIsSkipped() {
        Long kettle = equipment(F, "1号夹层锅", "GL-01", "idle", DAY);
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        needs(F, "PT-BEEF", kettle);
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "50", "袋", null);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1"), 7L);

        Map<String, Object> alloc = allocByPlan(dto).get("PP-1");
        assertThat(days(alloc)).containsExactly(DAY.plusDays(1) + "=50");
        assertThat(delays(alloc)).singleElement().satisfies(d -> assertThat(d.get("code")).isEqualTo("MAINTENANCE"));
    }

    // ==================== 场景 2: 原料未到 → 不排在到货前 ====================

    @Test
    void shortMaterialWaitsUntilTheDayAfterThePurchaseArrives() {
        materialProductWithSnapshot("PP-1", "PLAN-1", "100"); // 100 袋 × 0.5 kg = 50 kg
        stock(F, "RM-CHICKEN", "10");                          // 库存 10 kg, 差 40
        purchase(F, "RM-CHICKEN", "100", DAY.plusDays(3), PurchaseOrderStatus.SUBMITTED);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1"), 7L);

        Map<String, Object> alloc = allocByPlan(dto).get("PP-1");
        System.out.println("APS18_MATERIAL " + alloc + " WARN " + dto.getWarnings());
        assertThat(days(alloc)).containsExactly(DAY.plusDays(4) + "=100");
        assertThat(delays(alloc)).singleElement().satisfies(d -> {
            assertThat(d.get("code")).isEqualTo("MATERIAL_ARRIVAL");
            assertThat(d.get("to")).isEqualTo(DAY.plusDays(3).toString());
            assertThat((String) d.get("text")).contains("鸡胸肉").contains(DAY.plusDays(3).toString());
        });
    }

    @Test
    void negativeControlEnoughStockStartsOnTheFirstDay() {
        materialProductWithSnapshot("PP-1", "PLAN-1", "100");
        stock(F, "RM-CHICKEN", "500");
        purchase(F, "RM-CHICKEN", "100", DAY.plusDays(3), PurchaseOrderStatus.SUBMITTED);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1"), 7L);

        Map<String, Object> alloc = allocByPlan(dto).get("PP-1");
        assertThat(days(alloc)).containsExactly(DAY + "=100");
        assertThat(delays(alloc)).isEmpty();
    }

    @Test
    void draftPurchaseAndOtherFactoryPurchaseDoNotCountAsArrivals() {
        materialProductWithSnapshot("PP-1", "PLAN-1", "100");
        stock(F, "RM-CHICKEN", "10");
        purchase(F, "RM-CHICKEN", "100", DAY.plusDays(3), PurchaseOrderStatus.DRAFT);
        purchase(OTHER, "RM-CHICKEN", "100", DAY.plusDays(2), PurchaseOrderStatus.SUBMITTED);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1"), 7L);

        // 没有在走的采购单 ⇒ 不知道哪天到, 照排并提示
        Map<String, Object> alloc = allocByPlan(dto).get("PP-1");
        assertThat(days(alloc)).containsExactly(DAY + "=100");
        assertThat(notes(alloc)).anySatisfy(n -> assertThat(n).contains("鸡胸肉").contains("没有在走的采购单"));
    }

    // ==================== 场景 3: 缺数据 → 照排并提示 ====================

    @Test
    void missingEquipmentAndMaterialDataStillSchedulesWithNotes() {
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "150", "袋", null);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1"), 7L);

        assertThat(dto.getOutcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_SCHEDULED);
        Map<String, Object> alloc = allocByPlan(dto).get("PP-1");
        assertThat(days(alloc)).containsExactly(DAY + "=100", DAY.plusDays(1) + "=50");
        assertThat(notes(alloc)).anySatisfy(n -> assertThat(n).contains("没有配置要用的设备"))
                .anySatisfy(n -> assertThat(n).contains("标准物料快照"));
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w).contains("PLAN-1").contains("没有配置要用的设备"));
    }

    @Test
    void scrappedEquipmentLeavesThatPlanOutButOthersAreStillScheduled() {
        Long broken = equipment(F, "旧烤箱", "KX-00", "scrapped", null);
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        needs(F, "PT-BEEF", broken);
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "40", "袋", null);
        plan(F, "PP-2", "PLAN-2", "PT-PORK", "60", "袋", null);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1", "PP-2"), 7L);

        assertThat(allocByPlan(dto)).containsOnlyKeys("PP-2");
        assertThat(dto.getWarnings()).anySatisfy(w -> assertThat(w).contains("PLAN-1").contains("旧烤箱").contains("报废"));
    }

    // ==================== 半成品依赖 ====================

    @Test
    void consumerOfAnIntermediateStartsOnTheProducersLastDay() {
        product(F, "PT-SAUCE", "卤汁", "100", "kg");
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        plan(F, "PP-SAUCE", "PLAN-SAUCE", "PT-SAUCE", "250", "kg", DAY.plusDays(9));
        ProductionPlan beef = plan(F, "PP-BEEF", "PLAN-BEEF", "PT-BEEF", "50", "袋", DAY.plusDays(1));
        // 酱牛肉的配方钉住一行半成品 (引用码写的是卤汁的产品编码)
        recipeUsingIntermediate(beef, "C-PT-SAUCE");

        // 酱牛肉交期更早, 但它要等卤汁: 卤汁 DAY..DAY+2, 酱牛肉从 DAY+2 开始 (不留缓冲)
        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-BEEF", "PP-SAUCE"), 7L);

        Map<String, Map<String, Object>> alloc = allocByPlan(dto);
        System.out.println("APS18_DEPENDENCY " + alloc);
        assertThat(days(alloc.get("PP-SAUCE"))).last().asString().startsWith(DAY.plusDays(2).toString());
        assertThat(days(alloc.get("PP-BEEF"))).first().asString().startsWith(DAY.plusDays(2).toString());
        assertThat(delays(alloc.get("PP-BEEF"))).singleElement().satisfies(d -> {
            assertThat(d.get("code")).isEqualTo("PREDECESSOR");
            assertThat((String) d.get("text")).contains("PLAN-SAUCE");
        });
    }

    // ==================== 独立审查发现的回归 ====================

    @Test
    void aManuallyMovedScheduleOccupiesItsNewDatesNotItsOldDailySplit() {
        // 审查 #1: 人工改期只改起止时间, daily_allocations 还写着旧日子; 占用以起止时间为准
        Long kettle = equipment(F, "1号夹层锅", "GL-01", "idle", null);
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        needs(F, "PT-BEEF", kettle);
        needs(F, "PT-PORK", kettle);
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "100", "袋", null);
        plan(F, "PP-2", "PLAN-2", "PT-PORK", "60", "袋", null);
        SchedulingPlanDTO first = generator.generate(F, request(DAY, "PP-1"), 7L); // 酱牛肉 DAY
        LineSchedule moved = lineScheduleRepository.findById(first.getLineSchedules().get(0).getId()).orElseThrow();
        moved.setPlannedStartTime(DAY.plusDays(1).atStartOfDay());          // 人工拖到 DAY+1
        moved.setPlannedEndTime(DAY.plusDays(1).atTime(23, 59));
        lineScheduleRepository.saveAndFlush(moved);

        SchedulingPlanDTO second = generator.generate(F, request(DAY.plusDays(1), "PP-2"), 7L);

        // 锅 DAY+1 被改期后的酱牛肉占着 ⇒ 卤猪蹄 DAY+2 (旧算法按 days 认为 DAY+1 空闲)
        Map<String, Object> alloc = allocByPlan(second).get("PP-2");
        assertThat(days(alloc)).containsExactly(DAY.plusDays(2) + "=60");
        assertThat(delays(alloc)).singleElement().satisfies(d -> assertThat(d.get("code")).isEqualTo("EQUIPMENT_BUSY"));
    }

    @Test
    void aPlanThatCannotBeScheduledDoesNotTakeStockFromOthers() {
        // 审查 #3: A 的设备报废 (排不了) 交期更早, 不应吃掉库存让 B 去等采购
        materialProductWithSnapshot("PP-B", "PLAN-B", "100");              // B: 50 kg
        ProductionPlan b = planRepository.findByIdAndFactoryId("PP-B", F).orElseThrow();
        b.setExpectedCompletionDate(DAY.plusDays(9));
        planRepository.saveAndFlush(b);
        Long broken = equipment(F, "旧烤箱", "KX-00", "scrapped", null);
        product(F, "PT-JERKY", "牛肉干", "100", "袋");
        needs(F, "PT-JERKY", broken);
        plan(F, "PP-A", "PLAN-A", "PT-JERKY", "100", "袋", DAY.plusDays(1));
        snapshot("PP-A", "PT-JERKY");                                      // A 也要 50 kg
        stock(F, "RM-CHICKEN", "60");
        purchase(F, "RM-CHICKEN", "100", DAY.plusDays(3), PurchaseOrderStatus.SUBMITTED);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-A", "PP-B"), 7L);

        assertThat(allocByPlan(dto)).containsOnlyKeys("PP-B");
        assertThat(days(allocByPlan(dto).get("PP-B"))).containsExactly(DAY + "=100");
    }

    @Test
    void aPlansOwnReservationCountsForItInsteadOfBeingSubtractedTwice() {
        // 审查 #4: 计划自己预留了 50 kg (可用库存因此是 0), 它自己不应被判缺料去等采购
        materialProductWithSnapshot("PP-1", "PLAN-1", "100");             // 50 kg
        String lot = stock(F, "RM-CHICKEN", "50");
        MaterialBatch batch = em.find(MaterialBatch.class, lot);
        batch.setReservedQuantity(new BigDecimal("50"));
        em.flush();
        ProductionPlanBatchUsage usage = new ProductionPlanBatchUsage();
        usage.setId("PPBU-1");
        usage.setProductionPlanId("PP-1");
        usage.setMaterialBatchId(lot);
        usage.setPlannedQuantity(new BigDecimal("50"));
        usage.setReservedQuantity(new BigDecimal("50"));
        em.persist(usage);
        em.flush();
        purchase(F, "RM-CHICKEN", "100", DAY.plusDays(3), PurchaseOrderStatus.SUBMITTED);

        SchedulingPlanDTO dto = generator.generate(F, request(DAY, "PP-1"), 7L);

        Map<String, Object> alloc = allocByPlan(dto).get("PP-1");
        assertThat(days(alloc)).containsExactly(DAY + "=100");
        assertThat(delays(alloc)).isEmpty();
    }

    @Test
    void aPlansOldLiveScheduleStillOccupiesTheLineWhenItIsScheduledAgain() {
        // 审查 #5: PP-1 已在 DAY+1 的排程里占满一天; 从 DAY 重排 PP-1 + PP-2 时旧的那份仍算占用
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "100", "袋", null);
        plan(F, "PP-2", "PLAN-2", "PT-PORK", "100", "袋", null);
        generator.generate(F, request(DAY.plusDays(1), "PP-1"), 7L);       // 旧: PP-1 占满 DAY+1

        SchedulingPlanDTO again = generator.generate(F, request(DAY, "PP-1", "PP-2"), 7L);

        Map<String, Map<String, Object>> alloc = allocByPlan(again);
        assertThat(days(alloc.get("PP-1"))).containsExactly(DAY + "=100");
        assertThat(days(alloc.get("PP-2"))).containsExactly(DAY.plusDays(2) + "=100");
        assertThat(again.getWarnings()).anySatisfy(w -> assertThat(w).contains("PLAN-1").contains("已经排过"));
    }

    // ==================== 预览 (给插单用): 不落库 ====================

    @Test
    void previewComputesTheSameButWritesNothingAndPinsTheUrgentPlanFirst() {
        Long kettle = equipment(F, "1号夹层锅", "GL-01", "idle", null);
        product(F, "PT-BEEF", "酱牛肉", null, null); // 没日产能: 预览里补, 不写库
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        needs(F, "PT-BEEF", kettle);
        needs(F, "PT-PORK", kettle);
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "40", "袋", DAY.plusDays(1));
        plan(F, "PP-URGENT", "PLAN-URGENT", "PT-PORK", "60", "袋", DAY.plusDays(9));
        long plansBefore = schedulingPlanRepository.count();
        long rowsBefore = lineScheduleRepository.count();

        DailyCapacityOverride cap = new DailyCapacityOverride();
        cap.setProductTypeId("PT-BEEF");
        cap.setQuantityPerDay(new BigDecimal("100"));
        cap.setUnit("袋");
        ConstrainedSchedulePreview preview = generator.preview(F, new SchedulePreviewRequest(DAY, null,
                List.of("PP-1", "PP-URGENT"), List.of("PP-URGENT"), Set.of(), List.of(cap)));

        em.flush();
        assertThat(schedulingPlanRepository.count()).isEqualTo(plansBefore);
        assertThat(lineScheduleRepository.count()).isEqualTo(rowsBefore);
        em.clear();
        assertThat(productTypeRepository.findByIdAndFactoryId("PT-BEEF", F).orElseThrow().getDailyCapacity()).isNull();

        assertThat(preview.outcome()).isEqualTo(SchedulingPlanDTO.OUTCOME_SCHEDULED);
        assertThat(preview.productionLineId()).isEqualTo(lineId);
        // 与 planIds 同序; 插单钉在最前 ⇒ 它先占锅, 交期更早的 PLAN-1 推到第二天
        assertThat(preview.plans()).extracting(ConstrainedSchedulePreview.PlanResult::planId)
                .containsExactly("PP-1", "PP-URGENT");
        assertThat(preview.plans().get(1).days()).extracting(ConstrainedSchedulePreview.Day::date).containsExactly(DAY);
        assertThat(preview.plans().get(0).days()).extracting(ConstrainedSchedulePreview.Day::date)
                .containsExactly(DAY.plusDays(1));
        assertThat(preview.plans().get(0).delays()).extracting(ConstrainedSchedulePreview.Delay::code)
                .containsExactly("EQUIPMENT_BUSY");
        assertThat(preview.plans().get(0).dailyAllocations()).containsKey("days");
    }

    @Test
    void previewTreatsMovableSchedulesAsFree() {
        Long kettle = equipment(F, "1号夹层锅", "GL-01", "idle", null);
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        product(F, "PT-PORK", "卤猪蹄", "100", "袋");
        needs(F, "PT-BEEF", kettle);
        needs(F, "PT-PORK", kettle);
        plan(F, "PP-1", "PLAN-1", "PT-BEEF", "100", "袋", null);
        plan(F, "PP-URGENT", "PLAN-URGENT", "PT-PORK", "60", "袋", null);
        SchedulingPlanDTO saved = generator.generate(F, request(DAY, "PP-1"), 7L);
        String movable = saved.getLineSchedules().get(0).getId();

        ConstrainedSchedulePreview fixed = generator.preview(F, new SchedulePreviewRequest(DAY, null,
                List.of("PP-URGENT"), List.of(), Set.of(), null));
        ConstrainedSchedulePreview moved = generator.preview(F, new SchedulePreviewRequest(DAY, null,
                List.of("PP-URGENT", "PP-1"), List.of("PP-URGENT"), Set.of(movable), null));

        // 不让挪: 插单绕开已排的酱牛肉; 让挪: 插单先占 DAY, 酱牛肉重排到 DAY+1
        assertThat(fixed.plans().get(0).days()).extracting(ConstrainedSchedulePreview.Day::date)
                .containsExactly(DAY.plusDays(1));
        assertThat(moved.plans().get(0).days()).extracting(ConstrainedSchedulePreview.Day::date).containsExactly(DAY);
        assertThat(moved.plans().get(1).days()).extracting(ConstrainedSchedulePreview.Day::date)
                .containsExactly(DAY.plusDays(1));
    }

    // ==================== fixtures ====================

    private static GenerateScheduleRequest request(LocalDate day, String... planIds) {
        GenerateScheduleRequest req = new GenerateScheduleRequest();
        req.setPlanDate(day);
        req.setPlanIds(new ArrayList<>(List.of(planIds)));
        return req;
    }

    private static Map<String, Map<String, Object>> allocByPlan(SchedulingPlanDTO dto) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (LineScheduleDTO ls : dto.getLineSchedules()) {
            out.put(ls.getProductionPlanId(), ls.getDailyAllocations());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> days(Map<String, Object> alloc) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) alloc.get("days")) {
            out.add(d.get("date") + "=" + new BigDecimal(d.get("quantity").toString()).stripTrailingZeros().toPlainString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> delays(Map<String, Object> alloc) {
        return (List<Map<String, Object>>) alloc.get("delays");
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Map<String, Object> alloc) {
        return (List<String>) alloc.get("constraintNotes");
    }

    private ProductionLine line(String factoryId, String name) {
        ProductionLine line = new ProductionLine();
        line.setFactoryId(factoryId);
        line.setName(name);
        line.setStatus(ProductionLine.LineStatus.active);
        line.setHourlyCapacity(new BigDecimal("999"));
        return lineRepository.saveAndFlush(line);
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

    private ProductionPlan plan(String factoryId, String id, String number, String productTypeId, String quantity,
                                String unit, LocalDate due) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setPlanNumber(number);
        p.setProductTypeId(productTypeId);
        p.setPlannedQuantity(new BigDecimal(quantity));
        p.setPlannedUnit(unit);
        p.setExpectedCompletionDate(due);
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setSourceType(PlanSourceType.MANUAL);
        p.setCreatedBy(7L);
        p.setIsLocked(false);
        p.setSkipProcessReporting(false);
        return planRepository.saveAndFlush(p);
    }

    private Long equipment(String factoryId, String name, String code, String status, LocalDate nextMaintenance) {
        FactoryEquipment e = new FactoryEquipment();
        e.setFactoryId(factoryId);
        e.setCode(code);
        e.setEquipmentCode(code);
        e.setEquipmentName(name);
        e.setStatus(status);
        e.setNextMaintenanceDate(nextMaintenance);
        e.setCreatedBy(7L);
        em.persist(e);
        em.flush();
        return e.getId();
    }

    private void needs(String factoryId, String productTypeId, Long equipmentId) {
        ProductEquipmentRequirement r = new ProductEquipmentRequirement();
        r.setFactoryId(factoryId);
        r.setProductTypeId(productTypeId);
        r.setEquipmentId(equipmentId);
        r.setSource(ProductionLineCapacityConfig.Source.FACTORY_CONFIG);
        r.setActorType(AuthenticatedActorType.FACTORY_USER);
        r.setActorId(7L);
        em.persist(r);
        em.flush();
    }

    /** 酱牛肉: 开计划时的标准物料快照 = 每袋 0.5 kg 鸡胸肉。 */
    private void materialProductWithSnapshot(String planId, String number, String quantity) {
        product(F, "PT-BEEF", "酱牛肉", "100", "袋");
        RawMaterialType raw = new RawMaterialType();
        raw.setId("RM-CHICKEN");
        raw.setFactoryId(F);
        raw.setCode("RM-CHICKEN");
        raw.setName("鸡胸肉");
        raw.setUnit("kg");
        em.persist(raw);
        plan(F, planId, number, "PT-BEEF", quantity, "袋", null);
        snapshot(planId, "PT-BEEF");
    }

    /** 开计划时的标准物料快照: 每袋 0.5 kg 鸡胸肉 (原料 RM-CHICKEN 须已存在)。 */
    private void snapshot(String planId, String productTypeId) {
        PlanStandardCostLine line = new PlanStandardCostLine();
        line.setFactoryId(F);
        line.setPlanId(planId);
        line.setLineType(PlanStandardCostLine.TYPE_MATERIAL);
        line.setMaterialTypeId("RM-CHICKEN");
        line.setMaterialName("鸡胸肉");
        line.setProductTypeId(productTypeId);
        line.setStandardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT);
        line.setStandardRate(new BigDecimal("0.5"));
        line.setStandardUnit("kg");
        line.setCapturedAt(LocalDateTime.now());
        em.persist(line);
        em.flush();
    }

    private String stock(String factoryId, String materialTypeId, String quantity) {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("WH-" + (++seq));
        wh.setFactoryId(factoryId);
        wh.setCode("WH" + seq);
        wh.setName("原料仓" + seq);
        wh.setType(FactoryWarehouse.WarehouseType.RAW);
        em.persist(wh);
        MaterialBatch b = new MaterialBatch();
        b.setId(java.util.UUID.randomUUID().toString());
        b.setFactoryId(factoryId);
        b.setBatchNumber("MB-APS18-" + (++seq));
        b.setMaterialTypeId(materialTypeId);
        b.setReceiptDate(DAY.minusDays(3));
        b.setWarehouseId(wh.getId());
        b.setReceiptQuantity(new BigDecimal(quantity));
        b.setQuantityUnit("kg");
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setCreatedBy(7L);
        em.persist(b);
        em.flush();
        return b.getId();
    }

    private void purchase(String factoryId, String materialTypeId, String quantity, LocalDate expected,
                          PurchaseOrderStatus status) {
        PurchaseOrder o = new PurchaseOrder();
        o.setFactoryId(factoryId);
        o.setOrderNumber("PO-APS18-" + (++seq));
        o.setOrderDate(DAY.minusDays(1));
        o.setExpectedDeliveryDate(expected);
        o.setStatus(status);
        o.setCreatedBy(7L);
        em.persist(o);
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setPurchaseOrderId(o.getId());
        i.setMaterialTypeId(materialTypeId);
        i.setMaterialName("鸡胸肉");
        i.setQuantity(new BigDecimal(quantity));
        i.setUnit("kg");
        i.setPriceUnit("kg");
        em.persist(i);
        em.flush();
    }

    private void recipeUsingIntermediate(ProductionPlan plan, String refCode) {
        BomRecipe recipe = BomRecipe.builder()
                .id("RCP-BEEF")
                .factoryId(F)
                .recipeCode("RCP-BEEF")
                .productTypeId(plan.getProductTypeId())
                .productName("酱牛肉")
                .outputQuantityPerUnit(BigDecimal.ONE)
                .outputUnit("袋")
                .build();
        em.persist(recipe);
        BomRecipeItem item = BomRecipeItem.builder()
                .recipeId(recipe.getId())
                .factoryId(F)
                .materialName("卤汁")
                .semiFinishedRefCode(refCode)
                .materialCategory("SEMI_FINISHED")
                .standardQuantity(new BigDecimal("0.2"))
                .unit("kg")
                .build();
        em.persist(item);
        plan.setSelectedBomRecipeId(recipe.getId());
        plan.setSelectedBomVersion(1);
        planRepository.saveAndFlush(plan);
        em.flush();
    }
}
