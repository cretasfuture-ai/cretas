package com.cretas.aims.service.smartbi.chart;

import com.cretas.aims.dto.smartbi.chart.*;
import java.util.*;

/**
 * Interface for evaluating chart sufficiency via LLM.
 * Determines if a chart type adequately represents the data.
 *
 * <p>工艺保密 (Steve 2026-10-29): 列名会发给外部模型, 按 {@code factoryId} (数据所属工厂, 服务端认定)
 * 的保密产品名脱敏。不带工厂的旧签名按 {@code LlmEgressGuard} 的绑定 / 登录身份, 都没有则走规则评估 (不发)。
 */
public interface ChartSufficiencyEvaluator {

    /**
     * Evaluate if current chart type sufficiently displays the data
     * @param factoryId Factory whose data this is (masking scope)
     * @param data List of row data
     * @param features Extracted data features
     * @param currentChartType The chart type being evaluated
     * @return ChartSufficiencyResult with evaluation details
     */
    ChartSufficiencyResult evaluate(
        String factoryId,
        List<Map<String, Object>> data,
        DataFeatures features,
        String currentChartType
    );

    default ChartSufficiencyResult evaluate(
        List<Map<String, Object>> data,
        DataFeatures features,
        String currentChartType
    ) {
        return evaluate(null, data, features, currentChartType);
    }

    /**
     * Evaluate without existing chart (initial analysis)
     * @param factoryId Factory whose data this is (masking scope)
     * @param data List of row data
     * @param features Extracted data features
     * @return ChartSufficiencyResult with recommended chart types
     */
    ChartSufficiencyResult evaluateInitial(
        String factoryId,
        List<Map<String, Object>> data,
        DataFeatures features
    );

    default ChartSufficiencyResult evaluateInitial(
        List<Map<String, Object>> data,
        DataFeatures features
    ) {
        return evaluateInitial(null, data, features);
    }
}
