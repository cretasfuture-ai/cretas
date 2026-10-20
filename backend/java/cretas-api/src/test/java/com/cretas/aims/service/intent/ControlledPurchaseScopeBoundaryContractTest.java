package com.cretas.aims.service.intent;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.ai.tool.impl.purchase.PurchaseOrderCreateTool;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.controller.AIIntentConfigController;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.ai.AiPurchaseTaskGrant;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.ai.AiPurchaseTaskGrantRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.*;
import com.cretas.aims.service.calibration.*;
import com.cretas.aims.service.execution.*;
import com.cretas.aims.service.impl.IntentConfigRollbackService;
import com.cretas.aims.service.impl.IntentExecutorServiceImpl;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP JSON -> facade -> confirmation/claim -> purchase transaction, not an auth test.
 * Authentication/configuration collaborators are controlled fixtures. Scope, digest, claim,
 * preview issuance, grant consumption and purchase persistence are never mocked.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {AiPurchaseTaskGrant.class, PurchaseOrder.class, PurchaseOrderItem.class,
        Factory.class, Supplier.class, User.class, RawMaterialType.class, PurchaseReceiveRecord.class})
@EnableJpaRepositories(basePackageClasses = {AiPurchaseTaskGrantRepository.class,
        PurchaseOrderRepository.class, PurchaseOrderItemRepository.class, IntentPreviewTokenRepository.class})
