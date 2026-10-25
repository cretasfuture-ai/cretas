package com.cretas.aims.service.workprocess;

import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺口 04「按工艺图开工」—— Steve 2026-10-25 裁定: <b>上游产出的料够了就解锁</b>。
 *
 * <p>图 (卤味线简化):
 * <pre>
 *   ① 解冻 (原料猪蹄) ──→ [半成品 X 解冻蹄] ──┐
 *                                              ├──→ ③ 卤制 (要 X 和 Y)
 *   ② 配辅料 (原料香料) ──→ [半成品 Y 料包] ──┘
 * </pre>
 * ① ② 是并行分支, 都只吃原料; ③ 要两个上游的产出。
 */
@DisplayName("按工艺图解锁: 上游料到了就能报, 并行分支各报各的")
class WorkflowStepUnlockGuardContractTest {

    private static final String F = "F006";
    private static final long INST = 900L;

    private WorkProcessTaskRepository taskRepo;
    private WorkflowTaskPortRepository portRepo;
    private WorkflowStepUnlockGuard guard;

    private WorkProcessTask thaw;    // ①
    private WorkProcessTask spice;   // ②
    private WorkProcessTask braise;  // ③
    private final List<WorkflowTaskPort> ports = new ArrayList<>();
    private long portSeq = 1;

    @BeforeEach
    void setUp() {
        taskRepo = mock(WorkProcessTaskRepository.class);
        portRepo = mock(WorkflowTaskPortRepository.class);
        WorkProcessRepository wpRepo = mock(WorkProcessRepository.class);
        guard = new WorkflowStepUnlockGuard(taskRepo, portRepo, wpRepo);

        thaw = task(1L, 1, "WP-THAW");
        spice = task(2L, 2, "WP-SPICE");
        braise = task(3L, 3, "WP-BRAISE");
        when(taskRepo.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(F, INST))
                .thenReturn(List.of(thaw, spice, braise));

        port(thaw, WorkflowTaskPort.Direction.INPUT, "RAW-HOOF", null, true);
        port(thaw, WorkflowTaskPort.Direction.OUTPUT, "SEMI-X", null, true);
        port(spice, WorkflowTaskPort.Direction.INPUT, "RAW-SPICE", null, true);
        port(spice, WorkflowTaskPort.Direction.OUTPUT, "SEMI-Y", null, true);
        port(braise, WorkflowTaskPort.Direction.INPUT, "SEMI-X", null, true);
        port(braise, WorkflowTaskPort.Direction.INPUT, "SEMI-Y", null, true);
        when(portRepo.findByFactoryIdAndWorkflowInstanceId(F, INST)).thenReturn(ports);

        when(wpRepo.findByFactoryIdAndIdIn(eq(F), anyList())).thenReturn(List.of(
                wp("WP-THAW", "解冻"), wp("WP-SPICE", "配辅料"), wp("WP-BRAISE", "卤制")));
    }

    @Test
    @DisplayName("并行分支: 只吃原料的 ① ② 一开始就能报 (⛔ 不再只放第一道)")
    void parallelRawOnlyBranchesAreOpenFromTheStart() {
        assertThat(guard.evaluate(F, thaw).unlocked()).isTrue();
        assertThat(guard.evaluate(F, spice).unlocked()).as("第二道也能和第一道同时报").isTrue();
    }

    @Test
    @DisplayName("两个上游都没产出: ③ 锁住, 原因点名两道上游")
    void downstreamWaitsForBothUpstreams() {
        WorkflowStepUnlockGuard.Readiness r = guard.evaluate(F, braise);

        assertThat(r.unlocked()).isFalse();
        assertThat(r.waitingFor()).containsExactly("解冻", "配辅料");
        assertThat(r.blockedReason()).contains("解冻").contains("配辅料");
    }

    @Test
    @DisplayName("① 报了第一批 (还在进行中) 而 ② 没报: 只剩 ② 挡着 —— ⛔ 不要求上游全部完工")
    void partialUpstreamOutputUnlocksItsShare() {
        thaw.setStatus(Status.IN_PROGRESS);
        thaw.setActualQuantity(new BigDecimal("48"));

        WorkflowStepUnlockGuard.Readiness r = guard.evaluate(F, braise);

        assertThat(r.unlocked()).isFalse();
        assertThat(r.waitingFor()).containsExactly("配辅料");
    }

