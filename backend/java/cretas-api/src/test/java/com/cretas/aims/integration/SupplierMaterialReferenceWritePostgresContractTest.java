package com.cretas.aims.integration;

import com.cretas.aims.dto.supplier.SupplierMaterialRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.material.MaterialReferenceWriteGuard;
import com.cretas.aims.service.supplier.SupplierMaterialServiceImpl;
import com.cretas.aims.service.unit.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service transactions share the merge raw-material lock before any relation write. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Import({SupplierMaterialServiceImpl.class, MaterialReferenceWriteGuard.class,
        com.cretas.aims.service.secrecy.FactorySecrecyConfig.class})
class SupplierMaterialReferenceWritePostgresContractTest {
    private static final String SCHEMA = "supplier_reference_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired SupplierMaterialServiceImpl service;
    @Autowired RawMaterialTypeRepository materials;
    @Autowired SupplierRepository suppliers;
    @Autowired SupplierMaterialRepository relations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @SpyBean MaterialReferenceWriteGuard guard;
    @MockBean UnitContractService units;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        try (var c = DriverManager.getConnection(url, required("CRETAS_WORKFLOW_PG_USER"), password());
             var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
        } catch (Exception e) { throw new IllegalStateException(e); }
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> required("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", SupplierMaterialReferenceWritePostgresContractTest::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @AfterAll
    static void dropOwnedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        try (var c = DriverManager.getConnection(url, required("CRETAS_WORKFLOW_PG_USER"), password());
             var s = c.createStatement()) { s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    }

    @BeforeEach
    void unitContract() {
        reset(guard);
        var kg = new CanonicalUnit("kg", UnitDimension.MASS, "g", new BigDecimal("1000"), "kg", 3);
        when(units.normalize(anyString(), eq("kg"))).thenReturn(new UnitNormalizationResult("kg", "kg", kg));
        when(units.supportsUsage(anyString(), eq("kg"), any())).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createAndUpdateWaitForMergeThenRejectItsCommittedTombstone(boolean update) throws Exception {
        Fixture f = seed();
        String relationId = update ? service.create(f.factory, f.supplier, request(f.material)).getId() : null;
        CountDownLatch materialLocked = new CountDownLatch(1);
        CountDownLatch releaseMerge = new CountDownLatch(1);
        CountDownLatch writerEnteredGuard = new CountDownLatch(1);
        doAnswer(invocation -> {
            writerEnteredGuard.countDown();
            return invocation.callRealMethod();
        }).when(guard).lockActive(eq(f.factory), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var merge = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                RawMaterialType raw = materials.lockByFactoryIdAndIdIn(f.factory, List.of(f.material)).getFirst();
                raw.setIsActive(false);
                materials.saveAndFlush(raw);
                materialLocked.countDown();
                await(releaseMerge);
            }));
            assertThat(materialLocked.await(15, TimeUnit.SECONDS)).isTrue();
            var writer = executor.submit(() -> update
                    ? service.update(f.factory, f.supplier, relationId, changedRequest(f.material))
                    : service.create(f.factory, f.supplier, request(f.material)));
            assertThat(writerEnteredGuard.await(15, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> writer.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseMerge.countDown();
            merge.get(20, TimeUnit.SECONDS);
            assertThatThrownBy(() -> writer.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error.getCause()).getErrorCode())
                            .isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
            assertThat(relations.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(f.factory, f.supplier))
                    .hasSize(update ? 1 : 0);
            if (update) {
                assertThat(relations.findByIdAndFactoryId(relationId, f.factory).orElseThrow().getDefaultPurchasePrice())
                        .isEqualByComparingTo("10");
            }
        } finally {
            releaseMerge.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MERGED", "DELETED", "FOREIGN", "MISSING"})
    void cannotCreateReferenceToUnavailableMaterial(String state) {
        Fixture f = seed();
        if (state.equals("MERGED")) jdbc.update("UPDATE raw_material_types SET merged_into_id=? WHERE id=?", "master", f.material);
        if (state.equals("DELETED")) jdbc.update("UPDATE raw_material_types SET deleted_at=NOW() WHERE id=?", f.material);
        String id = state.equals("MISSING") ? "missing" : f.material;
        String callerFactory = state.equals("FOREIGN") ? seed().factory : f.factory;
        String callerSupplier = f.supplier;
        if (state.equals("FOREIGN")) {
            callerSupplier = suppliers.findByFactoryId(callerFactory).getFirst().getId();
        }
        String supplierId = callerSupplier;
        assertThatThrownBy(() -> service.create(callerFactory, supplierId, request(id)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("MATERIAL_REFERENCE_CONFLICT"));
        assertThat(relations.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(callerFactory, supplierId)).isEmpty();
    }

    private Fixture seed() {
        String factory = "SR-" + UUID.randomUUID().toString().substring(0, 8);
        String supplier = UUID.randomUUID().toString();
        String material = UUID.randomUUID().toString();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO factories (id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) VALUES (?,?,?,0,TRUE,FALSE,20,NOW(),NOW())", factory, factory, "FACTORY");
            long userId = ThreadLocalRandom.current().nextLong(10000, Long.MAX_VALUE);
            jdbc.update("INSERT INTO users (id,factory_id,username,password_hash,is_active,created_at,updated_at) VALUES (?,?,?,?,TRUE,NOW(),NOW())",
                    userId, factory, supplier, "test-only-not-a-login-hash");
            Supplier s = new Supplier();
            s.setId(supplier); s.setFactoryId(factory); s.setName("工厂供应商");
            s.setCode(supplier); s.setSupplierCode(supplier); s.setCreatedBy(userId); s.setVersion(0L);
            suppliers.saveAndFlush(s);
            RawMaterialType raw = new RawMaterialType();
            raw.setId(material); raw.setFactoryId(factory); raw.setCode(material); raw.setName("工厂物料"); raw.setUnit("kg");
            materials.saveAndFlush(raw);
        });
        return new Fixture(factory, supplier, material);
    }

    private SupplierMaterialRequest request(String id) {
        SupplierMaterialRequest r = new SupplierMaterialRequest();
        r.setMaterialTypeId(id); r.setPurchaseUnit("kg"); r.setDefaultPurchasePrice(new BigDecimal("10"));
        return r;
    }
    private SupplierMaterialRequest changedRequest(String id) {
        SupplierMaterialRequest r = request(id); r.setDefaultPurchasePrice(new BigDecimal("99")); return r;
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("merge release timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing " + key);
        return value;
    }
    private static String password() { return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""); }
    private record Fixture(String factory, String supplier, String material) {}
}
