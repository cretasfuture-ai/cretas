package com.cretas.aims.service.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.processing.ProcessTaskQueryTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.service.execution.ToolDispatchService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 2026-10-06 prod 实测: 「今天我的工序任务」, ProcessTaskQueryTool 返回了 16 条任务,
 * 一线只看到「查询完成 包含 2 项数据指标 — 详情请查看下方数据卡片或对应报表模块。」, 没有任何卡片。
 *
 * <p>根因 (ResultFormatterServiceImpl#formatGenericList → formatDataSummary):
 * 工具结果是信封 {@code {message, data:{tasks:[…], total}}}, resultData 的外层只有 message 和 data 两个键,
 * 一个是字符串、一个是嵌套 Map —— 既没有 {@code content} 列表, 也没有顶层数字/列表, 于是落到
 * 「包含 N 项数据指标」这句占位模板, N 恰好是外层键数 (2)。
 *
 * <p>本测试走<b>真实链路</b>: 真 {@link ProcessTaskQueryTool#execute} → 真
 * {@link ToolDispatchService#parseToolResultToResponse} → 真 {@link ResultFormatterServiceImpl#format},
 * 只桩掉底层 {@link WorkProcessTaskService} (外部 IO)。
 */
@DisplayName("工具结果里的「行列表」要写进文本 (信封 / 平铺), 不能落成「包含 N 项数据指标」")
class ResultFormatterToolListEnvelopeTest {

    private static final String FACTORY = "F006";
    private static final String TEMPLATE = "项数据指标";
    private static final String PRODUCT_UUID = "eb0aa47b-1111-4222-8333-444455556666";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResultFormatterServiceImpl formatter = new ResultFormatterServiceImpl();
    private WorkProcessTaskService taskService;
    private ProcessTaskQueryTool tool;
    private ToolDispatchService dispatch;
    private AIIntentConfig intent;

    @BeforeEach
    void setUp() throws Exception {
        taskService = mock(WorkProcessTaskService.class);
        tool = new ProcessTaskQueryTool(taskService);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        dispatch = mock(ToolDispatchService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        ReflectionTestUtils.setField(dispatch, "objectMapper", mapper);
        // 与 prod 意图配置一致: V20260312_08 里 PROCESS_TASK_QUERY 的 category 是 DATA_QUERY
        intent = new AIIntentConfig();
        intent.setIntentCode("PROCESS_TASK_QUERY");
        intent.setIntentName("查询工序任务");
        intent.setIntentCategory("DATA_QUERY");
        intent.setToolName("process_task_query");
    }

    // ==================== 真实链路: 16 条工序任务 ====================

    @Test
    @DisplayName("16 条工序任务 → 文本里有「共 16 条」和前 8 行 (工序 · 产品 · 批次 · 中文状态); 没有占位模板")
    void sixteenTasksBecomeAReadableList() throws Exception {
        String text = runQueryTool(tasks(16));

        assertThat(text).as("文本:\n" + text).contains("共 16 条").contains("以上是前 8 条");
        // 每一行都要出来 —— 前 8 道工序名各不相同 (参数化的那一维真的在变)
        for (int i = 1; i <= 8; i++) {
            assertThat(text).as("第 " + i + " 行").contains("工序" + i + " ·").contains("PB-2026100" + i + " ·");
        }
        // 对照: 第 9 条起不列 (上限 8), 证明「共 16 条」不是把 16 条全摊开
        assertThat(text).doesNotContain("工序9 ·").doesNotContain("工序16 ·");
        assertThat(text).contains("1. 工序1 · 卤猪蹄 · PB-20261001 · 待开始");
        assertThat(text).contains("2. 工序2 · 卤猪蹄 · PB-20261002 · 进行中");
        // 阴性: 占位模板、英文状态码、UUID、内部编号都不许出现
        assertThat(text).doesNotContain(TEMPLATE).doesNotContain("详情请查看下方数据卡片")
                .doesNotContain("PENDING").doesNotContain("IN_PROGRESS")
                .doesNotContain(PRODUCT_UUID).doesNotContain("productionBatchId");
        // 工具自己说的那句话 (来自 tool.message) 作为抬头保留
        assertThat(text).startsWith("查询到 16 个工序任务");
    }

    @Test
    @DisplayName("占位模板分支仍然存在 (对照): 信封里没有 message 也没有列表 → 老模板, 证明上面的断言不是恒真")
    void templateStillReachableWhenEnvelopeCarriesNothingUseful() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("data", Map.of("nested", Map.of("a", 1)));

        String text = formatter.format(response("DATA_QUERY", "PROCESS_TASK_QUERY", data));

        assertThat(text).contains("包含 1 项数据指标");
    }

    @Test
    @DisplayName("少于 8 条: 只说「共 N 条。」, 不说「以上是前」")
    void shortListHasNoTruncationNote() throws Exception {
        String text = runQueryTool(tasks(3));

        assertThat(text).contains("共 3 条。").doesNotContain("以上是前");
        assertThat(text).contains("1. 工序1").contains("3. 工序3");
    }

    @Test
    @DisplayName("0 条: 工具那句话 + 「共 0 条。」, 不是「包含 2 项数据指标」")
    void emptyListSaysZero() throws Exception {
        String text = runQueryTool(tasks(0));

        assertThat(text).startsWith("查询到 0 个工序任务").contains("共 0 条。").doesNotContain(TEMPLATE);
    }

    // ==================== 平铺形状 / 行里的脏字段 ====================

    @Test
    @DisplayName("平铺形状 {message, tasks:[…], total} 同样列出; 英文键「tasks」不外泄")
    void flatShapeAlsoListsRows() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "共找到工序任务");
        data.put("tasks", List.of(
                row("切片", "PB-1", "IN_PROGRESS", "卤猪蹄"),
                row("装袋", "PB-2", "PENDING", "卤猪蹄")));
        data.put("total", 2);

        String text = formatter.format(response("DATA_QUERY", "PROCESS_TASK_QUERY", data));

        assertThat(text).contains("1. 切片 · 卤猪蹄 · PB-1 · 进行中").contains("2. 装袋 · 卤猪蹄 · PB-2 · 待开始")
                .contains("共 2 条。").doesNotContain("tasks").doesNotContain("- total");
    }

    @Test
    @DisplayName("行里的 UUID 和英文状态码不显示; 认不出的状态直接省略, 不回退成英文码")
    void rawIdsAndUnknownStatusCodesAreNeverShown() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", 1827);
        r.put("name", PRODUCT_UUID);              // 名字字段里是 UUID (数据缺陷): 不显示
        r.put("productName", "卤猪蹄");
        r.put("status", "SOME_NEW_STATE");        // 状态表里没有
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "查询结果");
        data.put("items", List.of(r));

        String text = formatter.format(response("DATA_QUERY", "X", data));

        assertThat(text).contains("1. 卤猪蹄").doesNotContain(PRODUCT_UUID).doesNotContain("SOME_NEW_STATE");
    }

    @Test
    @DisplayName("信封里没有列表、但工具有一句话 → 用工具那句话, 不是占位模板")
    void envelopeWithoutListFallsBackToToolMessage() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "工序任务 12 无进行中记录");
        data.put("data", Map.of("taskId", 12));

        String text = formatter.format(response("DATA_QUERY", "PROCESS_TASK_SUMMARY", data));

        assertThat(text).isEqualTo("工序任务 12 无进行中记录");
    }

    // ==================== 对照: 单值 KPI 不变 ====================

    @Test
    @DisplayName("对照: 单值 KPI 结果 (顶层数字) 文本与改动前完全一致")
    void singleValueKpiResultIsUnchanged() {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("totalOutput", 120);

        String text = formatter.format(response("DATA_QUERY", "SOME_KPI", kpi));

        assertThat(text).isEqualTo("查询完成\n- 总产量: 120");
    }

    @Test
    @DisplayName("对照: 老的 content 列表路径不变 (分页结果仍走原分支)")
    void contentListPathIsUnchanged() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("content", List.of(Map.of("name", "切片机")));
        page.put("totalElements", 5);

        String text = formatter.format(response("DATA_QUERY", "SOME_LIST", page));

        assertThat(text).isEqualTo("查询到 5 条记录：\n• 切片机");
    }

    // ==================== helpers ====================

    private String runQueryTool(List<WorkProcessTaskDTO> all) throws Exception {
        when(taskService.list(eq(FACTORY), any(), any(), any(), any()))
                .thenReturn(PageResponse.of(all, 1, 200, (long) all.size()));
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY);
        ctx.put("userId", 42L);
        ctx.put("userRole", "operator");
        String json = tool.execute(ToolCall.of("tc-1", "process_task_query", "{}"), ctx);

        IntentExecuteResponse response = dispatch.parseToolResultToResponse(json, intent);
        assertThat(response.getStatus()).as("工具结果解析: " + json).isEqualTo("SUCCESS");
        // 复现 prod 现场: 解析后 resultData 外层恰好是 {message, data} 两个键 → 老路径的「2 项」
        assertThat(response.getResultData()).isInstanceOf(Map.class);
        assertThat(new java.util.HashSet<Object>(((Map<?, ?>) response.getResultData()).keySet()))
                .containsExactlyInAnyOrder("message", "data");
        String text = formatter.format(response);
        assertThat(text).isNotBlank();
        return text;
    }

    /** 交替 PENDING / IN_PROGRESS, 工序名、批次号逐行不同; 带一个 UUID 产品编号 (不该出现在文本里)。 */
    private static List<WorkProcessTaskDTO> tasks(int n) {
        List<WorkProcessTaskDTO> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(WorkProcessTaskDTO.builder()
                    .id(1800L + i)
                    .factoryId(FACTORY)
                    .productionBatchId(10800L + i)
                    .batchNumber(String.format("PB-202610%02d", i))
                    .productTypeId(PRODUCT_UUID)
                    .productTypeName("卤猪蹄")
                    .processName("工序" + i)
                    .status(i % 2 == 1 ? Status.PENDING : Status.IN_PROGRESS)
                    .plannedQuantity(new BigDecimal("100"))
                    .plannedUnit("kg")
                    .build());
        }
        return list;
    }

    private static Map<String, Object> row(String process, String batch, String status, String product) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", 1);
        r.put("processName", process);
        r.put("batchNumber", batch);
        r.put("status", status);
        r.put("productName", product);
        return r;
    }

    private static IntentExecuteResponse response(String category, String code, Object resultData) {
        return IntentExecuteResponse.builder()
                .intentCategory(category).intentCode(code).status("SUCCESS").resultData(resultData).build();
    }
}
