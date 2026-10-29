package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.ai.AiPurchaseTaskGrantRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.*;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real persisted token/grant lifecycle. Only identity, references and purchase boundary are mocked. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {
        com.cretas.aims.entity.ai.AiPurchaseTaskGrant.class,
        IntentPreviewToken.class,
        com.cretas.aims.entity.ai.PurchaseGrantTimerBinding.class,
        com.cretas.aims.entity.cron.ScheduledTask.class,
        com.cretas.aims.entity.Factory.class,
        com.cretas.aims.entity.User.class,
        com.cretas.aims.entity.Supplier.class,
        com.cretas.aims.entity.RawMaterialType.class,
        com.cretas.aims.entity.inventory.PurchaseOrder.class,
        com.cretas.aims.entity.inventory.PurchaseOrderItem.class,
        com.cretas.aims.entity.inventory.PurchaseReceiveRecord.class})
@EnableJpaRepositories(basePackageClasses = {AiPurchaseTaskGrantRepository.class, IntentPreviewTokenRepository.class,
        PurchaseOrderRepository.class, PurchaseOrderItemRepository.class,
        com.cretas.aims.repository.ai.PurchaseGrantTimerBindingRepository.class,
        com.cretas.aims.repository.cron.ScheduledTaskRepository.class})
@Import({PurchaseGrantTimerExecutionService.class,PurchaseTaskGrantService.class, ControlledPurchaseTaskExecutionService.class,
        PurchaseGrantCallerService.class, com.cretas.aims.ai.tool.impl.purchase.PurchaseGrantExecuteTool.class,
        com.cretas.aims.controller.PurchaseGrantCallerController.class,
        com.cretas.aims.service.skill.impl.SkillExecutorImpl.class,
        com.cretas.aims.ai.tool.WriteGuardService.class,
        PurchaseGrantRefusalAuditService.class, com.cretas.aims.controller.AiPurchaseTaskGrantController.class,
        com.cretas.aims.service.impl.PreviewTokenServiceImpl.class,
        PurchaseGrantTimerPostgresLifecycleContractTest.JsonConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PurchaseGrantTimerPostgresLifecycleContractTest {
    @Autowired com.cretas.aims.controller.AiPurchaseTaskGrantController actualGrantController;
    private static final String FACTORY = "F-TIMER";
    private static final long USER = 94243L;
    private static final long OTHER_USER = 94244L;
    @org.springframework.boot.test.context.TestConfiguration
    static class JsonConfiguration {
        @org.springframework.context.annotation.Bean
        ObjectMapper deferredJson() { return new ObjectMapper().findAndRegisterModules(); }
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            if (!url.startsWith("jdbc:postgresql://127.0.0.1:") || !url.endsWith("/cretas_workflow_verify_test")) throw new IllegalStateException("unsafe deferred lifecycle database");
            p.add("spring.datasource.url", () -> url); p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            p.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            p.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            p.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            p.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else p.add("spring.datasource.url", () -> "jdbc:h2:mem:purchase_timer_lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "create"); p.add("spring.flyway.enabled", () -> "false");
    }
    @Autowired ControlledPurchaseTaskExecutionService service;
    @Autowired PurchaseGrantCallerService callers;
    @Autowired com.cretas.aims.ai.tool.impl.purchase.PurchaseGrantExecuteTool callerTool;
    @MockBean com.cretas.aims.ai.client.DashScopeClient callerLlm;
    @MockBean com.cretas.aims.service.execution.ToolPermissionChain callerPermissionChain;
    @Autowired PurchaseTaskGrantService grantService;
    @Autowired PreviewTokenService previewTokens;
    @SpyBean PreviewTokenService previewTokenServiceSpy;
    @Autowired IntentPreviewTokenRepository tokens;
    @Autowired AiPurchaseTaskGrantRepository grants;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PurchaseOrderRepository orders;
    @Autowired PurchaseOrderItemRepository orderItems;
    @Autowired PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;
    private PurchaseService boundarySpy;
    @MockBean ToolPrincipalPolicy policy;
    @MockBean AuthenticatedToolPrincipalFactory principalFactory;
    @MockBean RawMaterialTypeRepository materials;
    @MockBean SupplierRepository suppliers;
    @MockBean SupplierMaterialRepository supplierMaterials;
    @MockBean PurchaseService purchaseService;
    @MockBean com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository frontline;
    @MockBean com.cretas.aims.ai.tool.ToolRegistry toolRegistry;

    @Autowired PurchaseGrantTimerExecutionService timers;
    @SpyBean com.cretas.aims.repository.ai.PurchaseGrantTimerBindingRepository timerBindings;
    @Autowired com.cretas.aims.repository.cron.ScheduledTaskRepository timerTasks;
    @Autowired com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository timerRunLogs;
    @MockBean com.cretas.aims.service.cron.DynamicScheduler dynamicScheduler;

    @BeforeEach void fixture() {
        tx = new TransactionTemplate(transactionManager);
        ReflectionTestUtils.setField(grantService, "purchaseService", purchaseService);
        boundarySpy = null;
        tx.executeWithoutResult(status -> {
        // Test-only prerequisite fixture: native scalar insert intentionally omits
        // Factory.inference_data.  PG17 maps that column to jsonb while the entity
        // exposes String, so Hibernate's INSERT binding fails before this lifecycle
        // reaches the real token/grant path.  Keep the actual entity read below.
        jdbc.update("INSERT INTO factories(id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) VALUES(?,?,?,0,TRUE,FALSE,20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                FACTORY, "deferred", com.cretas.aims.entity.enums.FactoryType.FACTORY.name());
        em.clear();
        var persistedFactory = em.find(com.cretas.aims.entity.Factory.class, FACTORY);
        assertThat(persistedFactory).isNotNull();
        assertThat(persistedFactory.getName()).isEqualTo("deferred");
        assertThat(persistedFactory.getType()).isEqualTo(com.cretas.aims.entity.enums.FactoryType.FACTORY);
        assertThat(persistedFactory.getIsActive()).isTrue();
        assertThat(persistedFactory.getLevel()).isZero();
        assertThat(persistedFactory.getAiWeeklyQuota()).isEqualTo(20);
        // Stable actor IDs are native test fixtures, not detached IDENTITY entities.
        jdbc.update("INSERT INTO users(id,factory_id,username,password_hash,is_active,created_at,updated_at) VALUES(?,?,?,'test',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", USER, FACTORY, "deferred");
        jdbc.update("INSERT INTO users(id,factory_id,username,password_hash,is_active,created_at,updated_at) VALUES(?,?,?,'test',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", OTHER_USER, FACTORY, "other");
        var actor = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, "purchaser");
        var http = new MockHttpServletRequest(); http.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor); RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        var principal = new ExecutionPrincipal(FACTORY, "FACTORY", Long.toString(USER), PrincipalType.USER, Set.of("purchaser"), Set.of("procurement:read_write"), Set.of());
        when(principalFactory.create(FACTORY, USER, "purchaser")).thenReturn(principal);
        when(policy.rehydrate(principal)).thenReturn(Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(principal, Map.of("canViewPurchasePrices", true))));
        var otherPrincipal = new ExecutionPrincipal(FACTORY, "FACTORY", Long.toString(OTHER_USER), PrincipalType.USER, Set.of("purchaser"), Set.of("procurement:read_write"), Set.of());
        when(principalFactory.create(FACTORY, OTHER_USER, "purchaser")).thenReturn(otherPrincipal);
        when(policy.rehydrate(otherPrincipal)).thenReturn(Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(otherPrincipal, Map.of("canViewPurchasePrices", true))));
        when(toolRegistry.isToolEnabledForFactory(FACTORY, "purchase_order_create")).thenReturn(true);
        when(toolRegistry.isToolEnabledForFactory(FACTORY, "purchase_grant_execute")).thenReturn(true);
        when(toolRegistry.getExecutor("purchase_grant_execute")).thenReturn(Optional.of(callerTool));
        when(toolRegistry.hasExecutor("purchase_grant_execute")).thenReturn(true);
        when(callerPermissionChain.denyReason(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        var supplier = new com.cretas.aims.entity.Supplier(); supplier.setId("SUP"); supplier.setFactoryId(FACTORY); supplier.setCode("SUP"); supplier.setSupplierCode("SUP"); supplier.setName("deferred supplier"); supplier.setIsActive(true); supplier.setCreatedBy(USER); em.persist(supplier);
        var material = new com.cretas.aims.entity.RawMaterialType(); material.setId("MAT"); material.setFactoryId(FACTORY); material.setCode("MAT"); material.setName("deferred material"); material.setUnit("kg"); material.setIsActive(true); material.setCreatedBy(USER); em.persist(material);
        when(suppliers.findByIdAndFactoryId("SUP", FACTORY)).thenAnswer(inv -> Optional.ofNullable(em.find(com.cretas.aims.entity.Supplier.class, "SUP")));
        when(materials.findByIdAndFactoryId("MAT", FACTORY)).thenAnswer(inv -> Optional.ofNullable(em.find(com.cretas.aims.entity.RawMaterialType.class, "MAT")));
        when(materials.findById("MAT")).thenAnswer(inv -> Optional.ofNullable(em.find(com.cretas.aims.entity.RawMaterialType.class, "MAT")));
        var relation = new com.cretas.aims.entity.SupplierMaterial(); relation.setId("REL"); relation.setFactoryId(FACTORY); relation.setSupplierId("SUP"); relation.setMaterialTypeId("MAT"); relation.setPurchaseUnit("kg"); relation.setActive(true);
        when(supplierMaterials.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(FACTORY, "SUP", "MAT")).thenReturn(true);
        when(supplierMaterials.findByFactoryIdAndSupplierIdAndMaterialTypeId(FACTORY, "SUP", "MAT")).thenReturn(Optional.of(relation));
        jdbc.update("UPDATE users SET role_code='purchaser' WHERE factory_id=?", FACTORY);
        em.flush();
        });
    }
    @AfterEach void clear() {
        RequestContextHolder.resetRequestAttributes();
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM purchase_grant_timer_bindings WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM scheduled_task_run_logs WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM scheduled_tasks WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM ai_audit_logs WHERE factory_id=? AND question_type='grant_refusal'", FACTORY);
            jdbc.update("DELETE FROM ai_purchase_task_grants WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM intent_preview_tokens WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM purchase_order_items WHERE purchase_order_id IN (SELECT id FROM purchase_orders WHERE factory_id=?)", FACTORY);
            jdbc.update("DELETE FROM purchase_orders WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM raw_material_types WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM suppliers WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM users WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM factories WHERE id=?", FACTORY);
        });
    }


    private PurchaseGrantTimerExecutionService.View bind(int ttl, int delay) {
        replacePurchaseBoundaryWithRealService();
        var token=authorize(ttl);
        return timers.bind(FACTORY,"preview-"+token.getId(),1L,java.time.LocalDateTime.now().plusSeconds(delay));
    }
    private PurchaseGrantTimerExecutionService.View fire(PurchaseGrantTimerExecutionService.View b) {
        RequestContextHolder.resetRequestAttributes();
        var task=timerTasks.findById(b.taskId()).orElseThrow();
        return timers.fire(b.taskId(),FACTORY,task.getTaskCode());
    }
    private void due(PurchaseGrantTimerExecutionService.View b) throws Exception {
        while(java.time.LocalDateTime.now().isBefore(b.executeAt())) Thread.sleep(40);
    }
    @Test void serverBindingComesFromRealConfirmationAndRejectsRepeatVersionTenantAndTimeReplacement() {
        var b=bind(300,4);
        var row=timerBindings.findById(b.bindingId()).orElseThrow();
        var g=grants.findById(b.grantId()).orElseThrow();
        assertThat(row.getInputDigest()).isEqualTo(g.getInputDigest());
        assertThat(row.getInputSnapshot()).isEqualTo(g.getApprovedInputSnapshot());
        assertThat(row.getUserId()).isEqualTo(USER);
        assertThat(row.getFactoryId()).isEqualTo(FACTORY);
        assertThat(row.getExpiresAt()).isEqualTo(g.getExpiresAt());
        assertThat(row.getExpectedGrantVersion()).isEqualTo(g.getVersion());
        assertThat(timerTasks.findById(b.taskId())).get().extracting(com.cretas.aims.entity.cron.ScheduledTask::getHandlerBeanName)
            .isEqualTo(PurchaseGrantTimerExecutionService.HANDLER);
        assertThatThrownBy(()->timers.bind(FACTORY,b.grantId(),1L,b.executeAt().plusSeconds(1))).isInstanceOf(Throwable.class);
        assertThatThrownBy(()->timers.bind("FOREIGN",b.grantId(),1L,b.executeAt())).isInstanceOf(Throwable.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Long.class)).isZero();
    }
    @Test void scheduledExecutionRehydratesWithoutRequestCreatesExactlyOneDraftAndReplaysOnlyDurableResult() throws Exception {
        var b=bind(300,3); due(b);
        var first=fire(b);
        assertThat(first.state()).isEqualTo("SUCCEEDED");
        assertThat(first.purchaseOrderId()).isNotBlank();
        assertThat(fire(b).purchaseOrderId()).isEqualTo(first.purchaseOrderId());
        timers.recoverAfterRestart();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE factory_id=?",Long.class,FACTORY)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE factory_id<>?",Long.class,FACTORY)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items",Long.class)).isEqualTo(1);
        var grant=grants.findById(b.grantId()).orElseThrow();
        assertThat(grant.getConsumedRequestKey()).isEqualTo("timer:"+b.bindingId());
        assertThat(grant.getResultPurchaseOrderId()).isEqualTo(first.purchaseOrderId());
        assertThat(grant.getVersion()).isEqualTo(2);
        assertThat(timerTasks.findById(b.taskId()).orElseThrow().getEnabled()).isFalse();
    }
    @Test void actualCanvasThreadPoolCronCommitsRealOrderAndRunLogWithoutManualInvocation() throws Exception {
        var b=bind(300,4);
        RequestContextHolder.resetRequestAttributes();
        var app=mock(org.springframework.context.ApplicationContext.class);
        var locks=mock(net.javacrumbs.shedlock.core.LockProvider.class);
        var handler=new com.cretas.aims.service.cron.handler.PurchaseGrantTimerTaskHandler(timers);
        when(app.containsBean(PurchaseGrantTimerExecutionService.HANDLER)).thenReturn(true);
        when(app.getBean(PurchaseGrantTimerExecutionService.HANDLER)).thenReturn(handler);
        when(locks.lock(any())).thenReturn(Optional.of(mock(net.javacrumbs.shedlock.core.SimpleLock.class)));
        var scheduler=new com.cretas.aims.service.cron.DynamicScheduler(timerTasks, timerRunLogs,app,locks);
        try {
            scheduler.configureTasks(new org.springframework.scheduling.config.ScheduledTaskRegistrar());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
            while(System.nanoTime()<deadline && !"SUCCEEDED".equals(timerBindings.findById(b.bindingId()).orElseThrow().getState())) Thread.sleep(80);
            assertThat(timerBindings.findById(b.bindingId()).orElseThrow().getState()).isEqualTo("SUCCEEDED");
            while(System.nanoTime()<deadline && jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_task_run_logs WHERE task_id=? AND status='SUCCESS'",Long.class,b.taskId())==0) Thread.sleep(40);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_task_run_logs WHERE task_id=? AND status='SUCCESS'",Long.class,b.taskId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE factory_id=?",Long.class,FACTORY)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items",Long.class)).isEqualTo(1);
        } finally {((org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler)ReflectionTestUtils.getField(scheduler,"taskScheduler")).shutdown();}
    }
    @Test void revokeWinningBeforeFireLeavesNoDraftAndIndependentRefusal() throws Exception {
        var b=bind(300,3); service.revoke(FACTORY,b.grantId(),1L,"test domain revoke"); due(b);
        assertThat(fire(b).state()).isEqualTo("REFUSED"); noBusinessAndAudit();
    }
    @Test void boundGrantRejectsExistingManualHttpToolAndSkillThenTimerStillCommitsOnce() throws Exception {
        var b=bind(300,8);
        assertThatThrownBy(()->actualGrantController.executeLater(FACTORY,b.grantId(),
            new com.cretas.aims.controller.AiPurchaseTaskGrantController.ExecuteLaterRequest(1L))).isInstanceOf(Throwable.class);
        for(var channel:PurchaseGrantCallerService.Channel.values()) {
            assertThatThrownBy(()->callers.executeGrant(FACTORY,b.grantId(),1L,channel)).isInstanceOf(Throwable.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Long.class)).isZero();
        assertThat(grants.findById(b.grantId()).orElseThrow().getVersion()).isEqualTo(1);
        due(b);assertThat(fire(b).state()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE factory_id=?",Long.class,FACTORY)).isEqualTo(1);
    }

    @Test void staleRecoveryScanCannotOverwriteAnotherTransactionsTerminalSuccess() throws Exception {
        var b=bind(300,3);due(b);
        var scanned=new CountDownLatch(1);var terminalCommitted=new CountDownLatch(1);
        doAnswer(inv->{
            // Use real repository query first, leaving the PENDING entity managed in recovery's EM.
            // A derived Spring Data interface method has no Java "real method" for Mockito.
            // Delegate to another actual Repository proxy on this same transaction-bound EM,
            // so the PENDING rows remain real managed entities, not hand-crafted snapshots.
            var realRepository=new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em)
                    .getRepository(com.cretas.aims.repository.ai.PurchaseGrantTimerBindingRepository.class);
            var rows=realRepository.findByStateIn(inv.getArgument(0));scanned.countDown();
            assertThat(terminalCommitted.await(10,TimeUnit.SECONDS)).isTrue();return rows;
        }).when(timerBindings).findByStateIn(anyCollection());
        var pool=Executors.newSingleThreadExecutor();
        try {
            var recovery=pool.submit(()->timers.recoverAfterRestart());
            assertThat(scanned.await(5,TimeUnit.SECONDS)).isTrue();
            var outcome=fire(b);assertThat(outcome.state()).isEqualTo("SUCCEEDED");
            terminalCommitted.countDown();recovery.get(10,TimeUnit.SECONDS);
            assertThat(timerBindings.findById(b.bindingId()).orElseThrow().getState()).isEqualTo("SUCCEEDED");
            assertThat(timerBindings.findById(b.bindingId()).orElseThrow().getResultPurchaseOrderId()).isEqualTo(outcome.purchaseOrderId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE factory_id=?",Long.class,FACTORY)).isEqualTo(1);
        } finally {terminalCommitted.countDown();pool.shutdownNow();}
    }

    @Test void explicitMissedPendingRecoveryClosesWithoutCatchUpPurchaseAndLeavesAudit() throws Exception {
        var b=bind(300,3);
        while(java.time.LocalDateTime.now().isBefore(b.executeAt().plusSeconds(6))) Thread.sleep(40);
        assertThat(timers.reconcileStatus(FACTORY,b.grantId()).state()).isEqualTo("REFUSED");
        assertThat(fire(b).state()).isEqualTo("REFUSED");noBusinessAndAudit();
        assertThat(timerTasks.findById(b.taskId()).orElseThrow().getEnabled()).isFalse();
    }
    @Test void naturalExpiryBeforeLateCallbackIsRefusedWithoutClockMutation() throws Exception {
        var b=bind(6,3);
        while(java.time.LocalDateTime.now().isBefore(b.expiresAt())) Thread.sleep(50);
        assertThat(fire(b).state()).isEqualTo("REFUSED"); noBusinessAndAudit();
    }
    @Test void actualCurrentUserInactiveFailsClosed() throws Exception {
        var b=bind(300,3);
        jdbc.update("UPDATE users SET is_active=FALSE WHERE id=? AND factory_id=?",USER,FACTORY);due(b);
        assertThat(fire(b).state()).isEqualTo("REFUSED"); noBusinessAndAudit();
    }
    @Test void changedCurrentPermissionBeforeCommitRollsBackRealDraftAndConsumption() throws Exception {
        var b=bind(300,3);
        replacePurchaseBoundaryWithRealService(()->when(policy.rehydrate(any())).thenReturn(Optional.empty()));
        due(b); assertThat(fire(b).state()).isEqualTo("REFUSED"); noBusinessAndAudit();
        assertThat(grants.findById(b.grantId()).orElseThrow().isConsumed()).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"cron","disabled","tenant","handler"})
    void taskConfigurationMutationAndDisabledTaskAreRefused(String mutation) throws Exception {
        var b=bind(300,3);
        tx.executeWithoutResult(s->{var task=timerTasks.findById(b.taskId()).orElseThrow();
            switch(mutation){case "cron"->task.setCronExpression("0 0 0 1 1 *");case "disabled"->task.setEnabled(false);case "tenant"->task.setFactoryId("FOREIGN");case "handler"->task.setHandlerBeanName("otherHandler");default->throw new AssertionError();}
            timerTasks.saveAndFlush(task);});
        due(b); assertThat(fire(b).state()).isEqualTo("REFUSED");noBusinessAndAudit();
        if("tenant".equals(mutation)) jdbc.update("UPDATE scheduled_tasks SET factory_id=? WHERE id=?",FACTORY,b.taskId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"quantity","object","action","digest","snapshot"})
    void persistedGrantBusinessReplacementIsRefusedBeforeEffect(String mutation) throws Exception {
        var b=bind(300,3);
        switch(mutation){
            case "quantity"->jdbc.update("UPDATE ai_purchase_task_grants SET quantity_cap=3 WHERE id=?",b.grantId());
            case "object"->jdbc.update("UPDATE ai_purchase_task_grants SET supplier_id='OTHER' WHERE id=?",b.grantId());
            case "action"->jdbc.update("UPDATE ai_purchase_task_grants SET tool_name='purchase_order_submit' WHERE id=?",b.grantId());
            case "digest"->jdbc.update("UPDATE ai_purchase_task_grants SET input_digest='changed' WHERE id=?",b.grantId());
            case "snapshot"->jdbc.update("UPDATE ai_purchase_task_grants SET approved_input_snapshot='changed' WHERE id=?",b.grantId());
            default->throw new AssertionError();
        }
        due(b);assertThat(fire(b).state()).isEqualTo("REFUSED");noBusinessAndAudit();
    }
    @Test void unknownNeverRetriesAndRestartOnlyReadsOriginalEvidence() throws Exception {
        var b=bind(300,3);
        doThrow(new IllegalStateException("test connection unavailable before effect")).when(boundarySpy).createPurchaseOrder(eq(FACTORY),any(),eq(USER));
        due(b); assertThat(fire(b).state()).isEqualTo("UNKNOWN");
        assertThat(fire(b).state()).isEqualTo("UNKNOWN");timers.recoverAfterRestart();
        verify(boundarySpy,times(1)).createPurchaseOrder(eq(FACTORY),any(),eq(USER));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_logs WHERE factory_id=? AND question_type='grant_refusal'",Long.class,FACTORY)).isZero();
    }
    @Test void requestOnlyMethodDoesNotAcceptTimerOrAsyncIdentity() {
        var b=bind(300,3);RequestContextHolder.resetRequestAttributes();
        try(var ignored=AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,USER,FACTORY,"purchaser"))) {
            assertThatThrownBy(()->service.executeLater(FACTORY,b.grantId(),1L)).isInstanceOf(Throwable.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Long.class)).isZero();
    }
    @Test void statusReadHasNoDatabaseMutationAndExplicitUnknownReconciliationNeverExecutes() {
        var b=bind(300,3);
        jdbc.update("UPDATE purchase_grant_timer_bindings SET state='UNKNOWN' WHERE id=? AND factory_id=?",b.bindingId(),FACTORY);
        var bindingBefore=jdbc.queryForList("SELECT * FROM purchase_grant_timer_bindings WHERE id=?",b.bindingId());
        var taskBefore=jdbc.queryForList("SELECT * FROM scheduled_tasks WHERE id=?",b.taskId());
        assertThat(timers.status(FACTORY,b.grantId()).state()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForList("SELECT * FROM purchase_grant_timer_bindings WHERE id=?",b.bindingId())).isEqualTo(bindingBefore);
        assertThat(jdbc.queryForList("SELECT * FROM scheduled_tasks WHERE id=?",b.taskId())).isEqualTo(taskBefore);
        assertThat(timers.reconcileStatus(FACTORY,b.grantId()).state()).isEqualTo("UNKNOWN");
        verify(boundarySpy,never()).createPurchaseOrder(any(),any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Long.class)).isZero();
    }
    private void noBusinessAndAudit(){
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_logs WHERE factory_id=? AND user_id=? AND question_type='grant_refusal' AND is_success=FALSE",Long.class,FACTORY,USER)).isEqualTo(1);
    }
    private void replacePurchaseBoundaryWithRealService() {
        replacePurchaseBoundaryWithRealService(() -> { });
    }

    private void replacePurchaseBoundaryWithRealService(Runnable afterWrite) {
        var lookup = materials;
        var real = new PurchaseServiceImpl(orders, orderItems, null, suppliers, lookup,
                null, null, null, null, null);
        ReflectionTestUtils.setField(real, "supplierMaterialRepository", supplierMaterials);
        boundarySpy = spy(real);
        doAnswer(invocation -> {
            var result = (com.cretas.aims.entity.inventory.PurchaseOrder) invocation.callRealMethod();
            em.flush();
            afterWrite.run();
            return result;
        }).when(boundarySpy).createPurchaseOrder(eq(FACTORY), any(), eq(USER));
        ReflectionTestUtils.setField(grantService, "purchaseService", boundarySpy);
    }

    private IntentPreviewToken authorize() { return authorize(300); }
    private IntentPreviewToken authorize(int ttl) {
        var input = new HashMap<String,Object>(Map.of("supplierId", "SUP", "materialTypeId", "MAT", "quantity", "2", "unitPrice", "10", "taxRate", "13", "unit", "kg", "currency", "CNY", "amountCap", "22.60"));
        var canonical = service.previewParameters(FACTORY, input);
        var token = previewTokens.createBoundToken(new PreviewTokenService.BoundTokenRequest(FACTORY, USER, "purchaser", "PURCHASE_ORDER_CREATE", "采购", "purchase_order_create", "2.0.0", ToolExecutionMode.EXECUTE, "PURCHASE_ORDER", "preview", "CREATE", canonical, Map.of(), canonical, ttl));
        assertThat(service.authorizeForLater(FACTORY, new ConfirmationProof(token.getToken(), token.getCommandDigest(), Instant.now().plusSeconds(300))).state()).isEqualTo("PENDING");
        return token;
    }
}
