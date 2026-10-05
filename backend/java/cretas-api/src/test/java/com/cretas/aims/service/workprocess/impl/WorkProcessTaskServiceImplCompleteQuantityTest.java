package com.cretas.aims.service.workprocess.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 完成工序的实际产量规则: 必填、不能为负、0 合法。
 *
 * <p>走 {@code complete()} 的真实入口 (只桩掉仓储)。每条「拒绝」配「放行」的对照,
 * 且拒绝的断言同时看 {@code save} 一次都没被调 —— 被拒的请求不许留下半截状态。
 */
class WorkProcessTaskServiceImplCompleteQuantityTest {

    private static final String FACTORY = "F006";
    private static final long TASK_ID = 12L;

    private WorkProcessTaskRepository taskRepository;
    private WorkProcessTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(WorkProcessTaskRepository.class);
        service = new WorkProcessTaskServiceImpl(
                taskRepository,
                mock(WorkProcessRepository.class),
                mock(UserRepository.class),
                mock(ProductionBatchRepository.class),
                mock(ProductTypeRepository.class),
                mock(ProductProcessWorkflowRuntimeService.class));
        WorkProcessTask inProgress = WorkProcessTask.builder()
                .id(TASK_ID)
                .factoryId(FACTORY)
                .workProcessId("wp-1")
                .status(WorkProcessTask.Status.IN_PROGRESS)
                .build();
        when(taskRepository.findByFactoryIdAndId(FACTORY, TASK_ID)).thenReturn(Optional.of(inProgress));
        when(taskRepository.save(any(WorkProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("负数实际产量 → 400 + 中文原因, 任务不落库")
    void negativeQuantityRejected() {
        for (String negative : new String[]{"-1", "-0.01", "-95.5"}) {
            assertThatThrownBy(() -> service.complete(FACTORY, TASK_ID, 42L, request(negative)))
                    .as(negative)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                        assertThat(e.getMessage()).contains("实际产量不能是负数");
                    });
        }
        verify(taskRepository, never()).save(any(WorkProcessTask.class));
        // 对「被拒」的归因: 拒在读任务之前 (同预览的校验顺序), 不是任务状态/归属导致的
        verify(taskRepository, never()).findByFactoryIdAndId(anyString(), any());
    }

    @Test
    @DisplayName("对照: 0 合法 (一道工序可以什么都没产出), 正数照常完成")
    void zeroAndPositiveAccepted() {
        for (String ok : new String[]{"0", "0.00", "0.01", "95.5"}) {
            WorkProcessTask fresh = WorkProcessTask.builder()
                    .id(TASK_ID).factoryId(FACTORY).workProcessId("wp-1")
                    .status(WorkProcessTask.Status.IN_PROGRESS).build();
            when(taskRepository.findByFactoryIdAndId(FACTORY, TASK_ID)).thenReturn(Optional.of(fresh));

            WorkProcessTaskDTO dto = service.complete(FACTORY, TASK_ID, 42L, request(ok));

            assertThat(dto.getStatus()).as(ok).isEqualTo(WorkProcessTask.Status.COMPLETED);
            assertThat(dto.getActualQuantity()).as(ok).isEqualByComparingTo(new BigDecimal(ok));
        }
    }

    @Test
    @DisplayName("对照: 缺实际产量仍然是 400「不能为空」, 与负数是两条不同的拒绝")
    void missingQuantityStillRejectedWithItsOwnReason() {
        assertThatThrownBy(() -> service.complete(FACTORY, TASK_ID, 42L, request(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("actualQuantity 不能为空")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("负数"));
    }

    private static WorkProcessTaskDTO.CompleteRequest request(String quantity) {
        return WorkProcessTaskDTO.CompleteRequest.builder()
                .actualQuantity(quantity == null ? null : new BigDecimal(quantity))
                .build();
    }
}
