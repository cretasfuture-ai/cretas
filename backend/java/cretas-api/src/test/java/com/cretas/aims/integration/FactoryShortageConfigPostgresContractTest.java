package com.cretas.aims.integration;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.impl.config.FactoryShortageConfigTool;
import com.cretas.aims.controller.CanvasFactoryConfigController;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService;
import com.cretas.aims.service.config.aiconfig.FactoryShortageConfigStore;
import com.cretas.aims.service.governance.ToolSimilarityService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.cretas.aims.service.shortage.ShortageStartGate;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER;
import static com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService.ENABLED_PROPERTY;
import static com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService.NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = FactoryShortageConfigPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {ENABLED_PROPERTY + "=true", "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create", "spring.sql.init.mode=never",
                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "spring.jpa.show-sql=false", "management.endpoint.health.validate-group-membership=false"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FactoryShortageConfigPostgresContractTest {
    private static final String SCHEMA = "aiconfig_" + UUID.randomUUID().toString().replace("-", "");
    private static final String AUDIT_FILTER =
            " where factory_id = ? and action = 'AI_SHORTAGE_CONFIG_CHANGE'";
    private static final Set<String> TABLES = Set.of("factories", "users", "factory_settings",
            "factory_tool_configs", "factory_module_configs", "platform_role_permissions",
            "intent_preview_tokens", "tool_execution_idempotency",
            "tool_execution_audit_events", "operation_logs", "shortage_cases", "shortage_case_lines",
            "shortage_case_events");

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories(basePackages = "com.cretas.aims.repository", includeFilters = @Filter(
            type = FilterType.REGEX, pattern = ".*\\.(FactoryRepository|UserRepository|FactorySettingsRepository"
            + "|FactoryToolConfigRepository|FactoryModuleConfigRepository|PlatformRolePermissionRepository"
            + "|IntentPreviewTokenRepository|ToolExecutionIdempotencyRepository"
            + "|ToolExecutionAuditEventRepository|OperationLogRepository|ShortageCaseRepository"
            + "|ShortageCaseLineRepository|ShortageCaseEventRepository)"))
    @Import({FactoryShortageConfigService.class, FactoryShortageConfigStore.class, FactoryShortageConfigTool.class,
            ToolRegistry.class, ToolRuntimeRegistry.class, LegacyToolMigrationRegistry.class,
            AuthenticatedToolPrincipalFactory.class, ToolPrincipalPolicy.class, PermissionServiceImpl.class,
            ToolConfirmationLease.class, PreviewTokenServiceImpl.class, ToolExecutionLedgerService.class,
            DefaultToolExecutionGateway.class, ShortageStartGate.class})
    static class Runtime {
        @Bean
        ToolSimilarityService toolSimilarityService() {
            return mock(ToolSimilarityService.class);
        }

        @Bean
        HibernatePropertiesCustomizer narrowSchema() {
            return properties -> properties.put("hibernate.hbm2ddl.schema_filter_provider",
                    new SchemaFilterProvider() {
                        private final SchemaFilter filter = new SchemaFilter() {
                            @Override public boolean includeNamespace(Namespace namespace) { return true; }
                            @Override public boolean includeTable(Table table) { return TABLES.contains(table.getName()); }
                            @Override public boolean includeSequence(
                                    org.hibernate.boot.model.relational.Sequence sequence) { return true; }
                        };
                        @Override public SchemaFilter getCreateFilter() { return filter; }
                        @Override public SchemaFilter getDropFilter() { return filter; }
                        @Override public SchemaFilter getTruncatorFilter() { return filter; }
                        @Override public SchemaFilter getMigrateFilter() { return filter; }
                        @Override public SchemaFilter getValidateFilter() { return filter; }
                    });
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String url = pgUrl();
        schemaSql("CREATE SCHEMA " + SCHEMA);
        registry.add("spring.datasource.url", () -> url
                + "?currentSchema=" + SCHEMA + "&stringtype=unspecified&ApplicationName=cretas_shortage_config_test");
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", FactoryShortageConfigPostgresContractTest::pgPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @AfterAll
    static void dropPrivateSchema() throws Exception {
        schemaSql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private static void schemaSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(pgUrl(), System.getenv("CRETAS_WORKFLOW_PG_USER"),
                pgPassword()); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String pgUrl() {
        return DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
    }

    private static String pgPassword() {
        return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
    }

    @Autowired private FactoryShortageConfigService service;
    @Autowired private ToolRegistry registry;
    @Autowired private ToolRuntimeRegistry runtime;
    @Autowired private FactoryRepository factories;
    @Autowired private FactorySettingsRepository settings;
    @Autowired private UserRepository users;
    @Autowired private ShortageCaseRepository shortages;
    @Autowired private ShortageCaseLineRepository lines;
    @Autowired private ShortageStartGate gate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void previewConfirmReplayAndUndoReadCommittedVersionsAndChangeTheRealStartGate() {
        Fixture fixture = fixture(true);
        String factory = fixture.factory();
        var plan = openShortage(fixture);
        assertThat(registry.getExecutor(NAME)).hasValueSatisfying(tool -> assertThat(tool.isEnabled()).isTrue());
        assertThat(runtime.isGoverned(NAME)).isTrue();
        var before = row(factory);
        String otherFields = otherFields(factory);
        assertThatCode(() -> checkGate(fixture, plan)).doesNotThrowAnyException();

        var preview = as(fixture.admin(), () -> service.preview(factory, BooleanNode.TRUE, "开启拦截 token=fixture-value"));
        assertThat(preview.plan().current().version()).isEqualTo(before.getVersion());
        assertThat(preview.plan().impact()).contains("后续开工", "插单", "已有业务记录保留");
        assertThat(preview.confirmationToken()).isNotBlank();
        assertUnchanged(fixture, false, before.getVersion(), 0);

        var saved = as(fixture.admin(), () -> service.confirm(factory, preview.confirmationToken()));
        assertThat(saved.operationId()).isEqualTo(preview.operationId().toString());
        assertThat(saved.current()).isEqualTo(saved.applied());
        assertThat(saved.applied().version()).isEqualTo(before.getVersion() + 1);
        assertThat(otherFields(factory)).isEqualTo(otherFields);
        var appliedModifiedAt = row(factory).getLastModifiedAt();
        assertThat(appliedModifiedAt).isNotNull();
        assertUnchanged(fixture, true, before.getVersion() + 1, 1);
        assertThatThrownBy(() -> checkGate(fixture, plan)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ShortageStartGate.CODE));
        var audit = jdbc.queryForMap("select user_id, summary, diff::text as diff, created_at from operation_logs"
                + AUDIT_FILTER, factory);
        assertThat(audit.get("user_id")).isEqualTo(fixture.admin().id());
        assertThat(audit.get("summary")).isEqualTo("开启拦截 token=[REDACTED]");
        assertThat((String) audit.get("diff")).contains("blockStartOnOpenShortage");
        assertThat(audit.get("created_at")).isNotNull();

        assertThat(as(fixture.admin(), () -> service.receipt(factory, preview.operationId()))).contains(saved);
        assertThat(as(fixture.admin(), () -> service.confirm(factory, preview.confirmationToken()))).isEqualTo(saved);
        var otherAdmin = user(fixture.otherFactory(), "permission_admin");
        assertThat(as(otherAdmin, () -> service.receipt(fixture.otherFactory(), preview.operationId()))).isEmpty();
        assertThat(as(otherAdmin, () -> service.previewUndo(fixture.otherFactory(), preview.operationId(), "跨厂撤销"))
                .plan().canDo()).isFalse();
        assertUnchanged(fixture, true, before.getVersion() + 1, 1);

        var undo = as(fixture.permissionAdmin(), () -> service.previewUndo(factory, preview.operationId(), "撤销刚才的设置"));
        var restored = as(fixture.permissionAdmin(), () -> service.confirm(factory, undo.confirmationToken()));
        assertThat(restored.undoOf()).isEqualTo(saved.operationId());
        assertThat(restored.current()).isEqualTo(restored.applied());
        assertThat(restored.applied().enabled()).isFalse();
        assertThat(restored.applied().version()).isEqualTo(before.getVersion() + 2);
        assertThat(otherFields(factory)).isEqualTo(otherFields);
        assertThat(row(factory).getLastModifiedAt()).isAfter(appliedModifiedAt);
        assertUnchanged(fixture, false, before.getVersion() + 2, 2);
        assertThatCode(() -> checkGate(fixture, plan)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from shortage_cases where factory_id = ?",
                Integer.class, factory)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from shortage_case_events where factory_id = ?",
                Integer.class, factory)).isZero();
        System.out.printf("[AICONFIG_PG] operation=%s undo=%s source=%s versions=%d/%d/%d audits=2%n",
                saved.operationId(), restored.operationId(), restored.undoOf(), before.getVersion(),
                saved.applied().version(), restored.applied().version());
    }

    @Test
    void absentOrSameValueHasNoCredentialAndCancellationPreventsCommit() {
        Fixture absent = fixture(false);
        var defaults = as(absent.admin(), () -> service.preview(absent.factory(), BooleanNode.TRUE, "开启"));
        assertThat(defaults.plan().current().enabled()).isFalse();
        assertThat(defaults.plan().message()).contains("初始化");
        assertThat(defaults.confirmationToken()).isNull();
        assertThat(settings.findByFactoryId(absent.factory())).isEmpty();
        assertThat(audits(absent.factory())).isZero();

        Fixture fixture = fixture(true);
        var unchanged = as(fixture.admin(), () -> service.preview(fixture.factory(), BooleanNode.FALSE, "保持关闭"));
        assertThat(unchanged.plan().canDo()).isFalse();
        assertThat(unchanged.confirmationToken()).isNull();
        var preview = preview(fixture);
        assertThat(as(fixture.admin(), () -> service.cancel(fixture.factory(), preview.confirmationToken()))).isTrue();
        reject(() -> as(fixture.admin(), () -> service.confirm(fixture.factory(), preview.confirmationToken())), 409);
        assertUnchanged(fixture, false, 0, 0);
        assertUnchanged(absent, null, null, 0);
    }

    @Test
    void deniesPlatformSameIdCrossTenantOtherUsersInvalidTypesAndForgedOrTamperedProofs() {
        Fixture f = fixture(true);
        var preview = preview(f);
        var platform = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,
                f.admin().id(), f.factory(), "factory_super_admin");
        var unprivileged = user(f.factory(), "viewer");
        var otherAdmin = user(f.otherFactory(), "factory_super_admin");
        reject(() -> as(platform, () -> service.preview(f.factory(), BooleanNode.TRUE, "代看")), 403);
        reject(() -> as(platform, () -> service.confirm(f.factory(), preview.confirmationToken())), 403);
        reject(() -> as(platform, () -> service.previewUndo(f.factory(), preview.operationId(), "代看")), 403);
        reject(() -> as(unprivileged, () -> service.preview(f.factory(), BooleanNode.TRUE, "开启")), 403);
        reject(() -> as(unprivileged, () -> service.confirm(f.factory(), preview.confirmationToken())), 403);
        reject(() -> as(unprivileged, () -> service.previewUndo(f.factory(), preview.operationId(), "撤销")), 403);
        reject(() -> as(otherAdmin, () -> service.preview(f.factory(), BooleanNode.TRUE, "跨厂")), 403);
        reject(() -> as(otherAdmin, () -> service.confirm(f.otherFactory(), preview.confirmationToken())), 409);
        reject(() -> as(f.permissionAdmin(), () -> service.confirm(f.factory(), preview.confirmationToken())), 409);
        reject(() -> as(f.admin(), () -> service.preview(f.factory(), TextNode.valueOf("true"), "开启")), 400);
        reject(() -> as(f.admin(), () -> service.confirm(f.factory(), UUID.randomUUID().toString())), 409);
        assertThat(as(otherAdmin, () -> service.previewUndo(f.otherFactory(), preview.operationId(), "跨厂撤销"))
                .plan().canDo()).isFalse();
        createFactory("SHARED");
        assertThat(as(user("SHARED", "factory_super_admin"),
                () -> catchCode(() -> service.preview("SHARED", BooleanNode.TRUE, "共享设置")))).isEqualTo(403);

        jdbc.update("update intent_preview_tokens set preview_data = replace(preview_data,"
                + " '\"newValue\":true', '\"newValue\":false') where token = ?", preview.confirmationToken());
        reject(() -> as(f.admin(), () -> service.confirm(f.factory(), preview.confirmationToken())), 409);
        assertUnchanged(f, false, 0, 0);
    }

    @Test
    void reauthorizesAgainstCurrentRoleAfterPreview() {
        Fixture f = fixture(true);
        var preview = preview(f);
        jdbc.update("update users set role_code = 'viewer' where id = ?", f.admin().id());
        reject(() -> as(f.admin(), () -> service.confirm(f.factory(), preview.confirmationToken())), 403);
        assertUnchanged(f, false, 0, 0);
    }

    @Test
    void conventionalSettingsChangeInvalidatesPreviewAndAnOlderUndo() {
        Fixture f = fixture(true);
        var stale = preview(f);
        var canvas = new CanvasFactoryConfigController(null, null, null, null, null, settings);
        assertThat(canvas.updateSettings(f.factory(), Map.of("version", 0L, "language", "en-US")).getSuccess()).isTrue();
        reject(() -> as(f.admin(), () -> service.confirm(f.factory(), stale.confirmationToken())), 409);
        assertThat(row(f.factory()).getLanguage()).isEqualTo("en-US");
        assertUnchanged(f, false, 1, 0);

        var fresh = preview(f);
        as(f.admin(), () -> service.confirm(f.factory(), fresh.confirmationToken()));
        var undo = as(f.admin(), () -> service.previewUndo(f.factory(), fresh.operationId(), "撤销"));
        assertThat(canvas.updateSettings(f.factory(), Map.of("version", 2L, "timezone", "UTC")).getSuccess()).isTrue();
        reject(() -> as(f.admin(), () -> service.confirm(f.factory(), undo.confirmationToken())), 409);
        assertThat(as(f.admin(), () -> service.previewUndo(f.factory(), fresh.operationId(), "撤销")).plan().canDo())
                .isFalse();
        assertThat(row(f.factory()).getTimezone()).isEqualTo("UTC");
        assertUnchanged(f, true, 3, 1);
    }

    @Test
    void optimisticLockRejectsAConventionalSaveBetweenRecheckAndWrite() throws Exception {
        Fixture f = fixture(true);
        var preview = preview(f);
        var canvas = new CanvasFactoryConfigController(null, null, null, null, null, settings);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var confirmation = new TransactionTemplate(transactions).execute(status -> {
                assertThat(canvas.updateSettings(f.factory(), Map.of("version", 0L, "language", "en-US"))
                        .getSuccess()).isTrue();
                // Conventional save is flushed but uncommitted. The gateway sees the old version
                // in its preview recheck, then must wait for this transaction at the actual UPDATE.
                var raced = CompletableFuture.supplyAsync(() -> as(f.admin(), () -> catchCode(
                        () -> service.confirm(f.factory(), preview.confirmationToken()))), worker);
                awaitBlockedSettingsUpdate();
                return raced;
            });
            assertThat(confirmation.get(15, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(row(f.factory()).getLanguage()).isEqualTo("en-US");
        assertUnchanged(f, false, 1, 0);
    }

    private void awaitBlockedSettingsUpdate() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do {
            jdbc.execute("select pg_stat_clear_snapshot()");
            Integer blocked = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where application_name = 'cretas_shortage_config_test'
                      and wait_event_type = 'Lock' and query like 'update factory_settings %'
                    """, Integer.class);
            if (Integer.valueOf(1).equals(blocked)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待并发写入被中断", error);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("配置助手未到达 PostgreSQL 设置行写锁");
    }

    @Test
    void expiredUndoLeavesTheAppliedVersionIntact() {
        Fixture f = fixture(true);
        var initial = preview(f);
        as(f.admin(), () -> service.confirm(f.factory(), initial.confirmationToken()));
        var undo = as(f.admin(), () -> service.previewUndo(f.factory(), initial.operationId(), "撤销"));
        jdbc.update("update intent_preview_tokens set expires_at = CURRENT_TIMESTAMP - interval '1 hour'"
                + " where token = ?", undo.confirmationToken());
        reject(() -> as(f.admin(), () -> service.confirm(f.factory(), undo.confirmationToken())), 409);
        assertUnchanged(f, true, 1, 1);
    }

    @Test
    void auditConstraintFailureRollsBackTheAlreadyFlushedSetting() {
        Fixture f = fixture(true);
        var preview = preview(f);
        jdbc.execute("alter table operation_logs add constraint reject_shortage_test_audit"
                + " check (factory_id <> '" + f.factory() + "')");
        try {
            reject(() -> as(f.admin(), () -> service.confirm(f.factory(), preview.confirmationToken())), 409);
            assertThat(as(f.admin(), () -> service.receipt(f.factory(), preview.operationId()))).isEmpty();
            assertUnchanged(f, false, 0, 0);
        } finally {
            jdbc.execute("alter table operation_logs drop constraint reject_shortage_test_audit");
        }
    }

    private FactoryShortageConfigService.Preview preview(Fixture f) {
        return as(f.admin(), () -> service.preview(f.factory(), BooleanNode.TRUE, "开启缺料开工拦截"));
    }

    private Fixture fixture(boolean initialize) {
        String id = UUID.randomUUID().toString().substring(0, 12);
        String first = "SC-A-" + id;
        String second = "SC-B-" + id;
        createFactory(first);
        createFactory(second);
        if (initialize) {
            initialize(first);
        }
        initialize(second);
        return new Fixture(first, second, user(first, "factory_super_admin"), user(first, "permission_admin"));
    }

    private void createFactory(String id) {
        var factory = new Factory();
        factory.setId(id);
        factory.setName(id);
        factories.saveAndFlush(factory);
    }

    private void initialize(String id) {
        var row = new FactorySettings();
        row.setFactoryId(id);
        row.setFactoryName("检验字段保留");
        settings.saveAndFlush(row);
    }

    private AuthenticatedActor user(String factory, String role) {
        var user = new User();
        user.setFactoryId(factory);
        user.setUsername(factory + "-" + role);
        user.setPasswordHash("fixture-no-login");
        user.setRoleCode(role);
        return new AuthenticatedActor(FACTORY_USER, users.saveAndFlush(user).getId(), factory, role);
    }

    private ProductionPlan openShortage(Fixture fixture) {
        var shortage = new ShortageCase();
        shortage.setFactoryId(fixture.factory());
        shortage.setSourceType(ShortageCase.SourceType.SALES_ORDER);
        shortage.setSourceId("test-order");
        shortage.setStage(ShortageCase.Stage.DETECTED);
        shortage.setOwnerRole("production_manager");
        shortage.setGapLineCount(1);
        shortage.setInputFingerprint("fixture");
        shortage.setLastRefreshedAt(LocalDateTime.now());
        shortage.setLastRefreshReason("TEST");
        shortages.saveAndFlush(shortage);
        var line = new ShortageCaseLine();
        line.setFactoryId(fixture.factory());
        line.setCaseId(shortage.getId());
        line.setMaterialTypeId("test-material");
        line.setMaterialName("原料");
        line.setUnit("kg");
        line.setGapQty(BigDecimal.ONE);
        line.setRequiredQty(BigDecimal.ONE);
        line.setReleasedQty(BigDecimal.ZERO);
        line.setInspectingQty(BigDecimal.ZERO);
        line.setOnOrderQty(BigDecimal.ZERO);
        lines.saveAndFlush(line);
        var plan = new ProductionPlan();
        plan.setPlanNumber("test-plan");
        plan.setSourceOrderId("test-order");
        return plan;
    }

    private void checkGate(Fixture f, ProductionPlan plan) {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                gate.check(f.factory(), plan, null, f.admin().id()));
    }

    private FactorySettings row(String factory) {
        return settings.findByFactoryId(factory).orElseThrow();
    }

    private String otherFields(String factory) {
        // FactorySettings and BaseEntity update their timestamps through JPA lifecycle callbacks.
        return jdbc.queryForObject("select (to_jsonb(s) - 'block_start_on_open_shortage' - 'version'"
                + " - 'updated_at' - 'last_modified_at')::text from factory_settings s where factory_id = ?",
                String.class, factory);
    }

    private int audits(String factory) {
        return jdbc.queryForObject("select count(*) from operation_logs" + AUDIT_FILTER, Integer.class, factory);
    }

    private void assertUnchanged(Fixture fixture, Boolean enabled, Number version, int expectedAudits) {
        if (version == null) {
            assertThat(settings.findByFactoryId(fixture.factory())).isEmpty();
        } else {
            assertThat(row(fixture.factory()).getBlockStartOnOpenShortage()).isEqualTo(enabled);
            assertThat(row(fixture.factory()).getVersion()).isEqualTo(version.longValue());
        }
        assertThat(audits(fixture.factory())).isEqualTo(expectedAudits);
        assertThat(row(fixture.otherFactory()).getBlockStartOnOpenShortage()).isFalse();
        assertThat(row(fixture.otherFactory()).getVersion()).isZero();
        assertThat(audits(fixture.otherFactory())).isZero();
    }

    private static <T> T as(AuthenticatedActor actor, Supplier<T> call) {
        var request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return call.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static void reject(Runnable operation, int code) {
        assertThat(catchCode(operation)).isEqualTo(code);
    }

    private static int catchCode(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("预期拒绝此操作");
        } catch (BusinessException ex) {
            return ex.getCode();
        }
    }

    private record Fixture(String factory, String otherFactory,
                           AuthenticatedActor admin, AuthenticatedActor permissionAdmin) {}
}
