package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.ConfirmUrgentInsertRequest;
import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.entity.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.scheduling.LineCapacityConfigService;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
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
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Committed PostgreSQL rows and production writers, not mocked lock or save counts. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Import({SchedulingResourceWriteLock.class, LineCapacityConfigService.class, LineCapacityEquipmentCheck.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
class SchedulingResourceConcurrencyPostgresContractTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SchedulingResourceWriteLock locks;
    @Autowired LineCapacityConfigService configuration;
    @Autowired LineCapacityEquipmentCheck checks;
    @Autowired InsertSlotRepository slots;
    @Autowired InsertSlotPreviewRepository previews;
    @Autowired ProductTypeRepository products;
    @Autowired ProductionPlanRepository plans;
    @Autowired EquipmentRepository equipment;
    @Autowired ProductEquipmentRequirementRepository requirements;
    @Autowired ProductionLineCapacityConfigRepository capacities;
    @Autowired JdbcTemplate jdbc;
    private String factory, product, line, slot;
    private Long actor, machine;
    private ConfirmUrgentInsertRequest request;
    private UrgentInsertServiceImpl service;
    private EquipmentServiceImpl equipmentService;

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

    private void tx(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }

    @BeforeEach
    void committedFixture() {
        factory = "APS-" + UUID.randomUUID().toString().substring(0, 8);
        product = UUID.randomUUID().toString(); line = UUID.randomUUID().toString(); slot = UUID.randomUUID().toString();
        LocalDateTime start = LocalDateTime.now().plusDays(2).withNano(0);
        tx(() -> {
            Factory f = new Factory(); f.setId(factory); f.setName(factory); em.persist(f);
            User user = new User(); user.setFactoryId(factory); user.setUsername("aps-" + UUID.randomUUID());
            user.setPasswordHash("unused-test-fixture"); user.setRoleCode("factory_super_admin"); em.persist(user); em.flush(); actor = user.getId();
            ProductType p = new ProductType(); p.setId(product); p.setFactoryId(factory); p.setCode(product); p.setName("count output"); p.setUnit("个"); em.persist(p);
            ProductionLine l = new ProductionLine(); l.setId(line); l.setFactoryId(factory); l.setName("actual line"); em.persist(l);
            FactoryEquipment e = new FactoryEquipment(); e.setFactoryId(factory); e.setCode("E"); e.setEquipmentCode("E"); e.setEquipmentName("machine"); e.setStatus("idle"); e.setCreatedBy(actor); em.persist(e); em.flush(); machine = e.getId();
            ProductEquipmentRequirement requirement = new ProductEquipmentRequirement(); requirement.setFactoryId(factory);
            requirement.setProductTypeId(product); requirement.setEquipmentId(machine); requirement.setSource(ProductionLineCapacityConfig.Source.FACTORY_CONFIG);
            requirement.setActor(new ActorRef(AuthenticatedActorType.FACTORY_USER, actor)); em.persist(requirement);
            InsertSlot storedSlot = slots.saveAndFlush(InsertSlot.builder().id(slot).factoryId(factory).productionLineId(line).productionLineName("actual line")
                    .startTime(start).endTime(start.plusHours(2)).availableCapacity(BigDecimal.ZERO).impactLevel("none")
                    .requiredWorkers(0).availableWorkers(0).switchCostMinutes(0).recommendScore(80).status("available").build());
            em.refresh(storedSlot);
            previews.saveAndFlush(InsertSlotPreview.builder().id(UUID.randomUUID().toString()).factoryId(factory).slotId(slot)
                    .productTypeId(product).previewQuantity(new BigDecimal("17")).quantityUnit("PIECE").plannedUnit("个")
                    .productionLineId(line).windowStart(start).windowEnd(start.plusHours(2)).candidateNumber(1)
                    .slotUpdatedAt(storedSlot.getUpdatedAt()).impactLevel("none").requiresApproval(false).affectedPlanCount(0).build());
        });
        configuration.setCapacity(factory, line, product, new BigDecimal("100"), "PIECE", ProductionLineCapacityConfig.Source.FACTORY_CONFIG,
                new ActorRef(AuthenticatedActorType.FACTORY_USER, actor));
        request = new ConfirmUrgentInsertRequest(); request.setSlotId(slot); request.setProductTypeId(product);
        request.setPlannedQuantity(new BigDecimal("17")); request.setQuantityUnit("PIECE"); request.setUrgentReason("isolated concurrency fixture");
        request.setPreviewId(jdbc.queryForObject("SELECT id FROM insert_slot_previews WHERE factory_id=? AND slot_id=?", String.class, factory, slot));
        ImpactAnalysisService impact = mock(ImpactAnalysisService.class);
        when(impact.calculateChainImpact(eq(factory), any(), any(), eq(product), any())).thenReturn(
                ChainImpactResult.builder().impactLevel("none").requiresApproval(false).totalAffectedPlans(0)
                        .directConflicts(0).cascadeDelays(0).maxDelayMinutes(0).averageDelayMinutes(0.0)
                        .totalDelayMinutes(0).affectedVipCustomers(0).criticalCrPlans(0).exceedingDeadlinePlans(0).impactScore(0).build());
        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.ofEntries(
                Map.entry(InsertSlotRepository.class, slots), Map.entry(InsertSlotPreviewRepository.class, previews),
                Map.entry(ProductTypeRepository.class, products), Map.entry(ProductionPlanRepository.class, plans),
                Map.entry(EquipmentRepository.class, equipment), Map.entry(ProductEquipmentRequirementRepository.class, requirements),
                Map.entry(ProductionLineCapacityConfigRepository.class, capacities),
                Map.entry(LineCapacityEquipmentCheck.class, checks), Map.entry(SchedulingResourceWriteLock.class, locks),
                Map.entry(ImpactAnalysisService.class, impact),
                Map.entry(ObjectMapper.class, new ObjectMapper())));
        equipmentService = UrgentInsertTestSupport.construct(EquipmentServiceImpl.class,
                Map.of(EquipmentRepository.class, equipment, SchedulingResourceWriteLock.class, locks));
    }

    private long planCount() {
        return jdbc.queryForObject("SELECT count(*) FROM production_plans WHERE factory_id=?", Long.class, factory);
    }

    private void confirm(boolean force) {
        tx(() -> { if (force) service.forceInsert(factory, actor, request); else service.confirmInsert(factory, actor, request); });
    }

    private static void await(CountDownLatch signal) {
        try { assertThat(signal.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    /** A real writer commits a new fact while confirm waits on the shared factory row. */
    private void writerWins(Runnable writer, boolean force) throws Exception {
        CountDownLatch changed = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx(() -> { locks.lockFactory(factory); writer.run(); changed.countDown(); await(release); }));
            await(changed);
            Future<?> second = pool.submit(() -> { attempting.countDown(); confirm(force); });
            await(attempting);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); first.get(20, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class);
            assertThat(planCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM insert_slots WHERE id=?", String.class, slot)).isEqualTo("available");
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void committedCapacityReductionIsRecheckedAfterWaiting() throws Exception {
        writerWins(() -> configuration.setCapacity(factory, line, product, BigDecimal.ONE, "PIECE", ProductionLineCapacityConfig.Source.FACTORY_CONFIG,
                new ActorRef(AuthenticatedActorType.FACTORY_USER, actor)), false);
    }

    @Test
    void normalEquipmentStatusWriterCannotRacePastForceConfirmation() throws Exception {
        writerWins(() -> equipmentService.updateEquipmentStatus(factory, machine.toString(), "maintenance"), true);
    }

    @Test
    void rolledBackPlanAndSlotClaimReleaseTheMutexForAValidRetry() {
        assertThatThrownBy(() -> tx(() -> {
            service.confirmInsert(factory, actor, request); em.flush();
            throw new IllegalStateException("rollback owned fixture");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback owned fixture");
        assertThat(planCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM insert_slots WHERE id=?", String.class, slot)).isEqualTo("available");
        confirm(false);
        assertThat(planCount()).isEqualTo(1);
    }

    @Test
    void changedProductUnitInvalidatesBothConfirmPathsWithoutClaiming() {
        tx(() -> {
            ProductType p = products.findByIdAndFactoryIdForUpdate(product, factory).orElseThrow();
            p.setUnit("kg"); products.saveAndFlush(p);
        });
        for (boolean force : new boolean[]{false, true}) {
            assertThatThrownBy(() -> confirm(force)).isInstanceOf(BusinessException.class);
        }
        assertThat(planCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM insert_slots WHERE id=?", String.class, slot)).isEqualTo("available");
    }

    @Test
    void confirmedUnitPersistsAndEquipmentWriterWaitsForCommit() throws Exception {
        CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx(() -> {
                service.confirmInsert(factory, actor, request); em.flush(); staged.countDown(); await(release);
            }));
            await(staged);
            Future<?> second = pool.submit(() -> { attempting.countDown(); tx(() -> equipmentService.updateEquipmentStatus(factory, machine.toString(), "maintenance")); });
            await(attempting);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            assertThat(planCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT planned_unit FROM production_plans WHERE factory_id=?", String.class, factory)).isEqualTo("个");
            assertThat(jdbc.queryForObject("SELECT status FROM factory_equipment WHERE id=?", String.class, machine)).isEqualTo("maintenance");
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void twoConcurrentPreviewsForOnePhysicalSlotCreateExactlyOneCommittedPlan() throws Exception {
        ConfirmUrgentInsertRequest second = new ConfirmUrgentInsertRequest();
        second.setSlotId(slot); second.setProductTypeId(product); second.setPlannedQuantity(new BigDecimal("17"));
        second.setQuantityUnit("PIECE"); second.setUrgentReason("second preview");
        tx(() -> {
            InsertSlot s = slots.findByIdAndFactoryId(slot, factory).orElseThrow();
            String id = UUID.randomUUID().toString(); second.setPreviewId(id);
            previews.saveAndFlush(InsertSlotPreview.builder().id(id).factoryId(factory).slotId(slot).productTypeId(product)
                    .previewQuantity(new BigDecimal("17")).quantityUnit("PIECE").plannedUnit("个").productionLineId(line)
                    .windowStart(s.getStartTime()).windowEnd(s.getEndTime()).candidateNumber(2)
                    .slotUpdatedAt(s.getUpdatedAt()).impactLevel("none").requiresApproval(false).affectedPlanCount(0).build());
        });
        ExecutorService pool = Executors.newFixedThreadPool(2); CyclicBarrier start = new CyclicBarrier(2);
        try {
            Callable<String> one = () -> { start.await(10, TimeUnit.SECONDS); try { confirm(false); return "OK"; } catch (BusinessException e) { return "DENIED"; } };
            Callable<String> two = () -> { start.await(10, TimeUnit.SECONDS); try { tx(() -> service.confirmInsert(factory, actor, second)); return "OK"; } catch (BusinessException e) { return "DENIED"; } };
            Future<String> first = pool.submit(one), other = pool.submit(two);
            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), other.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "DENIED");
            assertThat(planCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM insert_slots WHERE id=?", String.class, slot)).isEqualTo("selected");
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue(); }
    }
}
