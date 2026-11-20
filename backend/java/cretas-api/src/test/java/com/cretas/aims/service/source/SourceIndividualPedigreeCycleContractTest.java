package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.dto.source.UpdateSourceIndividualRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * #1844 终审 L-a: 改个体的父本/母本时, 服务层必须拦住系谱成环(沿父本、母本两条链向上找),
 * 返回 409 {@code PEDIGREE_CYCLE} 和一句说清是哪一头的中文; 两人同时把两头个体设成对方父本时,
 * 后到的一方必须被串行并稳定拿到同一个 409, ⛔ 不是死锁变 500。
 *
 * <p>真 Hibernate/H2, 本类不跑在测试事务里(数据真提交, 并发用例要两个真事务);
 * 每条用例独立 factoryId。PG 上的同一并发场景见 {@code SourceIndividualLockPostgresContractTest}。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SourceIndividualPedigreeCycleContractTest {

    private static final long HOLD_MS = 600;

    @Autowired SourceIndividualRepository repository;
    @Autowired MaterialBatchRepository materialBatchRepository;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager entityManager;
    private SourceIndividualService service;
    private TransactionTemplate tx;
    private String factory;

    @BeforeEach
    void setUp() {
        service = new SourceIndividualService(repository, org.mockito.Mockito.mock(OperationLogRepository.class));
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        tx = new TransactionTemplate(txManager);
        factory = "F-PC-" + UUID.randomUUID().toString().substring(0, 8);
        tx.execute(s -> SourceIndividualTestUsers.persistUser(entityManager, factory));
    }

    private String create(String code, String sire, String dam) {
        return tx.execute(s -> service.create(factory, CreateSourceIndividualRequest.builder()
                .individualCode(code).sireIndividualId(sire).damIndividualId(dam).build()).getId());
    }

    private void setSire(String id, String sire) {
        tx.executeWithoutResult(s -> service.update(factory, id,
                UpdateSourceIndividualRequest.builder().sireIndividualId(sire).build()));
    }

    private void setDam(String id, String dam) {
        tx.executeWithoutResult(s -> service.update(factory, id,
                UpdateSourceIndividualRequest.builder().damIndividualId(dam).build()));
    }

    private String sireOf(String id) {
        return repository.findByIdAndFactoryId(id, factory).orElseThrow().getSireIndividualId();
    }

    private String damOf(String id) {
        return repository.findByIdAndFactoryId(id, factory).orElseThrow().getDamIndividualId();
    }

    @Test
    @DisplayName("直接成环: B 的父本是 A, 再把 A 的父本设成 B → 409 PEDIGREE_CYCLE, A 不被改")
    void directCycleViaSireIsRejected() {
        String a = create("PC-A", null, null);
        String b = create("PC-B", a, null);

        BusinessException e = catchThrowableOfType(() -> setSire(a, b), BusinessException.class);

        assertThat(e).as("必须是业务错误而不是放行").isNotNull();
        assertThat(e.getCode()).isEqualTo(409);
        assertThat(e.getErrorCode()).isEqualTo("PEDIGREE_CYCLE");
        assertThat(e.getMessage()).contains("父本").contains("PC-B").contains("PC-A").contains("成环");
        assertThat(sireOf(a)).isNull();
    }

    @Test
    @DisplayName("隔代且经母本链成环: B.母本=A, C.父本=B, 再把 A 的母本设成 C → 409, 指名母本")
    void indirectCycleThroughDamAndSireIsRejected() {
        String a = create("PC-A", null, null);
        String b = create("PC-B", null, a);
        String c = create("PC-C", b, null);

        BusinessException e = catchThrowableOfType(() -> setDam(a, c), BusinessException.class);

        assertThat(e).isNotNull();
        assertThat(e.getCode()).isEqualTo(409);
        assertThat(e.getErrorCode()).isEqualTo("PEDIGREE_CYCLE");
        assertThat(e.getMessage()).startsWith("母本「PC-C」");
        assertThat(damOf(a)).isNull();
    }

    @Test
    @DisplayName("阳性对照: 不成环的系谱照常保存(含共同祖先的菱形系谱), 清空父本也照常")
    void nonCyclicPedigreeStillSaves() {
        String g = create("PC-G", null, null);
        String p = create("PC-P", g, null);
        String m = create("PC-M", g, null);
        String x = create("PC-X", null, null);

        setSire(x, p);
        setDam(x, m);
        assertThat(sireOf(x)).isEqualTo(p);
        assertThat(damOf(x)).isEqualTo(m);

        setSire(x, "");
        assertThat(sireOf(x)).isNull();
    }

    @Test
    @DisplayName("并发互设父本: 后到的一方被串行, 看到先到方刚提交的边, 稳定 409 PEDIGREE_CYCLE")
    void concurrentMutualSireIsSerialisedIntoABusinessError() throws Exception {
        String x = create("PC-X", null, null);
        String a = create("PC-A", null, null);
        CountDownLatch firstLocked = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            service.update(factory, x, UpdateSourceIndividualRequest.builder().sireIndividualId(a).build());
            firstLocked.countDown();
            sleep(HOLD_MS);
        }));
        assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Throwable> secondError = new AtomicReference<>();
        long t0 = System.nanoTime();
        try {
            setSire(a, x);
        } catch (Throwable t) {
            secondError.set(t);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        first.get(20, TimeUnit.SECONDS);

        assertThat(secondError.get())
                .as("后到方必须拿到成环的业务错误 (waited=%dms)", waitedMs)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("PEDIGREE_CYCLE");
                });
        assertThat(waitedMs).as("后到方确实被行锁阻塞").isGreaterThanOrEqualTo(HOLD_MS / 2);
        assertThat(sireOf(x)).isEqualTo(a);
        assertThat(sireOf(a)).isNull();
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
