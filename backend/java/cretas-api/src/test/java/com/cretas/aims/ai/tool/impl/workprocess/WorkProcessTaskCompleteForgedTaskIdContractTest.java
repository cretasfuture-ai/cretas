package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import com.cretas.aims.service.workprocess.impl.WorkProcessTaskServiceImpl;
import com.cretas.aims.utils.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实入口: AI 报工工具的预览 / 确认后执行 / 「选哪一道」列表, 接<b>真实</b>
 * {@link WorkProcessTaskServiceImpl} (只桩仓储)。客户端把 /preview 的 context 里的 taskId 换成同厂别人的,
 * 预览不出确认卡、确认后执行也被拒 (不落库); 换回自己的 → 能预览、能执行 (阳性对照)。
 *
 * <p>主体绑定方式同 AI 异步线程 ({@code SseStreamingService}): 已认证主体 + 受信 userId 两个 scope, 没有 HTTP 请求。
 */
@DisplayName("AI 报工: 伪造同厂他人的 taskId → 预览与执行都被拒")
class WorkProcessTaskCompleteForgedTaskIdContractTest {

    private static final String F = "F006";
    private static final long ME = 42L;
    private static final long OTHER = 77L;
    private static final long MY_TASK = 101L;
    private static final long OTHERS_TASK = 303L;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules(); // 生产 mapper 带 JavaTime (完成时间)
    private WorkProcessTaskRepository taskRepository;
    private WorkProcessTaskCompleteTool tool;

    @BeforeEach
    void setUp() {
        taskRepository = mock(WorkProcessTaskRepository.class);
        WorkProcessTaskServiceImpl service = new WorkProcessTaskServiceImpl(taskRepository,
                mock(WorkProcessRepository.class), mock(UserRepository.class), mock(ProductionBatchRepository.class),
                mock(ProductTypeRepository.class), mock(ProductProcessWorkflowRuntimeService.class));
        ReflectionTestUtils.setField(service, "pwpAssigneeRepository", mock(ProductWorkProcessAssigneeRepository.class));
        tool = new WorkProcessTaskCompleteTool(service);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        when(taskRepository.findByFactoryIdAndId(F, MY_TASK)).thenReturn(Optional.of(task(MY_TASK, ME)));
        when(taskRepository.findByFactoryIdAndId(F, OTHERS_TASK)).thenReturn(Optional.of(task(OTHERS_TASK, OTHER)));
        when(taskRepository.save(any(WorkProcessTask.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("预览: 别人的 taskId → canDo=false「不是派给您的」; 自己的 → PREVIEW")
    void previewRefusesAForgedTaskId() throws Exception {
        Map<String, Object> forged = data(as("operator", () -> tool.preview(call(OTHERS_TASK), ctx())));
        assertThat(forged.get("canDo")).isEqualTo(false);
        assertThat(forged.get("status")).isEqualTo("NOT_PERMITTED");
        assertThat(String.valueOf(forged.get("message"))).contains("这道工序不是派给您的")
                .doesNotContainPattern("[a-z]+[A-Z][A-Za-z]*");
        assertThat(forged).doesNotContainKey("newValues");

        Map<String, Object> mine = data(as("operator", () -> tool.preview(call(MY_TASK), ctx())));
        assertThat(mine.get("canDo")).as("阳性对照: 自己的能预览").isEqualTo(true);
        assertThat(mine.get("status")).isEqualTo("PREVIEW");
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("确认后执行: 别人的 taskId → 失败 (权限), 不落库; 自己的 → 完成; 主管 → 别人的也能完成")
    void executeRefusesAForgedTaskId() throws Exception {
        Map<String, Object> forged = parse(as("operator", () -> tool.execute(call(OTHERS_TASK), ctx())));
        assertThat(forged.get("success")).isEqualTo(false);
        assertThat(String.valueOf(forged)).contains("这道工序不是派给您的");
        verify(taskRepository, never()).save(any());

        Map<String, Object> mine = parse(as("operator", () -> tool.execute(call(MY_TASK), ctx())));
        assertThat(mine.get("success")).as("阳性对照: 自己的能完成 " + mine).isEqualTo(true);
        verify(taskRepository, times(1)).save(any());

        Map<String, Object> bySupervisor = parse(as("workshop_supervisor", () -> tool.execute(call(OTHERS_TASK), ctx())));
        assertThat(bySupervisor.get("success")).as("主管 " + bySupervisor).isEqualTo(true);
        verify(taskRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("列表口径: 操作员查「我的 + 未指派」; 主管查本厂全部进行中 (列出来的都是点了能报的)")
    void choiceListFollowsTheSameRule() throws Exception {
        when(taskRepository.findByFilters(eq(F), eq(Status.IN_PROGRESS), isNull(), any(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(task(MY_TASK, ME), task(OTHERS_TASK, OTHER))));

        WorkProcessTaskCompleteTool.Resolution operator = as("operator",
                () -> tool.resolveChoices(F, ME, Map.of("actualQuantity", 100), "我要报工"));
        verify(taskRepository).findByFilters(eq(F), eq(Status.IN_PROGRESS), isNull(), eq(ME), any(Pageable.class));
        // 仓储桩故意把别人的那道也给了回来: 工具按同一条判据筛掉, 只剩自己的
        assertThat(operator.kind()).isEqualTo(WorkProcessTaskCompleteTool.Kind.SINGLE);

        WorkProcessTaskCompleteTool.Resolution supervisor = as("workshop_supervisor",
                () -> tool.resolveChoices(F, ME, Map.of("actualQuantity", 100), "我要报工"));
        verify(taskRepository).findByFilters(eq(F), eq(Status.IN_PROGRESS), isNull(), isNull(), any(Pageable.class));
        assertThat(supervisor.kind()).isEqualTo(WorkProcessTaskCompleteTool.Kind.MANY);
        assertThat(supervisor.choices()).hasSize(2);
    }

    // ------------------------------------------------------------------ helpers

    private interface Call<T> { T run() throws Exception; }

    private <T> T as(String role, Call<T> call) throws Exception {
        try (AuthenticatedActor.ActorScope actor = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, ME, F, role));
             SecurityUtils.Scope user = SecurityUtils.trustedUserIdScope(ME)) {
            return call.run();
        }
    }

    private static WorkProcessTask task(long id, Long assignedTo) {
        return WorkProcessTask.builder().id(id).factoryId(F).workProcessId("wp-1")
                .assignedTo(assignedTo).status(Status.IN_PROGRESS).build();
    }

    private ToolCall call(long taskId) throws Exception {
        return ToolCall.of("tc-1", tool.getToolName(),
                mapper.writeValueAsString(Map.of("taskId", taskId, "actualQuantity", 100)));
    }

    private static Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", F);
        ctx.put("userId", ME);
        ctx.put("userRole", "operator");
        return ctx;
    }

    private Map<String, Object> parse(String json) throws Exception {
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(String json) throws Exception {
        Map<String, Object> root = parse(json);
        assertThat(root.get("success")).as("预览出口: " + json).isEqualTo(true);
        return (Map<String, Object>) root.get("data");
    }
}
