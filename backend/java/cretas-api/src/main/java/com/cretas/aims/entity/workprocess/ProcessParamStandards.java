package com.cretas.aims.entity.workprocess;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工艺参数的「产品标准」: 标准值/上下限/单位存在各产品工艺画布工序节点的 {@code data.processParams} 里,
 * 跟着产品工艺版本发布, 产品之间互不影响。报工判超标时, 先取<b>本计划锁定的那一版</b>画布里对应工序节点的
 * 参数, 按 label 覆盖到工序 {@code customFieldSchema} 里同名数字字段的 单位/标准值/下限/上限 上, 再交给
 * {@link ProcessParamCheck#evaluate}。
 *
 * <ul>
 *   <li>画布没配这个参数(或该产品没有这道节点的参数) → schema 原样返回 = 旧行为。</li>
 *   <li>画布参数某一项没给(null/空) → 该项保留工序 schema 上原有的值(旧行为), 不当成 0。</li>
 *   <li>只覆盖 schema 里已有的同名<b>数字</b>字段; 画布参数在 schema 里没有对应字段就没有录入框, 不判。
 *       发布时 {@code WorkflowProcessParamMaterializer} 会补出这个字段(只有名字和单位)。</li>
 *   <li>工序在图里出现多次且报工侧给不出节点 id 时不猜, 不覆盖。</li>
 * </ul>
 */
public final class ProcessParamStandards {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> OVERRIDABLE = List.of("unit", "standardValue", "min", "max");

    private ProcessParamStandards() {
    }

    /**
     * 画布节点 JSON(节点数组) 里某道工序节点的 processParams。
     *
     * @param workflowNodeId 节点 id; 为空时按 workProcessId 找, 且只在唯一匹配时返回
     * @return 没有、解析不了或有歧义 → 空列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> paramsOf(String nodesJson, String workflowNodeId, String workProcessId) {
        if (nodesJson == null || nodesJson.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> nodes;
        try {
            nodes = MAPPER.readValue(nodesJson, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            if (node == null || !"PROCESS".equals(node.get("kind")) || !(node.get("data") instanceof Map<?, ?> data)) {
                continue;
            }
            boolean hit = workflowNodeId != null && !workflowNodeId.isBlank()
                    ? workflowNodeId.equals(node.get("id"))
                    : workProcessId != null && workProcessId.equals(data.get("workProcessId"));
            if (hit) {
                matched.add((Map<String, Object>) data);
            }
        }
        if (matched.size() != 1 || !(matched.get(0).get("processParams") instanceof List<?> params)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object p : params) {
            if (p instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /** 把画布参数的 单位/标准值/上下限 覆盖到 schema 里同 label 的数字字段上; 不改传入对象。 */
    public static List<Map<String, Object>> overlay(List<Map<String, Object>> schema,
                                                    List<Map<String, Object>> canvasParams) {
        if (schema == null || canvasParams == null || canvasParams.isEmpty()) {
            return schema;
        }
        List<Map<String, Object>> out = new ArrayList<>(schema.size());
        for (Map<String, Object> field : schema) {
            if (field == null || !"number".equals(field.get("type"))) {
                out.add(field);
                continue;
            }
            String label = String.valueOf(field.get("label")).trim();
            Map<String, Object> param = canvasParams.stream()
                    .filter(p -> label.equals(String.valueOf(p.get("label")).trim()))
                    .findFirst().orElse(null);
            if (param == null) {
                out.add(field);
                continue;
            }
            Map<String, Object> merged = new LinkedHashMap<>(field);
            for (String k : OVERRIDABLE) {
                Object v = param.get(k);
                if (v instanceof String s) {
                    v = s.isBlank() ? null : s.trim();
                }
                if (v != null) {
                    merged.put(k, v);
                }
            }
            out.add(merged);
        }
        return out;
    }
}
