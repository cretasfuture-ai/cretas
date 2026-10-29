package com.cretas.aims.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.ProcessSecretGuardInterceptor;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * D-S2-11: the history preview's pinned-recipe route carries a recipe (quantities), so it must be no more
 * visible than the product's process is to the same user. Real routing + guard interceptor.
 */
class BomPinnedRecipeRouteSecurityTest {
    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;

    BomWorkflowRevisionService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BomWorkflowRevisionService.class);
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretAccessService access =
                new ProcessSecretAccessService(policies, grants, mock(ProcessSecretAuditRecorder.class));
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        when(service.findRecipePinnedToWorkflow(anyString(), anyString(), anyLong())).thenReturn(Optional.of(new BomRecipe()));

        mvc = MockMvcBuilders.standaloneSetup(new BomPinnedRecipeController(service))
                .addMappedInterceptors(new String[]{"/api/mobile/**"}, new ProcessSecretGuardInterceptor(access,
                        mock(BomRecipeRepository.class), mock(BomRecipeItemRepository.class),
                        mock(ProductProcessWorkflowRepository.class),
                        mock(com.cretas.aims.repository.bom.BomVersionRepository.class),
                        mock(com.cretas.aims.repository.ProductionBatchRepository.class),
                        mock(com.cretas.aims.repository.ProductionPlanRepository.class),
                        mock(com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, long userId, String role) {
        return builder.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, F, role));
    }

    @Test
    void aUserWhoCannotSeeAConfidentialProductCannotReadItsHistoricalRecipeEitherAndTheOwnerCan() throws Exception {
        String url = "/api/mobile/F-A/bom/recipes/by-product/" + SECRET + "/pinned-to-workflow/5";
        mvc.perform(as(get(url), 77, "factory_super_admin")).andExpect(status().isForbidden());
        verify(service, never()).findRecipePinnedToWorkflow(anyString(), anyString(), anyLong());

        mvc.perform(as(get(url), OWNER, "factory_super_admin")).andExpect(status().isOk());
        mvc.perform(as(get("/api/mobile/F-A/bom/recipes/by-product/OPEN-1/pinned-to-workflow/5"), 77, "production_manager"))
                .andExpect(status().isOk());
        verify(service, times(2)).findRecipePinnedToWorkflow(anyString(), anyString(), anyLong());
    }
}
