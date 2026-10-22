package com.cretas.aims.controller;

import com.cretas.aims.dto.bom.BomSeasoningResponse;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.ProcessSecretGuardInterceptor;
import com.cretas.aims.security.ProcessSecretResponseAdvice;
import com.cretas.aims.service.bom.BomCopyService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 Spring MVC 链路 (路由 → 拦截器 → controller → 响应 advice → 全局异常处理) 上的工艺保密:
 * 判定服务是真的, 只有仓库与业务服务是桩。守的是「注解 + 拦截器 + advice 真的接上了」, 而不是单个方法。
 */
class ProcessSecretBomRouteSecurityTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;

    BomRecipeService recipeService;
    BomRecipeRepository recipeRepository;
    ProcessSecretPolicyRepository policies;
    ProcessSecretAuditRecorder recorder;
    ProcessSecretGrantRepository grants;
    BomCopyService copyService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        recipeService = mock(BomRecipeService.class);
        recipeRepository = mock(BomRecipeRepository.class);
        policies = mock(ProcessSecretPolicyRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        grants = mock(ProcessSecretGrantRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, grants, recorder);

        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));

        BomRecipe recipe = new BomRecipe();
        recipe.setId("r1");
        recipe.setFactoryId(F);
        recipe.setProductTypeId(SECRET);
        recipe.setProductName("秘制牛肉");
        BomRecipeItem item = new BomRecipeItem();
        item.setId(7L);
        item.setRecipeId("r1");
        item.setFactoryId(F);
        item.setMaterialName("秘方香料");
        item.setStandardQuantity(new BigDecimal("3.25"));
        recipe.setItems(List.of(item));
        when(recipeRepository.findById("r1")).thenReturn(Optional.of(recipe));
        when(recipeService.getRecipe(F, "r1")).thenReturn(recipe);
        when(recipeService.getSeasoning(F, "r1")).thenReturn(new BomSeasoningResponse());

        copyService = mock(BomCopyService.class);
        BomRecipeController controller = new BomRecipeController(recipeService, copyService,
                mock(BomSeasoningWorkspaceService.class), mock(BomItemSubstituteService.class),
                mock(ProductTypeRepository.class), access, recipeRepository);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addMappedInterceptors(new String[]{"/api/mobile/**"}, new ProcessSecretGuardInterceptor(access,
                        recipeRepository, mock(BomRecipeItemRepository.class),
                        mock(ProductProcessWorkflowRepository.class),
                        mock(com.cretas.aims.repository.bom.BomVersionRepository.class),
                        mock(com.cretas.aims.repository.ProductionBatchRepository.class),
                        mock(com.cretas.aims.repository.ProductionPlanRepository.class),
                        mock(com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler(),
                        new ProcessSecretResponseAdvice(access, recipeRepository, mock(com.cretas.aims.repository.ProductionPlanRepository.class), json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, long userId, String role) {
        return builder.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, F, role));
    }

    @Test
    void guardedSeasoningRouteIs403ForSuperAdminWhoIsNotOwnerAndServiceNeverRuns() throws Exception {
        String body = utf8(mvc.perform(as(get("/api/mobile/F-A/bom/recipes/r1/seasoning"), 77,
                        "factory_super_admin"))
                .andExpect(status().isForbidden()));
        org.assertj.core.api.Assertions.assertThat(body).contains("保密").doesNotContain("秘方香料");
        verify(recipeService, never()).getSeasoning(anyString(), anyString());
    }

    @Test
    void ownerPassesTheSameGuardedRoute() throws Exception {
        mvc.perform(as(get("/api/mobile/F-A/bom/recipes/r1/seasoning"), OWNER, "factory_super_admin"))
                .andExpect(status().isOk());
        verify(recipeService).getSeasoning(F, "r1");
    }

    @Test
    void detailRouteReturnsRestrictedSummaryForNonOwnerAndFullRecipeForOwner() throws Exception {
        String restricted = utf8(mvc.perform(as(get("/api/mobile/F-A/bom/recipes/r1"), 77, "production_manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processSecret.visibility").value("RESTRICTED")));
        org.assertj.core.api.Assertions.assertThat(restricted).contains("秘制牛肉")
                .doesNotContain("秘方香料").doesNotContain("3.25").doesNotContain("\"items\"");
        String full = utf8(mvc.perform(as(get("/api/mobile/F-A/bom/recipes/r1"), OWNER, "factory_super_admin"))
                .andExpect(status().isOk()));
        // 阳性对照: 同一条路由、同一份数据, 负责人拿到完整明细 (证明上面的「不含」不是解码或空响应造成的)
        org.assertj.core.api.Assertions.assertThat(full).contains("秘方香料").contains("3.25");
    }

    static String utf8(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void viewOnlyGrantCannotCopyProtectedRecipeToAnotherProduct() throws Exception {
        // 审查 M1: 只有查看授权 (含限时平台授权) 不能把保密配方复制到未保护产品上「洗白」
        when(grants.findActive(org.mockito.ArgumentMatchers.eq(F), org.mockito.ArgumentMatchers.eq(SECRET),
                org.mockito.ArgumentMatchers.eq(com.cretas.aims.entity.processsecret.ProcessSecretGranteeType.FACTORY_USER),
                org.mockito.ArgumentMatchers.eq(20L), any()))
                .thenReturn(List.of(com.cretas.aims.entity.processsecret.ProcessSecretGrant.builder()
                        .factoryId(F).productTypeId(SECRET)
                        .granteeType(com.cretas.aims.entity.processsecret.ProcessSecretGranteeType.FACTORY_USER)
                        .granteeId(20L).canViewFull(true).canExport(false).canEdit(false).purpose("看")
                        .expiresAt(java.time.LocalDateTime.now().plusDays(1)).grantedBy(OWNER).build()));
        String body = "{\"targetProductTypeId\":\"PLAIN\",\"sourceRecipeId\":\"r1\"}";
        mvc.perform(as(post("/api/mobile/F-A/bom/recipes/copy-to-draft"), 20, "production_manager")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(copyService, never()).copySelectedRulesToDraft(anyString(), any());
        // 阳性对照: 负责人可以复制
        mvc.perform(as(post("/api/mobile/F-A/bom/recipes/copy-to-draft"), OWNER, "factory_super_admin")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(copyService).copySelectedRulesToDraft(anyString(), any());
    }

    @Test
    void writeRouteForProtectedRecipeIs403WithoutEditGrant() throws Exception {
        mvc.perform(as(post("/api/mobile/F-A/bom/recipes/r1/archive"), 77, "factory_super_admin"))
                .andExpect(status().isForbidden());
        verify(recipeService, never()).archiveRecipe(anyString(), anyString());
    }
}
