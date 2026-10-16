package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.ApprovalWorkflowRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("采购审批工作流发布角色闸契约")
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowPublishGuardContractTest {

    private static final String FACTORY_ID = "F001";
    private static final String DRAFT_ID = "wf-purchase-draft";

    @Mock
    private ApprovalWorkflowRepository repository;

    @Mock
    private ApprovalWorkflowInstanceRepository workflowInstanceRepository;

    private ApprovalWorkflowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowServiceImpl(repository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "workflowInstanceRepository", workflowInstanceRepository);
    }

    @Test
    @DisplayName("采购 user-only 发布在停用旧 active 前拒绝")
    void purchaseUserOnlyPublishRejectedBeforeCutover() {
        ApprovalWorkflow candidate = workflow(DecisionType.PURCHASE_ORDER_APPROVAL, null, true);
        candidate.setId(DRAFT_ID);
        candidate.setFactoryId(FACTORY_ID);
        candidate.setPublishStatus("draft");
        candidate.setEnabled(false);

        ApprovalWorkflow previousActive = workflow(
                DecisionType.PURCHASE_ORDER_APPROVAL, List.of("purchase_manager"), false);
        previousActive.setId("wf-purchase-active");
        previousActive.setFactoryId(FACTORY_ID);
        previousActive.setPublishStatus("published");
        previousActive.setEnabled(true);

        when(repository.findById(DRAFT_ID)).thenReturn(Optional.of(candidate));
        lenient().when(repository.findActiveByDecisionType(FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL))
                .thenReturn(List.of(previousActive));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishDraft(FACTORY_ID, DRAFT_ID));

        assertPurchaseRoleError(error);
        assertEquals("draft", candidate.getPublishStatus());
        assertFalse(candidate.getEnabled());
        assertTrue(previousActive.getEnabled(), "旧 active 必须保持启用");
        verify(repository, never()).findActiveByDecisionType(
                FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL);
        verify(repository, never()).save(any(ApprovalWorkflow.class));
    }

    @Test
    @DisplayName("采购空白角色发布拒绝且指定用户不能替代角色")
    void purchaseBlankRolesPublishRejected() {
        ApprovalWorkflow candidate = workflow(
                DecisionType.PURCHASE_ORDER_APPROVAL, List.of(" ", "\t"), true);
        candidate.setId(DRAFT_ID);
        candidate.setFactoryId(FACTORY_ID);
        candidate.setPublishStatus("draft");
        candidate.setEnabled(false);
        when(repository.findById(DRAFT_ID)).thenReturn(Optional.of(candidate));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishDraft(FACTORY_ID, DRAFT_ID));

        assertPurchaseRoleError(error);
        verify(repository, never()).save(any(ApprovalWorkflow.class));
    }

    @Test
    @DisplayName("采购角色-only 发布成功")
    void purchaseRoleOnlyPublishSucceeds() {
        ApprovalWorkflow candidate = workflow(
                DecisionType.PURCHASE_ORDER_APPROVAL, List.of("purchase_manager"), false);
        candidate.setId(DRAFT_ID);
        candidate.setFactoryId(FACTORY_ID);
        candidate.setPublishStatus("draft");
        candidate.setEnabled(false);
        when(repository.findById(DRAFT_ID)).thenReturn(Optional.of(candidate));
        when(repository.findActiveByDecisionType(FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL))
                .thenReturn(List.of());
        when(repository.save(any(ApprovalWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalWorkflow published = service.publishDraft(FACTORY_ID, DRAFT_ID);

        assertEquals("published", published.getPublishStatus());
        assertTrue(published.getEnabled());
    }

    @Test
    @DisplayName("其他决策类型仍兼容 user-only 发布")
    void otherDecisionTypeUserOnlyPublishRemainsCompatible() {
        ApprovalWorkflow candidate = workflow(DecisionType.QUALITY_RELEASE, null, true);
        candidate.setId(DRAFT_ID);
        candidate.setFactoryId(FACTORY_ID);
        candidate.setPublishStatus("draft");
        candidate.setEnabled(false);
        when(repository.findById(DRAFT_ID)).thenReturn(Optional.of(candidate));
        when(repository.findActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of());
        when(repository.save(any(ApprovalWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalWorkflow published = service.publishDraft(FACTORY_ID, DRAFT_ID);

        assertEquals("published", published.getPublishStatus());
        assertTrue(published.getEnabled());
    }

    @Test
    @DisplayName("采购 draft 仍可保存 user-only 草稿")
    void purchaseDraftRemainsSavable() {
        ApprovalWorkflow draft = workflow(DecisionType.PURCHASE_ORDER_APPROVAL, null, true);
        draft.setName("purchase-draft");
        when(repository.existsByFactoryIdAndDecisionTypeAndName(
                FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL, "purchase-draft"))
                .thenReturn(false);
        when(repository.save(any(ApprovalWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalWorkflow saved = service.create(FACTORY_ID, draft);

        assertEquals("draft", saved.getPublishStatus());
        assertTrue(saved.getEnabled());
    }

    @Test
    @DisplayName("已发布采购流程启用前拒绝 user-only")
    void purchaseEnableRejectsUserOnlyBeforeWrite() {
        ApprovalWorkflow existing = workflow(DecisionType.PURCHASE_ORDER_APPROVAL, null, true);
        existing.setId(DRAFT_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");
        existing.setEnabled(false);
        when(repository.findById(DRAFT_ID)).thenReturn(Optional.of(existing));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.toggleEnabled(FACTORY_ID, DRAFT_ID, true));

        assertPurchaseRoleError(error);
        assertFalse(existing.getEnabled());
        verify(repository, never()).findActiveByDecisionType(
                FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL);
        verify(repository, never()).save(any(ApprovalWorkflow.class));
    }

    @Test
    @DisplayName("已启用 published 采购流程原地更新不能绕过角色闸")
    void enabledPublishedPurchaseUpdateRejectsUserOnlyBeforeSave() {
        ApprovalWorkflow existing = workflow(DecisionType.PURCHASE_ORDER_APPROVAL, null, true);
        existing.setId(DRAFT_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");
        existing.setEnabled(true);
        when(repository.findById(DRAFT_ID)).thenReturn(Optional.of(existing));
        when(workflowInstanceRepository.findByFactoryIdAndWorkflowIdAndStatus(
                FACTORY_ID, DRAFT_ID, ApprovalWorkflowInstance.InstanceStatus.RUNNING))
                .thenReturn(List.of());

        ApprovalWorkflow partial = new ApprovalWorkflow();
        partial.setName("attempted-user-only-update");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(FACTORY_ID, DRAFT_ID, partial, false));

        assertPurchaseRoleError(error);
        assertTrue(existing.getEnabled());
        assertEquals("published", existing.getPublishStatus());
        verify(repository, never()).save(any(ApprovalWorkflow.class));
    }

    private void assertPurchaseRoleError(BusinessException error) {
        assertEquals(422, error.getCode());
        assertEquals("PURCHASE_APPROVER_ROLE_REQUIRED", error.getErrorCode());
        assertTrue(error.getMessage().contains("审批角色"));
        assertTrue(error.getActionHint().contains("指定审批人不能替代"));
        assertEquals("nodes", error.getHintTarget());
    }

    private ApprovalWorkflow workflow(DecisionType decisionType,
                                     List<String> roles,
                                     boolean includeUserIds) {
        Map<String, Object> approvalConfig = new LinkedHashMap<>();
        if (roles != null) {
            approvalConfig.put("approverRoles", roles);
        }
        if (includeUserIds) {
            approvalConfig.put("approverUserIds", List.of(2));
        }

        List<ApprovalWorkflowNode> nodes = List.of(
                ApprovalWorkflowNode.builder().id("n_start").type("start").label("入口")
                        .config(Map.of()).build(),
                ApprovalWorkflowNode.builder().id("n_approve").type("approval").label("采购审批")
                        .config(approvalConfig).build(),
                ApprovalWorkflowNode.builder().id("n_end").type("end").label("通过")
                        .config(Map.of("outcome", "APPROVED")).build());
        List<ApprovalWorkflowEdge> edges = List.of(
                ApprovalWorkflowEdge.builder().id("e1").source("n_start").target("n_approve").build(),
                ApprovalWorkflowEdge.builder().id("e2").source("n_approve").target("n_end").build());

        ApprovalWorkflow workflow = ApprovalWorkflow.builder()
                .decisionType(decisionType)
                .name("approval-guard-fixture")
                .startNodeId("n_start")
                .build();
        workflow.setNodesJson(service.serializeNodes(nodes));
        workflow.setEdgesJson(service.serializeEdges(edges));
        return workflow;
    }
}
