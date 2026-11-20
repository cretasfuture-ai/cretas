package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1844 复审 M-A 竞态: 「撤销」与「批次挂上这头个体」必须在个体行上串行。
 *
 * <p>没有锁时的失败序列: 撤销方查到 0 个批次引用 → 挂载方插入批次并提交 → 撤销方软删,
 * 结果是批次挂着一个已撤销个体, 溯源图把它过滤掉。两边都对个体行
 * {@code SELECT … FOR UPDATE} 后, 后到的一方必须等先到的一方提交。
 *
 * <p>真 Hibernate/H2, 两个线程、两个真事务(本类不跑在测试事务里, 数据真提交;
 * 每条用例用独立 factoryId, 互不干扰)。⚠️ H2 不是 PG: 这里证明的是「锁在两条路径上都发出、
 * 后到方确实被阻塞并看到先到方的结果」; PG READ COMMITTED 下重判 {@code deleted_at IS NULL}
 * 的语义另由 PG 本身保证。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SourceIndividualRevokeLockContractTest {

    /** 先到方拿到锁后再持有这么久才提交; 后到方被阻塞的时长必须接近它。 */
    private static final long HOLD_MS = 600;

    @Autowired SourceIndividualRepository repository;
    @Autowired MaterialBatchRepository materialBatchRepository;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager entityManager;
    private SourceIndividualService service;
    private TransactionTemplate tx;
    /** 批次 created_by 有到 users 的 FK —— 每条用例自建并提交工厂+用户, ⛔ 不依赖别的测试类留在共享 H2 里的行。 */
    private Long userId;

    @BeforeEach
    void setUp() {
        service = new SourceIndividualService(repository, org.mockito.Mockito.mock(OperationLogRepository.class));
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        tx = new TransactionTemplate(txManager);
    }

    private String newIndividual(String factory) {
        userId = tx.execute(s -> SourceIndividualTestUsers.persistUser(entityManager, factory));
        assertThat(userId).isNotNull();
        return tx.execute(s -> service.create(factory,
                CreateSourceIndividualRequest.builder().individualCode("LK-" + UUID.randomUUID()).build()).getId());
    }

    private MaterialBatch batchFor(String factory, String individualId) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId("MB-LK-" + UUID.randomUUID());
        mb.setFactoryId(factory);
        mb.setBatchNumber("MB-LK-" + System.nanoTime());
        mb.setReceiptDate(LocalDate.of(2026, 11, 18));
        mb.setWarehouseId("WH-1");
        mb.setReceiptQuantity(new BigDecimal("10"));
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setCreatedBy(userId);
        mb.setSourceIndividualId(individualId);
        mb.setCustomFields(null);
        return mb;
    }

    @Test
    @DisplayName("挂载方先锁: 撤销被阻塞到批次提交, 随后看到这个批次并 409; 个体仍在")
    void revokeWaitsForLinkAndThenSeesTheBatch() throws Exception {
        String factory = "F-LK-A-" + UUID.randomUUID().toString().substring(0, 8);
        String id = newIndividual(factory);
        CountDownLatch linked = new CountDownLatch(1);

        CompletableFuture<Void> linker = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            service.requireLinkable(factory, id);          // 拿到个体行锁
            linked.countDown();
            sleep(HOLD_MS);
            materialBatchRepository.saveAndFlush(batchFor(factory, id));
        }));
        assertThat(linked.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> revokeError = new AtomicReference<>();
        long t0 = System.nanoTime();
        try {
            tx.executeWithoutResult(s -> service.revoke(factory, id, "录入错误", "1"));
        } catch (Throwable t) {
            revokeError.set(t);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        linker.get(20, TimeUnit.SECONDS);

        assertThat(revokeError.get())
                .as("撤销必须因为刚提交的批次而 409 (waited=%dms)", waitedMs)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(waitedMs).as("撤销确实被个体行锁阻塞").isGreaterThanOrEqualTo(HOLD_MS / 2);
        assertThat(repository.findByIdAndFactoryId(id, factory)).isPresent();
        assertThat(materialBatchRepository.findByFactoryIdAndSourceIndividualIdOrderByReceiptDateDesc(factory, id))
                .hasSize(1);
    }

    @Test
    @DisplayName("撤销方先锁: 挂载被阻塞到撤销提交, 随后被拒(409), 不会挂上已撤销个体")
    void linkWaitsForRevokeAndThenIsRejected() throws Exception {
        String factory = "F-LK-B-" + UUID.randomUUID().toString().substring(0, 8);
        String id = newIndividual(factory);
        CountDownLatch revoked = new CountDownLatch(1);

        CompletableFuture<Void> revoker = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            service.revoke(factory, id, "录入错误", "1");   // 锁 + 写撤销, 尚未提交
            revoked.countDown();
            sleep(HOLD_MS);
        }));
        assertThat(revoked.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> linkError = new AtomicReference<>();
        long t0 = System.nanoTime();
        try {
            tx.executeWithoutResult(s -> {
                service.requireLinkable(factory, id);
                materialBatchRepository.saveAndFlush(batchFor(factory, id));
            });
        } catch (Throwable t) {
            linkError.set(t);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        revoker.get(20, TimeUnit.SECONDS);

        assertThat(linkError.get())
                .as("挂载必须在撤销提交后被拒 (waited=%dms)", waitedMs)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(waitedMs).as("挂载确实被个体行锁阻塞").isGreaterThanOrEqualTo(HOLD_MS / 2);
        assertThat(materialBatchRepository.findByFactoryIdAndSourceIndividualIdOrderByReceiptDateDesc(factory, id))
                .isEmpty();
        assertThat(repository.findByIdAndFactoryId(id, factory)).isEmpty();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
