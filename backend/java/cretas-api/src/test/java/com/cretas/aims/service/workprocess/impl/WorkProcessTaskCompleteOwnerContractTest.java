package com.cretas.aims.service.workprocess.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.ProductWorkProcessAssignee;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报完工「本人可报」(服务层, 所有入口都过 {@code complete()}): 口径同 RN 报工 ReportAuthGuard。
 *
 * <p>走 {@code WorkProcessTaskServiceImpl#complete} 真实方法, 只桩仓储。主体用
 * {@code AuthenticatedActor.trustedAsyncScope} 绑定 —— 与 AI 异步线程同一种来源 (没有 HTTP 请求)。
 * 每条「拒」同时断言 {@code save} 一次都没调; 每条「过」断言 {@code save} 确实调了 (硬约束 9)。
 */
@DisplayName("报完工: 本人 / 未指派 / 多负责人 / 主管可报, 同厂他人与跨厂拒")
class WorkProcessTaskCompleteOwnerContractTest {

    private static final String F = "F006";
    private static final long TASK = 12L;
    private static final long PWP = 900L;
    private static final long ME = 42L;
    private static final long OTHER = 77L;

    private WorkProcessTaskRepository taskRepository;
    private ProductWorkProcessAssigneeRepository assignees;
    private WorkProcessTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(WorkProcessTaskRepository.class);
        assignees = mock(ProductWorkProcessAssigneeRepository.class);
        service = new WorkProcessTaskServiceImpl(taskRepository, mock(WorkProcessRepository.class),
                mock(UserRepository.class), mock(ProductionBatchRepository.class),
                mock(ProductTypeRepository.class), mock(ProductProcessWorkflowRuntimeService.class));
        ReflectionTestUtils.setField(service, "pwpAssigneeRepository", assignees);
        when(taskRepository.save(any(WorkProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignees.findByProductWorkProcessId(PWP)).thenReturn(List.of());
    }

    @Test
    @DisplayName("派给本人 → 能报")
    void assignedToMe() {
        task(ME, null);
        assertThat(completeAs(ME, "operator", F).getStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
        verify(taskRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("未指派 → 谁都能报")
    void unassigned() {
        task(null, null);
        assertThat(completeAs(ME, "operator", F).getStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
        verify(taskRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("任务负责人是别人, 但我在工序多负责人表里 → 能报 (同 RN 报工)")
    void joinTableAssignee() {
        task(OTHER, PWP);
        when(assignees.findByProductWorkProcessId(PWP)).thenReturn(List.of(
                assignee(OTHER), assignee(ME)));
        assertThat(completeAs(ME, "operator", F).getStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
        verify(taskRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("车间主管 → 本厂别人的工序也能报")
    void supervisorMayCompleteOthers() {
        task(OTHER, null);
        assertThat(completeAs(ME, "workshop_supervisor", F).getStatus())
                .isEqualTo(WorkProcessTask.Status.COMPLETED);
        verify(taskRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("同厂操作员报派给别人的工序 → 403「不是派给您的」, 不落库; 未指派但多负责人表里没有我 → 同样拒")
    void sameFactoryOtherOperatorRefused() {
        task(OTHER, null);
        assertRefused(() -> completeAs(ME, "operator", F), "这道工序不是派给您的");

        task(null, PWP);
        when(assignees.findByProductWorkProcessId(PWP)).thenReturn(List.of(assignee(OTHER)));
        assertRefused(() -> completeAs(ME, "operator", F), "这道工序不是派给您的");
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("别厂账号 (哪怕是主管, 哪怕任务未指派) → 403「不是本厂的」, 不落库")
    void otherFactoryRefused() {
        task(null, null);
        assertRefused(() -> completeAs(ME, "workshop_supervisor", "F001"), "不是本厂的");
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("主管角色但主体不是这位操作人 (id 对不上) → 不按主管放行")
    void supervisorRoleOfAnotherIdentityDoesNotCount() {
        task(OTHER, null);
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 5L, F, "workshop_supervisor"))) {
            assertRefused(() -> service.complete(F, TASK, ME, quantity()), "这道工序不是派给您的");
        }
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("assertCanComplete (预览 / 列表用) 与 complete 同一判据")
    void assertCanCompleteSameRule() {
        WorkProcessTaskDTO others = WorkProcessTaskDTO.builder().id(TASK).factoryId(F).assignedTo(OTHER).build();
        WorkProcessTaskDTO mine = WorkProcessTaskDTO.builder().id(TASK).factoryId(F).assignedTo(ME).build();
        try (AuthenticatedActor.ActorScope ignored = scope(ME, "operator", F)) {
            assertRefused(() -> service.assertCanComplete(F, others, ME), "这道工序不是派给您的");
            service.assertCanComplete(F, mine, ME);
            assertThat(service.isCompletionSupervisor(F, ME)).isFalse();
        }
        try (AuthenticatedActor.ActorScope ignored = scope(ME, "workshop_supervisor", F)) {
            service.assertCanComplete(F, others, ME);
            assertThat(service.isCompletionSupervisor(F, ME)).isTrue();
        }
    }

    private WorkProcessTaskDTO completeAs(long userId, String role, String actorFactory) {
        try (AuthenticatedActor.ActorScope ignored = scope(userId, role, actorFactory)) {
            return service.complete(F, TASK, userId, quantity());
        }
    }

    private static AuthenticatedActor.ActorScope scope(long userId, String role, String factory) {
        return AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, factory, role));
    }

    private void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String text) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).satisfies(e -> {
            assertThat(((BusinessException) e).getCode()).isEqualTo(403);
            assertThat(e.getMessage()).contains(text).doesNotContainPattern("[a-z]+[A-Z][A-Za-z]*");
        });
    }

    private void task(Long assignedTo, Long pwpId) {
        when(taskRepository.findByFactoryIdAndId(F, TASK)).thenReturn(Optional.of(WorkProcessTask.builder()
                .id(TASK).factoryId(F).workProcessId("wp-1").productWorkProcessId(pwpId)
                .assignedTo(assignedTo).status(WorkProcessTask.Status.IN_PROGRESS).build()));
    }

    private static ProductWorkProcessAssignee assignee(long workerId) {
        return ProductWorkProcessAssignee.builder().productWorkProcessId(PWP).workerId(workerId).build();
    }

    private static WorkProcessTaskDTO.CompleteRequest quantity() {
        return WorkProcessTaskDTO.CompleteRequest.builder().actualQuantity(new BigDecimal("100")).build();
    }
}
