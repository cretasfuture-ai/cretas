package com.cretas.aims.controller;

import com.cretas.aims.annotation.ProcessSecretGuard;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.dto.workflow.WorkflowCompletenessIssue;
import com.cretas.aims.dto.workflow.WorkflowPublishAndActivateRequest;
import com.cretas.aims.dto.workflow.WorkflowPublishAndActivateResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ProductProcessWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductProcessWorkflowCompletenessRouteContractTest {

    private final ProductProcessWorkflowService service = mock(ProductProcessWorkflowService.class);
    private final ProductProcessWorkflowController controller = new ProductProcessWorkflowController(service);

    @Test
    void checkRouteUsesDraftPermissionsAndCallsOnlyReadSide() throws Exception {
        var save = ProductProcessWorkflowController.class.getMethod(
                "saveDraft", String.class, String.class, ProductProcessWorkflowDTO.class);
        var check = ProductProcessWorkflowController.class.getMethod(
                "checkCompleteness", String.class, String.class, ProductProcessWorkflowDTO.class);
        assertThat(check.getAnnotation(RequirePermission.class)).isEqualTo(save.getAnnotation(RequirePermission.class));
        assertThat(check.getAnnotation(ProcessSecretGuard.class)).isEqualTo(save.getAnnotation(ProcessSecretGuard.class));
        assertThat(check.getAnnotation(RequireRole.class)).isEqualTo(save.getAnnotation(RequireRole.class));

        when(service.checkCompleteness(eq("F006"), eq("mock-400g"), any()))
                .thenReturn(List.of(new WorkflowCompletenessIssue("INPUT_REQUIRED", "wash", null,
                        "「清洗」还没有投入", "把原料接到清洗工序")));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/mobile/F006/product-process-workflows/mock-400g/completeness-check")
                        .contentType(APPLICATION_JSON).content("""
                                {"nodes": [], "edges": [], "viewport": {"x": 0, "y": 0, "zoom": 1}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("INPUT_REQUIRED"))
                .andExpect(jsonPath("$.data[0].nodeId").value("wash"));
        verify(service).checkCompleteness(eq("F006"), eq("mock-400g"), any());
        verifyNoMoreInteractions(service);
    }

    @Test
    void acknowledgedSaveAuditsTheReturnedRevisionOnlyAfterSaving() {
        var submitted = new ProductProcessWorkflowDTO();
        submitted.setCompletenessAcknowledged(true);
        var saved = new ProductProcessWorkflowDTO();
        saved.setRevisionId(74L);
        when(service.saveDraft("F006", "mock-400g", submitted)).thenReturn(saved);

        controller.saveDraft("F006", "mock-400g", submitted);

        var order = inOrder(service);
        order.verify(service).saveDraft("F006", "mock-400g", submitted);
        order.verify(service).recordAcknowledgedCompleteness("F006", "mock-400g", saved, "仍然保存工艺配置");
        order.verifyNoMoreInteractions();
    }

    @Test
    void acknowledgedApplyAuditsOnceAndNeverBypassesHardPublishFailure() {
        var body = new WorkflowPublishAndActivateRequest();
        body.setLockVersion(0L);
        body.setIdempotencyKey("check-replay");
        body.setRevisionId(74L);
        body.setRevisionHash("exact-hash");
        body.setDefinitionVersion(3);
        body.setCompletenessAcknowledged(true);
        var request = new MockHttpServletRequest();
        request.setAttribute("userId", 42L);
        var saved = new ProductProcessWorkflowDTO();
        var activated = new ProductProcessWorkflowActivationDTO();
        when(service.publishAndActivate("F006", "mock-400g", 0L, "check-replay", 74L, "exact-hash", 3, 42L))
                .thenThrow(new BusinessException(409, "现有发布校验未通过"))
                .thenReturn(WorkflowPublishAndActivateResponse.builder().workflow(saved).activation(activated).build())
                .thenReturn(WorkflowPublishAndActivateResponse.builder().workflow(saved).activation(activated).replayed(true).build());

        assertThatThrownBy(() -> controller.publishAndActivate("F006", "mock-400g", body, request))
                .isInstanceOf(BusinessException.class).hasMessage("现有发布校验未通过");
        controller.publishAndActivate("F006", "mock-400g", body, request);
        controller.publishAndActivate("F006", "mock-400g", body, request);
        verify(service).recordAcknowledgedCompleteness("F006", "mock-400g", saved, "仍然应用工艺配置");
    }
}
