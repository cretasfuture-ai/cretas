package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.ai.AiPurchaseTaskGrantRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.security.*;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledPurchaseTaskExecutionContractTest {
    PurchaseTaskGrantService grants = mock(PurchaseTaskGrantService.class);
    AiPurchaseTaskGrantRepository grantRepo = mock(AiPurchaseTaskGrantRepository.class);
    IntentPreviewTokenRepository tokens = mock(IntentPreviewTokenRepository.class);
    ToolPrincipalPolicy policy = mock(ToolPrincipalPolicy.class);
    AuthenticatedToolPrincipalFactory factory = mock(AuthenticatedToolPrincipalFactory.class);
    RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    SupplierRepository suppliers = mock(SupplierRepository.class);
    PreviewTokenService preview = mock(PreviewTokenService.class);
    EntityManager em = mock(EntityManager.class);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<com.cretas.aims.ai.tool.ToolRegistry> toolProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    com.cretas.aims.ai.tool.ToolRegistry toolRegistry = mock(com.cretas.aims.ai.tool.ToolRegistry.class);
    com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository frontline = mock(com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository.class);
    ControlledPurchaseTaskExecutionService service = new ControlledPurchaseTaskExecutionService(grants, grantRepo,
            tokens, policy, factory, materials, suppliers, preview, mapper, em, toolProvider, frontline);
    ExecutionPrincipal principal = new ExecutionPrincipal("F-1", "FACTORY", "42", PrincipalType.USER,
            Set.of("purchaser"), Set.of("procurement:read_write"), Set.of());

    @BeforeEach void currentIdentity() {
        bind(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 42L, "F-1", "purchaser"));
        User activeUser = new User(); activeUser.setFactoryId("F-1"); activeUser.setIsActive(true);
        Factory activeFactory = new Factory(); activeFactory.setIsActive(true);
        when(em.find(User.class, 42L)).thenReturn(activeUser);
        when(em.find(Factory.class, "F-1")).thenReturn(activeFactory);
        when(factory.create("F-1",42L,"purchaser")).thenReturn(principal);
        when(policy.rehydrate(principal)).thenReturn(Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(principal, Map.of("canViewPrices",true))));
        RawMaterialType activeMaterial = new RawMaterialType(); activeMaterial.setIsActive(true); activeMaterial.setFactoryId("F-1");
        Supplier activeSupplier = new Supplier(); activeSupplier.setIsActive(true); activeSupplier.setFactoryId("F-1");
        when(materials.findByIdAndFactoryId("MAT-1","F-1")).thenReturn(Optional.of(activeMaterial));
        when(suppliers.findByIdAndFactoryId("SUP-1","F-1")).thenReturn(Optional.of(activeSupplier));
        when(toolProvider.getObject()).thenReturn(toolRegistry);
        when(toolRegistry.isToolEnabledForFactory("F-1","purchase_order_create")).thenReturn(true);
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    private void bind(AuthenticatedActor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    private Map<String,Object> input() {
        return new HashMap<>(Map.of("supplierId","SUP-1","materialTypeId","MAT-1","quantity","2",
                "unitPrice","10","taxRate","13.00","unit","kg","currency","CNY","amountCap","22.60"));
    }
    @Test void previewBindsThirteenPercentNetTaxAndGrossWithoutAnyPurchaseOrGrantWrite() {
        var parameters = service.previewParameters("F-1",input());
        assertThat(parameters.get("net").toString()).isEqualTo("20.00");
        assertThat(parameters.get("tax").toString()).isEqualTo("2.60");
        assertThat(parameters.get("gross").toString()).isEqualTo("22.60");
        assertThat(service.preview("F-1",parameters).get("status")).isEqualTo("PREVIEW");
        verifyNoInteractions(grants,grantRepo,preview);
    }
    @Test void forgedMapCannotSupplyMissingHttpPrincipalOrPermissions() {
        RequestContextHolder.resetRequestAttributes();
        var fake=input(); fake.put("userId",42L); fake.put("confirmed",true); fake.put("role","factory_super_admin");
        assertThatThrownBy(()->service.previewParameters("F-1",fake)).hasMessageContaining("可信登录主体");
        verifyNoInteractions(grants,grantRepo);
    }
    @Test void platformIdCollisionAndWrongFactoryAreRejected() {
        bind(new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,42L,"F-1","platform_admin"));
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("可信登录主体");
        bind(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,42L,"OTHER","purchaser"));
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("可信登录主体");
    }
    @Test void currentPermissionRemovalAndPriceDenialFailClosed() {
        when(policy.rehydrate(principal)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("授权不可用");
        when(policy.rehydrate(principal)).thenReturn(Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(principal,Map.of("canViewPrices",false))));
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("价格权限");
    }
    @Test void unsupportedScopeAndInsufficientGrossBudgetRejectBeforeIssuance() {
        for (var mutation:List.of(Map.entry("taxRate","9"),Map.entry("currency","USD"),Map.entry("unit","box"),Map.entry("amountCap","20.00"))) {
            var data=input(); data.put(mutation.getKey(),mutation.getValue());
            assertThatThrownBy(()->service.previewParameters("F-1",data)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(grants,grantRepo);
    }
    @Test void crossTenantMasterDataCannotBePurchased() {
        when(suppliers.findByIdAndFactoryId("SUP-1","F-1")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("不属于当前工厂");
    }
    @Test void callerClaimWithoutPersistedTokenCannotMintGrant() {
        when(em.find(IntentPreviewToken.class,99L,LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);
        assertThatThrownBy(()->service.executeConfirmed("F-1",99L,"client-claim")).hasMessageContaining("采购范围");
        verifyNoInteractions(grants,grantRepo);
    }
    @Test void disabledToolAndNewlyFrontlineDbRoleCannotReuseOldTokenRole() {
        when(toolRegistry.isToolEnabledForFactory("F-1","purchase_order_create")).thenReturn(false);
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("已停用");
        when(toolRegistry.isToolEnabledForFactory("F-1","purchase_order_create")).thenReturn(true);
        var changed = new ExecutionPrincipal("F-1","FACTORY","42",PrincipalType.USER,Set.of("operator"),Set.of("procurement:read_write"),Set.of());
        when(policy.rehydrate(principal)).thenReturn(Optional.of(new ToolPrincipalPolicy.RehydratedPrincipal(changed,Map.of("canViewPrices",true))));
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("一线角色");
        verify(frontline).findByFactoryIdAndRevokedAtIsNull("F-1");
    }
    @Test void inactiveMasterDataRejectsAndRecoveryIdsHaveOneCanonicalSpelling() {
        Supplier inactive = new Supplier(); inactive.setIsActive(false); inactive.setFactoryId("F-1");
        when(suppliers.findByIdAndFactoryId("SUP-1","F-1")).thenReturn(Optional.of(inactive));
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("已停用");
        for (String alias : List.of("preview-01","preview-+1","preview-0","preview--1")) {
            assertThatThrownBy(()->service.status("F-1",alias)).hasMessageContaining("标识无效");
        }
        verifyNoInteractions(grants);
    }
    @Test void wrongPersistedPreviewBindingRejectsAndPermissionOutageOnlyRedactsRecovery() {
        var grant = com.cretas.aims.entity.ai.AiPurchaseTaskGrant.builder().id("preview-1").factoryId("F-1").userId(42L)
                .toolName("purchase_order_create").descriptorVersion("2.0.0").previewTokenId(2L)
                .consumedAt(java.time.LocalDateTime.now()).resultPurchaseOrderId("PO-1").resultAmount(new java.math.BigDecimal("22.60"))
                .version(2L).build();
        when(grantRepo.findByIdAndFactoryIdAndUserId("preview-1","F-1",42L)).thenReturn(Optional.of(grant));
        assertThatThrownBy(()->service.status("F-1","preview-1")).hasMessageContaining("绑定不一致");
        grant.setPreviewTokenId(null);
        assertThatThrownBy(()->service.status("F-1","preview-1")).hasMessageContaining("绑定不一致");
        grant.setPreviewTokenId(1L);
        when(policy.rehydrate(principal)).thenThrow(new IllegalStateException("permission config offline"));
        var recovery = service.status("F-1","preview-1");
        assertThat(recovery.state()).isEqualTo("DRAFT"); assertThat(recovery.purchaseOrderId()).isEqualTo("PO-1");
        assertThat(recovery.grossAmount()).isNull();
        assertThatThrownBy(()->service.previewParameters("F-1",input())).hasMessageContaining("授权不可用");
    }
}
