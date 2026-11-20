package com.cretas.aims.service.impl;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.cretas.aims.service.scheduling.WorkerShiftAvailabilityCheck;
import com.cretas.aims.service.workflow.FactorySelfApprovalGate;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.workflow.impl.WorkflowEngineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1551-11: 强制插单画布审批在真实 PostgreSQL 上并发处理。
 *
 * <p>真实的: 画布引擎 ({@link WorkflowEngineServiceImpl}, 实例 + 审批记录两张表, 带乐观锁版本),
 * 计划 / 时段 / 工厂 / 用户仓库, 工厂行锁 ({@link SchedulingResourceWriteLock})、事务提交。
 * 桩: 画布定义读取 (固定一张单节点画布)、排程引擎 (只计数「排入了几次」, 并在事务里停 300ms 让对手真的撞上)。
 *
 * <p>守的性质: 两个审批人同时点 (批准 + 批准、批准 + 驳回), 结局只有一个且计划、时段、实例三者一致 ——
 * 不会插入两次, 不会「实例驳回了但计划插进去了」或反过来。
 * 没有 {@code CRETAS_WORKFLOW_PG_URL} 时整类跳过, 由 CI 的 postgres-reversal-concurrency 作业执行。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
class UrgentInsertForceInsertCanvasPostgresContractTest {

    private static final String APPROVAL_NODE = "pm_approval";

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProductionPlanRepository plans;
    @Autowired InsertSlotRepository slots;
    @Autowired UserRepository users;
    @Autowired FactoryRepository factories;
    @Autowired ApprovalWorkflowInstanceRepository instances;
    @Autowired ApprovalHistoryRepository histories;
    @Autowired JdbcTemplate jdbc;

