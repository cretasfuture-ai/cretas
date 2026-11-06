package com.cretas.aims.integration;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ApprovalPolicy;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ConfirmationPolicy;
import com.cretas.aims.ai.tool.gateway.DataClassification;
import com.cretas.aims.ai.tool.gateway.DefaultToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.IdempotencyPolicy;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolDescriptor;
import com.cretas.aims.ai.tool.gateway.ToolEgressPolicy;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.ai.tool.impl.dataop.ProductCreateTool;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.controller.AiProductCreateController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ai.ToolExecutionAuditEventRepository;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.ProductCreateCommandReceiptService;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Opt-in PG proof for the actual product_create Tool through the HTTP gateway.
 * Domain reads/writes are isolated at their service boundary, while receipt, token,
 * lease, ledger, audit, and the durable product fixture are real JPA/PG operations.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EntityScan(basePackageClasses = {
        ToolExecutionIdempotencyRecord.class, ToolExecutionAuditEvent.class, IntentPreviewToken.class})
@EnableJpaRepositories(basePackageClasses = {
        ToolExecutionIdempotencyRepository.class, ToolExecutionAuditEventRepository.class,
        IntentPreviewTokenRepository.class})
@Import({ProductCreateActualToolGatewayPostgresContractTest.JsonConfig.class,
        ProductCreateCommandReceiptService.class, PreviewTokenServiceImpl.class,
        com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService.class,
        com.cretas.aims.ai.tool.gateway.ToolConfirmationLease.class,
        DefaultToolExecutionGateway.class, AiProductCreateController.class,
        ProductCreateTool.class})
class ProductCreateActualToolGatewayPostgresContractTest {

