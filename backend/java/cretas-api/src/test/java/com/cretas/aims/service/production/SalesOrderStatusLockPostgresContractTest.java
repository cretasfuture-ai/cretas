package com.cretas.aims.service.production;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手工建计划读销售单状态的行锁 (真 PostgreSQL, 已提交行 + 两个真实事务):
 * 取消先拿锁 → 建计划的锁内读要等到取消提交并读到 CANCELLED (即使这个事务早先读过旧状态);
 * 建计划先拿锁 → 取消要等建计划提交。读写用的是取消订单同一把行锁 ({@code findByIdAndFactoryIdForUpdate})。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_URL", matches = ".+")
class SalesOrderStatusLockPostgresContractTest {

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SalesOrderRepository orders;
    @org.springframework.boot.test.mock.mockito.MockBean
    com.cretas.aims.service.discipline.FactoryDisciplinePolicy disciplinePolicy;

    private String factory;
    private String orderId;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

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

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    @BeforeEach
    void committedOrder() {
        factory = "SOL-" + UUID.randomUUID().toString().substring(0, 8);
        orderId = UUID.randomUUID().toString();
        tx().executeWithoutResult(s -> {
            // created_by 有外键指向 users: 先建工厂和真实用户, 不能写死 id=1
            com.cretas.aims.entity.Factory f = new com.cretas.aims.entity.Factory();
            f.setId(factory);
            f.setName(factory);
            em.persist(f);
            com.cretas.aims.entity.User user = new com.cretas.aims.entity.User();
            user.setFactoryId(factory);
            user.setUsername("sol-" + UUID.randomUUID());
            user.setPasswordHash("unused-test-fixture");
            user.setRoleCode("factory_super_admin");
            em.persist(user);
            em.flush();
            com.cretas.aims.entity.Customer customer = new com.cretas.aims.entity.Customer();
            customer.setId("C-" + orderId);
            customer.setFactoryId(factory);
            customer.setCode("C-" + orderId.substring(0, 8));
            customer.setCustomerCode("C-" + orderId.substring(0, 8));
            customer.setName("sol customer");
            customer.setCreatedBy(user.getId());
            em.persist(customer);
            em.flush();
            SalesOrder so = new SalesOrder();
            so.setId(orderId);
            so.setFactoryId(factory);
            so.setOrderNumber("SO-" + orderId.substring(0, 8));
            so.setCustomerId(customer.getId());
            so.setOrderDate(LocalDate.now());
            so.setStatus(SalesOrderStatus.FINANCE_APPROVED);
            so.setCreatedBy(user.getId());
            em.persist(so);
        });
    }

    private String committedStatus() {
        return tx().execute(s -> (String) em.createNativeQuery("SELECT status FROM sales_orders WHERE id = ?1")
                .setParameter(1, orderId).getSingleResult());
    }

    @Test
    void aCancelThatHoldsTheLockMakesThePlanCreatorReadTheCancelledStatusEvenIfItReadStaleStateBefore() throws Exception {
        CountDownLatch cancellerHoldsLock = new CountDownLatch(1);
        CountDownLatch cancellerMayCommit = new CountDownLatch(1);
        CountDownLatch creatorStarted = new CountDownLatch(1);
        CompletableFuture<Void> canceller = CompletableFuture.runAsync(() -> tx().executeWithoutResult(s -> {
            SalesOrder locked = orders.findByIdAndFactoryIdForUpdate(orderId, factory).orElseThrow();
            locked.setStatus(SalesOrderStatus.CANCELLED);
            em.flush();
            cancellerHoldsLock.countDown();
            await(cancellerMayCommit);
        }), pool);
        assertThat(cancellerHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<String> creator = CompletableFuture.supplyAsync(() -> tx().execute(s -> {
            // 创建计划的事务先把销售单读进持久化上下文 (旧状态), 再去拿锁读状态
            SalesOrder stale = orders.findById(orderId).orElseThrow();
            creatorStarted.countDown();
            String locked = orders.lockStatusForUpdate(orderId, factory).orElseThrow();
            assertThat(stale.getStatus()).as("持久化上下文里的旧副本不能当作锁内真值").isNotNull();
            return locked;
        }), pool);
        assertThat(creatorStarted.await(10, TimeUnit.SECONDS)).isTrue();
        // 取消还没提交: 建计划的锁内读必须被挡住, 不能拿到旧的 FINANCE_APPROVED
        assertThatThrownBy(() -> creator.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        cancellerMayCommit.countDown();
        canceller.get(10, TimeUnit.SECONDS);
        assertThat(creator.get(10, TimeUnit.SECONDS)).isEqualTo("CANCELLED");
        assertThat(committedStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void aPlanCreatorThatHoldsTheLockMakesTheCancelWaitForItsCommit() throws Exception {
        CountDownLatch creatorHoldsLock = new CountDownLatch(1);
        CountDownLatch creatorMayCommit = new CountDownLatch(1);
        CompletableFuture<String> creator = CompletableFuture.supplyAsync(() -> tx().execute(s -> {
            String status = orders.lockStatusForUpdate(orderId, factory).orElseThrow();
            creatorHoldsLock.countDown();
            await(creatorMayCommit);
            return status;
        }), pool);
        assertThat(creatorHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> canceller = CompletableFuture.runAsync(() -> tx().executeWithoutResult(s -> {
            SalesOrder locked = orders.findByIdAndFactoryIdForUpdate(orderId, factory).orElseThrow();
            locked.setStatus(SalesOrderStatus.CANCELLED);
        }), pool);
        assertThatThrownBy(() -> canceller.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(committedStatus()).as("建计划事务提交前取消不能先落库").isEqualTo("FINANCE_APPROVED");

        creatorMayCommit.countDown();
        assertThat(creator.get(10, TimeUnit.SECONDS)).isEqualTo("FINANCE_APPROVED");
        canceller.get(10, TimeUnit.SECONDS);
        assertThat(committedStatus()).isEqualTo("CANCELLED");
        // 取消提交之后再来锁内读: 看到的是已取消
        String after = tx().execute(s -> orders.lockStatusForUpdate(orderId, factory).orElseThrow());
        assertThat(after).isEqualTo("CANCELLED");
    }

    @Test
    void anotherFactorysOrderIsNeverLockedOrRead() {
        java.util.Optional<String> foreign = tx().execute(s -> orders.lockStatusForUpdate(orderId, "OTHER-" + factory));
        assertThat(foreign).isEmpty();
    }

    @Test
    void aMissingOrderReadsAsEmptyNotAsApproved() {
        java.util.Optional<String> none = tx().execute(s -> orders.lockStatusForUpdate("no-such-order", factory));
        assertThat(none).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
