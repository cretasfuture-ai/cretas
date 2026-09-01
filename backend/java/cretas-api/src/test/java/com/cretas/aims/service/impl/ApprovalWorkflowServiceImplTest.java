package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.config.ApprovalWorkflowRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ApprovalWorkflowServiceImpl 单元测试 — Sprint 3 Track-I (C-APPROVAL-EDITOR-1) Day 3.
 *
 * 覆盖 (≥ 6 cases per Day 3 gate):
 * 1. create happy path (sequential 2-step graph)
 * 2. create dup name → BusinessException(409)
 * 3. create invalid graph (missing start node) → BusinessException(400)
 * 4. create invalid graph (cycle) → BusinessException(400)
 * 5. update PATCH semantics + published→draft auto-revert
 * 6. update wrong factory → BusinessException(403)
 * 7. update not found → EntityNotFoundException
 * 8. getActiveByDecisionType returns highest priority published+enabled
 * 9. publishDraft happy path
 * 10. publishDraft non-draft → BusinessException(400)
 *
 * @since 2026-05-16
 */
@DisplayName("ApprovalWorkflowServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceImplTest {

    @Mock
    private ApprovalWorkflowRepository repository;

    @Mock
    private ApprovalWorkflowInstanceRepository workflowInstanceRepository;

    private ApprovalWorkflowServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String FACTORY_ID = "F001";
    private static final String OTHER_FACTORY = "F006";
    private static final String WORKFLOW_ID = "wf-uuid-001";

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowServiceImpl(repository, objectMapper);
        ReflectionTestUtils.setField(service, "workflowInstanceRepository", workflowInstanceRepository);
    }

    // ==================== fixtures ====================

    /** 合法 2-step graph: start → approval → end. */
    private ApprovalWorkflow validSequentialWorkflow() {
        ApprovalWorkflowNode start = ApprovalWorkflowNode.builder()
                .id("n_start").type("start").label("入口")
                .config(Map.of())
                .build();
        ApprovalWorkflowNode approval = ApprovalWorkflowNode.builder()
                .id("n_approve").type("approval").label("一级审批")
                .config(Map.of(
                        "approverRoles", List.of("factory_admin"),
                        "requiredApprovers", 1,
                        "timeoutMinutes", 60))
                .build();
        ApprovalWorkflowNode end = ApprovalWorkflowNode.builder()
                .id("n_end").type("end").label("通过")
                .config(Map.of("outcome", "APPROVED"))
                .build();

        ApprovalWorkflowEdge e1 = ApprovalWorkflowEdge.builder()
                .id("e1").source("n_start").target("n_approve").build();
        ApprovalWorkflowEdge e2 = ApprovalWorkflowEdge.builder()
                .id("e2").source("n_approve").target("n_end").build();

        ApprovalWorkflow w = ApprovalWorkflow.builder()
                .decisionType(DecisionType.QUALITY_RELEASE)
                .name("test-quality-release-workflow")
                .description("test fixture")
                .startNodeId("n_start")
                .build();
        w.setNodesJson(service.serializeNodes(List.of(start, approval, end)));
        w.setEdgesJson(service.serializeEdges(List.of(e1, e2)));
        return w;
    }

    /** Graph 缺 start 节点 (违法). */
    private ApprovalWorkflow workflowMissingStart() {
        ApprovalWorkflow w = validSequentialWorkflow();
        ApprovalWorkflowNode approval = ApprovalWorkflowNode.builder()
                .id("n_approve").type("approval").label("仅审批节点").build();
        ApprovalWorkflowNode end = ApprovalWorkflowNode.builder()
                .id("n_end").type("end").label("终点").build();
        w.setNodesJson(service.serializeNodes(List.of(approval, end)));
        w.setEdgesJson(service.serializeEdges(List.of(
                ApprovalWorkflowEdge.builder().id("e1").source("n_approve").target("n_end").build())));
        w.setStartNodeId("n_approve"); // 引用了非 start 类型
        return w;
    }

    /** Graph 含环: A → B → A (违法). */
    private ApprovalWorkflow workflowWithCycle() {
        ApprovalWorkflowNode start = ApprovalWorkflowNode.builder()
                .id("n_start").type("start").build();
        ApprovalWorkflowNode a = ApprovalWorkflowNode.builder()
                .id("n_a").type("approval").build();
        ApprovalWorkflowNode b = ApprovalWorkflowNode.builder()
                .id("n_b").type("approval").build();
        ApprovalWorkflowNode end = ApprovalWorkflowNode.builder()
                .id("n_end").type("end").build();

        ApprovalWorkflow w = ApprovalWorkflow.builder()
                .decisionType(DecisionType.QUALITY_RELEASE)
                .name("cycle-test")
                .startNodeId("n_start")
                .build();
        w.setNodesJson(service.serializeNodes(List.of(start, a, b, end)));
        // start → a → b → a (cycle), 同时 end 孤立但 cycle 先报
        w.setEdgesJson(service.serializeEdges(List.of(
                ApprovalWorkflowEdge.builder().id("e1").source("n_start").target("n_a").build(),
                ApprovalWorkflowEdge.builder().id("e2").source("n_a").target("n_b").build(),
                ApprovalWorkflowEdge.builder().id("e3").source("n_b").target("n_a").build(),
                ApprovalWorkflowEdge.builder().id("e4").source("n_a").target("n_end").build())));
        return w;
    }

    // ==================== tests ====================

    @Test
    @DisplayName("Case 1: create happy path — sequential 2-step graph 保存成功")
    void create_happyPath_savesWorkflow() {
        ApprovalWorkflow input = validSequentialWorkflow();
        when(repository.existsByFactoryIdAndDecisionTypeAndName(FACTORY_ID, DecisionType.QUALITY_RELEASE,
                "test-quality-release-workflow")).thenReturn(false);
        when(repository.save(any(ApprovalWorkflow.class))).thenAnswer(inv -> {
            ApprovalWorkflow w = inv.getArgument(0);
            w.setId(WORKFLOW_ID);
            return w;
        });

        ApprovalWorkflow result = service.create(FACTORY_ID, input);

        assertNotNull(result.getId());
        assertEquals(FACTORY_ID, result.getFactoryId());
        // 默认值由 service 应用
        assertEquals(true, result.getEnabled());
        assertEquals(1, result.getVersion());
        assertEquals(0, result.getPriority());
        assertEquals("draft", result.getPublishStatus());
        verify(repository).save(any(ApprovalWorkflow.class));
    }

    @Test
    @DisplayName("Case 2: create dup name → BusinessException(409)")
    void create_duplicateName_throws409() {
        ApprovalWorkflow input = validSequentialWorkflow();
        when(repository.existsByFactoryIdAndDecisionTypeAndName(eq(FACTORY_ID),
                eq(DecisionType.QUALITY_RELEASE), eq("test-quality-release-workflow")))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(FACTORY_ID, input));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 3: create with missing start node → BusinessException(400)")
    void create_missingStartNode_throws400() {
        ApprovalWorkflow input = workflowMissingStart();
        // 注意: 不需要 stub existsByFactoryIdAndDecisionTypeAndName,
        // 因为 createConfig 先 dup check 再 validate, 但 missing start 是
        // validate 阶段抛, 所以仍会走到 validate 之前. 我们让它返 false 让代码 进 validate.
        when(repository.existsByFactoryIdAndDecisionTypeAndName(any(), any(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(FACTORY_ID, input));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校验失败"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 4: create with cycle → BusinessException(400) cycle detected")
    void create_withCycle_throws400() {
        ApprovalWorkflow input = workflowWithCycle();
        when(repository.existsByFactoryIdAndDecisionTypeAndName(any(), any(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(FACTORY_ID, input));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("校验失败"));
    }

    @Test
    @DisplayName("Case 5: update published workflow with 0 running instances → allowed in place (M-6)")
    void update_publishedWorkflowWithNoRunningInstances_allowsInPlaceEdit() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");
        existing.setVersion(3);

        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        when(workflowInstanceRepository.findByFactoryIdAndWorkflowIdAndStatus(
                FACTORY_ID, WORKFLOW_ID, ApprovalWorkflowInstance.InstanceStatus.RUNNING))
                .thenReturn(List.of());
        when(repository.save(existing)).thenReturn(existing);
        ApprovalWorkflow partial = new ApprovalWorkflow();
        partial.setName("renamed");

        ApprovalWorkflow updated = service.update(FACTORY_ID, WORKFLOW_ID, partial, false);

        // 已完成的订单判定完了不受影响, 没有在途实例可影响 —— 原地改, 不强制走克隆,
        // publishStatus/version 都不因为这次编辑而改变。
        assertEquals("renamed", updated.getName());
        assertEquals("published", existing.getPublishStatus());
        assertEquals(3, existing.getVersion());
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("Case 5b: update published workflow with running instances, no force → rejected (M-6)")
    void update_publishedWorkflowWithRunningInstances_rejectsWithoutForce() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");

        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        when(workflowInstanceRepository.findByFactoryIdAndWorkflowIdAndStatus(
                FACTORY_ID, WORKFLOW_ID, ApprovalWorkflowInstance.InstanceStatus.RUNNING))
                .thenReturn(List.of(ApprovalWorkflowInstance.builder().id("inst-running").build()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, new ApprovalWorkflow(), false));

        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", exception.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 5c: update published workflow with running instances, force=true → allowed (M-6)")
    void update_publishedWorkflowWithRunningInstances_forceBypassesGuard() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");

        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        ApprovalWorkflow partial = new ApprovalWorkflow();
        partial.setName("renamed-despite-running-instance");

        ApprovalWorkflow updated = service.update(FACTORY_ID, WORKFLOW_ID, partial, true);

        assertEquals("renamed-despite-running-instance", updated.getName());
        verify(repository).save(existing);

        /*
         * 🔴 2026-08-20 合并树上 Mockito 报 UnnecessaryStubbing: 这里原本打桩了
         * 「查在途实例 → 返回一条 RUNNING」，而 force=true 这条路**根本不查**。
         *
         * ⚠️ 那个「多余的桩」本身是一条**有信息量的读数**，⛔ 不该只是删掉了事 ——
         *    它说明的正是本用例要证明的事：force 不是「查到了但放行」，而是**压根不问**。
         * ⇒ 把它翻成断言。
         *
         * ⚠️ 用 any() 而不是具体值：本仓记过 verify(never()) 在实参为 null 时不匹配，
         *    那样的阴性断言两条路都成立。
         */
        verify(workflowInstanceRepository, never())
                .findByFactoryIdAndWorkflowIdAndStatus(any(), any(), any());
    }

    // ═══════════════ M-6: 读不到在途实例时必须 fail-closed ═══════════════
    //
    // 原实现是 `if (workflowInstanceRepository == null) return 0;` —— 而 0 的语义是
    // 「没有在途」⇒ 闸放行。依赖读不到时判定为「可以随便改」是最坏的方向。
    //
    // ⚠️ #455 之后这道闸是**唯一**权威(前端「仍然直接改」逃生门已删、force 钉死 false),
    //    所以一个假的 0 会让 update/delete 在该拦的时候放行, 把在途单据改成
    //    缺陷 27 那种「看得见点不动」的死状态。
    //
    // 📌 严重度(实测, ⛔ 不是推断): 2026-09-01 生产上 16 条审批流里两条分别返回 1 / 1,
    //    其余为 0, 且与 admin-running 独立路径列出的 2 条 RUNNING 实例逐条吻合
    //    ⇒ repository 在生产上确实注入了, 这条 fail-open **不可达**。
    //    修它是纵深防御 —— @Autowired(required = false) 让「哪天注不进来」结构上可能。
    //
    // 🔴 这两条是本文件里**第一次**覆盖「repository 缺席」这一路: 之前没有任何用例走过它,
    //    这正是那个 return 0 能一直活着的原因。

    @Test
    @DisplayName("M-6: 拿不到在途实例数时抛 503, ⛔ 不许编一个 0 出来")
    void countRunningInstances_repositoryAbsent_failsClosed() {
        ReflectionTestUtils.setField(service, "workflowInstanceRepository", null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.countRunningInstances(FACTORY_ID, WORKFLOW_ID));

        assertEquals(503, ex.getCode());
        assertEquals("OA_RUNNING_INSTANCE_COUNT_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("M-6: 拿不到在途实例数时, 已发布审批流的原地修改必须被拦住")
    void update_publishedWorkflow_repositoryAbsent_isBlocked() {
        // 这条才是安全属性本身 —— 上面那条只证明它抛, 这条证明**闸真的关上了**。
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        ReflectionTestUtils.setField(service, "workflowInstanceRepository", null);

        ApprovalWorkflow partial = new ApprovalWorkflow();
        partial.setName("renamed-while-count-unavailable");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, partial, false));
        assertEquals(503, ex.getCode());

        // ⛔ 最要紧的一条: 没落库。原实现会当作「0 条在途」直接改下去。
        verify(repository, never()).save(any(ApprovalWorkflow.class));
        assertEquals("published", existing.getPublishStatus());
    }

    @Test
    @DisplayName("Case 6: update wrong factory → BusinessException(403)")
    void update_wrongFactory_throws403() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(OTHER_FACTORY);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, new ApprovalWorkflow(), false));
        assertEquals(403, ex.getCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 7: update not found → EntityNotFoundException")
    void update_notFound_throws() {
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, new ApprovalWorkflow(), false));
        verify(repository, never()).save(any());
    }

    @Test
    void runningInstanceBlocksDefinitionMutationButAllowsDisableAndArchive() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        when(workflowInstanceRepository.findByFactoryIdAndWorkflowIdAndStatus(
                FACTORY_ID, WORKFLOW_ID, ApprovalWorkflowInstance.InstanceStatus.RUNNING))
                .thenReturn(List.of(ApprovalWorkflowInstance.builder().id("inst-running").build()));

        // draft (validSequentialWorkflow 默认 publishStatus) 不受 M-6 的 force 影响,
        // 在途实例始终无条件挡住 —— 理论上草稿也不该有在途实例, 这里是 defense-in-depth。
        BusinessException update = assertThrows(BusinessException.class,
                () -> service.update(FACTORY_ID, WORKFLOW_ID, new ApprovalWorkflow(), true));
        BusinessException delete = assertThrows(BusinessException.class,
                () -> service.delete(FACTORY_ID, WORKFLOW_ID));
        when(repository.save(existing)).thenReturn(existing);
        ApprovalWorkflow archived = service.archive(FACTORY_ID, WORKFLOW_ID);
        ApprovalWorkflow disabled = service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, false);

        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", update.getErrorCode());
        assertEquals("OA_WORKFLOW_RUNNING_INSTANCE_EXISTS", delete.getErrorCode());
        assertEquals("archived", archived.getPublishStatus());
        assertFalse(disabled.getEnabled());
        assertEquals(1, service.countRunningInstances(FACTORY_ID, WORKFLOW_ID));
        verify(repository, never()).delete(any());
        verify(repository, times(2)).save(existing);
    }

    @Test
    @DisplayName("Case 8: getActiveByDecisionType returns the unique published+enabled version")
    void getActiveReturnsUniqueVersion() {
        ApprovalWorkflow high = validSequentialWorkflow();
        high.setId("wf-high");
        high.setPriority(100);
        when(repository.findActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of(high));

        Optional<ApprovalWorkflow> result = service.getActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE);

        assertTrue(result.isPresent());
        assertEquals("wf-high", result.get().getId());
    }

    @Test
    void multipleActiveWorkflowsFailClosed() {
        ApprovalWorkflow first = validSequentialWorkflow();
        ApprovalWorkflow second = validSequentialWorkflow();
        when(repository.findActiveByDecisionType(
                FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL))
                .thenReturn(List.of(first, second));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.getActiveByDecisionType(
                        FACTORY_ID, DecisionType.PURCHASE_ORDER_APPROVAL));

        assertEquals("OA_MULTIPLE_ACTIVE_WORKFLOWS", error.getErrorCode());
    }

    @Test
    @DisplayName("Case 9: getActiveByDecisionType 无 active → empty (caller fallback to flat-list)")
    void getActive_empty_returnsEmpty() {
        when(repository.findActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of());

        Optional<ApprovalWorkflow> result = service.getActiveByDecisionType(FACTORY_ID, DecisionType.QUALITY_RELEASE);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("克隆已发布版本会创建独立递增草稿且不修改源版本")
    void cloneAsDraft_createsIndependentIncrementedDraft() {
        ApprovalWorkflow source = validSequentialWorkflow();
        source.setId(WORKFLOW_ID);
        source.setFactoryId(FACTORY_ID);
        source.setPublishStatus("published");
        source.setEnabled(true);
        source.setVersion(3);
        source.setPriority(20);
        when(repository.findByFactoryIdAndIdForUpdate(FACTORY_ID, WORKFLOW_ID))
                .thenReturn(Optional.of(source));
        when(repository.findByFactoryIdAndDecisionTypeOrderByPriorityDesc(
                FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of(source));
        when(repository.save(any(ApprovalWorkflow.class))).thenAnswer(invocation -> {
            ApprovalWorkflow saved = invocation.getArgument(0);
            saved.setId("wf-draft-v4");
            return saved;
        });

        ApprovalWorkflow draft = service.cloneAsDraft(FACTORY_ID, WORKFLOW_ID);

        assertEquals("wf-draft-v4", draft.getId());
        assertEquals(4, draft.getVersion());
        assertEquals("draft", draft.getPublishStatus());
        assertFalse(draft.getEnabled());
        assertEquals(source.getNodesJson(), draft.getNodesJson());
        assertEquals("published", source.getPublishStatus());
        assertTrue(source.getEnabled());
    }

    @Test
    @DisplayName("已有同业务草稿时克隆幂等返回该草稿")
    void cloneAsDraft_returnsExistingDraftIdempotently() {
        ApprovalWorkflow source = validSequentialWorkflow();
        source.setId(WORKFLOW_ID);
        source.setFactoryId(FACTORY_ID);
        source.setPublishStatus("published");
        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId("existing-draft");
        draft.setFactoryId(FACTORY_ID);
        draft.setPublishStatus("draft");
        draft.setVersion(4);
        when(repository.findByFactoryIdAndIdForUpdate(FACTORY_ID, WORKFLOW_ID))
                .thenReturn(Optional.of(source));
        when(repository.findByFactoryIdAndDecisionTypeOrderByPriorityDesc(
                FACTORY_ID, DecisionType.QUALITY_RELEASE))
                .thenReturn(List.of(draft, source));

        ApprovalWorkflow result = service.cloneAsDraft(FACTORY_ID, WORKFLOW_ID);

        assertSame(draft, result);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("发布草稿会启用新版本供新实例选择")
    void publishDraft_enablesPublishedVersion() {
        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId(WORKFLOW_ID);
        draft.setFactoryId(FACTORY_ID);
        draft.setPublishStatus("draft");
        draft.setEnabled(false);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(draft));
        when(repository.save(any(ApprovalWorkflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalWorkflow published = service.publishDraft(FACTORY_ID, WORKFLOW_ID);

        assertEquals("published", published.getPublishStatus());
        assertTrue(published.getEnabled());
    }

    @Test
    void publishingNewVersionAtomicallyDisablesPreviousActiveVersion() {
        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId(WORKFLOW_ID);
        draft.setFactoryId(FACTORY_ID);
        draft.setPublishStatus("draft");
        draft.setEnabled(false);
        ApprovalWorkflow previous = validSequentialWorkflow();
        previous.setId("wf-active-v1");
        previous.setFactoryId(FACTORY_ID);
        previous.setPublishStatus("published");
        previous.setEnabled(true);

        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(draft));
        when(repository.findActiveByDecisionType(
                FACTORY_ID, draft.getDecisionType())).thenReturn(List.of(previous));
        when(repository.save(any(ApprovalWorkflow.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalWorkflow published = service.publishDraft(FACTORY_ID, WORKFLOW_ID);

        assertFalse(previous.getEnabled());
        assertTrue(published.getEnabled());
        assertEquals("published", published.getPublishStatus());
        verify(repository).save(argThat(workflow ->
                "wf-active-v1".equals(workflow.getId())
                        && Boolean.FALSE.equals(workflow.getEnabled())));
        verify(repository).save(argThat(workflow ->
                WORKFLOW_ID.equals(workflow.getId())
                        && "published".equals(workflow.getPublishStatus())
                        && Boolean.TRUE.equals(workflow.getEnabled())));
    }

    @Test
    @DisplayName("未接入业务只能保存草稿，不能发布或重新启用")
    void unwiredBusinessCannotBePublishedOrEnabled() {
        DecisionTypeMetadataRegistry registry = new DecisionTypeMetadataRegistry();
        registry.init();
        ReflectionTestUtils.setField(service, "decisionTypeMetadataRegistry", registry);

        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId(WORKFLOW_ID);
        draft.setFactoryId(FACTORY_ID);
        draft.setDecisionType(DecisionType.LEAVE_APPROVAL);
        draft.setPublishStatus("draft");
        draft.setEnabled(false);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(draft));

        BusinessException publishError = assertThrows(
                BusinessException.class,
                () -> service.publishDraft(FACTORY_ID, WORKFLOW_ID));
        assertEquals("OA_BUSINESS_NOT_WIRED", publishError.getErrorCode());

        draft.setPublishStatus("published");
        BusinessException enableError = assertThrows(
                BusinessException.class,
                () -> service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, true));
        assertEquals("OA_BUSINESS_NOT_WIRED", enableError.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    void onlyPublishedUniqueWorkflowCanBeEnabled() {
        ApprovalWorkflow draft = validSequentialWorkflow();
        draft.setId(WORKFLOW_ID);
        draft.setFactoryId(FACTORY_ID);
        draft.setPublishStatus("draft");
        draft.setEnabled(false);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(draft));

        BusinessException draftError = assertThrows(
                BusinessException.class,
                () -> service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, true));
        assertEquals("OA_WORKFLOW_NOT_PUBLISHED", draftError.getErrorCode());

        draft.setPublishStatus("published");
        ApprovalWorkflow other = validSequentialWorkflow();
        other.setId("wf-other-active");
        other.setEnabled(true);
        other.setPublishStatus("published");
        when(repository.findActiveByDecisionType(
                FACTORY_ID, draft.getDecisionType())).thenReturn(List.of(other));

        BusinessException activeError = assertThrows(
                BusinessException.class,
                () -> service.toggleEnabled(FACTORY_ID, WORKFLOW_ID, true));
        assertEquals("OA_ACTIVE_WORKFLOW_EXISTS", activeError.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Case 10: publishDraft non-draft → BusinessException(400)")
    void publish_nonDraft_throws400() {
        ApprovalWorkflow existing = validSequentialWorkflow();
        existing.setId(WORKFLOW_ID);
        existing.setFactoryId(FACTORY_ID);
        existing.setPublishStatus("published");
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.publishDraft(FACTORY_ID, WORKFLOW_ID));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("仅 draft"));
        verify(repository, never()).save(any());
    }
}
