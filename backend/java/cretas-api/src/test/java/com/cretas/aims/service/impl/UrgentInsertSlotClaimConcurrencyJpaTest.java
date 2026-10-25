package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.ConfirmUrgentInsertRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.PushNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Urgent-insert confirm against a real database row (H2, PostgreSQL mode) and real
 * transactions: the slot is the only thing that serializes two confirmations, so it has
 * to be a real table, not a mock.
 *
 * <p>The production plan repository is a counting mock on purpose: production_plans
 * carries FKs to factories/product_types/users, and what this test guards is "how many
 * plans did the service try to create", which the save count answers exactly.
 *
 * <p>To make the race real, plan save (inside the confirm transaction) waits on a
 * barrier so both transactions are open at the same time before either commits.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class UrgentInsertSlotClaimConcurrencyJpaTest {

    @Autowired InsertSlotRepository insertSlotRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<String> createdSlotIds = new ArrayList<>();
    private final AtomicInteger planSaves = new AtomicInteger();
    private final List<ProductionPlan> savedPlans = new CopyOnWriteArrayList<>();
    private PushNotificationService push;
    /** 释放时段时「本厂有没有从这个时刻开始的未取消计划」的答案 */
    private volatile boolean planOccupiesSlot;

    @AfterEach
    void cleanup() {
        for (String id : createdSlotIds) {
            jdbcTemplate.update("DELETE FROM insert_slots WHERE id = ?", id);
        }
    }

    /**
     * @param insideTx barrier awaited inside plan save (i.e. inside the confirm transaction,
     *                 after the slot claim) so both transactions are open at once; null = no wait.
     */
    private UrgentInsertServiceImpl service(CyclicBarrier insideTx) {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        when(planRepo.save(any(ProductionPlan.class))).thenAnswer(inv -> {
            planSaves.incrementAndGet();
            savedPlans.add(inv.getArgument(0));
            if (insideTx != null) {
                try {
                    // shorter than H2's row-lock timeout, so the loser waits out the winner
                    // and then sees 0 rows (the PostgreSQL READ COMMITTED behaviour) instead of timing out
                    insideTx.await(500, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // the loser is blocked on the row lock (or already failed) - that is the point
                }
            }
            return inv.getArgument(0);
        });
        when(planRepo.existsByFactoryIdAndStartTimeAndStatusNot(any(), any(), any()))
                .thenAnswer(inv -> planOccupiesSlot);
        push = mock(PushNotificationService.class);
        return UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.of(
                InsertSlotRepository.class, insertSlotRepository,
                ProductionPlanRepository.class, planRepo,
                ObjectMapper.class, new ObjectMapper(),
                PushNotificationService.class, push));
    }

    private String insertCommittedSlot(String factoryId) {
        String id = UUID.randomUUID().toString();
        createdSlotIds.add(id);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            LocalDateTime start = LocalDateTime.now().plusHours(2).withNano(0);
            insertSlotRepository.saveAndFlush(InsertSlot.builder()
                    .id(id).factoryId(factoryId)
                    .productionLineId("default").productionLineName("动态分配")
                    .startTime(start).endTime(start.plusHours(2))
                    .availableCapacity(new BigDecimal("200")).impactLevel("none")
                    .requiredWorkers(4).availableWorkers(4).switchCostMinutes(0).recommendScore(80)
                    .status("available")
                    .build());
        });
        return id;
    }

    private String statusInDb(String id) {
        return jdbcTemplate.queryForObject("SELECT status FROM insert_slots WHERE id = ?", String.class, id);
    }

    private static ConfirmUrgentInsertRequest request(String slotId) {
        ConfirmUrgentInsertRequest r = new ConfirmUrgentInsertRequest();
        r.setSlotId(slotId);
        r.setProductTypeId("PT-UI-TEST");
        r.setPlannedQuantity(new BigDecimal("50"));
        r.setPriority(9);
        r.setForceInsert(false);
        return r;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoConcurrentConfirmsOnOneSlotYieldAtMostOnePlan() throws Exception {
        String factoryId = "F-UI-CC-" + UUID.randomUUID().toString().substring(0, 8);
        String slotId = insertCommittedSlot(factoryId);

        UrgentInsertServiceImpl service = service(new CyclicBarrier(2));

        CyclicBarrier start = new CyclicBarrier(2);
        List<String> outcomes = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                                service.confirmInsert(factoryId, 1L, request(slotId)));
                        outcomes.add("OK");
                    } catch (BusinessException e) {
                        outcomes.add("BUSINESS_" + e.getCode());
                    } catch (Exception e) {
                        outcomes.add("ERR_" + e.getClass().getSimpleName());
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long ok = outcomes.stream().filter("OK"::equals).count();
        System.out.println("URGENT_INSERT_CONCURRENT_OUTCOMES=" + outcomes + " planSaves=" + planSaves.get()
                + " finalStatus=" + statusInDb(slotId));
        assertThat(outcomes).hasSize(2);
        assertThat(ok).as("exactly one concurrent confirm may succeed: %s", outcomes).isEqualTo(1);
        assertThat(planSaves.get()).as("only one production plan may be saved").isEqualTo(1);
        assertThat(statusInDb(slotId)).isEqualTo("selected");

        // 同一请求重复提交: 不能生成第二份计划
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.confirmInsert(factoryId, 1L, request(slotId))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        assertThat(planSaves.get()).as("a repeated submit must not save a second plan").isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmOrForceWithAnotherFactorysSlotIs404AndChangesNothing() {
        String ownerFactory = "F-UI-OWN-" + UUID.randomUUID().toString().substring(0, 8);
        String slotId = insertCommittedSlot(ownerFactory);
        UrgentInsertServiceImpl service = service(null);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.confirmInsert("F-UI-OTHER", 1L, request(slotId))))
                .isInstanceOf(EntityNotFoundException.class);
        ConfirmUrgentInsertRequest force = request(slotId);
        force.setForceInsert(true);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.forceInsert("F-UI-OTHER", 1L, force)))
                .isInstanceOf(EntityNotFoundException.class);

        System.out.println("URGENT_INSERT_CROSS_FACTORY status=" + statusInDb(slotId) + " planSaves=" + planSaves.get());
        assertThat(statusInDb(slotId)).as("other factory must not flip the slot").isEqualTo("available");
        assertThat(planSaves.get()).as("no plan may be created for another factory's slot").isZero();
    }

    /**
     * 计划 id 是 UUID。原来推送前做 Long.valueOf(uuid) 恒抛异常, 被 catch 吞掉 ⇒ 插单通知从没发出去过。
     * 判据: 确认插单之后, 推送真的被调用, 且带的就是那份计划的 UUID。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmSendsTheUrgentInsertPushWithTheUuidPlanId() {
        String factoryId = "F-UI-PUSH-" + UUID.randomUUID().toString().substring(0, 8);
        String slotId = insertCommittedSlot(factoryId);
        UrgentInsertServiceImpl service = service(null);

        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.confirmInsert(factoryId, 1L, request(slotId)));

        // 一定会发生的读数: 计划确实保存了, 且 id 是 UUID (不是数字)
        assertThat(savedPlans).hasSize(1);
        String planId = savedPlans.get(0).getId();
        assertThat(planId).isNotBlank().doesNotMatch("\\d+");
        verify(push).sendUrgentInsertNotification(eq(1L), eq(planId), anyString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void selectingASlotTwiceIsAConflictNotASecondSuccess() {
        String factoryId = "F-UI-SEL-" + UUID.randomUUID().toString().substring(0, 8);
        String slotId = insertCommittedSlot(factoryId);
        UrgentInsertServiceImpl service = service(null);

        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.markSlotAsSelected(factoryId, slotId));
        assertThat(statusInDb(slotId)).as("一定会发生: 第一次选中生效").isEqualTo("selected");

        // 原来无条件 setStatus("selected"), 第二次照样「成功」
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.markSlotAsSelected(factoryId, slotId)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));

        // 他厂: 404, 不改数据
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.releaseSlot("F-UI-OTHER", slotId)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(statusInDb(slotId)).isEqualTo("selected");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anExpiredSlotCannotBeSelected() {
        String factoryId = "F-UI-EXP-" + UUID.randomUUID().toString().substring(0, 8);
        String slotId = insertCommittedSlot(factoryId);
        jdbcTemplate.update("UPDATE insert_slots SET status = 'expired' WHERE id = ?", slotId);
        UrgentInsertServiceImpl service = service(null);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.markSlotAsSelected(factoryId, slotId)))
                .isInstanceOf(BusinessException.class);
        assertThat(statusInDb(slotId)).as("原来过期时段也会被改成 selected").isEqualTo("expired");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aSlotHoldingAConfirmedPlanCannotBeReleased() {
        String factoryId = "F-UI-REL-" + UUID.randomUUID().toString().substring(0, 8);
        String slotId = insertCommittedSlot(factoryId);
        UrgentInsertServiceImpl service = service(null);
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.confirmInsert(factoryId, 1L, request(slotId)));
        assertThat(statusInDb(slotId)).isEqualTo("selected");

        planOccupiesSlot = true;
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.releaseSlot(factoryId, slotId)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        assertThat(statusInDb(slotId)).as("原来能释放 ⇒ 同一时段可以再排第二份计划").isEqualTo("selected");

        // 阴性对照: 计划取消了 (不再占用) ⇒ 可以释放
        planOccupiesSlot = false;
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                service.releaseSlot(factoryId, slotId));
        assertThat(statusInDb(slotId)).isEqualTo("available");
    }
}