    private String factory;
    private String product;
    private String line;
    private Long submitter;
    private Long approverA;
    private Long approverB;
    private LocalDate day;
    private ApprovalWorkflow canvas;
    private WorkflowEngineService engine;
    private UrgentInsertServiceImpl service;
    private final AtomicInteger scheduleApplies = new AtomicInteger();

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]*test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Only owned loopback *_test PostgreSQL is allowed");
        }
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private Long persistUser(String role) {
        User u = new User();
        u.setFactoryId(factory);
        u.setUsername("fi-" + UUID.randomUUID());
        u.setFullName(role);
        u.setPasswordHash("unused-test-fixture");
        u.setRoleCode(role);
        em.persist(u);
        em.flush();
        return u.getId();
    }

    @BeforeEach
    void committedFixture() throws Exception {
        factory = "FI-" + UUID.randomUUID().toString().substring(0, 8);
        product = UUID.randomUUID().toString();
        line = UUID.randomUUID().toString();
        day = LocalDate.now().plusDays(3);
        tx(() -> {
            Factory f = new Factory();
            f.setId(factory);
            f.setName(factory);
            em.persist(f);
            submitter = persistUser("production_manager");
            approverA = persistUser("production_manager");
            approverB = persistUser("production_manager");
            ProductType p = new ProductType();
            p.setId(product);
            p.setFactoryId(factory);
            p.setCode(product);
            p.setName("force insert output");
            p.setUnit("kg");
            em.persist(p);
            ProductionLine l = new ProductionLine();
            l.setId(line);
            l.setFactoryId(factory);
            l.setName("force insert line");
            em.persist(l);
            return null;
        });

        ObjectMapper om = new ObjectMapper();
        Map<String, Object> nodeConfig = new HashMap<>();
        nodeConfig.put("approverRoles", List.of("production_manager"));
        List<ApprovalWorkflowNode> nodes = List.of(
                ApprovalWorkflowNode.builder().id("start").type("start").build(),
                ApprovalWorkflowNode.builder().id(APPROVAL_NODE).type("approval").config(nodeConfig).build(),
                ApprovalWorkflowNode.builder().id("approved").type("end").config(Map.of("outcome", "APPROVED")).build(),
                ApprovalWorkflowNode.builder().id("rejected").type("end").config(Map.of("outcome", "REJECTED")).build());
        List<ApprovalWorkflowEdge> edges = List.of(
                ApprovalWorkflowEdge.builder().id("e1").source("start").target(APPROVAL_NODE).build(),
                ApprovalWorkflowEdge.builder().id("e2").source(APPROVAL_NODE).target("approved")
                        .condition("#decision == 'APPROVE'").build(),
                ApprovalWorkflowEdge.builder().id("e3").source(APPROVAL_NODE).target("rejected")
                        .condition("#decision == 'REJECT'").priority(1).build());
        canvas = ApprovalWorkflow.builder()
                .id(UUID.randomUUID().toString()).factoryId(factory).decisionType(DecisionType.FORCE_INSERT)
                .name("强制插单 PG 并发").nodesJson(om.writeValueAsString(nodes)).edgesJson(om.writeValueAsString(edges))
                .startNodeId("start").version(1).publishStatus("published").enabled(true).build();
        ApprovalWorkflowService definitions = new ApprovalWorkflowServiceImpl(null, om) {
            @Override
            public Optional<ApprovalWorkflow> getByIdForUpdate(String factoryId, String id) {
                return Optional.of(canvas);
            }

            @Override
            public Optional<ApprovalWorkflow> getActiveByDecisionType(String factoryId, DecisionType type) {
                return Optional.of(canvas);
            }

            @Override
            public Optional<ApprovalWorkflow> getById(String factoryId, String id) {
                return Optional.of(canvas);
            }
        };
        engine = new WorkflowEngineServiceImpl(instances, histories, definitions,
                new com.cretas.aims.service.workflow.SandboxedSpelEvaluator(), null);
        // 生产上由 Spring 注入的同一份登记表 (FORCE_INSERT → moduleCode 的映射只在这里)
        com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry registry =
                new com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry();
        registry.init();
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "decisionTypeMetadataRegistry", registry);

        UrgentInsertScheduleService scheduler = mock(UrgentInsertScheduleService.class);
        when(scheduler.apply(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            scheduleApplies.incrementAndGet();
            Thread.sleep(300);   // 持锁停一会儿, 让并发的另一次审批真的排在锁后面
            return UrgentInsertTestSupport.okProposal();
        });
        WorkerShiftAvailabilityCheck workers = mock(WorkerShiftAvailabilityCheck.class);
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(new WorkerShiftAvailabilityCheck.Result(true, 0, 0, Set.of(), "ok"));
        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.ofEntries(
                Map.entry(InsertSlotRepository.class, slots),
                Map.entry(ProductionPlanRepository.class, plans),
                Map.entry(UserRepository.class, users),
                Map.entry(SchedulingResourceWriteLock.class, new SchedulingResourceWriteLock(factories)),
                Map.entry(WorkflowEngineService.class, engine),
                Map.entry(FactorySelfApprovalGate.class, new FactorySelfApprovalGate(null)),
                Map.entry(UrgentInsertScheduleService.class, scheduler),
                Map.entry(WorkerShiftAvailabilityCheck.class, workers),
                Map.entry(ObjectMapper.class, om)));
    }

    /** 已提交的: 待审批强制插单 + 它占着的时段 + RUNNING 画布实例 (经真实引擎创建)。 */
    private String[] pendingForceInsert() {
        String planId = UUID.randomUUID().toString();
        LocalDateTime start = day.atTime(9, 0);
        String slotId = UUID.randomUUID().toString();
        String instanceId = tx(() -> {
            slots.saveAndFlush(InsertSlot.builder().id(slotId).factoryId(factory).productionLineId(line)
                    .startTime(start).endTime(start.plusHours(2)).status("selected").build());
            ProductionPlan p = new ProductionPlan();
            p.setId(planId);
            p.setFactoryId(factory);
            p.setPlanNumber("URGENT-" + planId.substring(0, 8));
            p.setProductTypeId(product);
            p.setPlannedQuantity(new BigDecimal("50"));
            p.setPlannedUnit("kg");
            p.setPlannedDate(day);
            p.setStartTime(start);
            p.setSuggestedProductionLineId(line);
            p.setPlanType(ProductionPlanType.FROM_INVENTORY);
            p.setCreatedBy(submitter);
            p.markAsForceInsert(submitter, "客户急单", true);
            p.setStatus(ProductionPlanStatus.PENDING);
            plans.saveAndFlush(p);
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("planId", planId);
            ctx.put("impactLevel", "high");
            ApprovalWorkflowInstance inst = engine.startWorkflowIfConfigured(
                    factory, UrgentInsertServiceImpl.FORCE_INSERT_MODULE, planId, ctx, submitter).orElseThrow();
            return inst.getId();
        });
        return new String[] {planId, slotId, instanceId};
    }

    private List<String> race(String planId, String instanceId, Object[][] actors) throws Exception {
        CyclicBarrier go = new CyclicBarrier(actors.length);
        List<String> outcomes = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(actors.length);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Object[] a : actors) {
                futures.add(pool.submit(() -> {
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        tx(() -> service.applyWorkflowAction(factory, planId, instanceId, (Long) a[0],
                                "production_manager", APPROVAL_NODE, (HistoryAction) a[1], (String) a[2]));
                        outcomes.add("OK_" + a[1]);
                    } catch (BusinessException e) {
                        outcomes.add("BUSINESS_" + e.getErrorCode());
                    } catch (Exception e) {
                        outcomes.add("ERR_" + e.getClass().getSimpleName());
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    private String planApproval(String planId) {
        return jdbc.queryForObject("SELECT approval_status FROM production_plans WHERE id = ?", String.class, planId);
    }

    private String planStatus(String planId) {
        return jdbc.queryForObject("SELECT status FROM production_plans WHERE id = ?", String.class, planId);
    }

    private String slotStatus(String slotId) {
        return jdbc.queryForObject("SELECT status FROM insert_slots WHERE id = ?", String.class, slotId);
    }

    private String instanceStatus(String instanceId) {
        return jdbc.queryForObject("SELECT status FROM approval_workflow_instances WHERE id = ?", String.class, instanceId);
    }

    private int decisionRows(String instanceId) {
        return jdbc.queryForObject("SELECT count(*) FROM approval_history WHERE instance_id = ? AND action IN ('APPROVE','REJECT')",
                Integer.class, instanceId);
    }

    @Test
    void twoApproversApprovingAtOnceInsertTheOrderExactlyOnce() throws Exception {
        String[] f = pendingForceInsert();
        assertThat(instanceStatus(f[2])).as("一定会发生: 实例已真实创建并在等审批").isEqualTo("RUNNING");

        List<String> outcomes = race(f[0], f[2], new Object[][] {
                {approverA, HistoryAction.APPROVE, "同意"},
                {approverB, HistoryAction.APPROVE, "同意"}});

        System.out.println("FORCE_INSERT_PG_APPROVE_APPROVE outcomes=" + outcomes + " applies=" + scheduleApplies.get()
                + " instance=" + instanceStatus(f[2]) + " plan=" + planApproval(f[0]) + " decisions=" + decisionRows(f[2]));
        assertThat(outcomes).as("一个成功, 后来者被告知已处理").containsExactlyInAnyOrder(
                "OK_APPROVE", "BUSINESS_FORCE_INSERT_ALREADY_DECIDED");
        assertThat(scheduleApplies.get()).as("只排入一次").isEqualTo(1);
        assertThat(instanceStatus(f[2])).isEqualTo("APPROVED");
        assertThat(planApproval(f[0])).isEqualTo("APPROVED");
        assertThat(decisionRows(f[2])).as("审批记录只有一条决定").isEqualTo(1);
        assertThat(slotStatus(f[1])).isEqualTo("selected");
    }

    @Test
    void approveAndRejectAtOnceEndInOneConsistentOutcome() throws Exception {
        String[] f = pendingForceInsert();

        List<String> outcomes = race(f[0], f[2], new Object[][] {
                {approverA, HistoryAction.APPROVE, "同意"},
                {approverB, HistoryAction.REJECT, "产能不够"}});

        String inst = instanceStatus(f[2]);
        System.out.println("FORCE_INSERT_PG_APPROVE_REJECT outcomes=" + outcomes + " applies=" + scheduleApplies.get()
                + " instance=" + inst + " plan=" + planApproval(f[0]) + "/" + planStatus(f[0])
                + " slot=" + slotStatus(f[1]) + " decisions=" + decisionRows(f[2]));
        assertThat(outcomes).hasSize(2).noneMatch(o -> o.startsWith("ERR_"))
                .as("恰好一个生效, 另一个被告知已处理").contains("BUSINESS_FORCE_INSERT_ALREADY_DECIDED")
                .filteredOn(o -> o.startsWith("OK_")).hasSize(1);
        assertThat(decisionRows(f[2])).isEqualTo(1);
        if ("APPROVED".equals(inst)) {
            assertThat(scheduleApplies.get()).isEqualTo(1);
            assertThat(planApproval(f[0])).isEqualTo("APPROVED");
            assertThat(slotStatus(f[1])).isEqualTo("selected");
        } else {
            assertThat(inst).isEqualTo("REJECTED");
            assertThat(scheduleApplies.get()).as("驳回赢了就绝不能插入").isZero();
            assertThat(planApproval(f[0])).isEqualTo("REJECTED");
            assertThat(planStatus(f[0])).isEqualTo("CANCELLED");
            assertThat(slotStatus(f[1])).as("驳回释放时段").isEqualTo("available");
        }
    }

    @Test
    void theSubmitterCannotApproveTheirOwnInsertAndNothingIsWritten() {
        String[] f = pendingForceInsert();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx(() -> service.applyWorkflowAction(
                        factory, f[0], f[2], submitter, "production_manager", APPROVAL_NODE,
                        HistoryAction.APPROVE, "我自己批")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("FORCE_INSERT_SELF_APPROVAL_FORBIDDEN"));

        assertThat(instanceStatus(f[2])).isEqualTo("RUNNING");
        assertThat(decisionRows(f[2])).isZero();
        assertThat(planApproval(f[0])).isEqualTo("PENDING");
        assertThat(scheduleApplies.get()).isZero();
    }
}
