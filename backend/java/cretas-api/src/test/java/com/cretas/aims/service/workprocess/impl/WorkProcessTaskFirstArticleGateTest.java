package com.cretas.aims.service.workprocess.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.entity.quality.firstarticle.FirstArticlePolicy.Mode;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleStandard;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleInspectionRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticlePolicyRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleStandardRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.quality.firstarticle.FirstArticleGate;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工序首检闸接在真实的开工 / 完工入口 ({@code WorkProcessTaskServiceImpl#start / #complete}) 上:
 * <ul>
 *   <li>开关关 (没有开关行): 完工、开工结果与<b>没装首检闸</b>时逐字节相同, 且不查标准 / 首检单;</li>
 *   <li>拦截: 首检没合格 → 409 FIRST_ARTICLE_NOT_PASSED 且任务不落库; 首检合格 → 放行 (阳性对照);</li>
 *   <li>提示: 照常完成, 返回带 firstArticleNotice。</li>
 * </ul>
 */
class WorkProcessTaskFirstArticleGateTest {

    private static final String FACTORY = "F006";
    private static final long TASK_ID = 31L;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private WorkProcessTaskRepository taskRepository;
    private FirstArticlePolicyRepository policies;
    private FirstArticleStandardRepository standards;
    private FirstArticleInspectionRepository inspections;

    @BeforeEach
    void setUp() {
        taskRepository = mock(WorkProcessTaskRepository.class);
        policies = mock(FirstArticlePolicyRepository.class);
        standards = mock(FirstArticleStandardRepository.class);
        inspections = mock(FirstArticleInspectionRepository.class);
        when(taskRepository.save(any(WorkProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));
        // 这道工序有启用的首检标准、还没送检 —— 开关一开就该提示 / 拦截
        FirstArticleStandard std = new FirstArticleStandard();
        std.setId(5L);
        std.setName("修油首检");
        when(standards.findActive(FACTORY, "wp-1")).thenReturn(Optional.of(std));
        when(inspections.findEffectiveForTasks(anyString(), anyCollection())).thenReturn(List.of());
    }

    private WorkProcessTaskServiceImpl service(boolean withGate) {
        WorkProcessTaskServiceImpl s = new WorkProcessTaskServiceImpl(taskRepository, mock(WorkProcessRepository.class),
                mock(UserRepository.class), mock(ProductionBatchRepository.class), mock(ProductTypeRepository.class),
                mock(ProductProcessWorkflowRuntimeService.class));
        if (withGate) {
            ReflectionTestUtils.setField(s, "firstArticleGate", new FirstArticleGate(policies, standards, inspections));
        }
        return s;
    }

    private void stubTask(WorkProcessTask.Status status) {
        WorkProcessTask t = WorkProcessTask.builder().id(TASK_ID).factoryId(FACTORY).productionBatchId(9L)
                .workProcessId("wp-1").productTypeId("p-1").processOrder(1).status(status)
                .actualStartAt(status == WorkProcessTask.Status.IN_PROGRESS ? LocalDateTime.of(2026, 11, 16, 8, 0) : null)
                .build();
        when(taskRepository.findByFactoryIdAndId(FACTORY, TASK_ID)).thenReturn(Optional.of(t));
    }

    private static WorkProcessTaskDTO.CompleteRequest qty(String q) {
        return WorkProcessTaskDTO.CompleteRequest.builder().actualQuantity(new BigDecimal(q)).build();
    }

    /** 去掉随调用时刻变化的字段, 剩下的必须逐字节相同。 */
    private String stable(WorkProcessTaskDTO dto) throws Exception {
        dto.setActualEndAt(null);
        dto.setCompletedAt(null);
        dto.setActualMinutes(null);
        dto.setActualStartAt(null);
        return json.writeValueAsString(dto);
    }

    @Test
    @DisplayName("开关关 (没有开关行): 完工 / 开工与没装首检闸逐字节相同, 不查标准与首检单, 不出现 firstArticleNotice")
    void offIsByteIdenticalToNoGate() throws Exception {
        when(policies.findModeByFactoryId(FACTORY)).thenReturn(null);

        stubTask(WorkProcessTask.Status.IN_PROGRESS);
        String baselineComplete = stable(service(false).complete(FACTORY, TASK_ID, 42L, qty("10")));
        stubTask(WorkProcessTask.Status.IN_PROGRESS);
        String gatedComplete = stable(service(true).complete(FACTORY, TASK_ID, 42L, qty("10")));
        assertThat(gatedComplete).isEqualTo(baselineComplete).doesNotContain("firstArticleNotice");
        assertThat(gatedComplete).contains("\"status\":\"COMPLETED\"");

        stubTask(WorkProcessTask.Status.PENDING);
        String baselineStart = stable(service(false).start(FACTORY, TASK_ID, 42L));
        stubTask(WorkProcessTask.Status.PENDING);
        String gatedStart = stable(service(true).start(FACTORY, TASK_ID, 42L));
        assertThat(gatedStart).isEqualTo(baselineStart).doesNotContain("firstArticleNotice");
        assertThat(gatedStart).contains("\"status\":\"IN_PROGRESS\"");

        // 开关关时连标准都不查 (「一定会发生」的读数: 开关确实被问了)
        verify(policies, org.mockito.Mockito.atLeast(2)).findModeByFactoryId(FACTORY);
        verifyNoInteractions(standards, inspections);
    }

    @Test
    @DisplayName("开关 = OFF 行与没有行同一结论")
    void explicitOffRowSameAsNoRow() {
        when(policies.findModeByFactoryId(FACTORY)).thenReturn(Mode.OFF);
        stubTask(WorkProcessTask.Status.IN_PROGRESS);
        WorkProcessTaskDTO dto = service(true).complete(FACTORY, TASK_ID, 42L, qty("10"));
        assertThat(dto.getStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
        assertThat(dto.getFirstArticleNotice()).isNull();
        verifyNoInteractions(standards, inspections);
    }

    @Test
    @DisplayName("拦截: 首检没送检 → 409 FIRST_ARTICLE_NOT_PASSED, 任务不落库")
    void blockRejectsCompletionWithoutPassedFirstArticle() {
        when(policies.findModeByFactoryId(FACTORY)).thenReturn(Mode.BLOCK);
        stubTask(WorkProcessTask.Status.IN_PROGRESS);
        assertThatThrownBy(() -> service(true).complete(FACTORY, TASK_ID, 42L, qty("10")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException b = (BusinessException) e;
                    assertThat(b.getCode()).isEqualTo(409);
                    assertThat(b.getErrorCode()).isEqualTo(FirstArticleGate.CODE);
                    assertThat(b.getMessage()).contains("修油首检").contains("还没送检");
                });
        verify(taskRepository, never()).save(any(WorkProcessTask.class));
    }

    @Test
    @DisplayName("拦截: 首检不合格也拦; 阳性对照: 首检合格 → 放行且不带提示")
    void blockFailedStillRejectsAndPassedIsAllowed() {
        when(policies.findModeByFactoryId(FACTORY)).thenReturn(Mode.BLOCK);
        FirstArticleInspection failed = inspection(FirstArticleInspection.Status.FAILED);
        when(inspections.findEffectiveForTasks(anyString(), anyCollection())).thenReturn(List.of(failed));
        stubTask(WorkProcessTask.Status.IN_PROGRESS);
        assertThatThrownBy(() -> service(true).complete(FACTORY, TASK_ID, 42L, qty("10")))
                .hasMessageContaining("首检不合格");

        FirstArticleInspection passed = inspection(FirstArticleInspection.Status.PASSED);
        when(inspections.findEffectiveForTasks(anyString(), anyCollection())).thenReturn(List.of(passed));
        stubTask(WorkProcessTask.Status.IN_PROGRESS);
        WorkProcessTaskDTO dto = service(true).complete(FACTORY, TASK_ID, 42L, qty("10"));
        assertThat(dto.getStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
        assertThat(dto.getFirstArticleNotice()).isNull();
    }

    @Test
    @DisplayName("拦截不拦开工 (首件要开工后才做得出来), 开工返回带提示")
    void blockDoesNotBlockStart() {
        when(policies.findModeByFactoryId(FACTORY)).thenReturn(Mode.BLOCK);
        stubTask(WorkProcessTask.Status.PENDING);
        WorkProcessTaskDTO dto = service(true).start(FACTORY, TASK_ID, 42L);
        assertThat(dto.getStatus()).isEqualTo(WorkProcessTask.Status.IN_PROGRESS);
        assertThat(dto.getFirstArticleNotice()).contains("修油首检").contains("首检合格前不能报完工");
    }

    @Test
    @DisplayName("提示: 照常完成, 返回带 firstArticleNotice (序列化出来)")
    void warnCompletesWithNotice() throws Exception {
        when(policies.findModeByFactoryId(FACTORY)).thenReturn(Mode.WARN);
        stubTask(WorkProcessTask.Status.IN_PROGRESS);
        WorkProcessTaskDTO dto = service(true).complete(FACTORY, TASK_ID, 42L, qty("10"));
        assertThat(dto.getStatus()).isEqualTo(WorkProcessTask.Status.COMPLETED);
        assertThat(dto.getFirstArticleNotice()).contains("还没送检").contains("只提示");
        assertThat(json.writeValueAsString(dto)).contains("\"firstArticleNotice\":");
        verify(taskRepository).save(any(WorkProcessTask.class));
    }

    @Test
    @DisplayName("AI 完工预览镜像: 拦截时 assertFirstArticleAllowsComplete 抛 409; 关时不抛")
    void previewMirrorUsesSameGate() {
        stubTask(WorkProcessTask.Status.IN_PROGRESS);
        when(policies.findModeByFactoryId(FACTORY)).thenReturn(Mode.BLOCK);
        assertThatThrownBy(() -> service(true).assertFirstArticleAllowsComplete(FACTORY, TASK_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(FirstArticleGate.CODE));
        when(policies.findModeByFactoryId(FACTORY)).thenReturn(null);
        service(true).assertFirstArticleAllowsComplete(FACTORY, TASK_ID);
    }

    private static FirstArticleInspection inspection(FirstArticleInspection.Status status) {
        FirstArticleInspection i = new FirstArticleInspection();
        i.setId(77L);
        i.setWorkProcessTaskId(TASK_ID);
        i.setStatus(status);
        return i;
    }
}
