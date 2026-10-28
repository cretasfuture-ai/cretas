package com.cretas.aims.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cretas.aims.dto.workflow.WorkflowOutputResolutionDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
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
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * S2-06 / 工艺保密: the pinned-route preview of an old workflow version must not be more visible than
 * the product's process is to the same user. Real routing + guard interceptor; only repositories and
 * the resolution service are stubs.
 */
class ProcessSecretPinnedWorkflowRouteSecurityTest {
    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;

    ProductWorkflowResolutionService resolution;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        resolution = mock(ProductWorkflowResolutionService.class);
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretAccessService access =
                new ProcessSecretAccessService(policies, grants, mock(ProcessSecretAuditRecorder.class));
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));

        ProductProcessWorkflowRepository workflows = mock(ProductProcessWorkflowRepository.class);
        when(workflows.findById(55L)).thenReturn(Optional.of(workflow(55L, SECRET)));
        when(workflows.findById(56L)).thenReturn(Optional.of(workflow(56L, "OPEN-1")));
        when(resolution.findPinnedCandidate(anyString(), any(), any())).thenReturn(
                Optional.of(WorkflowOutputResolutionDTO.Candidate.builder().workflowId(55L)
                        .definitionVersion(1).processSteps(List.of("腌制")).build()));

        ProductProcessWorkflowController controller = new ProductProcessWorkflowController(
                mock(ProductProcessWorkflowService.class), mock(ProductProcessWorkflowActivationService.class),
                resolution);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addMappedInterceptors(new String[]{"/api/mobile/**"}, new ProcessSecretGuardInterceptor(access,
                        mock(BomRecipeRepository.class), mock(BomRecipeItemRepository.class), workflows,
                        mock(com.cretas.aims.repository.bom.BomVersionRepository.class),
                        mock(com.cretas.aims.repository.ProductionBatchRepository.class),
                        mock(com.cretas.aims.repository.ProductionPlanRepository.class),
                        mock(com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static ProductProcessWorkflow workflow(long id, String productTypeId) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId(F);
        workflow.setProductTypeId(productTypeId);
        workflow.setNodesJson("[]");
        workflow.setEdgesJson("[]");
        return workflow;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, long userId, String role) {
        return builder.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, F, role));
    }

    @Test
    void aUserWhoCannotSeeAConfidentialProductProcessCannotGetItsOldRouteEither() throws Exception {
        mvc.perform(as(get("/api/mobile/F-A/product-process-workflows/pinned/55").param("version", "1"),
                        77, "factory_super_admin"))
                .andExpect(status().isForbidden());
        verify(resolution, never()).findPinnedCandidate(anyString(), any(), any());
    }

    @Test
    void theOwnerAndAnyoneForAnUnprotectedProductStillGetThePinnedRoute() throws Exception {
        mvc.perform(as(get("/api/mobile/F-A/product-process-workflows/pinned/55").param("version", "1"),
                        OWNER, "factory_super_admin"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/mobile/F-A/product-process-workflows/pinned/56").param("version", "1"),
                        77, "production_manager"))
                .andExpect(status().isOk());
        verify(resolution, org.mockito.Mockito.times(2)).findPinnedCandidate(anyString(), any(), any());
    }
}
