package com.cretas.aims.service.smartbi.chart;

import com.cretas.aims.dto.smartbi.chart.*;
import java.util.*;

/**
 * Main interface for adaptive chart generation.
 * Orchestrates the full flow from data analysis to chart generation.
 *
 * <p>工艺保密 (Steve 2026-10-29): 样本行和列名会发给外部模型, 按 {@code factoryId} (上传所属工厂, 服务端认定)
 * 的保密产品名脱敏。不带工厂的旧签名按 {@code LlmEgressGuard} 的绑定 / 登录身份, 都没有则不发。
 */
public interface AdaptiveChartGenerator {

    /**
     * Generate optimal charts based on data features and evaluation
     * @param factoryId Factory whose data this is (masking scope)
     * @param data List of row data
     * @param features Extracted data features
     * @param evaluation Sufficiency evaluation result
     * @param maxCharts Maximum number of charts to generate
     * @return List of generated charts
     */
    List<AdaptiveChartResponse.GeneratedChart> generateOptimalCharts(
        String factoryId,
        List<Map<String, Object>> data,
        DataFeatures features,
        ChartSufficiencyResult evaluation,
        int maxCharts
    );

    default List<AdaptiveChartResponse.GeneratedChart> generateOptimalCharts(
        List<Map<String, Object>> data,
        DataFeatures features,
        ChartSufficiencyResult evaluation,
        int maxCharts
    ) {
        return generateOptimalCharts(null, data, features, evaluation, maxCharts);
    }

    /**
     * Generate a single chart of specific type
     * @param factoryId Factory whose data this is (masking scope)
     * @param data List of row data
     * @param features Extracted data features
     * @param chartType The type of chart to generate
     * @param purpose Description of chart purpose
     * @return Generated chart configuration
     */
    AdaptiveChartResponse.GeneratedChart generateChart(
        String factoryId,
        List<Map<String, Object>> data,
        DataFeatures features,
        String chartType,
        String purpose
    );

    default AdaptiveChartResponse.GeneratedChart generateChart(
        List<Map<String, Object>> data,
        DataFeatures features,
        String chartType,
        String purpose
    ) {
        return generateChart(null, data, features, chartType, purpose);
    }

    /**
     * Full adaptive chart generation flow
     * @param factoryId Factory from the request path; the upload must belong to it
     * @param uploadId The upload session ID
     * @param request Adaptive chart generation request
     * @return Complete adaptive chart response
     */
    AdaptiveChartResponse generateAdaptive(
        String factoryId,
        Long uploadId,
        AdaptiveChartRequest request
    );
}
