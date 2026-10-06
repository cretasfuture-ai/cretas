package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报工 (开始 / 完成工序) 只读预览。
 *
 * <p>全部走工具的<b>真实入口</b> {@code preview(ToolCall, context)} (基类的参数校验 + doPreview +
 * JSON 出口), 只桩掉 {@link WorkProcessTaskService}。每条「能做」都配一条「不能做」的对照,
 * 每条都断言 {@code start / complete} 一次都没被调 —— 预览不许改状态。
 */
class WorkProcessTaskTransitionPreviewToolTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F999";
    private static final long TASK_ID = 12L;

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkProcessTaskService service;
    private WorkProcessTaskStartTool startTool;
    private WorkProcessTaskCompleteTool completeTool;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(WorkProcessTaskService.class);
        startTool = new WorkProcessTaskStartTool(service);
        completeTool = new WorkProcessTaskCompleteTool(service);
        inject(startTool);
        inject(completeTool);
    }

    // ==================== START ====================

    @Test
    @DisplayName("START: 待开始 → 进行中, canDo=true, 带改前/改后, 不调 start")
    void startAllowedFromPending() throws Exception {
        stubTask(FACTORY, task(Status.PENDING));

        Map<String, Object> data = data(startTool.preview(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));

        assertThat(startTool.supportsPreview()).isTrue();
        assertThat(data.get("canDo")).isEqualTo(true);
        assertThat(data.get("status")).isEqualTo("PREVIEW");
        assertThat(data.get("action")).isEqualTo("START");
        assertThat(data.get("taskId")).isEqualTo((int) TASK_ID);
        assertThat(data.get("processName")).isEqualTo("切片");
        assertThat(data.get("batchNumber")).isEqualTo("B20261004-01");
        assertThat(data.get("productTypeName")).isEqualTo("卤猪蹄");
        assertThat(data.get("currentStatus")).isEqualTo("PENDING");
        assertThat(data.get("newStatus")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) data.get("plannedQuantity")).doubleValue()).isEqualTo(100.0);
        assertThat(rows(data, "currentValues")).containsEntry("状态", "待开始")
                .containsEntry("计划数量", "100 kg").containsEntry("负责人", "未指派");
        assertThat(rows(data, "newValues")).containsEntry("状态", "进行中")
                .containsEntry("负责人", "确认人（你）");
        assertNoMutation();
    }

    @Test
    @DisplayName("START 对照: 进行中 / 已完成 / 已跳过 / 已取消 → canDo=false + 中文原因, 不调 start")
    void startRefusedFromEveryNonPendingStatus() throws Exception {
        for (Status from : new Status[]{Status.IN_PROGRESS, Status.COMPLETED, Status.SKIPPED, Status.CANCELLED}) {
            stubTask(FACTORY, task(from));
            Map<String, Object> data = data(startTool.preview(call("work_process_task_start",
                    Map.of("taskId", TASK_ID)), ctx(FACTORY)));
            assertThat(data.get("canDo")).as(from.name()).isEqualTo(false);
            assertThat(data.get("status")).as(from.name()).isEqualTo("INVALID_STATUS");
            assertThat(data.get("currentStatus")).as("参数化那一维真的在变").isEqualTo(from.name());
            assertThat(String.valueOf(data.get("message")))
                    .as(from.name())
                    .contains("工序任务 12")
                    .contains(WorkProcessTaskTransitionPreview.label(from))
                    .doesNotContain(from.name()); // 一线看的是中文, 不是枚举码
            assertThat(data).as("拒绝时不给对比表").doesNotContainKey("newValues");
        }
        assertNoMutation();
    }

    @Test
    @DisplayName("START: 任务不存在 → canDo=false, TASK_NOT_FOUND")
    void startTaskNotFound() throws Exception {
        when(service.getById(FACTORY, TASK_ID))
                .thenThrow(new ResourceNotFoundException("WorkProcessTask", "id", String.valueOf(TASK_ID)));

        Map<String, Object> data = data(startTool.preview(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));

        assertThat(data.get("canDo")).isEqualTo(false);
        assertThat(data.get("status")).isEqualTo("TASK_NOT_FOUND");
        assertThat(String.valueOf(data.get("message"))).contains("找不到工序任务 12").contains("不属于本工厂");
        assertNoMutation();
    }

    @Test
    @DisplayName("START: 别厂的任务 → 按 context 的工厂读, 读不到 = 不存在; 伪造参数 factoryId 无效")
    void startTaskOfAnotherFactoryIsNotVisible() throws Exception {
        // 任务只在 F999 能读到 (service 的 findByFactoryIdAndId 语义)
        when(service.getById(eq(OTHER_FACTORY), eq(TASK_ID))).thenReturn(task(Status.PENDING));
        when(service.getById(eq(FACTORY), eq(TASK_ID)))
                .thenThrow(new ResourceNotFoundException("WorkProcessTask", "id", String.valueOf(TASK_ID)));

        Map<String, Object> args = new HashMap<>();
        args.put("taskId", TASK_ID);
        args.put("factoryId", OTHER_FACTORY); // 参数里伪造别厂, 必须被忽略
        Map<String, Object> mine = data(startTool.preview(call("work_process_task_start", args), ctx(FACTORY)));
        assertThat(mine.get("canDo")).isEqualTo(false);
        assertThat(mine.get("status")).isEqualTo("TASK_NOT_FOUND");
        verify(service, never()).getById(eq(OTHER_FACTORY), anyLong());

        // 对照: 同一个任务在它自己的工厂里是能开始的 —— 证明上面那条拒绝来自工厂隔离, 而不是别的
        Map<String, Object> theirs = data(startTool.preview(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(OTHER_FACTORY)));
        assertThat(theirs.get("canDo")).isEqualTo(true);
        assertNoMutation();
    }

    // ==================== COMPLETE ====================

    @Test
    @DisplayName("COMPLETE: 进行中 → 已完成, 带本次实际产量, canDo=true, 不调 complete")
    void completeAllowedFromInProgress() throws Exception {
        stubTask(FACTORY, task(Status.IN_PROGRESS));

        Map<String, Object> data = data(completeTool.preview(call("work_process_task_complete",
                Map.of("taskId", TASK_ID, "actualQuantity", 95.5, "notes", "边角料多")), ctx(FACTORY)));

        assertThat(completeTool.supportsPreview()).isTrue();
        assertThat(data.get("canDo")).isEqualTo(true);
        assertThat(data.get("status")).isEqualTo("PREVIEW");
        assertThat(data.get("action")).isEqualTo("COMPLETE");
        assertThat(data.get("currentStatus")).isEqualTo("IN_PROGRESS");
        assertThat(data.get("newStatus")).isEqualTo("COMPLETED");
        assertThat(String.valueOf(data.get("actualQuantity"))).isEqualTo("95.5");
        assertThat(rows(data, "currentValues")).containsEntry("状态", "进行中").containsEntry("实际产量", "—");
        assertThat(rows(data, "newValues")).containsEntry("状态", "已完成")
                .containsEntry("实际产量", "95.5 kg").containsEntry("备注", "边角料多");
        assertThat(String.valueOf(data.get("message"))).contains("95.5 kg");
        assertNoMutation();
    }

    @Test
    @DisplayName("COMPLETE 对照: 待开始 / 已完成 / 已跳过 / 已取消 → canDo=false + 中文原因")
    void completeRefusedFromEveryNonInProgressStatus() throws Exception {
        for (Status from : new Status[]{Status.PENDING, Status.COMPLETED, Status.SKIPPED, Status.CANCELLED}) {
            stubTask(FACTORY, task(from));
            Map<String, Object> data = data(completeTool.preview(call("work_process_task_complete",
                    Map.of("taskId", TASK_ID, "actualQuantity", 10)), ctx(FACTORY)));
            assertThat(data.get("canDo")).as(from.name()).isEqualTo(false);
            assertThat(data.get("status")).as(from.name()).isEqualTo("INVALID_STATUS");
            assertThat(data.get("currentStatus")).isEqualTo(from.name());
            assertThat(String.valueOf(data.get("message"))).as(from.name())
                    .contains(WorkProcessTaskTransitionPreview.label(from));
        }
        assertNoMutation();
    }

    @Test
    @DisplayName("COMPLETE: 实际产量不是数字 → canDo=false, 且根本不去读任务 (同写路径的校验顺序)")
    void completeUnparseableQuantityRefused() throws Exception {
        Map<String, Object> data = data(completeTool.preview(call("work_process_task_complete",
                Map.of("taskId", TASK_ID, "actualQuantity", "一百")), ctx(FACTORY)));

        assertThat(data.get("canDo")).isEqualTo(false);
        assertThat(data.get("status")).isEqualTo("PREVIEW_INVALID");
        assertThat(String.valueOf(data.get("message"))).contains("实际产量");
        verify(service, never()).getById(anyString(), anyLong());
        assertNoMutation();
    }

    @Test
    @DisplayName("COMPLETE: 负数实际产量 → canDo=false PREVIEW_INVALID + 中文原因, 且不去读任务 (同写路径顺序)")
    void completeNegativeQuantityRefused() throws Exception {
        stubTask(FACTORY, task(Status.IN_PROGRESS)); // 任务本身是能完成的 —— 拒绝只能来自产量
        for (double negative : new double[]{-1, -0.01, -95.5}) {
            Map<String, Object> data = data(completeTool.preview(call("work_process_task_complete",
                    Map.of("taskId", TASK_ID, "actualQuantity", negative)), ctx(FACTORY)));

            assertThat(data.get("canDo")).as("" + negative).isEqualTo(false);
            assertThat(data.get("status")).as("" + negative).isEqualTo("PREVIEW_INVALID");
            assertThat(String.valueOf(data.get("message"))).as("" + negative)
                    .contains("实际产量不能是负数").doesNotContain("actualQuantity");
            assertThat(data).as("拒绝时不给对比表").doesNotContainKey("newValues");
        }
        verify(service, never()).getById(anyString(), anyLong());
        assertNoMutation();
    }

    @Test
    @DisplayName("COMPLETE 对照: 实际产量 0 合法 (什么都没产出), 与同一个任务的正数一样 canDo=true")
    void completeZeroQuantityAllowed() throws Exception {
        stubTask(FACTORY, task(Status.IN_PROGRESS));
        for (Object ok : new Object[]{0, 0.0, 0.01, 95.5}) {
            Map<String, Object> data = data(completeTool.preview(call("work_process_task_complete",
                    Map.of("taskId", TASK_ID, "actualQuantity", ok)), ctx(FACTORY)));

            assertThat(data.get("canDo")).as("" + ok).isEqualTo(true);
            assertThat(data.get("status")).as("" + ok).isEqualTo("PREVIEW");
            assertThat(rows(data, "newValues")).as("" + ok).containsKey("实际产量");
        }
        assertNoMutation();
    }

    @Test
    @DisplayName("COMPLETE: 完全没给实际产量 → 基类早退 NEED_MORE_INFO (success=false), 问句是人话")
    void completeMissingQuantityAsksForIt() throws Exception {
        Map<String, Object> root = parse(completeTool.preview(call("work_process_task_complete",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));

        assertThat(root.get("success")).isEqualTo(false);
        assertThat(root.get("status")).isEqualTo("NEED_MORE_INFO");
        assertThat(String.valueOf(root.get("message"))).contains("实际产量").doesNotContain("actualQuantity");
        verify(service, never()).getById(anyString(), anyLong());
        assertNoMutation();
    }

    @Test
    @DisplayName("COMPLETE: 任务不存在 → canDo=false")
    void completeTaskNotFound() throws Exception {
        when(service.getById(FACTORY, TASK_ID))
                .thenThrow(new ResourceNotFoundException("WorkProcessTask", "id", String.valueOf(TASK_ID)));

        Map<String, Object> data = data(completeTool.preview(call("work_process_task_complete",
                Map.of("taskId", TASK_ID, "actualQuantity", 10)), ctx(FACTORY)));

        assertThat(data.get("canDo")).isEqualTo(false);
        assertThat(data.get("status")).isEqualTo("TASK_NOT_FOUND");
        assertNoMutation();
    }

    // ==================== 确认卡只显示人认得的名字 (2026-10-06 prod 实测) ====================

    private static final String PRODUCT_UUID = "eb0aa47b-1111-4222-8333-444455556666";

    @Test
    @DisplayName("确认卡: 产品显示名称、批次显示批次号; 任何一行都不是 UUID / 「#批次id」")
    void confirmCardShowsProductNameAndBatchNumberNeverRawIds() throws Exception {
        WorkProcessTaskDTO dto = task(Status.PENDING);
        dto.setProductTypeId(PRODUCT_UUID);
        dto.setProductionBatchId(10805L);
        dto.setBatchNumber("PB-20261006-001");
        dto.setProductTypeName("卤猪蹄");
        stubTask(FACTORY, dto);

        Map<String, Object> data = data(startTool.preview(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));

        for (String side : new String[]{"currentValues", "newValues"}) {
            assertThat(rows(data, side)).as(side)
                    .containsEntry("产品", "卤猪蹄")
                    .containsEntry("批次", "PB-20261006-001");
            assertThat(rows(data, side).values().stream().map(String::valueOf)).as(side)
                    .noneMatch(v -> v.contains(PRODUCT_UUID) || v.contains("#10805"));
        }
    }

    @Test
    @DisplayName("确认卡对照: 产品名 / 批次号查不到 → 写「—」, 不回退成 UUID / 「#10805」")
    void confirmCardShowsDashWhenNamesAreUnresolvable() throws Exception {
        WorkProcessTaskDTO dto = task(Status.PENDING);
        dto.setProductTypeId(PRODUCT_UUID);
        dto.setProductTypeName(null);
        dto.setProductionBatchId(10805L);
        dto.setBatchNumber(null);
        stubTask(FACTORY, dto);

        Map<String, Object> data = data(startTool.preview(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));

        for (String side : new String[]{"currentValues", "newValues"}) {
            assertThat(rows(data, side)).as(side).containsEntry("产品", "—").containsEntry("批次", "—");
            assertThat(rows(data, side).values().stream().map(String::valueOf)).as(side)
                    .noneMatch(v -> v.contains(PRODUCT_UUID) || v.contains("#10805"));
        }
    }

    @Test
    @DisplayName("确认卡: 计划数量两边都没有 → 整行不显示; 对照: 有计划数量时仍显示")
    void rowsEmptyOnBothSidesAreOmitted() throws Exception {
        WorkProcessTaskDTO noPlan = task(Status.PENDING);
        noPlan.setPlannedQuantity(null);
        noPlan.setPlannedUnit(null);
        noPlan.setBatchNumber(null);
        noPlan.setProductionBatchId(null);
        stubTask(FACTORY, noPlan);

        Map<String, Object> data = data(startTool.preview(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));
        for (String side : new String[]{"currentValues", "newValues"}) {
            assertThat(rows(data, side)).as(side + " 无计划数量").doesNotContainKey("计划数量");
            // 身份行例外: 批次查不到时显式写「—」, 不整行消失
            assertThat(rows(data, side)).as(side + " 无批次").containsEntry("批次", "—");
            // 「状态」两边不同、「工序任务」「产品」有值 —— 这些必须留
            assertThat(rows(data, side)).as(side).containsKeys("工序任务", "产品", "状态");
        }

        // 对照: 同一个任务补上计划数量 → 那一行回来 (证明上面不是「所有行都被丢了」)
        stubTask(FACTORY, task(Status.PENDING));
        Map<String, Object> withPlan = data(startTool.preview(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));
        assertThat(rows(withPlan, "currentValues")).containsEntry("计划数量", "100 kg");
        assertThat(rows(withPlan, "newValues")).containsEntry("计划数量", "100 kg");
    }

    @Test
    @DisplayName("确认卡: 完成工序时「实际产量 — → 95 kg」只有一边有值, 必须保留")
    void rowWithValueOnOneSideIsKept() throws Exception {
        WorkProcessTaskDTO dto = task(Status.IN_PROGRESS);
        dto.setPlannedQuantity(null);
        dto.setPlannedUnit("kg");
        stubTask(FACTORY, dto);

        Map<String, Object> data = data(completeTool.preview(call("work_process_task_complete",
                Map.of("taskId", TASK_ID, "actualQuantity", 95)), ctx(FACTORY)));

        assertThat(rows(data, "currentValues")).containsEntry("实际产量", "—").doesNotContainKey("计划数量");
        assertThat(rows(data, "newValues")).containsEntry("实际产量", "95 kg").doesNotContainKey("计划数量");
    }

    @Test
    @DisplayName("确认卡: 负责人查不到姓名 → 「已指派」, 不写「#用户id」")
    void unresolvableAssigneeIsNotShownAsRawId() throws Exception {
        WorkProcessTaskDTO dto = task(Status.PENDING);
        dto.setAssignedTo(777L);
        dto.setAssignedToName(null);
        stubTask(FACTORY, dto);

        Map<String, Object> data = data(startTool.preview(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));

        assertThat(rows(data, "currentValues")).containsEntry("负责人", "已指派");
        assertThat(rows(data, "newValues")).containsEntry("负责人", "已指派");
    }

    @Test
    @DisplayName("开始工序的结果文案: 状态写中文「进行中」, 不是 IN_PROGRESS (prod 实测: 状态 → IN_PROGRESS)")
    void startResultMessageUsesChineseStatusLabel() throws Exception {
        WorkProcessTaskDTO started = task(Status.IN_PROGRESS);
        when(service.start(eq(FACTORY), eq(TASK_ID), any())).thenReturn(started);

        Map<String, Object> root = parse(startTool.execute(call("work_process_task_start",
                Map.of("taskId", TASK_ID)), ctx(FACTORY)));

        @SuppressWarnings("unchecked")
        String message = String.valueOf(((Map<String, Object>) root.get("data")).get("message"));
        assertThat(message).contains("工序任务 12 已开始").contains("进行中")
                .doesNotContain("IN_PROGRESS").doesNotContain("→");
    }

    // ==================== helpers ====================

    private void assertNoMutation() {
        verify(service, never()).start(anyString(), anyLong(), any());
        verify(service, never()).complete(anyString(), anyLong(), any(), any());
        verify(service, never()).skip(anyString(), anyLong(), any(), any());
        verify(service, never()).updatePlan(anyString(), anyLong(), any());
    }

    private void stubTask(String factoryId, WorkProcessTaskDTO dto) {
        when(service.getById(factoryId, TASK_ID)).thenReturn(dto);
    }

    private static WorkProcessTaskDTO task(Status status) {
        return WorkProcessTaskDTO.builder()
                .id(TASK_ID)
                .factoryId(FACTORY)
                .productionBatchId(301L)
                .batchNumber("B20261004-01")
                .productTypeId("PT-1")
                .productTypeName("卤猪蹄")
                .processName("切片")
                .status(status)
                .plannedQuantity(new BigDecimal("100.00"))
                .plannedUnit("kg")
                .build();
    }

    private static ToolCall call(String name, Map<String, Object> args) throws Exception {
        return ToolCall.of("tc-1", name, new ObjectMapper().writeValueAsString(args));
    }

    private static Map<String, Object> ctx(String factoryId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
        ctx.put("userId", 42L);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rows(Map<String, Object> data, String key) {
        assertThat(data).containsKey(key);
        return (Map<String, Object>) data.get(key);
    }

    private void inject(Object tool) throws Exception {
        Class<?> c = tool.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("objectMapper");
                f.setAccessible(true);
                f.set(tool, mapper);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("objectMapper field not found");
    }
}
