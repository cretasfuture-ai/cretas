package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.TimeRangeFilter;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.report.ReportInventoryTool;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.config.ColloquialMappings;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.impl.QueryPreprocessorServiceImpl;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 走 Skill 的意图 (tool_name 为空, 如 REPORT_INVENTORY) 以前给工具的参数是空的 (S6 验收 2026-11-03:
 * 「上个月的库存」回了今天的报表, 没有「不能按时间筛选」提示)。
 *
 * <ul>
 *   <li>Skill 入口 ({@link DynamicToolSelectionService#sentenceParams}) 带上原话与<b>真的规则预处理</b>出的时间;</li>
 *   <li>库存报表是当前时点快照 ({@code x-time-range: none}): 句子里的时间不被当成「报表日期」, 回复开头说明;</li>
 *   <li>Skill 把结果包成 {toolName: result, _toolCount: 1}, 格式化仍认得里面的标记。</li>
 * </ul>
 */
@DisplayName("Skill 路由: 原话与时间到工具; 库存快照不冒充「上个月」")
class SkillRouteSentenceContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamicToolSelectionService selection;
    private final LocalDate lastMonthStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
    private final LocalDate lastMonthEnd = lastMonthStart.withDayOfMonth(lastMonthStart.lengthOfMonth());

    @BeforeEach
    void setUp() {
        selection = new DynamicToolSelectionService(mock(com.cretas.aims.service.ToolRouterService.class),
                mock(ToolRegistry.class), mapper);
        ReflectionTestUtils.setField(selection, "queryPreprocessorService",
                new QueryPreprocessorServiceImpl(new TimeNormalizationRules(), new ColloquialMappings(),
                        null, null, mapper, null, null));
    }

    @Test
    void theSkillEntryCarriesTheSentenceAndTheTimeRangeSaid() {
        Map<String, Object> params = selection.sentenceParams("上个月的库存");

        assertThat(params).containsEntry("userInput", "上个月的库存")
                .containsEntry("startDate", lastMonthStart.toString())
                .containsEntry("endDate", lastMonthEnd.toString())
                .containsEntry(TimeRangeFilter.SAID, lastMonthStart + "~" + lastMonthEnd);
        // 阴性对照: 不说时间就只有原话
        assertThat(selection.sentenceParams("查询库存")).containsOnlyKeys("userInput");
    }

    @Test
    void anInventorySnapshotIsNotPassedOffAsLastMonth() throws Exception {
        ReportService reports = mock(ReportService.class);
        when(reports.getInventoryReport(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("totalValue", 1));
        ReportInventoryTool tool = new ReportInventoryTool();
        ReflectionTestUtils.setField(tool, "reportService", reports);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);

        Map<String, Object> args = new HashMap<>(selection.sentenceParams("上个月的库存"));
        ToolCall call = ToolCall.of("c1", "report_inventory", mapper.writeValueAsString(args));
        Map<?, ?> out = mapper.readValue(tool.execute(call, Map.of("factoryId", "F-INV", "userId", 1L)), Map.class);
        Map<?, ?> result = (Map<?, ?>) out.get("data");

        // 句子里的「上个月」没有被当成报表日期: 服务按今天的快照查, 结果标明时间没用上
        verify(reports).getInventoryReport(eq("F-INV"), eq(LocalDate.now()));
        assertThat(result.get(TimeRangeFilter.NOT_APPLIED)).isNotNull();

        // Skill 包的形状 {report_inventory: result, _toolCount: 1}: 回复开头说明, 不是「库存报告 (今日)」冒充
        Map<String, Object> skillData = new LinkedHashMap<>();
        skillData.put("report_inventory", result);
        skillData.put("_executionOrder", "deterministic");
        skillData.put("_toolCount", 1);
        IntentExecuteResponse response = IntentExecuteResponse.builder().intentCode("REPORT_INVENTORY")
                .intentCategory("REPORT").status("SUCCESS").resultData(skillData).build();
        String reply = new ResultFormatterServiceImpl().format(response);

        assertThat(reply).startsWith("注意：库存报表是当前时点的库存").contains(lastMonthStart.toString());
    }
}
