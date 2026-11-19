package com.cretas.aims.service.impl;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 真 PostgreSQL: 同厂两个事务并发自动编码建 SKU, 必须拿到两个不同的连续编号。
 *
 * <p>A 先建一条 (未提交) 并持着取号锁; B 随后开始建。A 在提交前确认 B 真的在等锁 (pg_locks 里
 * B 的 advisory 锁未授予) —— 这是「两个事务确实重叠」的必然读数, 没有它下面的「编号不同」没有意义。
 * 去掉取号锁时 B 读不到 A 未提交的行, 算出同一个号, 等 A 提交后撞 uk_product_types_factory_code → 409, 本用例变红。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductTypeGeneratedCodeConcurrencyPostgresContractTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String bound = com.cretas.aims.integration.DisposablePostgresTargetGuard.requireSafeUrl(
                System.getenv("CRETAS_WORKFLOW_PG_URL")) + "?stringtype=unspecified";
        registry.add("spring.datasource.url", () -> bound);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired ProductTypeRepository productTypeRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void twoConcurrentAutoCodeCreatesInOneFactoryGetDistinctConsecutiveCodes() throws Exception {
        String factoryId = "FC" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long userId = tx.execute(status -> seedFactoryAndUser(factoryId));

        ProductTypeServiceImpl service = new ProductTypeServiceImpl(productTypeRepository, new ObjectMapper(),
                mock(CustomerRepository.class), mock(WorkflowUnitReviewService.class),
                mock(ProductSpecificationConversionSyncService.class));

        CountDownLatch aInsertedNotCommitted = new CountDownLatch(1);
        AtomicReference<String> observedWait = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> tx.execute(status -> {
                String code = service.createProductType(factoryId, dto("并发A", userId)).getCode();
                aInsertedNotCommitted.countDown();
                observedWait.set(awaitAnotherSessionWaitingOnLock());
                return code;
            }));
            assertThat(aInsertedNotCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            Future<String> second = pool.submit(() -> tx.execute(status ->
                    service.createProductType(factoryId, dto("并发B", userId)).getCode()));

            String codeA = first.get(60, TimeUnit.SECONDS);
            String codeB = second.get(60, TimeUnit.SECONDS);

            // 必然读数: B 确实在 A 未提交时开始并被取号锁挡住 (不是串行跑完的)
            assertThat(observedWait.get()).as("B 等待的锁类型").isEqualTo("advisory");
            assertThat(codeA).isNotEqualTo(codeB);
            assertThat(suffix(codeB)).isEqualTo(suffix(codeA) + 1);
            List<String> stored = jdbc.queryForList(
                    "SELECT code FROM product_types WHERE factory_id = ? ORDER BY code", String.class, factoryId);
            assertThat(stored).containsExactly(codeA, codeB);
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM product_types WHERE factory_id = ?", factoryId);
            jdbc.update("DELETE FROM users WHERE factory_id = ?", factoryId);
            jdbc.update("DELETE FROM factories WHERE id = ?", factoryId);
        }
    }

    /** 在 A 的事务里轮询, 直到看到别的会话有未授予的锁; 返回它等的锁类型, 超时返回 null。 */
    private String awaitAnotherSessionWaitingOnLock() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<String> waiting = jdbc.queryForList(
                    "SELECT locktype FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()", String.class);
            if (!waiting.isEmpty()) {
                return waiting.get(0);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private Long seedFactoryAndUser(String factoryId) {
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName("gen code pg " + factoryId);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername("gencode-pg-" + factoryId.toLowerCase());
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private static ProductTypeDTO dto(String name, Long userId) {
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setName(name);
        dto.setUnit("盒");
        dto.setCreatedBy(userId);
        dto.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        dto.setNetContentQuantity(new BigDecimal("500"));
        dto.setNetContentUnit("g");
        return dto;
    }

    private static long suffix(String code) {
        int i = code.length();
        while (i > 0 && Character.isDigit(code.charAt(i - 1))) {
            i--;
        }
        return Long.parseLong(code.substring(i));
    }
}
