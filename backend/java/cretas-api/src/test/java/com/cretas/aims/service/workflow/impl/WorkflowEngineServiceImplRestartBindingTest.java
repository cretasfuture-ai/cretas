package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 缺陷 27 —— {@code restartWithCurrentDefinition} 的行为闸。
 *
 * <h2>这组测试在守什么</h2>
 *
 * <p>生产事故: 改一次已发布的审批定义, 该链上在途单据的通过与驳回<b>两条路同时 409</b>,
 * 而产品内没有任何解卡路径 (LIUSHANMEN 的 PO-20260901-0001 至今卡着)。
 * 恢复入口本身容易长成第二个缺陷, 所以这里的判据分两半:
 *
 * <ul>
 *   <li><b>能救</b> — 摘要过期时确实作废重开, 并且把「审批依据换过」留痕 (不许静默)</li>
 *   <li><b>不能滥用</b> — 摘要没过期时<b>必须拒绝</b>。否则它就是一个
 *       「把任何审批倒回第一步」的按钮, 等于给了管理员一个抹掉已走审批的口子。
 *       {@link #refuses_when_definition_unchanged} 正是
 *       {@link #cancels_old_and_restarts_bound_to_current_definition} 的阴性对照:
 *       两者<b>唯一</b>的差别就是有没有真的改过定义。</li>
 * </ul>
 */
@DisplayName("缺陷 27 — 按当前审批流重开卡死的在途实例")
@ExtendWith(MockitoExtension.class)
class WorkflowEngineServiceImplRestartBindingTest {

    @Mock private ApprovalWorkflowInstanceRepository instanceRepository;
    @Mock private ApprovalHistoryRepository historyRepository;
    @Mock private ApprovalWorkflowService workflowService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();
    private WorkflowEngineServiceImpl engine;

    /** id → 最近一次落库的实例, 供 loadInstanceOrFail 读回。 */
    private final Map<String, ApprovalWorkflowInstance> saved = new HashMap<>();

    private static final String FACTORY_ID = "F001";
    private static final String OTHER_FACTORY = "F002";
    private static final String WORKFLOW_ID = "wf-restart-001";
    private static final String BUSINESS_ENTITY_ID = "PO-20260901-0001";
    private static final Long INITIATOR_ID = 100L;
    private static final Long ADMIN_ID = 900L;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngineServiceImpl(
                instanceRepository, historyRepository, workflowService,
                spelEvaluator, /* redisTemplate = */ null);

        lenient().when(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
                anyString(), anyString())).thenReturn(List.of());
        lenient().when(historyRepository.save(any(ApprovalHistory.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        lenient().when(instanceRepository.save(any(ApprovalWorkflowInstance.class)))
                .thenAnswer(inv -> {
                    ApprovalWorkflowInstance i = inv.getArgument(0);
                    saved.put(i.getId(), i);
                    return i;
                });
        lenient().when(instanceRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.getArgument(0))));

        lenient().when(workflowService.deserializeNodes(anyString()))
                .thenAnswer(inv -> deserialize(inv.getArgument(0), ApprovalWorkflowNode.class));
        lenient().when(workflowService.deserializeEdges(anyString()))
                .thenAnswer(inv -> deserialize(inv.getArgument(0), ApprovalWorkflowEdge.class));
    }

    // ==================== 测试 ====================

    @Test
    @DisplayName("阴性对照: 定义没被改过时拒绝重开 —— 它不是「把审批倒回第一步」的按钮")
    void refuses_when_definition_unchanged() {
        buildWorkflow();
        ApprovalWorkflowInstance inst = startInstance();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                engine.restartWithCurrentDefinition(
                        FACTORY_ID, inst.getId(), ADMIN_ID, "想把审批重来一遍"));

        assertEquals(409, ex.getCode());
        assertEquals("WORKFLOW_REBIND_NOT_STALE", ex.getErrorCode());
        // 原实例必须纹丝不动 —— 拒绝要是"拒绝了但已经把单子作废了"就更糟。
        assertEquals(InstanceStatus.RUNNING, saved.get(inst.getId()).getStatus());
    }

    @Test
    @DisplayName("摘要过期时: 旧实例作废 + 按当前定义新建, 并把换绑留痕")
    void cancels_old_and_restarts_bound_to_current_definition() {
        ApprovalWorkflow w = buildWorkflow();
        ApprovalWorkflowInstance inst = startInstance();
        String oldInstanceId = inst.getId();

        // 这条单已经走过一次审批 —— 重开会丢掉它, 所以必须在留痕里数出来。
        lenient().when(historyRepository.findByFactoryIdAndInstanceIdOrderByCreatedAtAsc(
                        FACTORY_ID, oldInstanceId))
                .thenReturn(List.of(historyRow(HistoryAction.APPROVE), historyRow(HistoryAction.START)));

        mutateDefinition(w);

        ApprovalWorkflowInstance restarted = engine.restartWithCurrentDefinition(
                FACTORY_ID, oldInstanceId, ADMIN_ID, "画布改过审批人角色，按新定义重走");

        assertNotEquals(oldInstanceId, restarted.getId(), "必须是一个新实例, 不是原地改摘要");
        assertEquals(InstanceStatus.CANCELLED, saved.get(oldInstanceId).getStatus());
        assertEquals(InstanceStatus.RUNNING, restarted.getStatus());
        assertEquals(BUSINESS_ENTITY_ID, restarted.getBusinessEntityId());
        assertEquals(INITIATOR_ID, restarted.getInitiatedBy(), "发起人应沿用原单, 不是执行恢复的管理员");

        @SuppressWarnings("unchecked")
        Map<String, Object> audit =
                (Map<String, Object>) restarted.getContextJson().get("approvalRebind");
        assertNotNull(audit, "⛔ 静默重绑 —— 审批依据换过这件事必须留痕");
        assertEquals(oldInstanceId, audit.get("previousInstanceId"));
        assertEquals(ADMIN_ID, audit.get("operatorUserId"));
        assertEquals(1L, audit.get("discardedApprovals"), "丢弃了 1 条已通过的审批, 必须数出来");
        assertNotEquals(audit.get("previousBoundDigest"), audit.get("currentBoundDigest"),
                "前后摘要必须都记下来且确实不同");
        assertEquals("画布改过审批人角色，按新定义重走", audit.get("reason"));
    }

    @Test
    @DisplayName("重开之后该单据仍然有且只有一个 RUNNING 实例 —— 业务侧 WORKFLOW_RUNNING 投影不用跟着改")
    void leaves_exactly_one_running_instance_for_the_business_entity() {
        // 这一条守的是「purchase 那半不需要改」这个判断本身。
        // 若重开后出现 0 个 RUNNING, PurchaseOrder 会停在 WORKFLOW_RUNNING 却查不到实例 ——
        // 那正是 submitOrder 里 PURCHASE_APPROVAL_INSTANCE_MISSING 描述的第二个死状态。
        ApprovalWorkflow w = buildWorkflow();
        ApprovalWorkflowInstance inst = startInstance();
        mutateDefinition(w);

        ApprovalWorkflowInstance restarted = engine.restartWithCurrentDefinition(
                FACTORY_ID, inst.getId(), ADMIN_ID, "定义变更后恢复");

        List<ApprovalWorkflowInstance> running = saved.values().stream()
                .filter(i -> BUSINESS_ENTITY_ID.equals(i.getBusinessEntityId()))
                .filter(i -> i.getStatus() == InstanceStatus.RUNNING)
                .toList();
        assertEquals(1, running.size(),
                "恢复后必须恰好一个 RUNNING 实例, 否则业务单据会掉进「已提交但没有实例」");
        assertEquals(restarted.getId(), running.get(0).getId());
    }

    @Test
    @DisplayName("不写恢复原因就拒绝 —— 留痕不是可选项")
    void rejects_blank_reason() {
        buildWorkflow();
        ApprovalWorkflowInstance inst = startInstance();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                engine.restartWithCurrentDefinition(FACTORY_ID, inst.getId(), ADMIN_ID, "   "));
        assertEquals(422, ex.getCode());
        assertEquals("WORKFLOW_REBIND_REASON_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("跨租户拿实例 id 重开: 404, 不泄露该实例是否存在")
    void rejects_cross_tenant_restart() {
        ApprovalWorkflow w = buildWorkflow();
        ApprovalWorkflowInstance inst = startInstance();
        mutateDefinition(w);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                engine.restartWithCurrentDefinition(
                        OTHER_FACTORY, inst.getId(), ADMIN_ID, "越权尝试"));
        assertEquals(404, ex.getCode());
        assertEquals(InstanceStatus.RUNNING, saved.get(inst.getId()).getStatus());
    }

    @Test
    @DisplayName("已结束的实例不给重开")
    void rejects_non_running_instance() {
        ApprovalWorkflow w = buildWorkflow();
        ApprovalWorkflowInstance inst = startInstance();
        mutateDefinition(w);
        engine.cancel(inst.getId(), ADMIN_ID, "先取消");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                engine.restartWithCurrentDefinition(
                        FACTORY_ID, inst.getId(), ADMIN_ID, "对已取消的单重开"));
        assertEquals(409, ex.getCode());
        assertEquals("WORKFLOW_REBIND_STATUS_INVALID", ex.getErrorCode());
    }

    // ==================== fixtures ====================

    /**
     * 改动定义 —— 只动节点 label, 不动结构。
     *
     * <p>⚠️ 这正是缺陷 27 触发面之所以大的原因: 摘要喂的是 {@code nodesJson} 整串,
     * 连 {@code position} 都在里面。所以"只是改了个显示名"甚至"挪了一像素"
     * 同样会让在途实例全部失配。这里用最轻的一种改动, 就是要钉住这个事实。
     */
    private void mutateDefinition(ApprovalWorkflow w) {
        w.setNodesJson(w.getNodesJson().replace("\"审批\"", "\"财务审批\""));
    }

    private ApprovalWorkflowInstance startInstance() {
        return engine.startWorkflow(
                FACTORY_ID, "PURCHASE_ORDER", BUSINESS_ENTITY_ID,
                new HashMap<>(Map.of("amount", 1000)), INITIATOR_ID);
    }

    private ApprovalHistory historyRow(HistoryAction action) {
        return ApprovalHistory.builder().action(action).build();
    }

    private ApprovalWorkflow buildWorkflow() {
        List<ApprovalWorkflowNode> nodes = List.of(
                ApprovalWorkflowNode.builder().id("start").type("start").label("开始")
                        .config(Map.of()).build(),
                ApprovalWorkflowNode.builder().id("approve1").type("approval").label("审批")
                        .config(Map.of("approverRoles", List.of("factory_super_admin"))).build(),
                ApprovalWorkflowNode.builder().id("end_ok").type("end").label("结束")
                        .config(Map.of("outcome", "APPROVED")).build());
        List<ApprovalWorkflowEdge> edges = List.of(
                ApprovalWorkflowEdge.builder().id("e1").source("start").target("approve1")
                        .priority(0).build(),
                ApprovalWorkflowEdge.builder().id("e2").source("approve1").target("end_ok")
                        .priority(0).build());

        ApprovalWorkflow w = ApprovalWorkflow.builder()
                .decisionType(DecisionType.PURCHASE_ORDER_APPROVAL)
                .name("采购订单审批")
                .startNodeId("start")
                .build();
        w.setId(WORKFLOW_ID);
        w.setFactoryId(FACTORY_ID);
        try {
            w.setNodesJson(objectMapper.writeValueAsString(nodes));
            w.setEdgesJson(objectMapper.writeValueAsString(edges));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        lenient().when(workflowService.getActiveByDecisionType(
                        FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL))
                .thenReturn(Optional.of(w));
        lenient().when(workflowService.getById(FACTORY_ID, WORKFLOW_ID)).thenReturn(Optional.of(w));
        lenient().when(workflowService.getByIdForUpdate(FACTORY_ID, WORKFLOW_ID))
                .thenReturn(Optional.of(w));
        return w;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List deserialize(String json, Class<?> elementType) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
