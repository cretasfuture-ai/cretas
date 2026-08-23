package com.cretas.aims.service.workprocess;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workprocess.impl.WorkProcessTaskServiceImpl;
import com.cretas.aims.utils.ReportAuthGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 指派工序是<b>排他</b>的，所以必须存在一条退回「未指派」的路。
 *
 * <h2>背景（2026-08-19 客户实报）</h2>
 *
 * <pre>
 * 「我需要的是分配工序给工人的方式，现在是新建了一个工人」
 * 「因为工人改不了被分配的生产单和工序」
 * 「我没找到改的入口」
 * </pre>
 *
 * <p>后端其实早就做好了：{@code PUT /work-process-tasks/{id}} 的 Operation summary
 * 逐字写着「修改工序任务计划（<b>分配责任人</b> / 计划时间 / 备注）」，
 * {@code updatePlan} 也会写 {@code assignedTo} —— <b>只是两个前端都没有入口</b>（形态 B）。
 *
 * <h2>为什么「能清空」是这件事的一部分，不是锦上添花</h2>
 *
 * <p>指派一旦落下去就是排他的，而且是<b>两处判据一起</b>排他：
 * <ul>
 *   <li>后端 {@link ReportAuthGuard#assertCanReport} —— 不是本人且非主管 → 403</li>
 *   <li>App {@code isTaskClaimableBy} —— 指派给他人的在「我的任务」里<b>看不到</b>
 *       （其注释原文：「⛔ 指派给他人的仍然不可认领 —— 放开那条是越权」）</li>
 * </ul>
 *
 * <p>而 {@code assignedTo} 传 {@code null} 的语义是「本次不改」，清不掉。
 * ⇒ 「徐师傅回家了，还不知道谁接手」就无路可走：改派得先知道改给谁，
 * 清空又做不到。一次派错，那道工序就焊死到主管手动介入为止。
 *
 * <p>prod 实测（2026-08-19）：F006 22 条工序任务 {@code assigned_to} <b>全为 null</b>，
 * 也就是说排他机制做好了但从没生效过 —— 这次加入口等于第一次把它启用。
 * ⇒ 逃生门必须和入口<b>同一次</b>上线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("契约 — 工序指派可改派, 也必须能退回「未指派」")
class TaskAssigneeCanBeClearedContractTest {

    private static final String FACTORY = "F006";
    private static final Long TASK_ID = 1805L;
    private static final Long XU_SHIFU = 1617L;   // f006_xushifu 徐师傅
    private static final Long YONG_ZHEN = 1618L;  // f006_yongzhen 永珍

    @Mock private WorkProcessTaskRepository taskRepository;
    // toDTO -> lookupDefinition 会查工序定义; 不 mock 就 NPE 在与本用例无关的地方。
    @Mock private WorkProcessRepository workProcessRepository;
    // toDTO 会 batch join 责任人姓名 (T142); 指派了人才会走到这里。
    @Mock private UserRepository userRepository;

    @InjectMocks private WorkProcessTaskServiceImpl service;

    private WorkProcessTask taskAssignedTo(Long workerId) {
        WorkProcessTask task = new WorkProcessTask();
        task.setId(TASK_ID);
        task.setFactoryId(FACTORY);
        task.setStatus(WorkProcessTask.Status.PENDING);
        task.setWorkProcessId("wp-1");
        task.setAssignedTo(workerId);
        when(taskRepository.findByFactoryIdAndId(FACTORY, TASK_ID)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(WorkProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));
        return task;
    }

    private WorkProcessTaskDTO.UpdatePlanRequest request() {
        return new WorkProcessTaskDTO.UpdatePlanRequest();
    }

    @Test
    @DisplayName("指派: 未指派 → 指给徐师傅")
    void assignsWhenPreviouslyUnassigned() {
        WorkProcessTask task = taskAssignedTo(null);
        WorkProcessTaskDTO.UpdatePlanRequest req = request();
        req.setAssignedTo(XU_SHIFU);

        service.updatePlan(FACTORY, TASK_ID, req);

        assertEquals(XU_SHIFU, task.getAssignedTo());
    }

    @Test
    @DisplayName("改派: 徐师傅 → 永珍 (临时换人)")
    void reassignsToAnotherWorker() {
        WorkProcessTask task = taskAssignedTo(XU_SHIFU);
        WorkProcessTaskDTO.UpdatePlanRequest req = request();
        req.setAssignedTo(YONG_ZHEN);

        service.updatePlan(FACTORY, TASK_ID, req);

        assertEquals(YONG_ZHEN, task.getAssignedTo());
    }

    @Test
    @DisplayName("🔴 逃生门: clearAssignee=true → 退回未指派 (谁都能报)")
    void clearsTheAssignee() {
        WorkProcessTask task = taskAssignedTo(XU_SHIFU);
        WorkProcessTaskDTO.UpdatePlanRequest req = request();
        req.setClearAssignee(Boolean.TRUE);

        service.updatePlan(FACTORY, TASK_ID, req);

        assertNull(task.getAssignedTo(),
                "退不回「未指派」的话, 一次派错就把这道工序焊死到主管介入为止");
    }

    @Test
    @DisplayName("⛔ 阴性对照: assignedTo 传 null 只是「本次不改」, ⛔ 不许顺手清空")
    void nullAssignedToLeavesItUnchanged() {
        WorkProcessTask task = taskAssignedTo(XU_SHIFU);
        WorkProcessTaskDTO.UpdatePlanRequest req = request();
        req.setNotes("只改备注");

        service.updatePlan(FACTORY, TASK_ID, req);

        assertEquals(XU_SHIFU, task.getAssignedTo(),
                "其余字段的语义就是「null = 不改」, 指派不能例外 —— 否则改个备注就把人清了");
    }

    @Test
    @DisplayName("clearAssignee 优先于 assignedTo —— 两个都传时以清空为准, 不产生歧义")
    void clearWinsOverAssign() {
        WorkProcessTask task = taskAssignedTo(XU_SHIFU);
        WorkProcessTaskDTO.UpdatePlanRequest req = request();
        req.setAssignedTo(YONG_ZHEN);
        req.setClearAssignee(Boolean.TRUE);

        service.updatePlan(FACTORY, TASK_ID, req);

        assertNull(task.getAssignedTo());
    }

    /**
     * 把「为什么必须能清空」钉成可执行的：清空之后，别的工人必须重新能报。
     * 这条直接跑 {@link ReportAuthGuard}，不是转述它的行为。
     */
    @Test
    @DisplayName("🔴 清空之后别人必须重新能报 —— 这才是逃生门真正要买的东西")
    void afterClearingAnyoneCanReportAgain() {
        boolean notSupervisor = false;

        // 指派给徐师傅时: 永珍被挡
        assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> ReportAuthGuard.assertCanReport(XU_SHIFU, YONG_ZHEN, notSupervisor),
                "指派是排他的 —— 这一条不红就说明排他根本没生效, 后面那条也就没有意义");

        // 清空后 (assignedTo = null): 永珍可以报
        assertDoesNotThrow(
                () -> ReportAuthGuard.assertCanReport(null, YONG_ZHEN, notSupervisor),
                "退回未指派之后必须谁都能报, 否则清空这个动作什么也没买到");

        // 阳性对照: 本人任何时候都能报
        assertDoesNotThrow(() -> ReportAuthGuard.assertCanReport(XU_SHIFU, XU_SHIFU, notSupervisor));
        // 阳性对照: 主管可越过
        assertDoesNotThrow(() -> ReportAuthGuard.assertCanReport(XU_SHIFU, YONG_ZHEN, true));
    }
}
