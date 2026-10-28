package com.cretas.aims.dto.view;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 保存视图的请求 / 响应对象。响应里绝不带结果行 (执行结果走 {@link ExecuteResult}, 不落库)。 */
public final class SavedViewDtos {

    private SavedViewDtos() {
    }

    /** 创建 / 修改。{@code version} 仅修改时用于并发保护。 */
    public record SaveRequest(String name, JsonNode definition, Boolean shared, Integer version) {
    }

    public record ViewSummary(String id, String name, String entity, Integer version, boolean shared,
                              boolean mine, Long ownerUserId, JsonNode definition,
                              LocalDateTime updatedAt) {
    }

    public record ExecuteResult(String viewId, String name, Integer version, String entity,
                                List<String> columns, List<String> hiddenColumns,
                                List<Map<String, Object>> rows, int rowCount, boolean truncated,
                                LocalDateTime executedAt) {
    }

    public record EntityOption(String key, String label, boolean allowed, List<FieldOption> fields) {
    }

    public record FieldOption(String name, String label, String type, boolean allowed, List<String> ops) {
    }
}
