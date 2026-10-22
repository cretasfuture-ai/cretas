package com.cretas.aims.security;

import com.cretas.aims.annotation.ProcessSecretGuard;
import com.cretas.aims.dto.bom.BomItemUsage;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.orchestration.BomTreeNode;
import com.cretas.aims.dto.orchestration.BomTreeResult;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.service.orchestration.RecursiveBomExpansionService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审查第二轮 (H1/H2/H3/M1/M2): 台账外的读出口、服务层写入口、凭证接管、跨厂令牌快路径。
 * 判定服务是真的 (桩只在仓库层), 断言落在最终行为上。
 */
class ProcessSecretSecondaryExitsSecurityTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;

    ProcessSecretPolicyRepository policies;
    ProcessSecretGrantRepository grants;
    ProcessSecretAccessService access;
    BomRecipeRepository recipes;

    @BeforeEach
    void setUp() {
        policies = mock(ProcessSecretPolicyRepository.class);
        grants = mock(ProcessSecretGrantRepository.class);
        recipes = mock(BomRecipeRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        access = new ProcessSecretAccessService(policies, grants, mock(ProcessSecretAuditRecorder.class));
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        when(policies.existsByFactoryIdAndOwnerUserId(F, OWNER)).thenReturn(true);
        BomRecipe recipe = new BomRecipe();
        recipe.setId("r1");
        recipe.setFactoryId(F);
        recipe.setProductTypeId(SECRET);
        when(recipes.findById("r1")).thenReturn(Optional.of(recipe));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "factory_super_admin");
    }

    static void assertCode(int code, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertEquals(code, e.getCode(), e.getMessage());
    }

    /** 让 {@link AuthenticatedActor#fromCurrentExecution()} 读到这个身份 (服务层入口就是这么取的)。 */
    static void actAs(AuthenticatedActor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    // ------------------------------------------------------------- H1: interceptor sources

    static ProcessSecretGuard guardOf(Class<?> controller, String method) {
        for (Method m : controller.getDeclaredMethods()) {
            if (m.getName().equals(method) && m.isAnnotationPresent(ProcessSecretGuard.class)) {
                return m.getAnnotation(ProcessSecretGuard.class);
            }
        }
        throw new AssertionError(controller.getSimpleName() + "#" + method + " has no @ProcessSecretGuard");
    }

    @Test
    void newGuardSourcesResolveTheProductFromServerSideRecords() {
        BomVersionRepository versions = mock(BomVersionRepository.class);
        ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
        ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
        FactoryMaterialRequisitionRepository requisitions = mock(FactoryMaterialRequisitionRepository.class);
        ProcessSecretGuardInterceptor interceptor = new ProcessSecretGuardInterceptor(access, recipes,
                mock(BomRecipeItemRepository.class), mock(ProductProcessWorkflowRepository.class),
                versions, batches, plans, requisitions);

        BomVersion version = new BomVersion();
        version.setId("v1");
        version.setFactoryId(F);
        version.setBomRecipeId("r1");
        when(versions.findById("v1")).thenReturn(Optional.of(version));
        ProductionBatch batch = new ProductionBatch();
        batch.setId(300L);
        batch.setFactoryId(F);
        batch.setProductTypeId(SECRET);
        when(batches.findByIdAndFactoryId(300L, F)).thenReturn(Optional.of(batch));
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-1");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        when(plans.findByIdAndFactoryId("plan-1", F)).thenReturn(Optional.of(plan));
        FactoryMaterialRequisition req = new FactoryMaterialRequisition();
        req.setId("mr-1");
        req.setFactoryId(F);
        req.setProductionPlanId("plan-1");
        when(requisitions.findById("mr-1")).thenReturn(Optional.of(req));

        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setParameter("planIds", "plan-1,plan-unknown");
        Map<String, String> vars = new HashMap<>(Map.of("factoryId", F, "versionId", "v1", "batchId", "300",
                "planId", "plan-1", "id", "mr-1", "bomRecipeId", "r1"));

        assertThat(interceptor.resolveProducts(guardOf(com.cretas.aims.controller.BomVersionController.class,
                "getById"), F, vars, http)).containsExactly(SECRET);
        assertThat(interceptor.resolveProducts(guardOf(
                com.cretas.aims.controller.ProductProcessWorkflowRuntimeController.class, "getRuntime"), F, vars, http))
                .containsExactly(SECRET);
        for (String print : List.of("printProductionWorkOrder", "printBatchingSheet",
                "printProductionDocumentPackage", "printMaterialRequisition", "printProductionWorkOrderMulti")) {
            assertThat(interceptor.resolveProducts(guardOf(com.cretas.aims.controller.PrintController.class, print),
                    F, vars, http)).as(print).containsExactly(SECRET);
        }
        // 跨厂: 版本 / 领料单属于别的工厂 → 解析不出产品 (交给业务报 404, 不泄露存在性)
        version.setFactoryId("F-B");
        req.setFactoryId("F-B");
        assertThat(interceptor.resolveProducts(guardOf(com.cretas.aims.controller.BomVersionController.class,
                "getById"), F, vars, http)).isEmpty();
        assertThat(interceptor.resolveProducts(guardOf(com.cretas.aims.controller.PrintController.class,
                "printMaterialRequisition"), F, vars, http)).isEmpty();
    }

    // ------------------------------------------------------------- H1: response summariser, M2

    ServletServerHttpRequest apiRequest(AuthenticatedActor actor, String pathFactory) {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/api/mobile/" + pathFactory + "/bom/x");
        raw.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        raw.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("factoryId", pathFactory));
        return new ServletServerHttpRequest(raw);
    }

    @Test
    void versionSnapshotsAndReverseQueryUsageAreSummarisedForNonOwners() {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProcessSecretResponseAdvice advice = new ProcessSecretResponseAdvice(access, recipes, json);
        BomVersion version = new BomVersion();
        version.setId("v1");
        version.setFactoryId(F);
        version.setBomRecipeId("r1");
        version.setSnapshotJson(Map.of("items", List.of(Map.of("materialName", "秘方香料", "standardQuantity", 3.25))));
        BomItemUsage usage = new BomItemUsage();
        usage.setRecipeId("r1");
        usage.setProductTypeId(SECRET);
        usage.setStandardQuantity(new BigDecimal("3.25"));
        usage.setYieldRate(new BigDecimal("87.31"));

        Object out = advice.beforeBodyWrite(ApiResponse.success(Map.of("v", version, "u", List.of(usage))), null,
                MediaType.APPLICATION_JSON, null, apiRequest(user(77), F), null);
        JsonNode data = json.valueToTree(out).get("data");
        assertThat(data.get("v").get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        assertThat(data.get("u").get(0).get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        assertThat(data.toString()).doesNotContain("秘方香料").doesNotContain("3.25").doesNotContain("87.31");

        // 阳性对照: 负责人看到完整快照
        Object full = advice.beforeBodyWrite(ApiResponse.success(version), null, MediaType.APPLICATION_JSON, null,
                apiRequest(user(OWNER), F), null);
        assertThat(json.valueToTree(full).toString()).contains("秘方香料");
    }

    @Test
    void crossFactoryTokenIsStillSummarisedByPathFactory() {
        // 审查 M2: factoryId=PLATFORM 的非平台令牌, 身份工厂下没有策略, 不能因此跳过裁剪
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProcessSecretResponseAdvice advice = new ProcessSecretResponseAdvice(access, recipes, json);
        when(policies.findByFactoryIdOrderByProductTypeIdAsc("PLATFORM")).thenReturn(List.of());
        AuthenticatedActor crossFactory = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 5L,
                "PLATFORM", "factory_super_admin");
        BomRecipe recipe = recipes.findById("r1").orElseThrow();
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId("r1");
        item.setFactoryId(F);
        item.setMaterialName("秘方香料");
        recipe.setItems(List.of(item));
        Object out = advice.beforeBodyWrite(ApiResponse.success(recipe), null, MediaType.APPLICATION_JSON, null,
                apiRequest(crossFactory, F), null);
        assertThat(json.valueToTree(out).toString()).contains("RESTRICTED").doesNotContain("秘方香料");
    }

    // ------------------------------------------------------------- H1: recursive tree

    @Test
    void protectedSemiFinishedChildIsNotExpandedForNonOwner() {
        BomRecipeItemRepository items = mock(BomRecipeItemRepository.class);
        com.cretas.aims.service.canvas.ThresholdResolverService thresholds =
                mock(com.cretas.aims.service.canvas.ThresholdResolverService.class);
        when(thresholds.getInteger(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(10);
        BomRecipeItem semi = new BomRecipeItem();
        semi.setSubProductTypeId(SECRET);
        semi.setMaterialName("秘制半成品");
        semi.setStandardQuantity(new BigDecimal("2"));
        BomRecipeItem spice = new BomRecipeItem();
        spice.setMaterialTypeId("SPICE-1");
        spice.setMaterialName("秘方香料");
        spice.setStandardQuantity(new BigDecimal("3.25"));
        when(items.findCurrentByProduct(F, "ROOT")).thenReturn(List.of(semi));
        when(items.findCurrentByProduct(F, SECRET)).thenReturn(List.of(spice));
        RecursiveBomExpansionService service = new RecursiveBomExpansionService(items,
                mock(com.cretas.aims.repository.MaterialBatchRepository.class),
                mock(com.cretas.aims.repository.ProductTypeRepository.class),
                mock(com.cretas.aims.repository.RawMaterialTypeRepository.class), thresholds, access);

        actAs(user(77));
        BomTreeResult restricted = service.expandTree(F, "ROOT", BigDecimal.ONE);
        BomTreeNode child = restricted.getRoot().getChildren().get(0);
        assertThat(child.isProcessSecretRestricted()).isTrue();
        assertThat(child.getChildren()).isEmpty();

        actAs(user(OWNER));
        BomTreeResult full = service.expandTree(F, "ROOT", BigDecimal.ONE);
        BomTreeNode ownerChild = full.getRoot().getChildren().get(0);
        assertThat(ownerChild.isProcessSecretRestricted()).isFalse();
        assertThat(ownerChild.getChildren()).extracting(BomTreeNode::getName).containsExactly("秘方香料");
    }

    // ------------------------------------------------------------- H2: service-level writes

    @Test
    void recipeGuardBlocksVersionAndBatchWritesOnProtectedRecipes() {
        ProcessSecretRecipeGuard guard = new ProcessSecretRecipeGuard(recipes, access);
        actAs(user(77));
        assertCode(403, () -> guard.requireRecipe(F, "r1", ProcessSecretAction.EDIT, "BomVersion.approve"));
        assertCode(403, () -> guard.requireRecipes(F, List.of(recipes.findById("r1").orElseThrow()),
                ProcessSecretAction.EDIT, "bom batch"));
        assertThat(guard.permitsFullContent(F, "r1", "AI")).isFalse();
        actAs(null);
        assertCode(403, () -> guard.requireRecipe(F, "r1", ProcessSecretAction.EDIT, "no actor"));
        actAs(user(OWNER));
        guard.requireRecipe(F, "r1", ProcessSecretAction.EDIT, "owner");
        assertThat(guard.permitsFullContent(F, "r1", "AI")).isTrue();
    }

    // ------------------------------------------------------------- H3: credential takeover

    @Test
    void adminsCannotResetCredentialsOfOwnersOrGranteesButSelfServiceWorks() {
        AuthenticatedActor otherAdmin = user(11);
        assertCode(403, () -> access.requireAdminMayChangeCredentials(otherAdmin, F, OWNER));
        assertCode(403, () -> access.requireAdminMayChangeCredentials(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, OWNER, F, "super_admin"), F, OWNER));
        access.requireAdminMayChangeCredentials(user(OWNER), F, OWNER);
        when(grants.countActiveForGrantee(eq(F), eq(ProcessSecretGranteeType.FACTORY_USER), eq(20L), any()))
                .thenReturn(1L);
        assertCode(403, () -> access.requireAdminMayChangeCredentials(otherAdmin, F, 20L));
        // 阳性对照: 普通员工照常可由管理员重置
        access.requireAdminMayChangeCredentials(otherAdmin, F, 30L);
    }
}