    private static final String FACTORY_ID = "F006";
    private static final long USER_ID = 42L;
    private static final String SOURCE_ID = "source-actual";
    private static final String SOURCE_NAME = "相似产品";
    private static final String PRODUCT_CATEGORY = "FINISHED_PRODUCT";
    private static final String PRIVATE_SCHEMA =
            "pc_actual_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

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
        String scoped = appendQuery(url,
                "currentSchema=" + PRIVATE_SCHEMA
                        + "&stringtype=unspecified&ApplicationName=cretas_product_actual_tool_test");
        registry.add("spring.datasource.url", () -> scoped);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @AfterAll
    static void dropPrivateSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (Connection connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + PRIVATE_SCHEMA + "\" CASCADE");
        }
    }

    private static void createPrivateSchema(String url) {
        try (Connection connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + PRIVATE_SCHEMA + "\"");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create isolated product tool schema", exception);
        }
    }

    private static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the opt-in PG proof");
        }
        return value;
    }

    @Autowired private EntityManager entityManager;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiProductCreateController controller;
    @Autowired private ProductCreateTool actualTool;
    @Autowired private ProductCreateCommandReceiptService receiptService;
    @Autowired private ToolExecutionIdempotencyRepository ledgerRepository;
    @Autowired private ToolExecutionAuditEventRepository auditRepository;
    @Autowired private IntentPreviewTokenRepository tokenRepository;

    @MockBean private ProductTypeService productTypeService;
    @MockBean private ProductTypeRepository productTypeRepository;
    @MockBean private ProductWorkProcessService productWorkProcessService;
    @MockBean private BomRecipeService bomRecipeService;
    @MockBean private ProcessSecretRecipeGuard processSecretGuard;
    @MockBean private AuthenticatedToolPrincipalFactory principalFactory;
    @MockBean private ToolPrincipalPolicy principalPolicy;
    @MockBean private ToolRuntimeRegistry runtimeRegistry;
    @MockBean private LegacyToolMigrationRegistry legacyToolMigrationRegistry;

    private String suffix;
    private String durableProductId;
    private ProductWorkProcessService.PreparedChain preparedChain;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        durableProductId = "P-ACTUAL-" + suffix;
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getSchema()).isEqualTo(PRIVATE_SCHEMA);
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/flyway/V20261106_2__product_create_command_result_reference.sql"));
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS product_create_actual_fixture ("
                        + "id VARCHAR(128) PRIMARY KEY, factory_id VARCHAR(64) NOT NULL, "
                        + "name VARCHAR(255) NOT NULL)");
            }
            connection.commit();
        }

        ProductType source = new ProductType();
        source.setId(SOURCE_ID);
        source.setFactoryId(FACTORY_ID);
        source.setName(SOURCE_NAME);
        source.setCode("SRC-001");
        source.setUnit("盒");
        source.setProductCategory(PRODUCT_CATEGORY);
        when(productTypeRepository.findByFactoryIdAndName(FACTORY_ID, SOURCE_NAME))
                .thenReturn(Optional.of(source));
        when(productTypeRepository.findByIdAndFactoryId(SOURCE_ID, FACTORY_ID))
                .thenReturn(Optional.of(source));
        when(productTypeService.suggestDefaults(anyString(), anyString(), anyString()))
                .thenReturn(null);
        when(processSecretGuard.permitsProduct(anyString(), anyString(), any(), anyString()))
                .thenReturn(true);
        preparedChain = new ProductWorkProcessService.PreparedChain(
                SOURCE_ID,
                List.of(new ProductWorkProcessService.PreparedProcess(
                        "wp-slice", 1, null, null, false, false, false, false,
                        null, null, null, null, null, null)),
                List.of(new ProductWorkProcessService.PreparedMaster("master-1", 1L, true)),
                "actual-chain-digest", List.of("切片"));
        when(productWorkProcessService.prepareChain(FACTORY_ID, SOURCE_ID)).thenReturn(preparedChain);
        when(bomRecipeService.getCurrentRecipe(anyString(), anyString())).thenReturn(Optional.empty());
        when(bomRecipeService.getSeasoningByProduct(anyString(), anyString())).thenReturn(Optional.empty());

        ExecutionPrincipal principal = new ExecutionPrincipal(
                FACTORY_ID, "FACTORY", Long.toString(USER_ID), PrincipalType.USER,
                Set.of("dispatcher"), Set.of("production:read_write", "system:read_write"), Set.of());
        when(principalFactory.create(FACTORY_ID, USER_ID, "dispatcher")).thenReturn(principal);
        when(principalPolicy.rehydrate(any(ExecutionPrincipal.class))).thenReturn(Optional.of(
                new ToolPrincipalPolicy.RehydratedPrincipal(principal, Map.of(
                        "factoryId", FACTORY_ID, "tenantId", FACTORY_ID,
                        "businessType", "FACTORY", "userId", USER_ID,
                        "userRole", "dispatcher", "canViewPrices", false,
                        "canViewPurchasePrices", false, "canViewPurchaseOrFinancePrices", false))));
        ToolDescriptor descriptor = descriptor();
        when(runtimeRegistry.resolve(any(), any())).thenReturn(Optional.of(new ResolvedTool(descriptor, actualTool)));
        lenient().when(legacyToolMigrationRegistry.resolve(any(), any())).thenReturn(Optional.empty());

        when(productTypeService.createProductType(eq(FACTORY_ID), any(ProductTypeDTO.class)))
                .thenAnswer(invocation -> {
                    ProductTypeDTO dto = invocation.getArgument(1);
                    entityManager.createNativeQuery(
                                    "INSERT INTO product_create_actual_fixture(id, factory_id, name) VALUES (:id, :factory, :name)")
                            .setParameter("id", durableProductId)
                            .setParameter("factory", FACTORY_ID)
                            .setParameter("name", dto.getName())
                            .executeUpdate();
                    return ProductTypeDTO.builder().id(durableProductId).name(dto.getName())
                            .unit(dto.getUnit()).productCategory(dto.getProductCategory()).build();
                });
    }

    @AfterEach
    void dropFixtures() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS product_create_actual_fixture");
        }
        reset(productTypeService, productWorkProcessService, productTypeRepository,
                bomRecipeService, processSecretGuard, principalFactory, principalPolicy,
                runtimeRegistry, legacyToolMigrationRegistry);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void stalePreviewUsesActualToolGatewayLeaseLedgerAndDoesNotCreateOrRetry() {
        Map<String, Object> body = Map.of(
                "productName", "实际过期方案-" + suffix,
                "productCategory", PRODUCT_CATEGORY,
                "inheritFrom", SOURCE_NAME);
        String token = preview(body);
        doThrow(new com.cretas.aims.exception.BusinessException(409, "源产品工序链或主工序版本已变化"))
                .when(productWorkProcessService).validatePreparedChain(
                        eq(FACTORY_ID), eq(SOURCE_ID), eq(preparedChain));

        MockHttpServletRequest request = request();
        assertThatThrownBy(() -> controller.create(FACTORY_ID, token, body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getMessage()).contains("变化");
                });
        assertThat(fixtureCount()).isZero();
        assertThat(executionState()).isEqualTo(ToolExecutionIdempotencyRecord.State.FAILED.name());
        assertThat(executionOutcome()).isEqualTo(ToolExecutionStatus.FAILED.name());
        assertThat(executionResultCode()).isEqualTo("TOOL_NEEDS_INFO");
        assertThat(executionReferenceId()).isNull();
        assertThat(tokenStatus(token)).isEqualTo(IntentPreviewToken.TokenStatus.FAILED.name());

        assertThatThrownBy(() -> controller.create(FACTORY_ID, token, body, request))
                .isInstanceOf(BusinessException.class);
        verify(productTypeService, never()).createProductType(anyString(), any(ProductTypeDTO.class));
        verify(productWorkProcessService, times(1)).validatePreparedChain(
                eq(FACTORY_ID), eq(SOURCE_ID), eq(preparedChain));
        assertThat(auditFailedCount()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualToolCopyFailureKeepsDurableProductAndUnknownBlocksSecondProduct(boolean businessConflict) {
        Map<String, Object> body = Map.of(
                "productName", "实际部分成功-" + suffix,
                "productCategory", PRODUCT_CATEGORY,
                "inheritFrom", SOURCE_NAME);
        String token = preview(body);
        when(productWorkProcessService.copyPreparedChain(
                eq(FACTORY_ID), eq(SOURCE_ID), eq(durableProductId), eq(preparedChain)))
                .thenThrow(businessConflict
                        ? new BusinessException(409, "fixture copy conflict after product commit")
                        : new IllegalStateException("fixture copy acknowledgement failure"));
        MockHttpServletRequest request = request();

        assertThatThrownBy(() -> controller.create(FACTORY_ID, token, body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(502);
                    assertThat(business.getErrorCode()).isEqualTo("PRODUCT_CHAIN_COPY_FAILED");
                    assertThat(business.getMessage()).contains(durableProductId);
                });
        assertThat(fixtureCount()).isEqualTo(1);
        assertThat(executionState()).isEqualTo(ToolExecutionIdempotencyRecord.State.IN_DOUBT.name());
        assertThat(executionOutcome()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN.name());
        assertThat(executionReferenceId()).isEqualTo(durableProductId);

        assertThatThrownBy(() -> controller.create(FACTORY_ID, token, body, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getMessage())
                        .contains(durableProductId));
        verify(productTypeService, times(1)).createProductType(anyString(), any(ProductTypeDTO.class));
        verify(productWorkProcessService, times(1)).copyPreparedChain(
                eq(FACTORY_ID), eq(SOURCE_ID), eq(durableProductId), eq(preparedChain));
        assertThat(fixtureCount()).isEqualTo(1);
    }

    private String preview(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) controller.preview(
                FACTORY_ID, body, request()).getData();
        assertThat(data).containsKey("confirmationToken");
        return String.valueOf(data.get("confirmationToken"));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("factoryId", FACTORY_ID);
        request.setAttribute("userId", Long.toString(USER_ID));
        request.setAttribute("role", "dispatcher");
        request.setAttribute("username", "actual-tool-fixture");
        return request;
    }

    private ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "product_create", ToolExecutor.ActionType.WRITE, ToolExecutor.RiskLevel.MEDIUM,
                Set.of("production:read_write", "system:read_write"), Set.of("dispatcher"),
                Set.of(com.cretas.aims.entity.enums.FactoryType.FACTORY), Set.of("product"),
                "2.0.0", true, ConfirmationPolicy.REQUIRED_FOR_EXECUTION,
                ApprovalPolicy.NOT_REQUIRED, IdempotencyPolicy.REQUIRED_FOR_EXECUTION,
                DataClassification.RESTRICTED, Set.of(ToolExecutionSource.HTTP_CONTROLLER),
                ToolEgressPolicy.denyAll(), DescriptorProvenance.EXPLICIT);
    }

    private long fixtureCount() {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM product_create_actual_fixture WHERE factory_id=:factory")
                .setParameter("factory", FACTORY_ID).getSingleResult()).longValue();
    }

    private String executionState() {
        return scalar("SELECT state FROM tool_execution_idempotency WHERE tool_name='product_create' "
                + "AND principal_id='42' ORDER BY created_at DESC LIMIT 1");
    }

    private String executionOutcome() {
        return scalar("SELECT outcome_status FROM tool_execution_idempotency WHERE tool_name='product_create' "
                + "AND principal_id='42' ORDER BY created_at DESC LIMIT 1");
    }

    private String executionResultCode() {
        return scalar("SELECT result_code FROM tool_execution_idempotency WHERE tool_name='product_create' "
                + "AND principal_id='42' ORDER BY created_at DESC LIMIT 1");
    }

    private String executionReferenceId() {
        return scalar("SELECT result_entity_id FROM tool_execution_idempotency WHERE tool_name='product_create' "
                + "AND principal_id='42' ORDER BY created_at DESC LIMIT 1");
    }

    private String tokenStatus(String token) {
        return scalar("SELECT status FROM intent_preview_tokens WHERE token=:token", Map.of("token", token));
    }

    private long auditFailedCount() {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM tool_execution_audit_events WHERE tool_name='product_create' "
                        + "AND execution_mode='EXECUTE' AND outcome_status='FAILED'")
                .getSingleResult()).longValue();
    }

    private String scalar(String sql) {
        return scalar(sql, Map.of());
    }

    private String scalar(String sql, Map<String, Object> parameters) {
        var query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        List<?> rows = query.getResultList();
        Object value = rows.isEmpty() ? null : rows.get(0);
        return value == null ? null : String.valueOf(value);
    }
}
