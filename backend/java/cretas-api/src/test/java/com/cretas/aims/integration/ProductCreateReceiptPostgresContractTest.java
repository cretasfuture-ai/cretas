package com.cretas.aims.integration;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ApprovalPolicy;
import com.cretas.aims.ai.tool.gateway.ConfirmationPolicy;
import com.cretas.aims.ai.tool.gateway.DataClassification;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.EgressMode;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.IdempotencyPolicy;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.ToolDescriptor;
import com.cretas.aims.ai.tool.gateway.ToolEgressPolicy;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.RehydratedPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import com.cretas.aims.repository.ai.ToolExecutionAuditEventRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.controller.AiProductCreateController;
import com.cretas.aims.ai.tool.impl.dataop.ProductCreateTool;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.service.ProductCreateCommandReceiptService;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService;
import com.cretas.aims.ai.tool.gateway.ToolConfirmationLease;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Opt-in real PostgreSQL proof for V2 result references and terminal replay classification. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EntityScan(basePackageClasses = {
        ToolExecutionIdempotencyRecord.class, ToolExecutionAuditEvent.class, IntentPreviewToken.class,
        ConversationSession.class})
@EnableJpaRepositories(basePackageClasses = {
        ToolExecutionIdempotencyRepository.class, ToolExecutionAuditEventRepository.class,
        IntentPreviewTokenRepository.class, ConversationSessionRepository.class})
@Import({ProductCreateReceiptPostgresContractTest.JsonConfig.class,
        ProductCreateCommandReceiptService.class, PreviewTokenServiceImpl.class,
        ToolExecutionLedgerService.class, ToolConfirmationLease.class,
        DefaultToolExecutionGateway.class, AiProductCreateController.class})
class ProductCreateReceiptPostgresContractTest {

    private static final String PRIVATE_SCHEMA =
            "pc_receipt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        createPrivateSchema(url);
        String scopedUrl = appendQuery(url,
                "currentSchema=" + PRIVATE_SCHEMA + "&stringtype=unspecified&ApplicationName=cretas_product_receipt_test");
        registry.add("spring.datasource.url", () -> scopedUrl);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @org.junit.jupiter.api.AfterAll
    static void dropPrivateSchema() throws Exception {
        String rawUrl = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (Connection connection = DriverManager.getConnection(
                rawUrl, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + PRIVATE_SCHEMA + "\" CASCADE");
        }
    }

    private static void createPrivateSchema(String rawUrl) {
        try (Connection connection = DriverManager.getConnection(
                rawUrl, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + PRIVATE_SCHEMA + "\"");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create private PostgreSQL test schema", exception);
        }
    }

    private static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    @Autowired private EntityManager entityManager;
    @Autowired private ToolExecutionIdempotencyRepository repository;
    @Autowired private ProductCreateCommandReceiptService service;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private AiProductCreateController controller;
    @Autowired private PreviewTokenService previewTokenService;

    @MockBean private ProductTypeService productTypeService;
    @MockBean private ProductWorkProcessService productWorkProcessService;
    @MockBean private AuthenticatedToolPrincipalFactory principalFactory;
    @MockBean private ToolPrincipalPolicy principalPolicy;
    @MockBean private ToolRuntimeRegistry runtimeRegistry;
    @MockBean private LegacyToolMigrationRegistry legacyToolMigrationRegistry;
    @MockBean private ToolExecutor toolExecutor;

    private String suffix;

