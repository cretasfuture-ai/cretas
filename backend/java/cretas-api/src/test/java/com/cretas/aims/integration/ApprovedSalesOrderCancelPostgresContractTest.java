package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.discipline.FactoryDisciplinePolicy;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.inventory.impl.ApprovedSalesOrderCancellation;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.production.SecondaryPlanFeedService;
import com.cretas.aims.service.production.SettlementSemiStockingService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 真 PostgreSQL + 真 Hibernate: 销售单财务批准后取消 (工厂开关, 2026-11-11) 连带取消自动生成的生产计划。
 *
 * <ul>
 *   <li>全有或全无: 本单两个自动计划里一个已开工有报工 ⇒ 整单 409, 先处理的那个计划也回滚成待处理, 订单不变;</li>
 *   <li>级联: 计划经正式取消入口 + 切面作废未开工排程 (CancelledPlanScheduleCascade, #1574/#1579 的同一份);</li>
 *   <li>并发: 别人先拿销售单行锁并插入一张发货单 ⇒ 取消等锁, 拿到锁后看到发货单 ⇒ 409, 计划一条都不动。</li>
 * </ul>
 * Mockito 给的列表和 save 计数照不出事务回滚和行锁, 所以要真库。开关同 {@link PlanCancelAfterMaterialReturnPostgresContractTest}。
 */
@SpringBootTest(classes = ApprovedSalesOrderCancelPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=6"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ApprovedSalesOrderCancelPostgresContractTest {

    private static final String F = "ASOC-PG";
    private static final Long BOSS = 7L;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ProductionPlanServiceImpl.class, WipInventoryServiceImpl.class,
            SecondaryPlanFeedService.class, SettlementSemiStockingService.class,
            com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl.class,
            com.cretas.aims.service.scheduling.SchedulingResourceWriteLock.class,
            com.cretas.aims.service.scheduling.CancelledPlanScheduleCascade.class,
            com.cretas.aims.service.scheduling.CancelledPlanScheduleCascadeAspect.class,
            com.cretas.aims.service.scheduling.impl.LineScheduleServiceImpl.class,
            ApprovedSalesOrderCancellation.class})
    @org.springframework.context.annotation.EnableAspectJAutoProxy
    static class Runtime { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @MockBean ProductionPlanMapper planMapper;
    @MockBean SchedulingService schedulingService;
    @MockBean ExcelUtil excelUtil;
    @MockBean InventoryLowStockEventPublisher lowStockEvents;
    @MockBean ProductFamilyResolver familyResolver;
    @MockBean UnitContractService unitContractService;
    @MockBean IntermediateOwnershipGuard ownershipGuard;
    @MockBean com.cretas.aims.service.workprocess.WorkProcessTaskService workProcessTaskService;
    @MockBean com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope processSecretLlmCostScope;
    @MockBean com.cretas.aims.service.finance.ProductionCostConsistency productionCostConsistency;
    @MockBean com.cretas.aims.service.finance.ReturnedCostSynchronizationService returnedCostSynchronizationService;
    @MockBean com.cretas.aims.service.NotificationService notificationService;
    @MockBean com.cretas.aims.service.PushNotificationService pushNotificationService;
    @MockBean com.cretas.aims.service.scheduling.core.WorkerAssignmentService workerAssignmentService;
    @MockBean com.cretas.aims.service.scheduling.core.SchedulingAIService schedulingAIService;
    // 开关和权限不在本测试的验证范围 (单测覆盖); 这里固定为「已开 / 有权限」, 只验事务和锁
    @MockBean FactoryDisciplinePolicy disciplinePolicy;
    @MockBean PermissionService permissionService;
    @org.springframework.boot.test.mock.mockito.SpyBean UserRepository userRepository;
    // 缺料实例的取消走 ShortageCaseActionService.decide, 本测试不建缺料实例, 不装配它的整条依赖链
    @MockBean com.cretas.aims.service.shortage.ShortageCaseActionService shortageCaseActionService;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApprovedSalesOrderCancellation cancellation;
    @Autowired SalesOrderRepository salesOrders;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductionBatchRepository batches;
    @Autowired MaterialConsumptionRepository consumptions;
    @Autowired com.cretas.aims.repository.SchedulingPlanRepository schedulingPlans;
    @Autowired com.cretas.aims.repository.LineScheduleRepository lineSchedules;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        when(disciplinePolicy.cancelApprovedSalesOrderAllowed(F)).thenReturn(true);
        User boss = new User();
        boss.setId(BOSS);
        boss.setFactoryId(F);
        org.mockito.Mockito.doReturn(Optional.of(boss)).when(userRepository).findByIdAndFactoryId(BOSS, F);
        when(permissionService.hasPermission(any(User.class), eq(PermissionService.SALES_ORDER_CANCEL_APPROVED_PERMISSION)))
                .thenReturn(true);
    }

    private String approvedOrder() {
        String id = "SO-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> {
            SalesOrder o = new SalesOrder();
            o.setId(id);
            o.setFactoryId(F);
            o.setOrderNumber("ASOC-" + id);
            o.setCustomerId("C-1");
            o.setOrderDate(LocalDate.now());
            o.setStatus(SalesOrderStatus.FINANCE_APPROVED);
            o.setCreatedBy(BOSS);
            salesOrders.save(o);
        });
        return id;
    }

    private String autoPlan(String soId, ProductionPlanStatus status, LocalDateTime createdAt) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String id = tx.execute(s -> {
            ProductionPlan p = new ProductionPlan();
            p.setId("PLAN-" + suffix);
            p.setFactoryId(F);
            p.setPlanNumber("ASOC-PP-" + suffix);
            p.setProductTypeId("FG-1");
            p.setPlannedQuantity(new BigDecimal("100"));
            p.setPlannedUnit("袋");
            p.setStatus(status);
            p.setCreatedBy(BOSS);
            p.setPlanSourceType("NORMAL");
            p.setSourceType(PlanSourceType.CUSTOMER_ORDER);
            p.setSourceOrderId(soId);
            p.setIsLocked(false);
            return plans.save(p).getId();
        });
        // 查询按 created_at 倒序: 显式定时间, 让「先处理的计划」可控
        jdbc.update("update production_plans set created_at = ? where id = ?", createdAt, id);
        return id;
    }

    /** 已开工 + 一笔未冲销的报工消耗 ⇒ 计划自己的取消闸会拦 (PLAN_HAS_PRODUCTION_DATA)。 */
    private void withProductionData(String planId) {
        tx.executeWithoutResult(s -> {
            ProductionBatch b = new ProductionBatch();
            b.setFactoryId(F);
            b.setBatchNumber("PB-" + UUID.randomUUID().toString().substring(0, 8));
            b.setProductTypeId("FG-1");
            b.setQuantity(BigDecimal.ONE);
            b.setUnit("袋");
            b.setStatus(ProductionBatchStatus.IN_PROGRESS);
            b.setProductionPlanId(planId);
            b = batches.save(b);
            MaterialConsumption c = new MaterialConsumption();
            c.setFactoryId(F);
            c.setProductionPlanId(planId);
            c.setProductionBatchId(b.getId());
            c.setBatchId("MB-1");
            c.setQuantity(BigDecimal.ONE);
            c.setUnitPrice(BigDecimal.TEN);
            c.setTotalCost(BigDecimal.TEN);
            c.setConsumptionTime(LocalDateTime.now());
            c.setConsumedAt(LocalDateTime.now());
            c.setRecordedBy(BOSS);
            c.setMaterialTypeId("MT-1");
            c.setSourceType("RAW_MATERIAL");
            c.setNotes("报工消耗");
            consumptions.save(c);
        });
    }

    private String pendingRow(String planId, LocalDate day) {
        return tx.execute(s -> {
            com.cretas.aims.entity.SchedulingPlan d = new com.cretas.aims.entity.SchedulingPlan();
            d.setFactoryId(F);
            d.setPlanDate(day);
            d.setPlanName(day + " 调度计划");
            d.setCreatedBy(BOSS);
            d.setStatus(com.cretas.aims.entity.SchedulingPlan.PlanStatus.draft);
            d = schedulingPlans.saveAndFlush(d);
            com.cretas.aims.entity.LineSchedule ls = new com.cretas.aims.entity.LineSchedule();
            ls.setPlan(d);
            ls.setProductionLineId("LINE-1");
            ls.setProductionPlanId(planId);
            ls.setSequenceOrder(0);
            ls.setPlannedStartTime(day.atTime(8, 0));
            ls.setPlannedEndTime(day.atTime(16, 0));
            ls.setPlannedQuantity(100);
            ls.setStatus(com.cretas.aims.entity.LineSchedule.ScheduleStatus.pending);
            return lineSchedules.saveAndFlush(ls).getId();
        });
    }

    private String planStatus(String id) {
        return jdbc.queryForObject("select status from production_plans where id = ?", String.class, id);
    }

    private String orderStatus(String id) {
        return jdbc.queryForObject("select status from sales_orders where id = ?", String.class, id);
    }

    /**
     * 与 SalesServiceImpl.cancelOrder 同一事务形状: 先不加锁读一次订单 (getSalesOrderById, 进持久化上下文),
     * 再连带取消 + 把锁住的订单置为已取消, 一起提交或一起回滚。
     */
    private ApprovedSalesOrderCancellation.Outcome cancelAsTheServiceDoes(String soId) {
        return tx.execute(s -> {
            salesOrders.findById(soId).orElseThrow();
            ApprovedSalesOrderCancellation.Outcome o = cancellation.cancelDownstream(F, soId, "客户撤单", BOSS);
            o.lockedOrder().setStatus(SalesOrderStatus.CANCELLED);
            salesOrders.save(o.lockedOrder());
            return o;
        });
    }

    @Test
    void cancellingCascadesThroughTheRealPlanCancelAndVoidsNotStartedSchedules() {
        String so = approvedOrder();
        String plan = autoPlan(so, ProductionPlanStatus.PENDING, LocalDateTime.now().minusHours(1));
        String row = pendingRow(plan, LocalDate.of(2026, 11, 20));

        ApprovedSalesOrderCancellation.Outcome o = cancelAsTheServiceDoes(so);

        assertThat(orderStatus(so)).isEqualTo("CANCELLED");
        assertThat(planStatus(plan)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from line_schedules where id = ?", String.class, row))
                .as("未开工排程经 CancelledPlanScheduleCascade 作废").isEqualTo("cancelled");
        assertThat(o.cancelledSchedules()).isEqualTo(1);
        System.out.println("ASOC_PG_CASCADE " + o.describe());
    }

    @Test
    void aStartedPlanWithReportsRollsBackTheWholeCancellationIncludingThePlanAlreadyCancelled() {
        String so = approvedOrder();
        // 新的先处理 (created_at DESC): 先取消它, 再撞到有报工的那个 ⇒ 必须连它一起回滚
        String fresh = autoPlan(so, ProductionPlanStatus.PENDING, LocalDateTime.now().minusMinutes(5));
        String started = autoPlan(so, ProductionPlanStatus.IN_PROGRESS, LocalDateTime.now().minusHours(3));
        withProductionData(started);
        String freshRow = pendingRow(fresh, LocalDate.of(2026, 11, 21));

        assertThatThrownBy(() -> cancelAsTheServiceDoes(so))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PLAN_HAS_PRODUCTION_DATA"));

        assertThat(orderStatus(so)).isEqualTo("FINANCE_APPROVED");
        assertThat(planStatus(fresh)).as("先取消的计划随整单回滚").isEqualTo("PENDING");
        assertThat(planStatus(started)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select status from line_schedules where id = ?", String.class, freshRow))
                .as("排程作废也回滚").isEqualTo("pending");
    }

    @Test
    void aStatusChangedWhileWaitingForTheLockIsReadAfterTheLock() throws Exception {
        String so = approvedOrder();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Object result;
        try {
            // B: 拿销售单行锁并把订单改成生产中 (像开始执行那样), 等 A 读过一次旧状态并在排队后再提交
            Future<?> mover = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForObject("select id from sales_orders where id = ? for update", String.class, so);
                jdbc.update("update sales_orders set status = 'PROCESSING' where id = ?", so);
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> canceller = pool.submit(() -> {
                try {
                    return cancelAsTheServiceDoes(so);   // 先读到 FINANCE_APPROVED (B 未提交), 再等锁
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Thread.sleep(500);
            release.countDown();
            mover.get(10, TimeUnit.SECONDS);
            result = canceller.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(result).as("锁后重读: 看到的是 B 提交后的 PROCESSING, 不是锁前读进来的旧状态")
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("处理中"));
        assertThat(orderStatus(so)).isEqualTo("PROCESSING");
    }

    @Test
    void aDeliveryInsertedUnderTheOrderLockIsSeenAfterTheLockAndBlocksTheCancellation() throws Exception {
        String so = approvedOrder();
        String plan = autoPlan(so, ProductionPlanStatus.PENDING, LocalDateTime.now().minusHours(1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // B: 和建发货单一样先拿销售单行锁, 插一张发货单, 等 A 已经在排队再提交
            Future<?> shipper = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForObject("select id from sales_orders where id = ? for update", String.class, so);
                jdbc.update("insert into sales_delivery_records (id, factory_id, delivery_number, record_role, "
                                + "customer_id, delivery_date, status, shipped_by, sales_order_id, created_at, updated_at) "
                                + "values (?, ?, ?, 'LEGACY', 'C-1', ?, 'PENDING_WAREHOUSE_CONFIRM', ?, ?, now(), now())",
                        UUID.randomUUID().toString(), F, "DLV-" + UUID.randomUUID().toString().substring(0, 8),
                        LocalDate.now(), BOSS, so);
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("B 拿到了销售单行锁").isTrue();
            Future<Object> canceller = pool.submit(() -> {
                try {
                    return cancelAsTheServiceDoes(so);
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Thread.sleep(500);
            assertThat(canceller.isDone()).as("A 在等 B 的行锁, 没有抢先读到「没有发货单」").isFalse();
            release.countDown();
            shipper.get(10, TimeUnit.SECONDS);
            Object result = canceller.get(20, TimeUnit.SECONDS);
            assertThat(result).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getMessage()).contains("发货单"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(orderStatus(so)).isEqualTo("FINANCE_APPROVED");
        assertThat(planStatus(plan)).as("被拦之后计划一条没动").isEqualTo("PENDING");
    }
}
