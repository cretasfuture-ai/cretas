package com.cretas.aims.integration;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.PurchaseReturnCapacityService;
import com.cretas.aims.service.inventory.impl.ReturnOrderServiceImpl;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in PostgreSQL proof of the purchase-return capacity service, including its
 * real JPA source-row lock and assigned-ID persistence. It also retains a small
 * disposable JDBC lock probe to make the database serialization observable.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false",
        "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = PurchaseOrderRepository.class)
@Import(PurchaseReturnConcurrencyPostgresContractTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class PurchaseReturnConcurrencyPostgresContractTest {

    private static final String TEST_SCHEMA = "purchase_return_capacity_"
            + UUID.randomUUID().toString().replace("-", "");

    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired private ReturnOrderRepository returnOrderRepository;
    @Autowired private ReturnOrderItemRepository returnOrderItemRepository;
    @Autowired private ReturnOrderServiceImpl returnOrderService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        createSchema(url);
        String isolatedUrl = withSchema(url);
        registry.add("spring.datasource.url", () -> isolatedUrl);
        registry.add("spring.datasource.username", () -> required("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> TEST_SCHEMA);
    }

    @AfterAll
    static void dropIsolatedSchema() {
        String rawUrl = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (rawUrl == null || rawUrl.isBlank()) return;
        String url = DisposablePostgresTargetGuard.requireSafeUrl(rawUrl);
        try (Connection connection = DriverManager.getConnection(url,
                required("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
        } catch (Exception error) {
            throw new IllegalStateException("failed to drop isolated PostgreSQL test schema", error);
        }
    }

    private static void createSchema(String url) {
        try (Connection connection = DriverManager.getConnection(url,
                required("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + TEST_SCHEMA);
        } catch (Exception error) {
            throw new IllegalStateException("failed to create isolated PostgreSQL test schema", error);
        }
    }

    private static String withSchema(String url) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + TEST_SCHEMA;
    }

    @TestConfiguration
    static class Config {
        @Bean
        PurchaseReturnCapacityService purchaseReturnCapacityService(PurchaseOrderRepository po,
                PurchaseOrderItemRepository poi, ReturnOrderRepository ro, ReturnOrderItemRepository roi,
                EntityManager entityManager) {
            PurchaseReturnCapacityService service = new PurchaseReturnCapacityService(po, poi, ro, roi);
            ReflectionTestUtils.setField(service, "entityManager", entityManager);
            return service;
        }

        @Bean
        ReturnOrderServiceImpl returnOrderService(PurchaseReturnCapacityService capacity,
                ReturnOrderRepository ro, ReturnOrderItemRepository roi, EntityManager entityManager,
                ArApService arApService, ApplicationEventPublisher events) {
            ReturnOrderServiceImpl service = new ReturnOrderServiceImpl(ro, roi, arApService, events);
            ReflectionTestUtils.setField(service, "purchaseReturnCapacityService", capacity);
            ReflectionTestUtils.setField(service, "entityManager", entityManager);
            return service;
        }

        @Bean
        ArApService arApService() { return org.mockito.Mockito.mock(ArApService.class); }

        @Bean
        ApplicationEventPublisher applicationEventPublisher() {
            return event -> { };
        }
    }

    @Test
    void sourceRowLockSerializesTwoCapacityDecisions() throws Exception {
        String url = withSchema(DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL")));
        String user = required("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String table = "purchase_return_capacity_probe_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection setup = DriverManager.getConnection(url, user, password)) {
            setup.setAutoCommit(true);
            try (Statement statement = setup.createStatement()) {
                statement.execute("CREATE TABLE " + table +
                        " (source_id VARCHAR(64) PRIMARY KEY, received NUMERIC(12,2) NOT NULL, returned NUMERIC(12,2) NOT NULL)");
                statement.executeUpdate("INSERT INTO " + table + " VALUES ('PO-1', 120.00, 40.00)");
            }
        }

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> decide(url, user, password, table, firstLocked, releaseFirst, true));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = pool.submit(() -> decide(url, user, password, table, new CountDownLatch(0), new CountDownLatch(0), false));
            Thread.sleep(250L);
            releaseFirst.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isFalse();
        } finally {
            pool.shutdownNow();
            try (Connection cleanup = DriverManager.getConnection(url, user, password);
                 Statement statement = cleanup.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualServiceConcurrentDifferentKeysAllowsOnlyOneAtCapacity() throws Exception {
        String poId = "PG-PO-" + UUID.randomUUID();
        seedSource(poId, new BigDecimal("120.00"), new BigDecimal("30.00"));
        CreateReturnOrderRequest firstRequest = request(poId, "50.00", UUID.randomUUID().toString());
        CreateReturnOrderRequest secondRequest = request(poId, "50.00", UUID.randomUUID().toString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> invokeCreate(firstRequest));
            Future<Object> second = pool.submit(() -> invokeCreate(secondRequest));
            Object firstResult = first.get(30, TimeUnit.SECONDS);
            Object secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(firstResult, secondResult).stream().filter(v -> v instanceof ReturnOrder).count()).isEqualTo(1);
            assertThat(List.of(firstResult, secondResult).stream().filter(v -> v instanceof BusinessException).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        new TransactionTemplate(transactionManager).execute(status -> {
            List<ReturnOrder> rows = returnOrderRepository
                    .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                            "PG-CAP", ReturnType.PURCHASE_RETURN, poId);
            List<ReturnOrder> created = rows.stream()
                    .filter(row -> row.getStatus() == ReturnOrderStatus.DRAFT).toList();
            assertThat(created).hasSize(1);
            assertThat(returnOrderItemRepository.findByReturnOrderId(created.get(0).getId()))
                    .singleElement().satisfies(item ->
                            assertThat(item.getQuantity()).isEqualByComparingTo(new BigDecimal("50.00")));
            return null;
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualServiceSameKeyReturnsOnePersistedId() throws Exception {
        String poId = "PG-PO-" + UUID.randomUUID();
        seedSource(poId, new BigDecimal("120.00"), new BigDecimal("110.00"));
        String key = UUID.randomUUID().toString();
        CreateReturnOrderRequest firstRequest = request(poId, "10.00", key);
        CreateReturnOrderRequest secondRequest = request(poId, "10.00", key);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> invokeCreate(firstRequest));
            Future<Object> second = pool.submit(() -> invokeCreate(secondRequest));
            Object firstResult = first.get(30, TimeUnit.SECONDS);
            Object secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(firstResult).isInstanceOf(ReturnOrder.class);
            assertThat(secondResult).isInstanceOf(ReturnOrder.class);
            assertThat(((ReturnOrder) firstResult).getId()).isEqualTo(((ReturnOrder) secondResult).getId());
        } finally {
            pool.shutdownNow();
        }
        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(returnOrderRepository
                    .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                            "PG-CAP", ReturnType.PURCHASE_RETURN, poId).stream()
                    .filter(row -> row.getStatus() == ReturnOrderStatus.DRAFT)).hasSize(1);
            return null;
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualServiceSameKeyDifferentSourceIsOneReplayOrConflict() throws Exception {
        String firstPo = "PG-PO-" + UUID.randomUUID();
        String secondPo = "PG-PO-" + UUID.randomUUID();
        seedSource(firstPo, new BigDecimal("120.00"), BigDecimal.ZERO);
        seedSource(secondPo, new BigDecimal("120.00"), BigDecimal.ZERO);
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> invokeCreate(request(firstPo, "10.00", key)));
            Future<Object> second = pool.submit(() -> invokeCreate(request(secondPo, "11.00", key)));
            Object firstResult = first.get(30, TimeUnit.SECONDS);
            Object secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(firstResult, secondResult).stream().filter(v -> v instanceof ReturnOrder).count()).isEqualTo(1);
            assertThat(List.of(firstResult, secondResult).stream().filter(v -> v instanceof BusinessException).count()).isEqualTo(1);
            assertThat(List.of(firstResult, secondResult).stream().anyMatch(v ->
                    v instanceof BusinessException e && e.getCode() == 409
                            && "PURCHASE_RETURN_IDEMPOTENCY_PAYLOAD_CONFLICT".equals(e.getErrorCode()))).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    private Object invokeCreate(CreateReturnOrderRequest request) {
        try { return returnOrderService.createReturnOrder("PG-CAP", request, 7L); }
        catch (BusinessException error) { return error; }
        catch (RuntimeException error) { return error; }
    }

    private void seedSource(String poId, BigDecimal received, BigDecimal existingReturned) {
        new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.update("INSERT INTO factories (id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) "
                    + "VALUES ('PG-CAP','PG capacity factory','FACTORY',1,TRUE,FALSE,0,NOW(),NOW()) ON CONFLICT (id) DO NOTHING");
            jdbcTemplate.update("INSERT INTO users (id,username,password_hash,is_active,factory_id,created_at,updated_at) "
                    + "VALUES (7,'pg-cap-user','test',TRUE,'PG-CAP',NOW(),NOW()) ON CONFLICT (id) DO NOTHING");
            jdbcTemplate.update("INSERT INTO suppliers (id,name,code,supplier_code,factory_id,is_active,created_by,version,created_at,updated_at) "
                    + "VALUES ('PG-SUP','PG supplier','PG-SUP','PG-SUP','PG-CAP',TRUE,7,0,NOW(),NOW()) ON CONFLICT (id) DO NOTHING");
            jdbcTemplate.update("INSERT INTO raw_material_types (id,factory_id,name,code,unit,is_active,is_byproduct,is_abaca_packaging,self_supplied,tax_treatment,created_at,updated_at) "
                    + "VALUES ('PG-MT','PG-CAP','PG material','PG-MT','kg',TRUE,FALSE,FALSE,FALSE,'TAXABLE',NOW(),NOW()) ON CONFLICT (id) DO NOTHING");
            PurchaseOrder po = new PurchaseOrder();
            po.setId(poId); po.setFactoryId("PG-CAP"); po.setOrderNumber(poId);
            po.setSupplierId("PG-SUP"); po.setPurchaseType(PurchaseType.DIRECT);
            po.setOrderDate(LocalDate.now()); po.setStatus(PurchaseOrderStatus.COMPLETED);
            po.setCreatedBy(7L); po.setInvoiceStatus(PurchaseInvoiceStatus.NOT_RECEIVED);
            purchaseOrderRepository.saveAndFlush(po);
            PurchaseOrderItem line = new PurchaseOrderItem();
            line.setPurchaseOrderId(poId); line.setMaterialTypeId("PG-MT");
            line.setQuantity(received); line.setReceivedQuantity(received); line.setUnit("kg");
            line.setPriceUnit("kg"); line.setQuantityToPriceFactor(BigDecimal.ONE);
            line.setUnitPrice(new BigDecimal("5.00")); line.setTaxRate(BigDecimal.ZERO);
            line.setPurchasePackageUnitSnapshot("kg"); line.setInventoryBaseUnitSnapshot("kg");
            purchaseOrderItemRepository.saveAndFlush(line);
            if (existingReturned.signum() > 0) {
                ReturnOrder old = new ReturnOrder();
                old.setId("PG-OLD-" + poId); old.setFactoryId("PG-CAP"); old.setReturnNumber("PG-OLD-" + poId);
                old.setReturnType(ReturnType.PURCHASE_RETURN); old.setStatus(ReturnOrderStatus.COMPLETED);
                old.setCounterpartyId("PG-SUP"); old.setSourceOrderId(poId); old.setReturnDate(LocalDate.now());
                old.setWithGoods(true); old.setCreatedBy(7L); old.setTotalAmount(existingReturned.multiply(new BigDecimal("5")));
                returnOrderRepository.saveAndFlush(old);
                ReturnOrderItem oldItem = new ReturnOrderItem(); oldItem.setReturnOrderId(old.getId());
                oldItem.setMaterialTypeId("PG-MT"); oldItem.setQuantity(existingReturned);
                oldItem.setUnitPrice(new BigDecimal("5.00")); oldItem.setLineAmount(old.getTotalAmount());
                returnOrderItemRepository.saveAndFlush(oldItem);
            }
            return null;
        });
    }

    private CreateReturnOrderRequest request(String poId, String quantity, String requestId) {
        CreateReturnOrderRequest request = new CreateReturnOrderRequest();
        request.setRequestId(requestId); request.setReturnType(ReturnType.PURCHASE_RETURN.name());
        request.setCounterpartyId("PG-SUP"); request.setSourceOrderId(poId); request.setReturnDate(LocalDate.now());
        request.setWithGoods(true); request.setReason("PG capacity");
        CreateReturnOrderRequest.ReturnOrderItemDTO item = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        item.setMaterialTypeId("PG-MT"); item.setQuantity(new BigDecimal(quantity)); item.setUnitPrice(new BigDecimal("5.00"));
        request.setItems(List.of(item)); return request;
    }

    private boolean decide(String url, String user, String password, String table,
                           CountDownLatch locked, CountDownLatch release, boolean first) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try {
                BigDecimalPair state = readLocked(connection, table, locked);
                boolean accepted = state.returned.add(new BigDecimal("50.00")).compareTo(state.received) <= 0;
                if (accepted) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE " + table + " SET returned = returned + 50.00 WHERE source_id = 'PO-1'")) {
                        update.executeUpdate();
                    }
                }
                if (first) {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
                }
                connection.commit();
                return accepted;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private BigDecimalPair readLocked(Connection connection, String table, CountDownLatch locked) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT received, returned FROM " + table + " WHERE source_id = 'PO-1' FOR UPDATE");
             ResultSet rows = query.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("probe source row missing");
            BigDecimalPair pair = new BigDecimalPair(rows.getBigDecimal(1), rows.getBigDecimal(2));
            locked.countDown();
            return pair;
        }
    }

    private record BigDecimalPair(BigDecimal received, BigDecimal returned) { }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}
