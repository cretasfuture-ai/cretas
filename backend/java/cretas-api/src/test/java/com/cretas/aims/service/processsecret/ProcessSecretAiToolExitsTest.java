package com.cretas.aims.service.processsecret;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.dataop.ProductCreateTool;
import com.cretas.aims.ai.tool.impl.material.BomExpansionTool;
import com.cretas.aims.aspect.ProcessSecretToolOutputAspect;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 工具出口: 工具结果是字符串, HTTP 响应裁剪看不到。这里守三件事 ——
 * <ol>
 *   <li>挂在 ToolExecutor 上的切面真的裁剪了工具结果 (经真实 Spring AOP 代理调用 execute);</li>
 *   <li>「数量随意填」的 BOM 展开对无权的人一律不算;</li>
 *   <li>按已有产品建品 (建品预览) 不能把受保护产品当继承来源。</li>
 * </ol>
 * 判定服务是真的, 拒绝都要在审计里留下记录。
 */
class ProcessSecretAiToolExitsTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;
    static final long OPERATOR = 5L;

    final ObjectMapper json = new ObjectMapper();
    ProcessSecretAuditRecorder recorder;
    ProcessSecretAccessService access;
    ProcessSecretRecipeGuard guard;
    ProcessSecretToolOutputAspect aspect;

    @BeforeEach
    void setUp() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        access = new ProcessSecretAccessService(policies, grants, recorder);
        BomRecipeRepository recipes = mock(BomRecipeRepository.class);
        BomRecipe recipe = new BomRecipe();
        recipe.setId("r1");
        recipe.setFactoryId(F);
        recipe.setProductTypeId(SECRET);
        when(recipes.findById("r1")).thenReturn(Optional.of(recipe));
        guard = new ProcessSecretRecipeGuard(recipes, access);
        aspect = new ProcessSecretToolOutputAspect(com.cretas.aims.service.processsecret.ProcessSecretTestHooks.scrubber(access, recipes,
                mock(ProductionWorkflowInstanceRepository.class), mock(ProductionPlanRepository.class)), json);
    }

    static AuthenticatedActor actor(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    static Map<String, Object> context(long userId) {
        return Map.of("factoryId", F, "userId", userId, "userRole", "operator");
    }

    @SuppressWarnings("unchecked")
    <T> T proxied(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }

    /** 模拟一个读了配方明细的工具 (真实工具读库时由 Hibernate PostLoad 打标)。 */
    static class RequisitionLikeTool implements ToolExecutor {
        @Override public String getToolName() { return "requisition_like"; }
        @Override public String getDescription() { return "test"; }
        @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
        @Override public String execute(ToolCall toolCall, Map<String, Object> context) {
            BomRecipeItem item = new BomRecipeItem();
            item.setFactoryId(F);
            item.setRecipeId("r1");
            ProcessSecretReadTracker.record(item);
            return "{\"success\":true,\"data\":{\"items\":[{\"materialName\":\"秘方香料\","
                    + "\"inputShareRatio\":0.35,\"requiredQty\":42}]}}";
        }
    }

    @Test
    void toolOutputIsScrubbedThroughTheRealAopProxyForAnOperatorAndTheScopeIsClosedAfterwards() throws Exception {
        ToolExecutor tool = proxied(new RequisitionLikeTool());
        String raw;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OPERATOR))) {
            raw = tool.execute(ToolCall.of("1", "requisition_like", "{}"), context(OPERATOR));
        }
        JsonNode item = json.readTree(raw).at("/data/items/0");
        assertThat(item.get("inputShareRatio").isNull()).isTrue();
        assertThat(item.get("requiredQty").asInt()).isEqualTo(42);
        assertThat(item.get("processSecret").get("visibility").asText()).isEqualTo("TOTALS_ONLY");
        assertThat(ProcessSecretReadTracker.reads()).isEmpty();
        verify(recorder).record(eq(F), eq(SECRET), eq(actor(OPERATOR)), eq(ProcessSecretAction.VIEW_FULL),
                eq("DENY"), contains("inputShareRatio"), eq("AI tool requisition_like"));
    }

    @Test
    void theOwnerGetsTheToolOutputUnchanged() throws Exception {
        ToolExecutor tool = proxied(new RequisitionLikeTool());
        String raw;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OWNER))) {
            raw = tool.execute(ToolCall.of("1", "requisition_like", "{}"), context(OWNER));
        }
        assertThat(json.readTree(raw).at("/data/items/0/inputShareRatio").asDouble()).isEqualTo(0.35);
    }

    @Test
    void anUnidentifiedCallerIsTreatedAsHavingNoRightsAtAll() throws Exception {
        String raw = proxied(new RequisitionLikeTool()).execute(ToolCall.of("1", "x", "{}"), context(OPERATOR));
        JsonNode item = json.readTree(raw).at("/data/items/0");
        assertThat(item.get("inputShareRatio").isNull()).isTrue();
        assertThat(item.get("requiredQty").isNull()).isTrue();
    }

    // ------------------------------------------------------------ bom_expansion_calculate

    BomExpansionTool bomExpansionTool(BomExpansionService service) {
        BomExpansionTool tool = new BomExpansionTool();
        ReflectionTestUtils.setField(tool, "bomExpansionService", service);
        ReflectionTestUtils.setField(tool, "processSecretGuard", guard);
        ReflectionTestUtils.setField(tool, "objectMapper", json);
        return tool;
    }

    @Test
    void bomExpansionWithAFreelyChosenQuantityIsRefusedWithoutFullViewAndAudited() throws Exception {
        BomExpansionService service = mock(BomExpansionService.class);
        BomExpansionTool tool = bomExpansionTool(service);
        String raw;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OPERATOR))) {
            raw = tool.execute(ToolCall.of("1", "bom_expansion_calculate",
                    "{\"productTypeId\":\"P17\",\"quantity\":1,\"checkStock\":false}"), context(OPERATOR));
        }
        assertThat(raw).contains("RESTRICTED").doesNotContain("requiredQuantity");
        verify(service, never()).expandBOM(any(), any(), any());
        verify(recorder).record(eq(F), eq(SECRET), eq(actor(OPERATOR)), eq(ProcessSecretAction.VIEW_FULL),
                eq("DENY"), anyString(), eq("AI tool bom_expansion_calculate"));
    }

    @Test
    void bomExpansionStillWorksForTheOwner() throws Exception {
        BomExpansionService service = mock(BomExpansionService.class);
        MaterialRequirement req = new MaterialRequirement();
        req.setMaterialTypeId("M1");
        req.setMaterialTypeName("秘方香料");
        req.setRequiredQuantity(new BigDecimal("3.25"));
        when(service.expandBOM(eq(F), eq(SECRET), any())).thenReturn(List.of(req));
        String raw;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OWNER))) {
            raw = bomExpansionTool(service).execute(ToolCall.of("1", "bom_expansion_calculate",
                    "{\"productTypeId\":\"P17\",\"quantity\":1,\"checkStock\":false}"), context(OWNER));
        }
        assertThat(raw).contains("3.25");
    }

    // ------------------------------------------------------------ product_create (建品预览)

    ProductCreateTool productCreateTool(BomRecipeService bomRecipeService) {
        ProductCreateTool tool = new ProductCreateTool();
        ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
        ProductType source = new ProductType();
        source.setId(SECRET);
        source.setName("秘制牛肉");
        source.setFactoryId(F);
        source.setProductCategory("FINISHED_PRODUCT");
        when(productTypes.findByFactoryIdAndName(F, "秘制牛肉")).thenReturn(Optional.of(source));
        ReflectionTestUtils.setField(tool, "productTypeService", mock(ProductTypeService.class));
        ReflectionTestUtils.setField(tool, "productTypeRepository", productTypes);
        ReflectionTestUtils.setField(tool, "productWorkProcessService", mock(ProductWorkProcessService.class));
        ReflectionTestUtils.setField(tool, "bomRecipeService", bomRecipeService);
        ReflectionTestUtils.setField(tool, "processSecretGuard", guard);
        ReflectionTestUtils.setField(tool, "objectMapper", json);
        return tool;
    }

    static String createArgs() {
        return "{\"productName\":\"新品\",\"productCategory\":\"FINISHED_PRODUCT\",\"inheritFrom\":\"秘制牛肉\"}";
    }

    @Test
    void namingAProtectedProductAsInheritanceSourceIsRefusedBeforeItsRecipeIsRead() throws Exception {
        BomRecipeService bomRecipeService = mock(BomRecipeService.class);
        String raw;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OPERATOR))) {
            raw = productCreateTool(bomRecipeService).preview(ToolCall.of("1", "product_create", createArgs()),
                    context(OPERATOR));
        }
        assertThat(raw).contains("工艺已设为保密").doesNotContain("suggestedBom");
        verify(bomRecipeService, never()).getCurrentRecipe(any(), any());
        verify(recorder).record(eq(F), eq(SECRET), eq(actor(OPERATOR)), eq(ProcessSecretAction.VIEW_FULL),
                eq("DENY"), anyString(), contains("product_create"));
    }

    @Test
    void theOwnerMayInheritFromTheirOwnProtectedProduct() throws Exception {
        BomRecipeService bomRecipeService = mock(BomRecipeService.class);
        String raw;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OWNER))) {
            raw = productCreateTool(bomRecipeService).preview(ToolCall.of("1", "product_create", createArgs()),
                    context(OWNER));
        }
        assertThat(json.readTree(raw).at("/data/inheritFromProduct").asText()).isEqualTo("秘制牛肉");
        verify(bomRecipeService).getCurrentRecipe(F, SECRET);
    }
}
