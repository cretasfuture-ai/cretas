package com.cretas.aims.service.platform;

import com.cretas.aims.blueprint.activation.AssemblyActivationActorResolver;
import com.cretas.aims.dto.config.ModuleConfigDTO;
import com.cretas.aims.engine.DDLExecutor;
import com.cretas.aims.engine.DynamicFieldService;
import com.cretas.aims.entity.config.FactoryConfiguration;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.config.ModuleSchema;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.config.impl.FactoryConfigServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.SpyBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@org.springframework.test.context.ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Import(FactoryFeatureMenuWriteLock.class)
class PlatformFactoryAccessPostgresContractTest {
    private static final String SCHEMA = "platform_access_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = com.cretas.aims.integration.DisposablePostgresTargetGuard.requireSafeUrl(required("CRETAS_WORKFLOW_PG_URL"));
        createSchema(url);
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + "&stringtype=unspecified");
        registry.add("spring.datasource.username", () -> required("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanup() throws Exception {
        String raw = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (raw == null || raw.isBlank()) return;
        try (Connection c = DriverManager.getConnection(com.cretas.aims.integration.DisposablePostgresTargetGuard.requireSafeUrl(raw), required("CRETAS_WORKFLOW_PG_USER"), System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "")); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired ModuleSchemaRepository schemas;
    @org.springframework.beans.factory.annotation.Autowired FactoryConfigurationRepository configurations;
    @org.springframework.beans.factory.annotation.Autowired FactoryModuleConfigRepository modules;
    @org.springframework.beans.factory.annotation.Autowired ConfigChangeLogRepository audits;
    @org.springframework.beans.factory.annotation.Autowired FactoryFeatureConfigRepository features;
    @org.springframework.beans.factory.annotation.Autowired com.cretas.aims.repository.UserRepository users;
    @org.springframework.beans.factory.annotation.Autowired com.cretas.aims.repository.auth.UserMenuPermissionRepository employeeRows;
    @org.springframework.beans.factory.annotation.Autowired com.cretas.aims.repository.permission.PlatformRolePermissionRepository globalPermissions;
    @org.springframework.beans.factory.annotation.Autowired com.cretas.aims.repository.UserModuleAccessRepository legacyEmployeeRows;
    @org.springframework.beans.factory.annotation.Autowired FactoryRepository factories;
    @org.springframework.beans.factory.annotation.Autowired PlatformTransactionManager transactions;
    @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;
    @SpyBean FactoryFeatureMenuWriteLock writeLock;

    @Test
    void moduleOnlyPublicationPreservesHistoryRolesAndTypedActor() {
        String factory = "PA-" + UUID.randomUUID().toString().substring(0, 8);
        ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 77L);
        seed(factory);
        FactoryConfigServiceImpl service = new FactoryConfigServiceImpl(schemas, configurations, modules, audits, new ObjectMapper());
        PlatformFactoryControlAuthority authority = mock(PlatformFactoryControlAuthority.class);
        when(authority.canManage(actor)).thenReturn(true);
        ReflectionTestUtils.setField(service, "platformControlAuthority", authority);
        ReflectionTestUtils.setField(service, "platformFeatureRepository", features);
        ReflectionTestUtils.setField(service, "ddlExecutor", mock(DDLExecutor.class));
        ReflectionTestUtils.setField(service, "dynamicFieldService", mock(DynamicFieldService.class));

        int version = new TransactionTemplate(transactions).execute(status -> service.setModuleEnabledForPlatform(factory, "production_plan", false, actor));
        assertThat(version).isEqualTo(2);
        FactoryConfiguration current = configurations.findLatestPublished(factory).orElseThrow();
        assertThat(current.getConfigVersion()).isEqualTo(2);
        assertThat(current.getPublishedActorType()).isEqualTo("PLATFORM_ADMIN");
        assertThat(current.getPublishedActorId()).isEqualTo(77L);
        assertThat(configurations.findByFactoryIdAndConfigVersion(factory, 1)).get().extracting(FactoryConfiguration::getStatus).isEqualTo("ARCHIVED");
        FactoryModuleConfig row = modules.findByFactoryIdAndModuleCodeAndConfigVersion(factory, "production_plan", 2).orElseThrow();
        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getFieldConfig()).containsEntry("preserve", true);
        assertThat(row.getRoleModuleOverride()).containsKey("operator");
        assertThat(audits.findAll()).anySatisfy(audit -> assertThat(audit.getOperation()).isEqualTo("PLATFORM_MODULE_TOGGLE"));
        verify(authority).lock(actor, factory);
        verifyNoInteractions((DDLExecutor) ReflectionTestUtils.getField(service, "ddlExecutor"));
    }

    @Test
    void pendingDraftIsRejectedWithoutPublication() {
        String factory = "PA-" + UUID.randomUUID().toString().substring(0, 8);
        seed(factory);
        configurations.saveAndFlush(FactoryConfiguration.builder().factoryId(factory).configVersion(2).status("DRAFT").build());
        FactoryConfigServiceImpl service = new FactoryConfigServiceImpl(schemas, configurations, modules, audits, new ObjectMapper());
        ReflectionTestUtils.setField(service, "platformControlAuthority", mock(PlatformFactoryControlAuthority.class));
        ReflectionTestUtils.setField(service, "platformFeatureRepository", features);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> service.setModuleEnabledForPlatform(factory, "production_plan", false, new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 77L))))
                .hasMessageContaining("未发布配置");
    }

    @Test
    void unchangedModuleDoesNotCreateVersionOrAudit() {
        String factory = "PA-" + UUID.randomUUID().toString().substring(0, 8);
        ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 77L);
        seed(factory);
        FactoryConfigServiceImpl service = new FactoryConfigServiceImpl(schemas, configurations, modules, audits, new ObjectMapper());
        PlatformFactoryControlAuthority authority = mock(PlatformFactoryControlAuthority.class);
        ReflectionTestUtils.setField(service, "platformControlAuthority", authority);
        ReflectionTestUtils.setField(service, "platformFeatureRepository", features);
        int version = new TransactionTemplate(transactions).execute(status -> service.setModuleEnabledForPlatform(factory, "production_plan", true, actor));
        assertThat(version).isEqualTo(1);
        assertThat(configurations.findByFactoryIdOrderByConfigVersionDesc(factory)).hasSize(1);
    }

    private void seed(String factory) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Factory row = new Factory(); row.setId(factory); row.setName(factory);
            factories.saveAndFlush(row);
            if (schemas.findByModuleCode("production_plan").isEmpty()) schemas.save(ModuleSchema.builder().moduleCode("production_plan").moduleName("Production plan").moduleCategory("production").fieldSchema(Map.of()).defaultConfig(Map.of()).build());
            configurations.saveAndFlush(FactoryConfiguration.builder().factoryId(factory).configVersion(1).status("PUBLISHED").build());
            modules.saveAndFlush(FactoryModuleConfig.builder().factoryId(factory).moduleCode("production_plan").configVersion(1).enabled(true)
                    .roleModuleOverride(Map.of("operator", Map.of("production_plan", "r"))).fieldConfig(Map.of("preserve", true)).build());
        });
    }

    @Test void employeeGrantRevokeRestoreAreTenantScopedAndEnforcedByRealRuntime() {
        String factory = "PA-" + UUID.randomUUID().toString().substring(0, 8); seed(factory);
        com.cretas.aims.entity.User user = new com.cretas.aims.entity.User();
        user.setFactoryId(factory); user.setUsername("personal-" + UUID.randomUUID());
        user.setPasswordHash("not-a-live-credential"); user.setRoleCode("viewer"); user.setIsActive(true);
        user = users.saveAndFlush(user);
        PlatformFactoryControlAuthority authority = mock(PlatformFactoryControlAuthority.class);
        PlatformEmployeeModulePermissions helper = new PlatformEmployeeModulePermissions(employeeRows, users, globalPermissions, authority);
        com.cretas.aims.service.impl.PermissionServiceImpl runtime = new com.cretas.aims.service.impl.PermissionServiceImpl();
        ReflectionTestUtils.setField(runtime, "employeeModulePermissions", helper);
        ReflectionTestUtils.setField(runtime, "platformRepo", globalPermissions);
        ReflectionTestUtils.setField(runtime, "factoryConfigRepo", modules);
        ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 77L);
        long globalCount = globalPermissions.count();
        helper.replace(factory, user.getId(), "production", "rw", actor); entityManager.flush(); entityManager.clear();
        assertThat(employeeRows.findByFactoryIdAndUserId(factory, user.getId().toString())).hasSize(2);
        assertThat(runtime.hasPermission(user, "production:write")).isTrue();
        assertThat(runtime.resolveModuleLevelsForAuthorization(user)).containsEntry("production", "rw");
        helper.replace(factory, user.getId(), "production", "-", actor); entityManager.flush(); runtime.invalidateCache();
        assertThat(runtime.hasPermission(user, "production:read")).isFalse();
        final long userId = user.getId();
        assertThatThrownBy(() -> helper.replace("OTHER", userId, "production", "rw", actor)).hasMessageContaining("所选工厂");
        assertThat(employeeRows.findByFactoryIdAndUserId("OTHER", user.getId().toString())).isEmpty();
        helper.replace(factory, user.getId(), "production", null, actor); entityManager.flush(); runtime.invalidateCache();
        assertThat(employeeRows.findByFactoryIdAndUserId(factory, user.getId().toString())).isEmpty();
        assertThat(runtime.hasPermission(user, "production:write")).isFalse();
        assertThat(globalPermissions.count()).isEqualTo(globalCount);

        var legacy = new com.cretas.aims.service.impl.UserModuleAccessServiceImpl(legacyEmployeeRows, runtime, users);
        legacy.setOverride(factory, user.getId().toString(), "production_plan", com.cretas.aims.entity.UserModuleAccess.AccessType.DENY, "test", "test");
        assertThat(legacy.canAccessModule(user, "production_plan")).isFalse();
        PlatformFactoryAccessService console = new PlatformFactoryAccessService(mock(com.cretas.aims.service.FactoryService.class),
            mock(com.cretas.aims.service.config.FactoryConfigService.class), configurations, runtime, authority, helper, users, modules, legacy, audits);
        console.updateEmployee(factory, userId, "production", "rw", actor); entityManager.flush();
        assertThat(legacyEmployeeRows.findActive(factory, user.getId().toString(), "production_plan")).isEmpty();
        assertThat(legacy.canAccessModule(user, "production_plan")).isTrue();
        assertThat(runtime.hasPermission(user, "production:write")).isTrue();
        assertThat(runtime.hasPermission(user, com.cretas.aims.service.impl.PermissionServiceImpl.YIELD_STANDARD_WRITE_PERMISSION)).isFalse();
        console.updateEmployee(factory, userId, "procurement", "rw", actor); entityManager.flush();
        assertThat(runtime.hasPermission(user, "procurement:price:view")).isFalse();
        assertThat(runtime.hasPermission(user, com.cretas.aims.service.impl.PermissionServiceImpl.FINANCE_REVIEW_VIEW_PERMISSION)).isFalse();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realPlatformAndLegacyWritersSerializeOnParentFactoryLock() throws Exception {
        String factory = "PA-" + UUID.randomUUID().toString().substring(0, 8);
        seed(factory);
        ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 77L);
        PlatformFactoryControlAuthority authority = mock(PlatformFactoryControlAuthority.class);
        doAnswer(invocation -> { writeLock.lock(factory); return null; }).when(authority).lock(actor, factory);
        FactoryConfigServiceImpl platform = service(authority);
        FactoryConfigServiceImpl legacy = service(null);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), secondAttempted = new CountDownLatch(1);
        AtomicBoolean firstLock = new AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger secondPid = new java.util.concurrent.atomic.AtomicInteger();
        FactoryFeatureMenuWriteLock lockTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(writeLock);
        doAnswer(invocation -> {
            boolean first = firstLock.compareAndSet(true, false);
            if (!first) secondAttempted.countDown();
            Object result = invocation.callRealMethod();
            if (first) { locked.countDown(); if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
            return result;
        }).when(lockTarget).lock(factory);
        var pool = Executors.newFixedThreadPool(2);
        try {
            TransactionTemplate requiresNew = new TransactionTemplate(transactions); requiresNew.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
            var first = pool.submit(() -> requiresNew.execute(status -> platform.setModuleEnabledForPlatform(factory, "production_plan", false, actor)));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> requiresNew.execute(status -> {
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                legacy.saveModuleConfig(factory, "production_plan", ModuleConfigDTO.builder().enabled(true).fieldConfig(Map.of("legacy", true)).build(), 7L); return null;
            }));
            assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            int blockers = 0; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (blockers == 0 && System.nanoTime() < deadline) {
                blockers = jdbc.queryForObject("select cardinality(pg_blocking_pids(?))", Integer.class, secondPid.get());
                if (blockers == 0) Thread.sleep(25);
            }
            assertThat(blockers).as("PostgreSQL positively observes legacy writer waiting for platform parent lock").isPositive();
            assertThat(second.isDone()).isFalse(); release.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertThat(configurations.findLatestPublished(factory)).get().extracting(FactoryConfiguration::getConfigVersion).isEqualTo(2);
        assertThat(configurations.findDraft(factory)).isPresent();
    }

    private FactoryConfigServiceImpl service(PlatformFactoryControlAuthority authority) {
        FactoryConfigServiceImpl service = new FactoryConfigServiceImpl(schemas, configurations, modules, audits, new ObjectMapper());
        if (authority != null) ReflectionTestUtils.setField(service, "platformControlAuthority", authority);
        ReflectionTestUtils.setField(service, "platformFeatureRepository", features);
        ReflectionTestUtils.setField(service, "ddlExecutor", mock(DDLExecutor.class));
        ReflectionTestUtils.setField(service, "dynamicFieldService", mock(DynamicFieldService.class));
        ReflectionTestUtils.setField(service, "configurationWriteLock", writeLock);
        return service;
    }

    private static void createSchema(String url) {
        try (Connection c = DriverManager.getConnection(url, required("CRETAS_WORKFLOW_PG_USER"), System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "")); Statement s = c.createStatement()) { s.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set"); return value; }
}
