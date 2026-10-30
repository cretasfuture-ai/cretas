package com.cretas.aims.integration;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.platform.PlatformFactoryMenuService;
import com.cretas.aims.controller.FeatureConfigController;
import com.cretas.aims.service.module.ModuleSwitchService;
import com.cretas.aims.service.module.ModuleSwitchLayers;
import com.cretas.aims.service.FactoryService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.utils.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Real PostgreSQL proof that the parent factory lock serializes JSON read-before-write operations. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false", "cretas.platform.factory-assembly.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({FactoryFeatureMenuWriteLock.class, PlatformFactoryMenuService.class, FeatureConfigController.class, ModuleSwitchService.class})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class PlatformFactoryMenuConcurrencyPostgresContractTest {
    private static final String SCHEMA = "platform_menu_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PlatformFactoryMenuService menuService;
    @Autowired FeatureConfigController legacyController;
    @Autowired ModuleSwitchService moduleSwitch;
    @SpyBean FactoryFeatureMenuWriteLock lock;
    @Autowired FactoryFeatureConfigRepository features;
    @MockBean FactoryService factoryService;
    @MockBean JwtUtil jwtUtil;
    @MockBean FactoryConfigService factoryConfigService;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        createSchema(url);
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> required("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @AfterAll
    static void dropSchema() {
        String raw = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (raw == null || raw.isBlank()) return;
        try (Connection c = DriverManager.getConnection(DisposablePostgresTargetGuard.requireSafeUrl(raw),
                required("CRETAS_WORKFLOW_PG_USER"), System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void parentLockSerializesMenuAndLegacyJsonWritersAndAliasPersists() throws Exception {
        String factory = "PM-" + UUID.randomUUID().toString().substring(0, 8);
        seed(factory);
        when(factoryService.getFactoryById(factory)).thenReturn(com.cretas.aims.dto.platform.FactoryDTO.builder()
                .id(factory).name(factory).type("FACTORY").build());
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) secondAttempted.countDown();
            Object result = invocation.callRealMethod();
            if (attempt == 1) {
                firstLocked.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
            }
            return result;
        }).when(org.springframework.test.util.AopTestUtils.<FactoryFeatureMenuWriteLock>getUltimateTargetObject(lock)).lock(factory);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                menuService.update(factory, "restaurantProcurement",
                        new com.cretas.aims.dto.platform.PlatformFactoryMenuDTO.Update(true, java.util.List.of("/restaurant/procurement")));
                return null;
            }));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                // The legacy endpoint replaces non-menu config; its normal UI
                // payload includes the unchanged ordinary fields as well.
                legacyController.update(factory, "restaurant_buy", Map.of("enabledForReports", true, "unrelated", "keep"));
                return null;
            }));
            assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> second.get(250, TimeUnit.MILLISECONDS));
            assertThat(second.isDone()).isFalse();
            release.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> config = features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factory, "restaurant_buy")
                .orElseThrow().getConfig();
        assertThat(config).containsEntry("menuVisible", true).containsEntry("enabledForReports", true)
                .containsEntry("unrelated", "keep");
    }

    @Test
    void frontendOnlyModuleSwitchAndMenuSaveSerializeInReverseOrder() throws Exception {
        String factory = "PM-" + UUID.randomUUID().toString().substring(0, 8);
        seedModule(factory, "smartbi", true);
        when(factoryService.getFactoryById(factory)).thenReturn(com.cretas.aims.dto.platform.FactoryDTO.builder()
                .id(factory).name(factory).type("FACTORY").build());
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) secondAttempted.countDown();
            Object result = invocation.callRealMethod();
            if (attempt == 1) {
                firstLocked.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
            }
            return result;
        }).when(org.springframework.test.util.AopTestUtils.<FactoryFeatureMenuWriteLock>getUltimateTargetObject(lock)).lock(factory);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                moduleSwitch.switchModule(factory, "smartbi", false, ModuleSwitchLayers.FRONTEND_ONLY, 7L);
                return null;
            }));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                menuService.update(factory, "analytics",
                        new com.cretas.aims.dto.platform.PlatformFactoryMenuDTO.Update(true, java.util.List.of("/analytics/overview")));
                return null;
            }));
            assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> second.get(250, TimeUnit.MILLISECONDS));
            assertThat(second.isDone()).isFalse();
            release.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        FactoryFeatureConfig row = features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factory, "smartbi").orElseThrow();
        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getConfig()).containsEntry("menuVisible", true).containsEntry("showInSidebar", true)
                .containsEntry("unrelated", "keep");
    }

    private void seed(String factory) {
        seedModule(factory, "restaurant_buy", true);
    }

    @Test
    void absentLongWebModulePersistsThroughBoundedAliasWithoutChangingDefaultEnablement() {
        String factory = "PM-" + UUID.randomUUID().toString().substring(0, 8);
        new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.update(
                "INSERT INTO factories (id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) VALUES (?,?,?,0,TRUE,FALSE,20,NOW(),NOW())",
                factory, factory, "FACTORY"));
        when(factoryService.getFactoryById(factory)).thenReturn(com.cretas.aims.dto.platform.FactoryDTO.builder()
                .id(factory).name(factory).type("FACTORY").build());
        menuService.update(factory, "restaurantProcurement",
                new com.cretas.aims.dto.platform.PlatformFactoryMenuDTO.Update(false, java.util.List.of()));
        FactoryFeatureConfig row = features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factory, "restaurant_buy").orElseThrow();
        assertThat(row.getEnabled()).isTrue();
        assertThat(row.getConfig()).containsEntry("menuVisible", false);
        assertThat(features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factory, "restaurantProcurement")).isEmpty();
    }

    private void seedModule(String factory, String module, boolean enabled) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO factories (id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) VALUES (?,?,?,0,TRUE,FALSE,20,NOW(),NOW())", factory, factory, "FACTORY");
            jdbc.update("INSERT INTO factory_feature_config (factory_id,module_id,module_name,enabled,config,created_at,updated_at) VALUES (?,?,?, ?,?::jsonb,NOW(),NOW())", factory, module, module, enabled, "{\"enabledForReports\":false,\"unrelated\":\"keep\"}");
        });
    }

    private static void createSchema(String url) {
        try (Connection c = DriverManager.getConnection(url, required("CRETAS_WORKFLOW_PG_USER"), System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "")); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }
}
