package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP 问题 47 复测: 趋势分析兜底摘要直接露「period: 30 / materialTrend: 0条 / productionTrend: 31条」英文字段名。
 * 数据形状就是 ProcessingServiceImpl 趋势分析的真实返回 (5 条序列 + metric + period)。
 */
class TrendsFallbackLabelsContractTest {

    @Test
    void fallbackSummaryShowsChineseLabelsNotFieldNames() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productionTrend", filled(31));
        data.put("qualityTrend", filled(31));
        data.put("costTrend", filled(31));
        data.put("materialTrend", new ArrayList<>());
        data.put("equipmentTrend", new ArrayList<>());
        data.put("metric", "QUALITY");   // 非产量指标: 不走「产量日序列」分支, 落到兜底摘要 (线上 execute_read 读到的就是这一形状)
        data.put("period", 30);
        String reply = new ResultFormatterServiceImpl().format(IntentExecuteResponse.builder()
                .intentCode("REPORT_TRENDS").intentCategory("REPORT").status("SUCCESS").resultData(data).build());
        assertThat(reply).contains("统计周期(天): 30", "产量趋势数据点: 31条");
        assertThat(reply).doesNotContain("period:", "materialTrend", "productionTrend", "qualityTrend", "costTrend", "equipmentTrend");
    }

    private static List<Map<String, Object>> filled(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(Map.of("date", "2026-10-" + (i + 1), "value", i));
        return rows;
    }
}