    @Test
    @DisplayName("两个上游都报出了产出 (都没完工): ③ 解锁")
    void bothUpstreamsProducedUnlocks() {
        thaw.setStatus(Status.IN_PROGRESS);
        thaw.setActualQuantity(new BigDecimal("48"));
        spice.setStatus(Status.IN_PROGRESS);
        spice.setActualQuantity(new BigDecimal("2"));

        assertThat(guard.evaluate(F, braise).unlocked()).isTrue();
    }

    @Test
    @DisplayName("上游跳过 = 不会再产出, 不能让下游永远锁死")
    void skippedUpstreamDoesNotDeadlock() {
        thaw.setStatus(Status.SKIPPED);
        spice.setStatus(Status.SKIPPED);

        assertThat(guard.evaluate(F, braise).unlocked()).isTrue();
    }

    @Test
    @DisplayName("选择组里有一个成员不需要等上游 (可改用原料), 整组算满足")
    void selectionGroupSatisfiedByAnAlternativeWithoutUpstream() {
        ports.removeIf(p -> p.getTaskId().equals(3L) && "SEMI-Y".equals(p.getMaterialNodeId()));
        // ③ 的第二个投入: 料包 Y 或外购现成料包, 二选一
        port(braise, WorkflowTaskPort.Direction.INPUT, "SEMI-Y", "G1", false).setSelectionGroupMode("EXACTLY_ONE");
        port(braise, WorkflowTaskPort.Direction.INPUT, "RAW-READY-PACK", "G1", false).setSelectionGroupMode("EXACTLY_ONE");
        thaw.setActualQuantity(new BigDecimal("10"));

        assertThat(guard.evaluate(F, braise).unlocked()).isTrue();
    }

    @Test
    @DisplayName("真实数据形状: 端口 required=false + 自动「实际投入」AT_LEAST_ONE 组 —— 闸照样响 (⛔ 不能按 required 过滤掉)")
    void realShapeActualIoGroupIsStillGated() {
        braiseInputsAsGroup("AT_LEAST_ONE", 1);

        assertThat(guard.evaluate(F, braise).unlocked())
                .as("两个上游都没产出: 组里没有一个成员可用").isFalse();

        thaw.setActualQuantity(new BigDecimal("5"));
        assertThat(guard.evaluate(F, braise).unlocked())
                .as("AT_LEAST_ONE: 有一个上游出料就能报").isTrue();
    }

    @Test
    @DisplayName("ALL_REQUIRED 组: 每个成员的上游都要出料")
    void allRequiredGroupNeedsEveryMember() {
        braiseInputsAsGroup("ALL_REQUIRED", 2);
        thaw.setActualQuantity(new BigDecimal("5"));

        WorkflowStepUnlockGuard.Readiness r = guard.evaluate(F, braise);
        assertThat(r.unlocked()).isFalse();
        assertThat(r.waitingFor()).containsExactly("配辅料");
    }

    @Test
    @DisplayName("OPTIONAL 组 / 最少选 0 个: 不设闸")
    void optionalGroupIsNotGated() {
        braiseInputsAsGroup("OPTIONAL", 0);

        assertThat(guard.evaluate(F, braise).unlocked()).isTrue();
    }

    @Test
    @DisplayName("非必需投入不设闸")
    void optionalInputIsNotGated() {
        ports.removeIf(p -> p.getTaskId().equals(3L) && "SEMI-Y".equals(p.getMaterialNodeId()));
        port(braise, WorkflowTaskPort.Direction.INPUT, "SEMI-Y", null, false);
        thaw.setActualQuantity(new BigDecimal("10"));

        assertThat(guard.evaluate(F, braise).unlocked()).isTrue();
    }

    @Test
    @DisplayName("回投 / 回料: 产出者在本道之后, 不当上游 (否则上下游互相等死)")
    void laterProducerIsNotTreatedAsUpstream() {
        // ① 也要吃 ③ 回投的料 Z
        port(braise, WorkflowTaskPort.Direction.OUTPUT, "SEMI-Z", null, true);
        port(thaw, WorkflowTaskPort.Direction.INPUT, "SEMI-Z", null, true);

        assertThat(guard.evaluate(F, thaw).unlocked()).isTrue();
    }

