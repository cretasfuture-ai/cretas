package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.ai.AiPurchaseTaskGrantRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.*;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real persisted token/grant lifecycle. Only identity, references and purchase boundary are mocked. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {
        com.cretas.aims.entity.ai.AiPurchaseTaskGrant.class,
        IntentPreviewToken.class,
        com.cretas.aims.entity.Factory.class,
        com.cretas.aims.entity.User.class,
        com.cretas.aims.entity.Supplier.class,
        com.cretas.aims.entity.RawMaterialType.class,
        com.cretas.aims.entity.inventory.PurchaseOrder.class,
        com.cretas.aims.entity.inventory.PurchaseOrderItem.class,
        com.cretas.aims.entity.inventory.PurchaseReceiveRecord.class})
@EnableJpaRepositories(basePackageClasses = {AiPurchaseTaskGrantRepository.class, IntentPreviewTokenRepository.class,
        PurchaseOrderRepository.class, PurchaseOrderItemRepository.class})
@Import({PurchaseTaskGrantService.class, ControlledPurchaseTaskExecutionService.class,
        PurchaseGrantRefusalAuditService.class, com.cretas.aims.controller.AiPurchaseTaskGrantController.class,
        com.cretas.aims.service.impl.PreviewTokenServiceImpl.class,
        DeferredPurchaseGrantPostgresLifecycleContractTest.JsonConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeferredPurchaseGrantPostgresLifecycleContractTest {
    @Autowired com.cretas.aims.controller.AiPurchaseTaskGrantController actualGrantController;
    private static final String FACTORY = "F-DG2";
    private static final long USER = 4243L;
    private static final long OTHER_USER = 4244L;
    @org.springframework.boot.test.context.TestConfiguration
    static class JsonConfiguration {
        @org.springframework.context.annotation.Bean
        ObjectMapper deferredJson() { return new ObjectMapper().findAndRegisterModules(); }
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            if (!url.startsWith("jdbc:postgresql://127.0.0.1:") || !url.endsWith("/cretas_workflow_verify_test")) throw new IllegalStateException("unsafe deferred lifecycle database");
            p.add("spring.datasource.url", () -> url); p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            p.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            p.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            p.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            p.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else p.add("spring.datasource.url", () -> "jdbc:h2:mem:deferred_lifecycle_2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "create"); p.add("spring.flyway.enabled", () -> "false");
    }
    @Autowired ControlledPurchaseTaskExecutionService service;
    @Autowired PurchaseTaskGrantService grantService;
    @Autowired PreviewTokenService previewTokens;
    @SpyBean PreviewTokenService previewTokenServiceSpy;
    @Autowired IntentPreviewTokenRepository tokens;
    @Autowired AiPurchaseTaskGrantRepository grants;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PurchaseOrderRepository orders;
    @Autowired PurchaseOrderItemRepository orderItems;
    @Autowired PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;
    private PurchaseService boundarySpy;
    @MockBean ToolPrincipalPolicy policy;
    @MockBean AuthenticatedToolPrincipalFactory principalFactory;
    @MockBean RawMaterialTypeRepository materials;
    @MockBean SupplierRepository suppliers;
    @MockBean SupplierMaterialRepository supplierMaterials;
    @MockBean PurchaseService purchaseService;
    @MockBean com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository frontline;
    @MockBean com.cretas.aims.ai.tool.ToolRegistry toolRegistry;

    @BeforeEach void fixture() {
        tx = new TransactionTemplate(transactionManager);
        ReflectionTestUtils.setField(grantService, "purchaseService", purchaseService);
        boundarySpy = null;
        tx.executeWithoutResult(status -> {
        // Test-only prerequisite fixture: native scalar insert intentionally omits
        // Factory.inference_data.  PG17 maps that column to jsonb while the entity
        // exposes String, so Hibernate's INSERT binding fails before this lifecycle
        // reaches the real token/grant path.  Keep the actual entity read below.
        jdbc.update("INSERT INTO factories(id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) VALUES(?,?,?,0,TRUE,FALSE,20,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                FACTORY, "deferred", com.cretas.aims.entity.enums.FactoryType.FACTORY.name());
        em.clear();
        var persistedFactory = em.find(com.cretas.aims.entity.Factory.class, FACTORY);
        assertThat(persistedFactory).isNotNull();
        assertThat(persistedFactory.getName()).isEqualTo("deferred");
        assertThat(persistedFactory.getType()).isEqualTo(com.cretas.aims.entity.enums.FactoryType.FACTORY);
        assertThat(persistedFactory.getIsActive()).isTrue();
        assertThat(persistedFactory.getLevel()).isZero();
        assertThat(persistedFactory.getAiWeeklyQuota()).isEqualTo(20);
        // Stable actor IDs are native test fixtures, not detached IDENTITY entities.
        jdbc.update("INSERT INTO users(id,factory_id,username,password_hash,is_active,created_at,updated_at) VALUES(?,?,?,'test',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", USER, FACTORY, "deferred");
        jdbc.update("INSERT INTO users(id,factory_id,username,password_hash,is_active,created_at,updated_at) VALUES(?,?,?,'test',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", OTHER_USER, FACTORY, "other");
        var actor = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, "purchaser");
        var http = new MockHttpServletRequest(); http.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor); RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        var principal = new ExecutionPrincipal(FACTORY, "FACTORY", Long.toString(USER), PrincipalType.USER, Set.of("purchaser"), Set.of("procurement:read_write"), Set.of());
        when(principalFactory.create(FACTORY, USER, "purchaser")).thenReturn(principal);
        when(policy.rehydrate(principal)).thenReturn(Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(principal, Map.of("canViewPrices", true))));
        var otherPrincipal = new ExecutionPrincipal(FACTORY, "FACTORY", Long.toString(OTHER_USER), PrincipalType.USER, Set.of("purchaser"), Set.of("procurement:read_write"), Set.of());
        when(principalFactory.create(FACTORY, OTHER_USER, "purchaser")).thenReturn(otherPrincipal);
        when(policy.rehydrate(otherPrincipal)).thenReturn(Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(otherPrincipal, Map.of("canViewPrices", true))));
        when(toolRegistry.isToolEnabledForFactory(FACTORY, "purchase_order_create")).thenReturn(true);
        var supplier = new com.cretas.aims.entity.Supplier(); supplier.setId("SUP"); supplier.setFactoryId(FACTORY); supplier.setCode("SUP"); supplier.setSupplierCode("SUP"); supplier.setName("deferred supplier"); supplier.setIsActive(true); supplier.setCreatedBy(USER); em.persist(supplier);
        var material = new com.cretas.aims.entity.RawMaterialType(); material.setId("MAT"); material.setFactoryId(FACTORY); material.setCode("MAT"); material.setName("deferred material"); material.setUnit("kg"); material.setIsActive(true); material.setCreatedBy(USER); em.persist(material);
        when(suppliers.findByIdAndFactoryId("SUP", FACTORY)).thenAnswer(inv -> Optional.ofNullable(em.find(com.cretas.aims.entity.Supplier.class, "SUP")));
        when(materials.findByIdAndFactoryId("MAT", FACTORY)).thenAnswer(inv -> Optional.ofNullable(em.find(com.cretas.aims.entity.RawMaterialType.class, "MAT")));
        when(materials.findById("MAT")).thenAnswer(inv -> Optional.ofNullable(em.find(com.cretas.aims.entity.RawMaterialType.class, "MAT")));
        var relation = new com.cretas.aims.entity.SupplierMaterial(); relation.setId("REL"); relation.setFactoryId(FACTORY); relation.setSupplierId("SUP"); relation.setMaterialTypeId("MAT"); relation.setPurchaseUnit("kg"); relation.setActive(true);
        when(supplierMaterials.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(FACTORY, "SUP", "MAT")).thenReturn(true);
        when(supplierMaterials.findByFactoryIdAndSupplierIdAndMaterialTypeId(FACTORY, "SUP", "MAT")).thenReturn(Optional.of(relation));
        em.flush();
        });
    }
    @AfterEach void clear() {
        RequestContextHolder.resetRequestAttributes();
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM ai_audit_logs WHERE factory_id=? AND question_type='grant_refusal'", FACTORY);
            jdbc.update("DELETE FROM ai_purchase_task_grants WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM intent_preview_tokens WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM purchase_order_items WHERE purchase_order_id IN (SELECT id FROM purchase_orders WHERE factory_id=?)", FACTORY);
            jdbc.update("DELETE FROM purchase_orders WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM raw_material_types WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM suppliers WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM users WHERE factory_id=?", FACTORY);
            jdbc.update("DELETE FROM factories WHERE id=?", FACTORY);
        });
    }

    @Test void authorizePersistsConfirmedTokenPendingGrantAndZeroOrders() throws Exception {
        var token = authorize();
        var persistedToken = tokens.findByToken(token.getToken()).orElseThrow();
        assertThat(persistedToken.getStatus()).isEqualTo(IntentPreviewToken.TokenStatus.CONFIRMED);
        var grant = grants.findById("preview-" + token.getId()).orElseThrow();
        assertThat(grant.isConsumed()).isFalse();
        assertThat(grant.getPreviewTokenId()).isEqualTo(persistedToken.getId());
        assertThat(grant.getFactoryId()).isEqualTo(FACTORY).isEqualTo(persistedToken.getFactoryId());
        assertThat(grant.getUserId()).isEqualTo(USER).isEqualTo(persistedToken.getUserId());
        assertThat(grant.getToolName()).isEqualTo("purchase_order_create").isEqualTo(persistedToken.getToolName());
        assertThat(grant.getDescriptorVersion()).isEqualTo("2.0.0").isEqualTo(persistedToken.getDescriptorVersion());
        assertThat(grant.getSupplierId()).isEqualTo("SUP");
        assertThat(grant.getMaterialTypeId()).isEqualTo("MAT");
        assertThat(grant.getQuantityCap()).isEqualByComparingTo("2");
        assertThat(grant.getQuantityUnit()).isEqualTo("kg");
        assertThat(grant.getUnitPrice()).isEqualByComparingTo("10");
        assertThat(grant.getTaxRate()).isEqualByComparingTo("13");
        assertThat(grant.getAmountCap()).isEqualByComparingTo("22.60");
        assertThat(grant.getCurrency()).isEqualTo("CNY");
        assertThat(grant.getExpiresAt()).isEqualTo(persistedToken.getExpiresAt());
        assertThat(grant.getVersion()).isEqualTo(1L);
        var parameters = new ObjectMapper().readTree(persistedToken.getPreviewData());
        String orderDate = parameters.path("purchaseOrderRequest").path("orderDate").asText();
        assertThat(orderDate).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
        StringBuilder expected = new StringBuilder();
        for (String value : List.of("SUP", "DIRECT", orderDate, "<NULL>", "<NULL>", "<NULL>",
                "<NULL>", "<NULL>", "<NULL>", "<NULL>", "1", "MAT", "<NULL>", "<NULL>",
                "<NULL>", "<NULL>", "2", "kg", "kg", "10", "kg", "13", "<NULL>", "<NULL>", "<NULL>", "<NULL>")) {
            expected.append(value.length()).append(':').append(value).append('|');
        }
        assertThat(grant.getApprovedInputSnapshot()).isEqualTo(expected.toString());
        String expectedDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(expected.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(grant.getInputDigest()).isEqualTo(expectedDigest);
        assertThat(persistedToken.getParametersHash()).isEqualTo(ToolCommandDigest.parametersHash(parameters));
        assertThat(persistedToken.getCommandDigest()).isEqualTo(ToolCommandDigest.commandDigest(
                FACTORY, USER, "purchase_order_create", "2.0.0", ToolExecutionMode.EXECUTE, parameters));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items", Long.class)).isZero();
    }

    @Test void authorizationCommitPermissionWithdrawalRollsBackIssuedGrantAndResolvedClaim() {
        var token = previewForRace();
        var observedIssuedGrant = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            boolean resolved = (boolean) invocation.callRealMethod();
            if (resolved) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_purchase_task_grants WHERE factory_id=? AND preview_token_id=?",
                        Long.class, FACTORY, token.getId())).isEqualTo(1L);
                assertThat(tokens.findById(token.getId()).orElseThrow().getStatus()).isEqualTo(IntentPreviewToken.TokenStatus.CONFIRMED);
                observedIssuedGrant.set(true);
                when(policy.rehydrate(any())).thenReturn(Optional.empty());
            }
            return resolved;
        }).when(previewTokenServiceSpy).resolveClaim(eq(token.getToken()), anyString(), eq(true), anyString());
        assertThatThrownBy(() -> service.authorizeForLater(FACTORY,
                new ConfirmationProof(token.getToken(), token.getCommandDigest(), Instant.now().plusSeconds(300))))
                .hasMessageContaining("当前授权不可用");
        assertThat(observedIssuedGrant).isTrue();
        var rolledBackToken = tokens.findById(token.getId()).orElseThrow();
        assertThat(rolledBackToken.getStatus()).isEqualTo(IntentPreviewToken.TokenStatus.PENDING);
        assertThat(rolledBackToken.getClaimId()).isNull();
        assertThat(grants.findById("preview-" + token.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items", Long.class)).isZero();
        verifyNoInteractions(purchaseService);
    }

    @Test void executeFailureRollsBackGrantConsumptionAndOrderWrite() {
        var token = authorize();
        when(purchaseService.createPurchaseOrder(eq(FACTORY), any(), eq(USER))).thenThrow(new RuntimeException("purchase boundary unavailable"));
        assertThatThrownBy(() -> service.executeLater(FACTORY, "preview-" + token.getId(), 1L)).hasMessageContaining("purchase boundary unavailable");
        em.clear();
        assertThat(grants.findById("preview-" + token.getId()).orElseThrow().isConsumed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
    }

    @Test void executeCreatesOneDraftAndOriginalVersionReplaysWithoutAnotherOrder() {
        var token = authorize();
        replacePurchaseBoundaryWithRealService();

        var first = service.executeLater(FACTORY, "preview-" + token.getId(), 1L);
        assertThat(first.state()).isEqualTo("DRAFT");
        assertThat(first.replay()).isFalse();
        assertThat(first.purchaseOrderId()).isNotBlank();
        long ordersAfterFirst = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class);
        long itemsAfterFirst = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items", Long.class);
        assertThat(ordersAfterFirst).isEqualTo(1L);
        assertThat(itemsAfterFirst).isEqualTo(1L);

        var replay = service.executeLater(FACTORY, "preview-" + token.getId(), 1L);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.purchaseOrderId()).isEqualTo(first.purchaseOrderId());
        assertThat(replay.grossAmount()).isEqualByComparingTo(first.grossAmount());
        assertThat(replay.quantity()).isEqualByComparingTo(first.quantity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(ordersAfterFirst);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items", Long.class)).isEqualTo(itemsAfterFirst);
        assertThatThrownBy(() -> service.executeLater(FACTORY, "preview-" + token.getId(), 99L))
                .hasMessageContaining("授权版本冲突");
    }

    @Test void expiredConsumedGrantReplaysReadOnlyWithoutPermissionOrPurchaseWrite() {
        var token = authorize();
        replacePurchaseBoundaryWithRealService();
        var first = service.executeLater(FACTORY, "preview-" + token.getId(), 1L);
        assertThat(first.replay()).isFalse();
        long orderCount = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class);
        clearInvocations(boundarySpy);
        tx.executeWithoutResult(status -> {
            var persistedToken = em.find(IntentPreviewToken.class, token.getId());
            persistedToken.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
            var persistedGrant = grants.findById("preview-" + token.getId()).orElseThrow();
            persistedGrant.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
            em.flush();
        });
        when(policy.rehydrate(any())).thenReturn(Optional.empty());

        var replay = service.executeLater(FACTORY, "preview-" + token.getId(), 1L);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.purchaseOrderId()).isEqualTo(first.purchaseOrderId());
        assertThat(replay.grossAmount()).isNull();
        assertThat(replay.quantity()).isEqualByComparingTo(first.quantity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(orderCount);
        verifyNoInteractions(boundarySpy);
    }

    @Test void commitTimePermissionWithdrawalRollsBackOrderAndGrant() {
        var token = authorize();
        var revoked = new java.util.concurrent.atomic.AtomicBoolean();
        replacePurchaseBoundaryWithRealService(() -> revoked.set(true));
        when(policy.rehydrate(any())).thenAnswer(invocation -> revoked.get()
                ? Optional.empty() : Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(
                new ExecutionPrincipal(FACTORY, "FACTORY", Long.toString(USER), PrincipalType.USER,
                        Set.of("purchaser"), Set.of("procurement:read_write"), Set.of()), Map.of("canViewPrices", true))));
        assertThatThrownBy(() -> service.executeLater(FACTORY, "preview-" + token.getId(), 1L)).isInstanceOf(Throwable.class);
        verify(boundarySpy).createPurchaseOrder(eq(FACTORY), any(), eq(USER));
        assertThat(grants.findById("preview-" + token.getId()).orElseThrow().isConsumed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
    }

    @Test void commitTimeReferenceDeactivationRollsBackOrderAndGrant() {
        var token = authorize();
        replacePurchaseBoundaryWithRealService(() -> {
            var current = em.find(com.cretas.aims.entity.Supplier.class, "SUP");
            current.setIsActive(false);
            em.flush();
        });
        assertThatThrownBy(() -> service.executeLater(FACTORY, "preview-" + token.getId(), 1L)).isInstanceOf(Throwable.class);
        verify(boundarySpy).createPurchaseOrder(eq(FACTORY), any(), eq(USER));
        assertThat(grants.findById("preview-" + token.getId()).orElseThrow().isConsumed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
    }

    @Test void commitTimeTokenExpiryRollsBackOrderAndGrant() {
        var token = authorize();
        replacePurchaseBoundaryWithRealService(() -> {
            var current = em.find(IntentPreviewToken.class, token.getId());
            current.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
            em.flush();
        });
        assertThatThrownBy(() -> service.executeLater(FACTORY, "preview-" + token.getId(), 1L)).isInstanceOf(Throwable.class);
        verify(boundarySpy).createPurchaseOrder(eq(FACTORY), any(), eq(USER));
        assertThat(grants.findById("preview-" + token.getId()).orElseThrow().isConsumed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
    }

    @Test void revokeBeforeDeferredExecutionPreventsAnyOrderWrite() {
        var token = authorize();
        var revoked = service.revoke(FACTORY, "preview-" + token.getId(), 1L, "operator revoke");
        assertThat(revoked.state()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.executeLater(FACTORY, "preview-" + token.getId(), 1L))
                .hasMessageContaining("授权版本冲突");
        assertThatThrownBy(() -> service.executeLater(FACTORY, "preview-" + token.getId(), revoked.version()))
                .hasMessageContaining("grant is revoked");
        assertThat(grants.findById("preview-" + token.getId()).orElseThrow().isConsumed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
    }

    @Test void otherTenantAndActorCannotConsumeTheConfirmedGrant() {
        var token = authorize();
        var grantBefore = grants.findById("preview-" + token.getId()).orElseThrow();
        var versionBefore = grantBefore.getVersion();
        var ordersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class);
        assertThatThrownBy(() -> service.executeLater("OTHER-TENANT", "preview-" + token.getId(), 1L))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(403))
                .hasMessageContaining("缺少当前租户的可信登录主体");

        var other = new MockHttpServletRequest();
        other.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, OTHER_USER, FACTORY, "purchaser"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(other));
        assertThatThrownBy(() -> service.executeLater(FACTORY, "preview-" + token.getId(), 1L))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(403))
                .hasMessageContaining("确认凭据不属于当前采购范围");
        var grantAfter = grants.findById("preview-" + token.getId()).orElseThrow();
        assertThat(grantAfter.isConsumed()).isFalse();
        assertThat(grantAfter.getVersion()).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(ordersBefore);
    }

    @Test void claimThenGrantAdmissionFailureRollsBackClaimAndLeavesNoGrant() {
        var token = previewForRace();
        when(suppliers.findByIdAndFactoryId("SUP", FACTORY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authorizeForLater(FACTORY,
                new ConfirmationProof(token.getToken(), token.getCommandDigest(), Instant.now().plusSeconds(300))))
                .isInstanceOf(Throwable.class);
        assertThat(tokens.findById(token.getId()).orElseThrow().getStatus())
                .isEqualTo(IntentPreviewToken.TokenStatus.PENDING);
        assertThat(grants.findById("preview-" + token.getId())).isEmpty();
    }

    @Test void resolveClaimFailureRollsBackIssuedGrantAndClaim() {
        var token = previewForRace();
        doReturn(false).when(previewTokenServiceSpy)
                .resolveClaim(anyString(), anyString(), eq(true), anyString());
        assertThatThrownBy(() -> service.authorizeForLater(FACTORY,
                new ConfirmationProof(token.getToken(), token.getCommandDigest(), Instant.now().plusSeconds(300))))
                .isInstanceOf(Throwable.class);
        assertThat(tokens.findById(token.getId()).orElseThrow().getStatus())
                .isEqualTo(IntentPreviewToken.TokenStatus.PENDING);
        assertThat(grants.findById("preview-" + token.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
    }

    @Test void revokedExecutionRefusalSurvivesActualServiceRollbackAndHasScopedSqlReadback() {
        var token = authorize();
        String id = ControlledPurchaseTaskExecutionService.grantId(token.getId());
        var initial = service.status(FACTORY, id);
        var revoked = service.revoke(FACTORY, id, initial.version(), "测试撤销，不再执行");
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_logs WHERE factory_id=? AND question_type='grant_refusal'", Long.class, FACTORY);
        assertThatThrownBy(() -> actualGrantController.executeLater(FACTORY, id,
                new com.cretas.aims.controller.AiPurchaseTaskGrantController.ExecuteLaterRequest(revoked.version())))
                .isInstanceOfAny(com.cretas.aims.exception.BusinessException.class, PurchaseTaskGrantService.GrantRejectedException.class)
                .hasMessage("grant is revoked");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_logs WHERE factory_id=? AND user_id=? AND question_type='grant_refusal' AND is_success=FALSE", Long.class, FACTORY, USER)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
        assertThat(grants.findByIdAndFactoryIdAndUserId(id, FACTORY, USER).orElseThrow().isConsumed()).isFalse();
    }

    @Test void actualControllerRejectsUnknownMalformedAndMissingAuthorityFieldsBeforeService() throws Exception {
        var serviceMock = mock(ControlledPurchaseTaskExecutionService.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new com.cretas.aims.controller.AiPurchaseTaskGrantController(serviceMock))
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();

        mvc.perform(post("/api/mobile/F-DG2/ai-purchase-task-grants/authorize-later")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proofToken\":\"p\",\"commandDigest\":\"d\",\"expiresAt\":\"2026-10-20T00:00:00Z\",\"factoryId\":\"EVIL\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/mobile/F-DG2/ai-purchase-task-grants/authorize-later")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proofToken\":\"p\",\"commandDigest\":\"d\",\"expiresAt\":\"broken\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/mobile/F-DG2/ai-purchase-task-grants/preview-1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(serviceMock);
    }

    @Test void expiredConfirmedExecutionWritesDurableRefusalWithoutOrderOrGrantConsumption() {
        var token = authorize();
        String id = ControlledPurchaseTaskExecutionService.grantId(token.getId());
        var initial = service.status(FACTORY, id);
        jdbc.update("UPDATE intent_preview_tokens SET expires_at=? WHERE id=? AND factory_id=? AND user_id=?",
                java.time.LocalDateTime.now().minusSeconds(1), token.getId(), FACTORY, USER);
        assertAuditedRefusal(id, initial.version(), USER);
    }

    @Test void currentPermissionRefusalWritesAuditAfterServiceTransactionEnds() {
        var token = authorize();
        String id = ControlledPurchaseTaskExecutionService.grantId(token.getId());
        var initial = service.status(FACTORY, id);
        when(policy.rehydrate(any())).thenReturn(Optional.empty());
        assertAuditedRefusal(id, initial.version(), USER);
    }

    @Test void differentCurrentUserCannotConsumeGrantButCanReadBackOwnRefusal() {
        var token = authorize();
        String id = ControlledPurchaseTaskExecutionService.grantId(token.getId());
        var initial = service.status(FACTORY, id);
        bindActor(OTHER_USER);
        assertAuditedRefusal(id, initial.version(), OTHER_USER);
    }

    @Test void commitTimePermissionRefusalRollsBackRealOrderButRetainsIndependentAudit() {
        var token = authorize();
        String id = ControlledPurchaseTaskExecutionService.grantId(token.getId());
        var initial = service.status(FACTORY, id);
        replacePurchaseBoundaryWithRealService(() -> when(policy.rehydrate(any())).thenReturn(Optional.empty()));
        assertAuditedRefusal(id, initial.version(), USER);
        verify(boundarySpy).createPurchaseOrder(eq(FACTORY), any(), eq(USER));
    }

    private void assertAuditedRefusal(String id, Long version, long actorId) {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_logs WHERE factory_id=? AND user_id=? AND question_type='grant_refusal'", Long.class, FACTORY, actorId);
        assertThatThrownBy(() -> actualGrantController.executeLater(FACTORY, id,
                new com.cretas.aims.controller.AiPurchaseTaskGrantController.ExecuteLaterRequest(version)))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_logs WHERE factory_id=? AND user_id=? AND question_type='grant_refusal' AND is_success=FALSE", Long.class, FACTORY, actorId)).isEqualTo(before + 1);
        var events = jdbc.queryForList("SELECT session_id,error_message,question FROM ai_audit_logs WHERE factory_id=? AND user_id=? AND question_type='grant_refusal' ORDER BY id DESC LIMIT 1", FACTORY, actorId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("session_id").toString()).startsWith("grant-refusal:");
        assertThat(events.get(0).get("question").toString()).contains(id);
        assertThat(events.get(0).get("error_message").toString()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items", Long.class)).isZero();
        assertThat(grants.findByIdAndFactoryIdAndUserId(id, FACTORY, USER).orElseThrow().isConsumed()).isFalse();
    }

    @Test void concurrentRealClaimAndDeferredAuthorizationHasOneTerminalWinner() throws Exception {
        replacePurchaseBoundaryWithRealService();
        IntentPreviewToken token = previewForRace();
        ConfirmationProof proof = new ConfirmationProof(token.getToken(), token.getCommandDigest(),
                token.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant());
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<String> deferredResult = new AtomicReference<>();
        AtomicReference<String> oneShotResult = new AtomicReference<>();
        Future<?> deferred = pool.submit(() -> {
            bindActor(USER);
            try {
                start.await(10, TimeUnit.SECONDS);
                var outcome = service.authorizeForLater(FACTORY, proof);
                deferredResult.set("DEFERRED:" + outcome.state());
            } catch (Throwable failure) {
                deferredResult.set("FAILED:" + failure.getMessage());
            } finally { RequestContextHolder.resetRequestAttributes(); }
        });
        Future<?> oneShot = pool.submit(() -> {
            bindActor(USER);
            try {
                start.await(10, TimeUnit.SECONDS);
                var claimRef = new AtomicReference<PreviewTokenService.ClaimResult>();
                tx.executeWithoutResult(status -> claimRef.set(
                        previewTokens.claimToken(proof.proofToken(), FACTORY, USER, proof.commandDigest())));
                var claim = claimRef.get();
                if (claim == null || !claim.isSuccess() || claim.getClaimId() == null) {
                    oneShotResult.set("FAILED:" + (claim == null ? "null claim" : claim.getMessage()));
                    return;
                }
                var outcomeRef = new AtomicReference<ControlledPurchaseTaskExecutionService.Outcome>();
                try {
                    tx.executeWithoutResult(status -> outcomeRef.set(
                            service.executeConfirmed(FACTORY, token.getId(), claim.getClaimId())));
                    tx.executeWithoutResult(status -> {
                        if (!previewTokens.resolveClaim(proof.proofToken(), claim.getClaimId(), true, "confirmed")) {
                            throw new IllegalStateException("resolve claim failed");
                        }
                    });
                    oneShotResult.set("ONESHOT:" + outcomeRef.get().state());
                } catch (Throwable failure) {
                    tx.executeWithoutResult(status -> previewTokens.resolveClaim(
                            proof.proofToken(), claim.getClaimId(), false, "race failure"));
                    throw new IllegalStateException("one-shot execution failed", failure);
                }
            } catch (Throwable failure) {
                oneShotResult.set("FAILED:" + failure.getMessage());
            } finally { RequestContextHolder.resetRequestAttributes(); }
        });
        deferred.get(30, TimeUnit.SECONDS); oneShot.get(30, TimeUnit.SECONDS); pool.shutdownNow();

        String deferredOutcome = deferredResult.get();
        String oneShotOutcome = oneShotResult.get();
        boolean deferredWon = deferredOutcome != null && deferredOutcome.startsWith("DEFERRED:");
        boolean oneShotWon = oneShotOutcome != null && oneShotOutcome.startsWith("ONESHOT:");
        assertThat(deferredWon ^ oneShotWon).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isLessThanOrEqualTo(1L);
        var persistedToken = tokens.findById(token.getId()).orElseThrow();
        var persistedGrant = grants.findById("preview-" + token.getId());
        if (deferredWon) {
            assertThat(persistedToken.getStatus()).isEqualTo(IntentPreviewToken.TokenStatus.CONFIRMED);
            assertThat(persistedGrant).isPresent().get().extracting(g -> g.isConsumed()).isEqualTo(false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
        } else {
            assertThat(persistedGrant).isPresent().get().extracting(g -> g.isConsumed()).isEqualTo(true);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(1L);
        }
    }

    private void bindActor(long userId) {
        var request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, FACTORY, "purchaser"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private IntentPreviewToken previewForRace() {
        var input = new HashMap<String, Object>(Map.of("supplierId", "SUP", "materialTypeId", "MAT", "quantity", "2", "unitPrice", "10", "taxRate", "13", "unit", "kg", "currency", "CNY", "amountCap", "22.60"));
        var canonical = service.previewParameters(FACTORY, input);
        return previewTokens.createBoundToken(new PreviewTokenService.BoundTokenRequest(FACTORY, USER, "purchaser", "PURCHASE_ORDER_CREATE", "采购", "purchase_order_create", "2.0.0", ToolExecutionMode.EXECUTE, "PURCHASE_ORDER", "preview", "CREATE", canonical, Map.of(), canonical, 300));
    }

    private void replacePurchaseBoundaryWithRealService() {
        replacePurchaseBoundaryWithRealService(() -> { });
    }

    private void replacePurchaseBoundaryWithRealService(Runnable afterWrite) {
        var lookup = materials;
        var real = new PurchaseServiceImpl(orders, orderItems, null, suppliers, lookup,
                null, null, null, null, null);
        ReflectionTestUtils.setField(real, "supplierMaterialRepository", supplierMaterials);
        boundarySpy = spy(real);
        doAnswer(invocation -> {
            var result = (com.cretas.aims.entity.inventory.PurchaseOrder) invocation.callRealMethod();
            em.flush();
            afterWrite.run();
            return result;
        }).when(boundarySpy).createPurchaseOrder(eq(FACTORY), any(), eq(USER));
        ReflectionTestUtils.setField(grantService, "purchaseService", boundarySpy);
    }

    private IntentPreviewToken authorize() {
        var input = new HashMap<String,Object>(Map.of("supplierId", "SUP", "materialTypeId", "MAT", "quantity", "2", "unitPrice", "10", "taxRate", "13", "unit", "kg", "currency", "CNY", "amountCap", "22.60"));
        var canonical = service.previewParameters(FACTORY, input);
        var token = previewTokens.createBoundToken(new PreviewTokenService.BoundTokenRequest(FACTORY, USER, "purchaser", "PURCHASE_ORDER_CREATE", "采购", "purchase_order_create", "2.0.0", ToolExecutionMode.EXECUTE, "PURCHASE_ORDER", "preview", "CREATE", canonical, Map.of(), canonical, 300));
        assertThat(service.authorizeForLater(FACTORY, new ConfirmationProof(token.getToken(), token.getCommandDigest(), Instant.now().plusSeconds(300))).state()).isEqualTo("PENDING");
        return token;
    }
}
