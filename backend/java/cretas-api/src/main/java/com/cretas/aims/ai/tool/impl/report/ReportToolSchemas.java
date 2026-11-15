package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.service.execution.ToolDispatchService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只有可选参数的只读查询工具, 默认不会让大模型从原话里抽参 (ToolDispatchService 只对声明了
 * {@link ToolDispatchService#EXTRACT_OPTIONAL_FLAG} 的工具抽, 避免每次多一次模型调用)。
 * 成本 / 毛利 / 出成率这类问法常带产品名 (「卤牛肉单位成本」), 不抽就会答成全厂汇总 (2026-11-15 财务复跑 Q01/Q02/Q16)。
 * 日期键 schema 带 format:date, 由 LlmParameterGrounding 丢掉原话里没说过的日期; 产品名不靠落地校验, 由工具拿本厂在用产品目录解析 (完全同名优先, 多个包含匹配时给候选不合计)。
 */
final class ReportToolSchemas {
    private ReportToolSchemas() {}

    static Map<String, Object> withOptionalExtraction(Map<String, Object> schema) {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put(ToolDispatchService.EXTRACT_OPTIONAL_FLAG, true);
        return copy;
    }
}
