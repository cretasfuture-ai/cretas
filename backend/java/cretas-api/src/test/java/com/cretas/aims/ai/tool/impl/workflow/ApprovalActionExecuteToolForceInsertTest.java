package com.cretas.aims.ai.tool.impl.workflow;

import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1551-11: AI 审批工具直接推进流程, 不走业务适配器 —— 对强制插单会让实例「已批准」而计划永远待审批、
 * 时段永远被占。强制插单只能在 OA 审批中心处理 (UrgentInsertServiceImpl#applyWorkflowAction)。
 */
class ApprovalActionExecuteToolForceInsertTest {

    private ApprovalActionExecuteTool tool(ApprovalWorkflowInstance instance, WorkflowEngineService engine) {
        ApprovalActionExecuteTool tool = new ApprovalActionExecuteTool();
        ApprovalWorkflowInstanceRepository repo = mock(ApprovalWorkflowInstanceRepository.class);
        when(repo.findById(instance.getId())).thenReturn(Optional.of(instance));
        ReflectionTestUtils.setField(tool, "instanceRepository", repo);
        ReflectionTestUtils.setField(tool, "workflowEngine", engine);
        return tool;
    }

    private static ApprovalWorkflowInstance running(String module) {
        return ApprovalWorkflowInstance.builder().id("INST-" + module).factoryId("F006").workflowId("WF")
                .moduleCode(module).businessEntityId("BIZ").status(InstanceStatus.RUNNING).build();
    }

    private static final Map<String, Object> CONTEXT = Map.of("userId", 1552L, "userRole", "production_manager");

    @Test
    void forceInsertCannotBeApprovedThroughTheAiTool() {
        WorkflowEngineService engine = mock(WorkflowEngineService.class);
        ApprovalActionExecuteTool tool = tool(running("FORCE_INSERT"), engine);

        assertThatThrownBy(() -> tool.doExecute("F006",
                Map.of("instanceId", "INST-FORCE_INSERT", "action", "APPROVE"), CONTEXT))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("FORCE_INSERT_APPROVAL_VIA_OA"));
        verify(engine, never()).transitionNode(any(), any(), any(), any(), any());
    }

    @Test
    void otherModulesStillReachTheEngine() {
        WorkflowEngineService engine = mock(WorkflowEngineService.class);
        ApprovalWorkflowInstance approved = running("PURCHASE_ORDER");
        approved.setStatus(InstanceStatus.APPROVED);
        when(engine.transitionNode(any(), any(), any(), any(), any())).thenReturn(approved);
        ApprovalActionExecuteTool tool = tool(running("PURCHASE_ORDER"), engine);

        try {
            tool.doExecute("F006", Map.of("instanceId", "INST-PURCHASE_ORDER", "action", "APPROVE"), CONTEXT);
        } catch (Exception ignoredDownstream) {
            // 推进之后的结果组装依赖别的协作者, 这里只守「到得了引擎」
        }
        verify(engine).transitionNode(any(), any(), any(), any(), any());
    }
}