    @Test
    @DisplayName("写入口: 锁住时 409 WORKFLOW_UPSTREAM_NOT_READY, 写明等哪一道; 解锁后放行")
    void assertUnlockedRejectsWith409() {
        assertThatThrownBy(() -> guard.assertUnlocked(F, braise))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo(WorkflowStepUnlockGuard.ERROR_CODE);
                    assertThat(be.getMessage()).contains("解冻");
                });

        thaw.setActualQuantity(new BigDecimal("1"));
        spice.setActualQuantity(new BigDecimal("1"));
        guard.assertUnlocked(F, braise);   // 阴性对照: 不抛
    }

    @Test
    @DisplayName("没有工艺图快照的旧式任务: 上游 = 前一道, 前一道报出产出就解锁")
    void legacyTaskUsesPreviousStep() {
        WorkProcessTask a = task(11L, 1, "WP-THAW");
        WorkProcessTask b = task(12L, 2, "WP-BRAISE");
        a.setWorkflowInstanceId(null);
        b.setWorkflowInstanceId(null);
        a.setProductionBatchId(77L);
        b.setProductionBatchId(77L);
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(F, 77L)).thenReturn(List.of(a, b));

        assertThat(guard.evaluate(F, a).unlocked()).isTrue();
        assertThat(guard.evaluate(F, b).waitingFor()).containsExactly("解冻");

        a.setStatus(Status.IN_PROGRESS);
        a.setActualQuantity(new BigDecimal("3"));
        assertThat(guard.evaluate(F, b).unlocked()).isTrue();
    }

    @Test
    @DisplayName("列表一次算一批: 每个任务各自一个结论, 同一工艺实例只查一次端口")
    void evaluateAllCoversEveryTask() {
        var all = guard.evaluateAll(F, List.of(thaw, spice, braise));

        assertThat(all).hasSize(3);
        assertThat(all.get(1L).unlocked()).isTrue();
        assertThat(all.get(2L).unlocked()).isTrue();
        assertThat(all.get(3L).unlocked()).isFalse();
        org.mockito.Mockito.verify(portRepo, org.mockito.Mockito.times(1))
                .findByFactoryIdAndWorkflowInstanceId(any(), any());
    }

    // ---------------------------------------------------------------------

    private static WorkProcessTask task(long id, int order, String processId) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id);
        t.setFactoryId(F);
        t.setProcessOrder(order);
        t.setWorkProcessId(processId);
        t.setWorkflowInstanceId(INST);
        t.setProductionBatchId(500L);
        t.setStatus(Status.PENDING);
        return t;
    }

    private WorkflowTaskPort port(WorkProcessTask t, WorkflowTaskPort.Direction dir, String materialNodeId,
                      String group, boolean required) {
        WorkflowTaskPort p = new WorkflowTaskPort();
        p.setId(portSeq++);
        p.setFactoryId(F);
        p.setWorkflowInstanceId(INST);
        p.setTaskId(t.getId());
        p.setDirection(dir);
        p.setMaterialNodeId(materialNodeId);
        p.setSelectionGroupId(group);
        p.setRequired(required);
        if (group != null) {
            p.setSelectionGroupMinSelections(1);
        }
        ports.add(p);
        return p;
    }

    /** 把 ③ 的投入换成编译器真实产出的形状: 端口 required=false, 全在一个组里 (prod 306/306 端口都是这样)。 */
    private void braiseInputsAsGroup(String mode, int min) {
        ports.removeIf(p -> p.getTaskId().equals(3L) && p.getDirection() == WorkflowTaskPort.Direction.INPUT);
        for (String node : List.of("SEMI-X", "SEMI-Y")) {
            WorkflowTaskPort p = port(braise, WorkflowTaskPort.Direction.INPUT, node, "actual-io:N3:input", false);
            p.setSelectionGroupMode(mode);
            p.setSelectionGroupMinSelections(min);
        }
    }

    private static WorkProcess wp(String id, String name) {
        WorkProcess w = new WorkProcess();
        w.setId(id);
        w.setProcessName(name);
        return w;
    }
}
