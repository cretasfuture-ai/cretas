package com.cretas.aims.security;

import com.cretas.aims.annotation.ProcessSecretGuard;
import com.cretas.aims.dto.bom.BomItemUsage;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 第二次复审 (H-A/H-B/H-C/M-1/M-2/M-3/L2): 多产出计划、领料数据、AI 工具、改手机号、全厂批量、解析失败。 */
class ProcessSecretRereviewSecurityTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final String ANCHOR = "__RAW_WORKFLOW_OWNER__RAW-1";
    static final long OWNER = 10L;

    ProcessSecretPolicyRepository policies;
    ProcessSecretAccessService access;
    BomRecipeRepository recipes;
    ProductionPlanRepository plans;
    ProductionPlan multiOutput;

    @BeforeEach
    void setUp() {
        policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        access = new ProcessSecretAccessService(policies, grants, mock(ProcessSecretAuditRecorder.class));
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        when(policies.existsByFactoryIdAndOwnerUserId(F, OWNER)).thenReturn(true);

        recipes = mock(BomRecipeRepository.class);
        BomRecipe recipe = new BomRecipe();
        recipe.setId("r1");
        recipe.setFactoryId(F);
        recipe.setProductTypeId(SECRET);
        when(recipes.findById("r1")).thenReturn(Optional.of(recipe));

        // 多产出计划: productTypeId 是原料锚点, 受保护成品只出现在「按产品钉住的配方」里
        plans = mock(ProductionPlanRepository.class);
        multiOutput = new ProductionPlan();
        multiOutput.setId("plan-m");
        multiOutput.setFactoryId(F);
        multiOutput.setProductTypeId(ANCHOR);
        Map<String, String> pinned = new LinkedHashMap<>();
        pinned.put("PLAIN", "r-plain");
        pinned.put(SECRET, "r1");
        multiOutput.setSelectedBomRecipeIdsByProduct(pinned);
        when(plans.findByIdAndFactoryId("plan-m", F)).thenReturn(Optional.of(multiOutput));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "warehouse_manager");
    }

    static void actAs(AuthenticatedActor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    static void assertCode(int code, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertEquals(code, e.getCode(), e.getMessage());
    }

    static ProcessSecretGuard guardOf(Class<?> controller, String method) {
        for (Method m : controller.getDeclaredMethods()) {
            if (m.getName().equals(method) && m.isAnnotationPresent(ProcessSecretGuard.class)) {
                return m.getAnnotation(ProcessSecretGuard.class);
            }
        }
        throw new AssertionError(controller.getSimpleName() + "#" + method + " has no @ProcessSecretGuard");
    }

    ProcessSecretGuardInterceptor interceptor(ProductionBatchRepository batches,
                                              FactoryMaterialRequisitionRepository requisitions) {
        return new ProcessSecretGuardInterceptor(access, recipes, mock(BomRecipeItemRepository.class),
                mock(ProductProcessWorkflowRepository.class), mock(BomVersionRepository.class), batches, plans,
                requisitions);
    }

    @Test
    void multiOutputPlanResolvesEveryPinnedProductNotJustTheRawAnchor() {
        ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
        ProductionBatch batch = new ProductionBatch();
        batch.setId(300L);
        batch.setFactoryId(F);
        batch.setProductTypeId(ANCHOR);
        batch.setProductionPlanId("plan-m");
        when(batches.findByIdAndFactoryId(300L, F)).thenReturn(Optional.of(batch));
        FactoryMaterialRequisitionRepository requisitions = mock(FactoryMaterialRequisitionRepository.class);
        FactoryMaterialRequisition req = new FactoryMaterialRequisition();
        req.setId("mr-1");
        req.setFactoryId(F);
        req.setProductionPlanId("plan-m");
        when(requisitions.findById("mr-1")).thenReturn(Optional.of(req));
        ProcessSecretGuardInterceptor guard = interceptor(batches, requisitions);

        Map<String, String> vars = new HashMap<>(Map.of("factoryId", F, "planId", "plan-m", "id", "mr-1",
                "batchId", "300"));
        MockHttpServletRequest http = new MockHttpServletRequest();
        for (String print : List.of("printProductionWorkOrder", "printBatchingSheet", "printProductionDocumentPackage",
                "printConsolidatedMaterialRequisition", "printMaterialRequisition")) {
            assertThat(guard.resolveProducts(guardOf(com.cretas.aims.controller.PrintController.class, print),
                    F, vars, http)).as(print).contains(SECRET);
        }
        assertThat(guard.resolveProducts(guardOf(
                com.cretas.aims.controller.ProductProcessWorkflowRuntimeController.class, "getRuntime"), F, vars, http))
                .contains(SECRET, ANCHOR);
    }

    @Test
    void planListParameterIsCappedBeforeAnyLookup() {
        ProcessSecretGuardInterceptor guard = interceptor(mock(ProductionBatchRepository.class),
                mock(FactoryMaterialRequisitionRepository.class));
        MockHttpServletRequest http = new MockHttpServletRequest();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 21; i++) ids.add("p" + i);
        http.setParameter("planIds", String.join(",", ids));
        assertCode(400, () -> guard.resolveProducts(guardOf(com.cretas.aims.controller.PrintController.class,
                "printProductionWorkOrderMulti"), F, Map.of("factoryId", F), http));
        verify(plans, never()).findByIdAndFactoryId(eq("p0"), anyString());
    }

    ServletServerHttpRequest apiRequest(AuthenticatedActor actor) {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/api/mobile/" + F + "/material-requisitions/x");
        raw.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        raw.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("factoryId", F));
        return new ServletServerHttpRequest(raw);
    }

    @Test
    void requisitionJsonForProtectedPlanIsSummarisedAndPlainRequisitionIsNot() {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProcessSecretResponseAdvice advice = new ProcessSecretResponseAdvice(access, recipes, plans, new com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber(access, recipes, org.mockito.Mockito.mock(com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository.class), org.mockito.Mockito.mock(com.cretas.aims.repository.ProductionPlanRepository.class)), json);
        FactoryMaterialRequisition secretReq = new FactoryMaterialRequisition();
        secretReq.setId("mr-1");
        secretReq.setFactoryId(F);
        secretReq.setProductionPlanId("plan-m");
        secretReq.setProductionPlanNumber("PP-001");
        secretReq.setRemarks("香料 3.25kg");
        FactoryMaterialRequisition manual = new FactoryMaterialRequisition();
        manual.setId("mr-2");
        manual.setFactoryId(F);
        manual.setRemarks("手工领料 9.9kg");

        // 控制器返回 ResponseEntity<Map>, advice 收到的是 Map 本体
        Object out = advice.beforeBodyWrite(Map.of("success", true, "data", List.of(secretReq, manual)), null,
                MediaType.APPLICATION_JSON, null, apiRequest(user(77)), null);
        JsonNode data = json.valueToTree(out).get("data");
        assertThat(data.get(0).get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        assertThat(data.get(0).toString()).doesNotContain("3.25");
        // 阳性对照: 不关联保密产品的领料单原样返回
        assertThat(data.get(1).toString()).contains("9.9kg").doesNotContain("processSecret");
    }

    @Test
    void unresolvableRecipeLinkedObjectIsRestrictedOnlyWhereTheFactoryUsesProtection() {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProcessSecretResponseAdvice advice = new ProcessSecretResponseAdvice(access, recipes, plans, new com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber(access, recipes, org.mockito.Mockito.mock(com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository.class), org.mockito.Mockito.mock(com.cretas.aims.repository.ProductionPlanRepository.class)), json);
        BomVersion orphan = new BomVersion();
        orphan.setId("v-orphan");
        orphan.setFactoryId(F);
        orphan.setBomRecipeId("deleted-recipe");
        orphan.setSnapshotJson(Map.of("materialName", "秘方香料"));
        Object out = advice.beforeBodyWrite(ApiResponse.success(orphan), null, MediaType.APPLICATION_JSON, null,
                apiRequest(user(77)), null);
        assertThat(json.valueToTree(out).toString()).contains("RESTRICTED").doesNotContain("秘方香料");

        // 阳性对照: 没有任何保密策略的工厂, 同样的孤儿对象不受影响
        when(policies.findByFactoryIdOrderByProductTypeIdAsc("F-PLAIN")).thenReturn(List.of());
        orphan.setFactoryId("F-PLAIN");
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/api/mobile/F-PLAIN/bom/versions/x");
        raw.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 5L, "F-PLAIN", "operator"));
        raw.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("factoryId", "F-PLAIN"));
        Object plain = advice.beforeBodyWrite(ApiResponse.success(orphan), null, MediaType.APPLICATION_JSON, null,
                new ServletServerHttpRequest(raw), null);
        assertThat(((ApiResponse<?>) plain).getData()).isSameAs(orphan);
    }

    @Test
    void factoryWideBatchSkipsDeniedProtectedRecipesInsteadOfBlockingEverything() {
        ProcessSecretRecipeGuard guard = new ProcessSecretRecipeGuard(recipes, access);
        BomRecipe secret = recipes.findById("r1").orElseThrow();
        BomRecipe plain = new BomRecipe();
        plain.setId("r-plain");
        plain.setFactoryId(F);
        plain.setProductTypeId("PLAIN");
        when(policies.findByFactoryIdAndProductTypeId(F, "PLAIN")).thenReturn(Optional.empty());
        actAs(user(77));
        ProcessSecretRecipeGuard.Partition implicit = guard.partitionForEdit(F, List.of(secret, plain), false, "batch");
        assertThat(implicit.allowed()).containsExactly(plain);
        assertThat(implicit.skipped()).containsOnlyKeys("r1");
        assertCode(403, () -> guard.partitionForEdit(F, List.of(secret, plain), true, "batch"));
        actAs(user(OWNER));
        assertThat(guard.partitionForEdit(F, List.of(secret, plain), false, "batch").allowed())
                .containsExactly(secret, plain);
    }

    @Test
    void reverseQueryToolNeverHandsProtectedUsageToTheModel() throws Exception {
        com.cretas.aims.service.bom.BomReverseQueryService reverse =
                mock(com.cretas.aims.service.bom.BomReverseQueryService.class);
        BomItemUsage secret = new BomItemUsage();
        secret.setRecipeId("r1");
        secret.setProductTypeId(SECRET);
        secret.setStandardQuantity(new BigDecimal("3.25"));
        BomItemUsage plain = new BomItemUsage();
        plain.setRecipeId("r-plain");
        plain.setProductTypeId("PLAIN");
        plain.setStandardQuantity(new BigDecimal("9.9"));
        when(reverse.findUsageByMaterial(F, "SPICE")).thenReturn(List.of(secret, plain));
        com.cretas.aims.ai.tool.impl.bom.BomReverseQueryTool tool = new com.cretas.aims.ai.tool.impl.bom.BomReverseQueryTool();
        ReflectionTestUtils.setField(tool, "reverseQueryService", reverse);
        ReflectionTestUtils.setField(tool, "processSecretGuard", new ProcessSecretRecipeGuard(recipes, access));
        Method exec = tool.getClass().getDeclaredMethod("doExecute", String.class, Map.class, Map.class);
        exec.setAccessible(true);

        actAs(user(77));
        String result = new ObjectMapper().writeValueAsString(
                exec.invoke(tool, F, Map.of("mode", "USAGE", "materialId", "SPICE"), Map.of()));
        assertThat(result).contains("RESTRICTED").doesNotContain("3.25").contains("9.9");

        actAs(user(OWNER));
        String full = new ObjectMapper().writeValueAsString(
                exec.invoke(tool, F, Map.of("mode", "USAGE", "materialId", "SPICE"), Map.of()));
        assertThat(full).contains("3.25");
    }

    @Test
    void adminCannotChangeOwnersPhoneButCanEditOtherFieldsAndOtherUsers() {
        com.cretas.aims.service.UserService users = mock(com.cretas.aims.service.UserService.class);
        com.cretas.aims.controller.UserController controller = new com.cretas.aims.controller.UserController(users,
                mock(com.cretas.aims.service.MobileService.class),
                mock(com.cretas.aims.repository.inventory.PurchaseOrderRepository.class));
        UserRepository userRepo = mock(UserRepository.class);
        User owner = new User();
        owner.setId(OWNER);
        owner.setFactoryId(F);
        owner.setPhone("13800000000");
        when(userRepo.findByIdAndFactoryId(OWNER, F)).thenReturn(Optional.of(owner));
        ReflectionTestUtils.setField(controller, "processSecretAccessService", access);
        ReflectionTestUtils.setField(controller, "userRepository", userRepo);
        actAs(user(11));

        CreateUserRequest changePhone = new CreateUserRequest();
        changePhone.setPhone("13900000000");
        assertCode(403, () -> controller.updateUser(F, OWNER, changePhone));
        verify(users, never()).updateUser(anyString(), anyLong(), any());

        // 阳性对照: 同号 (表单整份提交) 不算改手机号; 改别人手机号照常
        CreateUserRequest samePhone = new CreateUserRequest();
        samePhone.setPhone("13800000000");
        controller.updateUser(F, OWNER, samePhone);
        controller.updateUser(F, 30L, changePhone);
        verify(users).updateUser(F, OWNER, samePhone);
        verify(users).updateUser(F, 30L, changePhone);
    }

    // ------------------------------------------------------------- third review follow-ups

    @Test
    void requisitionWhosePlanWasDeletedIsRestrictedButManualOneIsNot() {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        ProcessSecretResponseAdvice advice = new ProcessSecretResponseAdvice(access, recipes, plans, new com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber(access, recipes, org.mockito.Mockito.mock(com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository.class), org.mockito.Mockito.mock(com.cretas.aims.repository.ProductionPlanRepository.class)), json);
        FactoryMaterialRequisition orphan = new FactoryMaterialRequisition();
        orphan.setId("mr-9");
        orphan.setFactoryId(F);
        orphan.setProductionPlanId("plan-deleted");
        orphan.setRemarks("香料 3.25kg");
        Object out = advice.beforeBodyWrite(Map.of("success", true, "data", orphan), null,
                MediaType.APPLICATION_JSON, null, apiRequest(user(77)), null);
        assertThat(json.valueToTree(out).toString()).contains("RESTRICTED").doesNotContain("3.25");

        FactoryMaterialRequisitionRepository requisitions = mock(FactoryMaterialRequisitionRepository.class);
        when(requisitions.findById("mr-9")).thenReturn(Optional.of(orphan));
        ProcessSecretGuardInterceptor guard = interceptor(mock(ProductionBatchRepository.class), requisitions);
        assertCode(403, () -> guard.resolveProducts(guardOf(com.cretas.aims.controller.PrintController.class,
                "printMaterialRequisition"), F, Map.of("factoryId", F, "id", "mr-9"), new MockHttpServletRequest()));

        // 阳性对照: 手工领料单 (无计划) 照常可打印、可读
        FactoryMaterialRequisition manual = new FactoryMaterialRequisition();
        manual.setId("mr-10");
        manual.setFactoryId(F);
        when(requisitions.findById("mr-10")).thenReturn(Optional.of(manual));
        assertThat(guard.resolveProducts(guardOf(com.cretas.aims.controller.PrintController.class,
                "printMaterialRequisition"), F, Map.of("factoryId", F, "id", "mr-10"), new MockHttpServletRequest()))
                .isEmpty();
    }

    @Test
    void factoryWideBatchSkipsProtectedRecipeWhenAllowCannotBeAudited() {
        ProcessSecretAuditRecorder failing = mock(ProcessSecretAuditRecorder.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down")).when(failing)
                .record(any(), any(), any(), any(), any(), any(), any());
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretAccessService strict = new ProcessSecretAccessService(policies, grants, failing);
        ProcessSecretRecipeGuard guard = new ProcessSecretRecipeGuard(recipes, strict);
        actAs(user(OWNER));
        ProcessSecretRecipeGuard.Partition p = guard.partitionForEdit(F,
                List.of(recipes.findById("r1").orElseThrow()), false, "batch");
        assertThat(p.allowed()).isEmpty();
        assertThat(p.skipped()).containsOnlyKeys("r1");
    }

    @Test
    void emptyPhoneOnOwnerWithoutPhoneIsNotTreatedAsAChange() {
        com.cretas.aims.service.UserService users = mock(com.cretas.aims.service.UserService.class);
        com.cretas.aims.controller.UserController controller = new com.cretas.aims.controller.UserController(users,
                mock(com.cretas.aims.service.MobileService.class),
                mock(com.cretas.aims.repository.inventory.PurchaseOrderRepository.class));
        UserRepository userRepo = mock(UserRepository.class);
        User owner = new User();
        owner.setId(OWNER);
        owner.setFactoryId(F);
        owner.setPhone(null);
        when(userRepo.findByIdAndFactoryId(OWNER, F)).thenReturn(Optional.of(owner));
        ReflectionTestUtils.setField(controller, "processSecretAccessService", access);
        ReflectionTestUtils.setField(controller, "userRepository", userRepo);
        actAs(user(11));
        CreateUserRequest blank = new CreateUserRequest();
        blank.setPhone("");
        controller.updateUser(F, OWNER, blank);
        verify(users).updateUser(F, OWNER, blank);
    }

    @Test
    void replaceAnalysisTotalsOnlyCoverVisibleRecipes() throws Exception {
        com.cretas.aims.service.bom.BomReverseQueryService reverse =
                mock(com.cretas.aims.service.bom.BomReverseQueryService.class);
        com.cretas.aims.dto.bom.MaterialReplacementImpact impact = new com.cretas.aims.dto.bom.MaterialReplacementImpact();
        com.cretas.aims.dto.bom.MaterialReplacementImpact.RecipeImpact hidden =
                new com.cretas.aims.dto.bom.MaterialReplacementImpact.RecipeImpact();
        hidden.setRecipeId("r1");
        hidden.setCostDelta(new BigDecimal("6.50"));
        com.cretas.aims.dto.bom.MaterialReplacementImpact.RecipeImpact visible =
                new com.cretas.aims.dto.bom.MaterialReplacementImpact.RecipeImpact();
        visible.setRecipeId("r-plain");
        visible.setCostDelta(new BigDecimal("1.25"));
        impact.setRecipeBreakdown(new ArrayList<>(List.of(hidden, visible)));
        impact.setTotalCostDelta(new BigDecimal("7.75"));
        impact.setAffectedRecipeCount(2);
        when(reverse.analyzeReplacement(F, "SPICE", "SPICE-2")).thenReturn(impact);
        com.cretas.aims.ai.tool.impl.bom.BomReverseQueryTool tool = new com.cretas.aims.ai.tool.impl.bom.BomReverseQueryTool();
        ReflectionTestUtils.setField(tool, "reverseQueryService", reverse);
        ReflectionTestUtils.setField(tool, "processSecretGuard", new ProcessSecretRecipeGuard(recipes, access));
        Method exec = tool.getClass().getDeclaredMethod("doExecute", String.class, Map.class, Map.class);
        exec.setAccessible(true);
        actAs(user(77));
        exec.invoke(tool, F, Map.of("mode", "REPLACE_ANALYSIS", "materialId", "SPICE", "newMaterialId", "SPICE-2"),
                Map.of());
        // 「总差额 − 可见之和」不能再推出隐藏配方的 6.50
        assertThat(impact.getTotalCostDelta()).isEqualByComparingTo("1.25");
        assertThat(impact.getAffectedRecipeCount()).isEqualTo(1);
        assertThat(impact.getRecipeBreakdown()).extracting(
                com.cretas.aims.dto.bom.MaterialReplacementImpact.RecipeImpact::getRecipeId).containsExactly("r-plain");
    }
}