@Import({PurchaseTaskGrantService.class, ControlledPurchaseTaskExecutionService.class,
        PreviewTokenServiceImpl.class, ControlledPurchaseScopeBoundaryContractTest.JsonConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ControlledPurchaseScopeBoundaryContractTest {
    private static final String FACTORY = "F-SCOPE";
    private static final long USER = 4242L;
    private static final String ROLE = "FACTORY_ADMIN";
    private static final String URL = "/api/mobile/" + FACTORY + "/ai-intents";
    private static final String SUPPLIER = "SCOPE-SUP-1", OTHER_SUPPLIER = "SCOPE-SUP-2";
    private static final String MATERIAL = "SCOPE-MAT-1", OTHER_MATERIAL = "SCOPE-MAT-2";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            // This class must never create schema or fixtures in a business database.
            if (!url.startsWith("jdbc:postgresql://127.0.0.1:") || !url.endsWith("/cretas_workflow_verify_test")) {
                throw new IllegalStateException("Scope contract requires the disposable loopback test database");
            }
            properties.add("spring.datasource.url", () -> url);
            properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else {
            properties.add("spring.datasource.url", () -> "jdbc:h2:mem:purchase_scope_boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        properties.add("spring.flyway.enabled", () -> "false");
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class JsonConfiguration {
        @org.springframework.context.annotation.Bean
        ObjectMapper boundaryJson() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired ControlledPurchaseTaskExecutionService controlled;
    @Autowired PreviewTokenService previewTokens;
    @Autowired IntentPreviewTokenRepository tokens;
    @Autowired PurchaseOrderRepository orders;
    @Autowired PurchaseOrderItemRepository items;
    @Autowired TestEntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockBean PurchaseService purchaseService;
    @MockBean ToolPrincipalPolicy principalPolicy;
    @MockBean AuthenticatedToolPrincipalFactory principalFactory;
    @MockBean ToolRegistry registry;
    @MockBean RawMaterialTypeRepository materials;
    @MockBean SupplierRepository suppliers;
    @MockBean com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository frontline;
    private MockMvc mvc;

    @BeforeEach
    void fixture() {
        seedReferences();
        var principal = new ExecutionPrincipal(FACTORY, "FACTORY", Long.toString(USER), PrincipalType.USER,
                Set.of(ROLE), Set.of("procurement:read_write"), Set.of());
        when(principalFactory.create(FACTORY, USER, ROLE)).thenReturn(principal);
        when(principalPolicy.rehydrate(principal)).thenReturn(Optional.of(
                new ToolPrincipalPolicy.RehydratedPrincipal(principal, Map.of("canViewPrices", true))));
        when(registry.isToolEnabledForFactory(FACTORY, "purchase_order_create")).thenReturn(true);
        when(suppliers.findByIdAndFactoryId(anyString(), eq(FACTORY))).thenAnswer(call ->
                Optional.ofNullable(entityManager.getEntityManager().find(Supplier.class, call.getArgument(0))));
        when(materials.findByIdAndFactoryId(anyString(), eq(FACTORY))).thenAnswer(call ->
                Optional.ofNullable(entityManager.getEntityManager().find(RawMaterialType.class, call.getArgument(0))));
        var realPurchase = realPurchaseService();
        when(purchaseService.createPurchaseOrder(eq(FACTORY), any(), eq(USER))).thenAnswer(call ->
                realPurchase.createPurchaseOrder(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        var tool = new PurchaseOrderCreateTool(controlled);
        ReflectionTestUtils.setField(tool, "objectMapper", json);
        when(registry.getExecutor("purchase_order_create")).thenReturn(Optional.of(tool));
        var dispatch = new ToolDispatchService(registry, json, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "previewTokenService", previewTokens);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(dispatch, "controlledPurchase", controlled);
        var ai = mock(AIIntentService.class);
        var orchestrator = new IntentExecutionOrchestrator(ai, mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class), mock(RuleEngineService.class), mock(ConversationService.class),
                mock(ConversationMemoryService.class), json, mock(DashScopeClient.class), mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class), mock(AIAnalysisResultRepository.class), registry,
                mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), dispatch,
                mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", previewTokens);
        ReflectionTestUtils.setField(orchestrator, "controlledPurchase", controlled);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(orchestrator, "intentConfigRepository",
                mock(com.cretas.aims.repository.config.AIIntentConfigRepository.class));
        var permission = mock(IntentPermissionGate.class);
        when(permission.check(any(), eq(USER), eq(ROLE))).thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", permission);
        var domain = mock(IntentConfigManagementService.class);
        when(domain.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        ReflectionTestUtils.setField(orchestrator, "configService", domain);
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", new BusinessTypeGate(domain));
        var executor = new IntentExecutorServiceImpl(orchestrator, mock(SseStreamingService.class),
                mock(MultiIntentExecutionService.class), mock(ConversationMemoryService.class));
        var jwt = mock(JwtUtil.class);
        when(jwt.getUserIdFromToken("scope-fixture-jwt")).thenReturn(USER);
        when(jwt.getRoleFromToken("scope-fixture-jwt")).thenReturn(ROLE);
        var controller = new AIIntentConfigController(ai, executor, mock(KeywordEffectivenessService.class),
                mock(IntentConfigRollbackService.class), mock(ParameterExtractionLearningService.class), jwt, domain);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new HandlerInterceptor() {
                    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE));
                        return true;
                    }
                }).build();
    }

    @ParameterizedTest(name = "task-outside {0} is explicitly rejected before claim")
    @ValueSource(strings = {"quantity", "supplierId", "materialTypeId", "action", "toolName", "executionMode"})
    void taskOutsideFieldsRejectAtActualConfirmationAndOriginalProofStillCreatesExactlyOneDraft(String field) throws Exception {
        // Both alternatives exist, are active and are in the SAME factory. Not a tenant/auth/missing-row failure.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM suppliers WHERE factory_id=? AND is_active=TRUE AND id IN (?,?)",
                Long.class, FACTORY, SUPPLIER, OTHER_SUPPLIER)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_material_types WHERE factory_id=? AND is_active=TRUE AND id IN (?,?)",
                Long.class, FACTORY, MATERIAL, OTHER_MATERIAL)).isEqualTo(2);
        if (Set.of("quantity", "supplierId", "materialTypeId").contains(field)) {
            // These values are valid as NEW tasks; they cannot replace this task's frozen input.
            var alternate = new LinkedHashMap<>(input());
            if (field.equals("quantity")) {
                alternate.put("quantity", "3.0000");
                alternate.put("amountCap", "33.90");
            } else {
                alternate.put(field, field.equals("supplierId") ? OTHER_SUPPLIER : OTHER_MATERIAL);
            }
            assertThat(preview(alternate).path("confirmableAction").path("confirmToken").asText()).isNotBlank();
        }
        JsonNode preview = preview(input());
        JsonNode proof = preview.path("confirmableAction");
        String token = proof.path("confirmToken").asText();
        assertThat(token).isNotBlank();
        var persisted = tokens.findByToken(token).orElseThrow();
        assertThat(persisted.getStatus().name()).isEqualTo("PENDING");
        assertThat(persisted.getCommandDigest()).isEqualTo(proof.path("commandDigest").asText());
        var before = snapshot();
        Map<String, Object> body = proofBody(proof);
        body.put(field, switch (field) {
            case "quantity" -> "3.0000";
            case "supplierId" -> OTHER_SUPPLIER;
            case "materialTypeId" -> OTHER_MATERIAL;
            case "toolName" -> "purchase_order_approve";
            case "executionMode" -> "APPROVE";
            default -> "APPROVE";
        });
        var refused = mvc.perform(post(URL + "/confirm").header("Authorization", "Bearer scope-fixture-jwt")
                .header("X-Cretas-Confirmation-Token", token).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(status().isBadRequest()).andReturn();
        assertThat(refused.getResponse().getContentAsString()).doesNotContain(token);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(tokens.findByToken(token).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        verify(purchaseService, never()).createPurchaseOrder(anyString(), any(), anyLong());

        // Positive control uses the identical persisted proof and actual confirmation path, not a hand-made grant.
        JsonNode created = confirm(proof);
        assertThat(created.path("status").asText()).isEqualTo("SUCCESS");
        var after = snapshot();
        assertThat(after.orders()).isEqualTo(before.orders() + 1);
        assertThat(after.items()).isEqualTo(before.items() + 1);
        assertThat(after.grants()).isEqualTo(before.grants() + 1);
        assertThat(after.consumed()).isEqualTo(before.consumed() + 1);
        assertThat(after.previews()).isEqualTo(before.previews());
        var row = jdbc.queryForMap("SELECT supplier_id,status,total_amount,tax_amount FROM purchase_orders WHERE factory_id=? AND created_by=?", FACTORY, USER);
        assertThat(row.get("supplier_id")).isEqualTo(SUPPLIER);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("total_amount").toString()).isEqualTo("20.00");
        assertThat(row.get("tax_amount").toString()).isEqualTo("2.60");
        var item = jdbc.queryForMap("SELECT i.material_type_id,i.quantity FROM purchase_order_items i JOIN purchase_orders p ON p.id=i.purchase_order_id WHERE p.factory_id=? AND p.created_by=?", FACTORY, USER);
        assertThat(item.get("material_type_id")).isEqualTo(MATERIAL);
        assertThat(new java.math.BigDecimal(item.get("quantity").toString())).isEqualByComparingTo("2.0000");
        assertThat(tokens.findByToken(token).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(confirm(proof).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(snapshot()).isEqualTo(after);
        verify(purchaseService, times(1)).createPurchaseOrder(eq(FACTORY), any(), eq(USER));
    }

    private JsonNode confirm(JsonNode proof) throws Exception {
        return response(mvc.perform(post(URL + "/confirm").header("Authorization", "Bearer scope-fixture-jwt")
                .header("X-Cretas-Confirmation-Token", proof.path("confirmToken").asText())
                .contentType("application/json").content(json.writeValueAsString(proofBody(proof))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode preview(Map<String, Object> input) throws Exception {
        JsonNode result = response(mvc.perform(post(URL + "/preview").header("Authorization", "Bearer scope-fixture-jwt")
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "intentCode", "PURCHASE_ORDER_CREATE", "userInput", "创建采购草稿", "context", input))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("status").asText()).isEqualTo("PREVIEW");
        return result;
    }

    private JsonNode response(String content) throws Exception { return json.readTree(content).path("data"); }
    private Map<String, Object> proofBody(JsonNode proof) {
        return new LinkedHashMap<>(Map.of("commandDigest", proof.path("commandDigest").asText(),
                "expiresAt", Instant.now().plusSeconds(120).toString(),
                "requestId", "scope-request-12345678", "idempotencyKey", "scope-idem-12345678"));
    }
    private Map<String, Object> input() {
        return Map.of("supplierId", SUPPLIER, "materialTypeId", MATERIAL, "quantity", "2",
                "unitPrice", "10", "taxRate", "13", "unit", "kg", "currency", "CNY", "amountCap", "22.60");
    }
    private Snapshot snapshot() {
        // JdbcTemplate reads committed state outside a test transaction/first-level persistence cache.
        return new Snapshot(count("SELECT COUNT(*) FROM purchase_orders WHERE factory_id=?"),
                count("SELECT COUNT(*) FROM purchase_order_items i JOIN purchase_orders p ON p.id=i.purchase_order_id WHERE p.factory_id=?"),
                count("SELECT COUNT(*) FROM ai_purchase_task_grants WHERE factory_id=?"),
                count("SELECT COUNT(*) FROM ai_purchase_task_grants WHERE factory_id=? AND consumed_at IS NOT NULL"),
                count("SELECT COUNT(*) FROM intent_preview_tokens WHERE factory_id=?"));
    }
    private long count(String sql) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, FACTORY)); }
    private record Snapshot(long orders, long items, long grants, long consumed, long previews) { }

    private void seedReferences() {
        jdbc.update("INSERT INTO factories(id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) VALUES(?,?,'FACTORY',0,TRUE,FALSE,20,NOW(),NOW())", FACTORY, "scope-test");
        jdbc.update("INSERT INTO users(id,factory_id,username,password_hash,is_active,created_at,updated_at) VALUES(?,?,?,'test',TRUE,NOW(),NOW())", USER, FACTORY, "scope-user");
        for (String id : List.of(SUPPLIER, OTHER_SUPPLIER)) {
            jdbc.update("INSERT INTO suppliers(id,factory_id,code,supplier_code,name,is_active,created_by,version,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,?,0,NOW(),NOW())", id, FACTORY, id, id, id, USER);
        }
        for (String id : List.of(MATERIAL, OTHER_MATERIAL)) {
            jdbc.update("INSERT INTO raw_material_types(id,factory_id,code,name,unit,is_active,is_abaca_packaging,is_byproduct,self_supplied,tax_treatment,created_by,created_at,updated_at) VALUES(?,?,?,?,'kg',TRUE,FALSE,FALSE,FALSE,'TAXABLE',?,NOW(),NOW())", id, FACTORY, id, id, USER);
        }
    }
    private PurchaseServiceImpl realPurchaseService() {
        var lookup = mock(RawMaterialTypeRepository.class);
        when(lookup.findById(anyString())).thenAnswer(call -> Optional.ofNullable(
                entityManager.getEntityManager().find(RawMaterialType.class, call.getArgument(0))));
        var relations = mock(SupplierMaterialRepository.class);
        var relation = new SupplierMaterial();
        relation.setId("SCOPE-REL"); relation.setFactoryId(FACTORY); relation.setSupplierId(SUPPLIER);
        relation.setMaterialTypeId(MATERIAL); relation.setPurchaseUnit("kg"); relation.setActive(true);
        when(relations.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(FACTORY, SUPPLIER, MATERIAL)).thenReturn(true);
        when(relations.findByFactoryIdAndSupplierIdAndMaterialTypeId(FACTORY, SUPPLIER, MATERIAL)).thenReturn(Optional.of(relation));
        var service = new PurchaseServiceImpl(orders, items, null, suppliers, lookup, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", relations);
        return service;
    }
    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        jdbc.update("DELETE FROM ai_purchase_task_grants WHERE factory_id=?", FACTORY);
        jdbc.update("DELETE FROM intent_preview_tokens WHERE factory_id=?", FACTORY);
        jdbc.update("DELETE FROM purchase_order_items WHERE purchase_order_id IN (SELECT id FROM purchase_orders WHERE factory_id=?)", FACTORY);
        jdbc.update("DELETE FROM purchase_orders WHERE factory_id=?", FACTORY);
        jdbc.update("DELETE FROM raw_material_types WHERE factory_id=?", FACTORY);
        jdbc.update("DELETE FROM suppliers WHERE factory_id=?", FACTORY);
        jdbc.update("DELETE FROM users WHERE factory_id=?", FACTORY);
        jdbc.update("DELETE FROM factories WHERE id=?", FACTORY);
    }
}
