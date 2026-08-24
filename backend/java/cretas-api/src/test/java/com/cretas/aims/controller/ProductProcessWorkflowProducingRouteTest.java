package com.cretas.aims.controller;

import com.cretas.aims.dto.workflow.WorkflowOutputDirectoryDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import com.cretas.aims.service.workflow.WorkflowOutputDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /producing 是字面量段, 而同层还有 GET /{productTypeId} 与 GET /{productTypeId}/versions
 * 两条路径变量映射。「Spring 字面量优先」是个约定而不是编译期保证 —— 如果它不成立,
 * /producing 会被当成 productTypeId="producing" 静默走进读草稿的接口, 返回 null 而不报错。
 * 这条用例真正发一次请求, 断言落在反查 handler 上。
 */
class ProductProcessWorkflowProducingRouteTest {

    private ProductProcessWorkflowService workflowService;
    private WorkflowOutputDirectoryService directoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workflowService = mock(ProductProcessWorkflowService.class);
        directoryService = mock(WorkflowOutputDirectoryService.class);
        when(workflowService.getEditorDefinition(anyString(), anyString()))
                .thenReturn(Optional.empty());
        ProductProcessWorkflowController controller = new ProductProcessWorkflowController(
                workflowService,
                mock(ProductProcessWorkflowActivationService.class),
                mock(ProductWorkflowResolutionService.class),
                directoryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void producingIsRoutedToTheReverseLookupAndNotSwallowedByTheProductTypeIdPath() throws Exception {
        when(directoryService.findWorkflowsProducing("F1", "P2")).thenReturn(
                WorkflowOutputDirectoryDTO.builder()
                        .finishedGoodProductTypeId("P2")
                        .workflows(List.of(WorkflowOutputDirectoryDTO.Entry.builder()
                                .workflowId(158L)
                                .definitionVersion(3)
                                .ownerProductTypeId("ANCHOR-UNRELATED")
                                .ownerProductName("拓扑成品C")
                                .workflowType("RAW_MATERIAL_SPLIT")
                                .terminalOutputs(List.of(
                                        WorkflowOutputDirectoryDTO.TerminalOutput.builder()
                                                .productTypeId("P1").productName("产出甲").build(),
                                        WorkflowOutputDirectoryDTO.TerminalOutput.builder()
                                                .productTypeId("P2").productName("产出乙").build()))
                                .anchorIsTerminalOutput(false)
                                .build()))
                        .build());

        mockMvc.perform(get("/api/mobile/F1/product-process-workflows/producing")
                        .param("finishedGoodProductTypeId", "P2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.finishedGoodProductTypeId").value("P2"))
                .andExpect(jsonPath("$.data.workflows[0].workflowId").value(158))
                .andExpect(jsonPath("$.data.workflows[0].anchorIsTerminalOutput").value(false))
                .andExpect(jsonPath("$.data.workflows[0].terminalOutputs[1].productName")
                        .value("产出乙"));

        // 反向: 如果路由被 /{productTypeId} 吞掉, 读草稿的 service 会被调到。
        verifyNoInteractions(workflowService);
    }

    @Test
    void anOrdinaryProductTypeIdStillReachesTheEditorDefinitionHandler() throws Exception {
        mockMvc.perform(get("/api/mobile/F1/product-process-workflows/PT-123"))
                .andExpect(status().isOk());

        verify(workflowService).getEditorDefinition("F1", "PT-123");
        verifyNoInteractions(directoryService);
    }

    @Test
    void producingWithoutTheRequiredParamFailsLoudlyInsteadOfReturningAnEmptyDirectory()
            throws Exception {
        mockMvc.perform(get("/api/mobile/F1/product-process-workflows/producing"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(directoryService);
    }

    @Test
    void manualSnapshotMutationEndpointIsRetired() throws Exception {
        mockMvc.perform(post("/api/mobile/F006/product-process-workflows/PT-123/snapshot")
                        .contentType("application/json")
                        .content("{\"lockVersion\":8}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(workflowService, directoryService);
    }

    @Test
    void stalePublishedWorkflowDraftReturnsChineseConflictWithoutSaving() throws Exception {
        when(workflowService.saveDraft(eq("F006"), eq("PT-123"), any()))
                .thenThrow(new BusinessException(409, "该 Workflow 已被其他人更新")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                        .withHint("请重新加载最新版本；如需保留当前内容，可复制当前草稿 JSON")
                        .withSeverity("warning"));

        mockMvc.perform(put("/api/mobile/F006/product-process-workflows/PT-123/draft")
                        .contentType("application/json")
                        .content("""
                                {
                                  "id": 185,
                                  "version": 4,
                                  "lockVersion": 2,
                                  "schemaVersion": 1,
                                  "nodes": [],
                                  "edges": [],
                                  "viewport": {"x": 0, "y": 0, "zoom": 1}
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_PROCESS_WORKFLOW_CONFLICT"))
                .andExpect(jsonPath("$.message").value("该 Workflow 已被其他人更新"))
                .andExpect(jsonPath("$.actionHint")
                        .value("请重新加载最新版本；如需保留当前内容，可复制当前草稿 JSON"));

        verify(workflowService).saveDraft(eq("F006"), eq("PT-123"), any());
        verifyNoInteractions(directoryService);
    }
}