    @BeforeEach
    void setUp() throws Exception {
        // The existing receipt/audit IDs are VARCHAR(36); keep the random suffix
        // comfortably within that production width instead of relying on H2 laxness.
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // DataJpaTest + Flyway's application-level depends-on hook form a circular
        // context on this repository's full application.  Keep the proof a real
        // PostgreSQL JPA context, but apply this exact V2 migration in a committed
        // JDBC transaction after Hibernate creates the isolated entity table.
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getSchema()).isEqualTo(PRIVATE_SCHEMA);
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/flyway/V20261106_2__product_create_command_result_reference.sql"));
            connection.commit();
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS product_create_receipt_fixture "
                        + "(id VARCHAR(128) PRIMARY KEY, kind VARCHAR(32) NOT NULL)");
            }
            connection.commit();
        }
    }

    @org.junit.jupiter.api.AfterEach
    void dropFixtureTable() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS product_create_receipt_fixture");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void migrationUpgradesAnOldLedgerShapeAndPreservesItsNullReferenceRow() throws Exception {
        String schema = "receipt_legacy_" + suffix;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA \"" + schema + "\"");
                statement.execute("SET LOCAL search_path TO \"" + schema + "\"");
                statement.execute("""
                        CREATE TABLE tool_execution_idempotency (
                            id VARCHAR(36) PRIMARY KEY,
                            tenant_id VARCHAR(50) NOT NULL,
                            business_type VARCHAR(32) NOT NULL,
                            principal_type VARCHAR(16) NOT NULL,
                            principal_id VARCHAR(100) NOT NULL,
                            tool_name VARCHAR(150) NOT NULL,
                            descriptor_version VARCHAR(64) NOT NULL,
                            idempotency_key_hash VARCHAR(64) NOT NULL,
                            command_digest VARCHAR(64) NOT NULL,
                            confirmation_fingerprint VARCHAR(64) NOT NULL,
                            state VARCHAR(20) NOT NULL,
                            outcome_status VARCHAR(32),
                            original_audit_event_id VARCHAR(36) NOT NULL,
                            result_code VARCHAR(64),
                            created_at TIMESTAMP NOT NULL,
                            updated_at TIMESTAMP NOT NULL,
                            completed_at TIMESTAMP
                        )
                        """);
                statement.execute("INSERT INTO tool_execution_idempotency "
                        + "(id, tenant_id, business_type, principal_type, principal_id, tool_name, "
                        + "descriptor_version, idempotency_key_hash, command_digest, confirmation_fingerprint, "
                        + "state, original_audit_event_id, created_at, updated_at) VALUES "
                        + "('legacy-1', 'F006', 'FACTORY', 'USER', '42', 'product_create', '2.0.0', "
                        + "'idem', 'command', 'confirmation', 'IN_PROGRESS', 'audit-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/flyway/V20261106_2__product_create_command_result_reference.sql"));
            try (var columns = connection.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema='" + schema + "' AND table_name='tool_execution_idempotency' "
                            + "AND column_name IN ('result_entity_type','result_entity_id','result_reference_stage')")) {
                columns.next();
                assertThat(columns.getInt(1)).isEqualTo(3);
            }
            try (var legacy = connection.createStatement().executeQuery(
                    "SELECT tenant_id, state, result_entity_type, result_entity_id, result_reference_stage "
                            + "FROM tool_execution_idempotency WHERE id='legacy-1'")) {
                assertThat(legacy.next()).isTrue();
                assertThat(legacy.getString(1)).isEqualTo("F006");
                assertThat(legacy.getString(2)).isEqualTo("IN_PROGRESS");
                assertThat(legacy.getString(3)).isNull();
                assertThat(legacy.getString(4)).isNull();
                assertThat(legacy.getString(5)).isNull();
            }
            connection.commit();
        } finally {
            try (Connection cleanup = dataSource.getConnection(); var statement = cleanup.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realControllerGatewayLeaseLedgerReplaysTheCommittedProductWithoutCallingToolTwice()
            throws Exception {
        ExecutionPrincipal principal = new ExecutionPrincipal(
                "F006", "FACTORY", "42", PrincipalType.USER,
                java.util.Set.of("dispatcher"), java.util.Set.of("production:read_write"), java.util.Set.of());
        when(principalFactory.create(any(), any(), any())).thenReturn(principal);
        when(principalPolicy.rehydrate(any(ExecutionPrincipal.class))).thenReturn(
                java.util.Optional.of(new RehydratedPrincipal(principal, java.util.Map.of(
                        "factoryId", "F006", "tenantId", "F006", "businessType", "FACTORY",
                        "userId", 42L, "userRole", "dispatcher"))));
        ToolDescriptor descriptor = new ToolDescriptor(
                "product_create", ToolExecutor.ActionType.WRITE, ToolExecutor.RiskLevel.MEDIUM,
                java.util.Set.of("production:read_write", "system:read_write"),
                java.util.Set.of("dispatcher"),
                java.util.Set.of(com.cretas.aims.entity.enums.FactoryType.FACTORY),
                java.util.Set.of("product"), "2.0.0", true,
                ConfirmationPolicy.REQUIRED_FOR_EXECUTION, ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.REQUIRED_FOR_EXECUTION, DataClassification.RESTRICTED,
                java.util.Set.of(ToolExecutionSource.HTTP_CONTROLLER), ToolEgressPolicy.denyAll(),
                DescriptorProvenance.EXPLICIT);
        when(runtimeRegistry.resolve(any(), any())).thenReturn(
                java.util.Optional.of(new ResolvedTool(descriptor, toolExecutor)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("factoryId", "F006");
        request.setAttribute("userId", "42");
        request.setAttribute("role", "dispatcher");
        request.setAttribute("username", "fixture-user");
        String binding = "{\"version\":1,\"productName\":\"SKU-REAL\","
                + "\"productCategory\":\"FINISHED_PRODUCT\",\"defaults\":{},"
                + "\"sourceName\":null,\"sourceProductId\":null,\"processNames\":[]}";
        when(toolExecutor.preview(any(), any())).thenAnswer(invocation -> {
            ProductCreateTool.capturePreviewBindingJson("F006", binding);
            return "{\"success\":true,\"data\":{\"productName\":\"SKU-REAL\"}}";
        });

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> preview =
                (java.util.Map<String, Object>) controller.preview(
                        "F006", java.util.Map.of("productName", "SKU-REAL"), request).getData();
        String token = String.valueOf(preview.get("confirmationToken"));
        String productId = "P-REAL-" + suffix;
        when(toolExecutor.execute(any(), any())).thenAnswer(invocation -> {
            service.recordProductCreated(productId);
            service.recordCopyCommitted(productId);
            service.recordReadbackVerified(productId);
            return "{\"success\":true,\"data\":{\"productId\":\"" + productId
                    + "\",\"productName\":\"SKU-REAL\"}}";
        });

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> first =
                (java.util.Map<String, Object>) controller.create(
                        "F006", token, java.util.Map.of("productName", "SKU-REAL"), request).getData();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> replay =
                (java.util.Map<String, Object>) controller.create(
                        "F006", token, java.util.Map.of("productName", "SKU-REAL"), request).getData();

        assertThat(first.get("productId")).isEqualTo(productId);
        assertThat(replay.get("productId")).isEqualTo(productId);
        verify(toolExecutor, times(1)).execute(any(), any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realJpaCreateWrapperCommitsProductFixtureAndReceiptTogether() throws Exception {
        String id = "receipt-create-" + suffix;
        seedRow(id, "create-command-" + suffix, "create-confirmation-" + suffix);
        ProductTypeDTO created = ProductTypeDTO.builder().id("P-CREATE-" + suffix)
                .name("fixture-product").unit("box").build();
        when(productTypeService.createProductType(any(), any())).thenAnswer(invocation -> {
            insertFixture(created.getId(), "CREATE");
            return created;
        });

        try (ProductCreateCommandReceiptService.CommandScope ignored =
                     scope(id, "create-command-" + suffix, "create-confirmation-" + suffix)) {
            service.createProductAndAttach("F006", ProductTypeDTO.builder().name("fixture-product")
                    .unit("box").build());
        }

        assertThat(fixtureCount(created.getId())).isEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().getResultEntityId()).isEqualTo(created.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realJpaCreateWrapperRollsBackProductFixtureWhenReceiptAttachFails() throws Exception {
        String id = "receipt-create-fail-" + suffix;
        ToolExecutionIdempotencyRecord row = seedRow(id, "create-fail-command-" + suffix,
                "create-fail-confirmation-" + suffix);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                repository.completeFromState(id, ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                        ToolExecutionIdempotencyRecord.State.SUCCEEDED, ToolExecutionStatus.SUCCEEDED,
                        "TOOL_SUCCEEDED", LocalDateTime.now()));
        ProductTypeDTO created = ProductTypeDTO.builder().id("P-CREATE-FAIL-" + suffix)
                .name("fixture-product").unit("box").build();
        when(productTypeService.createProductType(any(), any())).thenAnswer(invocation -> {
            insertFixture(created.getId(), "CREATE_FAIL");
            return created;
        });

        assertThatThrownBy(() -> {
            try (ProductCreateCommandReceiptService.CommandScope ignored = scope(
                    id, "create-fail-command-" + suffix, "create-fail-confirmation-" + suffix)) {
                service.createProductAndAttach("F006", ProductTypeDTO.builder().name("fixture-product")
                        .unit("box").build());
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(fixtureCount(created.getId())).isZero();
        assertThat(row.getResultEntityId()).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realJpaCopyWrapperRollsBackCopiedFixtureWhenReceiptCasFails() throws Exception {
        String id = "receipt-copy-fail-" + suffix;
        seedRow(id, "copy-fail-command-" + suffix, "copy-fail-confirmation-" + suffix);
        try (ProductCreateCommandReceiptService.CommandScope ignored = scope(
                id, "copy-fail-command-" + suffix, "copy-fail-confirmation-" + suffix)) {
            service.recordProductCreated("P-COPY-FAIL-" + suffix);
            service.recordCopyCommitted("P-COPY-FAIL-" + suffix);
        }
        when(productWorkProcessService.copyPreparedChain(any(), any(), any(), any())).thenAnswer(invocation -> {
            insertFixture("P-COPY-FAIL-" + suffix, "COPY_FAIL");
            return 1;
        });

        assertThatThrownBy(() -> {
            try (ProductCreateCommandReceiptService.CommandScope ignored = scope(
                    id, "copy-fail-command-" + suffix, "copy-fail-confirmation-" + suffix)) {
                service.copyPreparedAndAdvance("F006", "S", "P-COPY-FAIL-" + suffix,
                        new ProductWorkProcessService.PreparedChain("S", java.util.List.of(),
                                java.util.List.of(), "digest"));
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(fixtureCount("P-COPY-FAIL-" + suffix)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void emptyChainReadbackAdvancesReceiptToVerified() throws Exception {
        String id = "receipt-empty-" + suffix;
        seedRow(id, "empty-command-" + suffix, "empty-confirmation-" + suffix);
        try (ProductCreateCommandReceiptService.CommandScope ignored = scope(
                id, "empty-command-" + suffix, "empty-confirmation-" + suffix)) {
            service.recordProductCreated("P-EMPTY-" + suffix);
            service.verifyEmptyReadbackAndAdvance("F006", "P-EMPTY-" + suffix);
        }
        entityManager.clear();
        ToolExecutionIdempotencyRecord fresh = repository.findById(id).orElseThrow();
        assertThat(fresh.getResultReferenceStage()).isEqualTo(ProductCreateCommandReceiptService.READBACK_VERIFIED);
        verify(productWorkProcessService).verifyEmptyChainReadback("F006", "P-EMPTY-" + suffix);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void freshReceiptReadClearsStaleManagedNullReferenceAfterBulkAttach() throws Exception {
        String id = "receipt-stale-" + suffix;
        seedRow(id, "stale-command-" + suffix, "stale-confirmation-" + suffix);
        ToolExecutionIdempotencyRecord stale = new TransactionTemplate(transactionManager)
                .execute(status -> entityManager.find(ToolExecutionIdempotencyRecord.class, id));
        assertThat(stale).isNotNull();
        assertThat(stale.getResultEntityId()).isNull();

        try (ProductCreateCommandReceiptService.CommandScope ignored = scope(
                id, "stale-command-" + suffix, "stale-confirmation-" + suffix)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                int updated = repository.attachProductCreateReference(
                        "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                        hash("idem-" + id), hash("stale-command-" + suffix), hash("stale-confirmation-" + suffix),
                        ToolExecutionIdempotencyRecord.State.IN_PROGRESS, "PRODUCT_TYPE", "P-STALE-" + suffix,
                        ProductCreateCommandReceiptService.PRODUCT_CREATED);
                assertThat(updated).isEqualTo(1);
            });
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> repository.saveAndFlush(stale));
            ProductCreateCommandReceiptService.ReceiptSnapshot fresh = service.readCurrent().orElseThrow();
            assertThat(fresh.entityId()).isEqualTo("P-STALE-" + suffix);
            assertThat(fresh.stage()).isEqualTo(ProductCreateCommandReceiptService.PRODUCT_CREATED);
        }
    }

    private void insertFixture(String id, String kind) {
        entityManager.createNativeQuery("INSERT INTO product_create_receipt_fixture(id, kind) VALUES (:id, :kind)")
                .setParameter("id", id).setParameter("kind", kind).executeUpdate();
    }

    private int fixtureCount(String id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM product_create_receipt_fixture WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void postgresMigrationRetainsOldNullReferenceAndFullLocatorCasIsIsolated() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        String id = "receipt-pg-" + suffix;
        ToolExecutionIdempotencyRecord row = seedRow(id, "command-" + suffix, "confirmation-" + suffix);
        assertThat(row.getResultEntityId()).isNull();
        assertThat(entityManager.createNativeQuery(
                "select count(*) from information_schema.columns where table_schema=current_schema() "
                        + "and table_name='tool_execution_idempotency' "
                        + "and column_name in ('result_entity_type','result_entity_id','result_reference_stage')")
                .getSingleResult()).isEqualTo(3L);

        try (ProductCreateCommandReceiptService.CommandScope ignored = scope(
                id, "command-" + suffix, "confirmation-" + suffix)) {
            service.recordProductCreated("P-" + suffix);
            service.recordCopyCommitted("P-" + suffix);
            service.recordReadbackVerified("P-" + suffix);
            ToolExecutionIdempotencyRecord verified = repository.findById(id).orElseThrow();
            assertThat(verified.getResultEntityType()).isEqualTo("PRODUCT_TYPE");
            assertThat(verified.getResultEntityId()).isEqualTo("P-" + suffix);
            assertThat(verified.getResultReferenceStage()).isEqualTo("READBACK_VERIFIED");
            int wrongLocatorUpdate = new TransactionTemplate(transactionManager).execute(status ->
                    repository.advanceProductCreateReference(
                            id, "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                            hash("wrong"), hash("command-" + suffix), hash("confirmation-" + suffix),
                            ToolExecutionIdempotencyRecord.State.IN_PROGRESS, "PRODUCT_TYPE", "P-" + suffix,
                            ProductCreateCommandReceiptService.READBACK_VERIFIED, "PRODUCT_CREATED"));
            assertThat(wrongLocatorUpdate).isZero();
        }
        entityManager.clear();
        ToolExecutionIdempotencyRecord fresh = repository.findById(id).orElseThrow();
        assertThat(fresh.getResultReferenceStage()).isEqualTo("READBACK_VERIFIED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void terminalReplayRequiresVerifiedReceiptAndUsesSameFullLocator() {
        String command = "command-replay-" + suffix;
        String confirmation = "confirmation-replay-" + suffix;
        String id = "receipt-replay-" + suffix;
        ToolExecutionIdempotencyRecord row = seedRow(id, command, confirmation);
        try (ProductCreateCommandReceiptService.CommandScope ignored = scope(id, command, confirmation)) {
            service.recordProductCreated("P-REPLAY-" + suffix);
            service.recordCopyCommitted("P-REPLAY-" + suffix);
            service.recordReadbackVerified("P-REPLAY-" + suffix);
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    entityManager.createNativeQuery(
                            "update tool_execution_idempotency set state='SUCCEEDED', outcome_status='SUCCEEDED' where id=:id")
                            .setParameter("id", id).executeUpdate());

            IntentPreviewToken token = IntentPreviewToken.builder()
                    .token("token-" + suffix).factoryId("F006").tenantId("F006").userId(42L)
                    .status(IntentPreviewToken.TokenStatus.CONFIRMED)
                    .expiresAt(LocalDateTime.now().minusMinutes(1)).build();
            assertThat(service.classifyTerminal(token).kind())
                    .isEqualTo(ProductCreateCommandReceiptService.ReplayDecision.Kind.REPLAY);
        }
        assertThat(repository.findById(row.getId())).isPresent();
    }

    private ToolExecutionIdempotencyRecord seedRow(String id, String command, String confirmation) {
        String auditId = "a-" + id;
        ToolExecutionIdempotencyRecord row = ToolExecutionIdempotencyRecord.reserve(
                id, "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                hash("idem-" + id), hash(command), hash(confirmation), auditId, LocalDateTime.now());
        return repository.saveAndFlush(row);
    }

    private ProductCreateCommandReceiptService.CommandScope scope(
            String id, String command, String confirmation) {
        return ProductCreateCommandReceiptService.openScope(
                "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                hash("idem-" + id),
                hash(command), hash(confirmation));
    }

    private static String hash(String value) {
        return ToolCommandDigest.persistentSecretFingerprint(value);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for PostgreSQL proof");
        }
        return value;
    }
}
