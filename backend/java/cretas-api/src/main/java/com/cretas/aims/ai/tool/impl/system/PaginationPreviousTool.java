package com.cretas.aims.ai.tool.impl.system;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Shares the validated session/tenant replay contract, but never defaults to page+1. */
@Component
public class PaginationPreviousTool extends PaginationNextTool {
    @Override public String getToolName() { return "pagination_previous"; }
    @Override public String getDescription() { return "返回当前用户和工厂最近列表查询的上一页。"; }
    @Override protected int defaultTargetPage(int currentPage) { return currentPage - 1; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of("targetPage", Map.of(
                "type", "integer", "description", "目标页码；不传时采用上次页码-1")), "required", List.of());
    }
}
