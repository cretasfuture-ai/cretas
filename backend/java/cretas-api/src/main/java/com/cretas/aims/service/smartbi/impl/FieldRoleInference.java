package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.entity.smartbi.postgres.SmartBiPgFieldDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 字段定义里「既不是指标、也不是维度、也不是时间」的列，按实际取值补判角色。
 *
 * 味美 11-19 实测 (FOOD_0000_001): 字段映射认不出的列 (停机分钟、平均肉温、小料kg、
 * 理论产能kg、醒发分钟、温度℃...) 存进 smart_bi_pg_field_definitions 时
 * field_type 为空、三个角色全 false。整张表没有指标 → generateCharts 为空 →
 * 保存时 422「上传数据暂无可分析图表」。
 *
 * 规则 (只动三个角色全 false 的列，已有判定一律不改):
 * <ol>
 *   <li>列名像编号 (编码/编号/批号/单号/序号/code/id) → 维度，即使取值是数字;</li>
 *   <li>非空取值里 ≥ 80% 是数字 → 指标 (field_type=NUMERIC, chart_role=y_axis);</li>
 *   <li>否则，非空覆盖 ≥ 50% 且去重值 ≤ 50 个 (或 ≤ 非空数的一半) → 维度;</li>
 *       列名是时间/时刻/时段/备注/说明 (含「原因」除外) 的不当维度;</li>
 *   <li>其余 (大段说明、几乎全空、时刻 05:54 这类) 保持不分类。</li>
 * </ol>
 */
public final class FieldRoleInference {

    private static final Pattern ID_LIKE = Pattern.compile(
            ".*(编码|编号|批号|单号|序号|流水号|条码|(?i:code)|(?i:\\bid\\b)).*");
    /** 时刻、时段、备注类自由文本不当分组维度 (含「原因」的说明列除外)。 */
    private static final Pattern NOT_A_GROUP = Pattern.compile(".*(时间|时刻|时段|备注|说明).*");
    private static final Pattern NUMERIC = Pattern.compile("^[-+]?\\d[\\d,]*(\\.\\d+)?%?$|^[-+]?\\.\\d+%?$");

    private FieldRoleInference() {
    }

    /** 列是否尚未分类 (三个角色全不是 true)。 */
    static boolean isUnclassified(SmartBiPgFieldDefinition def) {
        return !Boolean.TRUE.equals(def.getIsMeasure())
                && !Boolean.TRUE.equals(def.getIsDimension())
                && !Boolean.TRUE.equals(def.getIsTime());
    }

    static boolean isNumericValue(Object v) {
        if (v == null) return false;
        if (v instanceof Number) return true;
        String s = v.toString().trim();
        return !s.isEmpty() && NUMERIC.matcher(s).matches();
    }

    /** 从数据行按列收集取值 (行以原列名为键)。 */
    public static Map<String, List<Object>> valuesByColumn(List<Map<String, Object>> rows) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        if (rows == null) return out;
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        return out;
    }

    /**
     * 按取值补判未分类列的角色，原地修改 defs。
     *
     * @param defs          字段定义
     * @param valuesByColumn 原列名 → 该列取值 (可含 null/空串)
     * @return 被改动的字段定义
     */
    public static List<SmartBiPgFieldDefinition> refine(List<SmartBiPgFieldDefinition> defs,
                                                        Map<String, List<Object>> valuesByColumn) {
        List<SmartBiPgFieldDefinition> changed = new ArrayList<>();
        if (defs == null || valuesByColumn == null || valuesByColumn.isEmpty()) return changed;
        for (SmartBiPgFieldDefinition def : defs) {
            if (def == null || !isUnclassified(def)) continue;
            String name = def.getOriginalName();
            List<Object> values = valuesByColumn.get(name);
            if (values == null || values.isEmpty()) continue;

            int total = values.size();
            int nonBlank = 0;
            int numeric = 0;
            Set<String> distinct = new HashSet<>();
            for (Object v : values) {
                if (v == null || v.toString().trim().isEmpty()) continue;
                nonBlank++;
                if (isNumericValue(v)) numeric++;
                distinct.add(v.toString().trim());
            }
            if (nonBlank == 0) continue;

            boolean idLike = name != null && ID_LIKE.matcher(name).matches();
            boolean lowCardinality = distinct.size() <= 50 || distinct.size() * 2 <= nonBlank;
            boolean wellCovered = nonBlank * 2 >= total;

            if (idLike) {
                if (!lowCardinality && !wellCovered) continue;
                def.setIsDimension(true);
                if (isBlank(def.getFieldType())) def.setFieldType("TEXT");
                if (isBlank(def.getChartRole())) def.setChartRole("series");
                changed.add(def);
            } else if (numeric * 10 >= nonBlank * 8) {
                def.setIsMeasure(true);
                if (isBlank(def.getFieldType())) def.setFieldType("NUMERIC");
                if (isBlank(def.getChartRole())) def.setChartRole("y_axis");
                changed.add(def);
            } else if (wellCovered && lowCardinality
                    && (name == null || !NOT_A_GROUP.matcher(name).matches() || name.contains("原因"))) {
                def.setIsDimension(true);
                if (isBlank(def.getFieldType())) def.setFieldType("TEXT");
                if (isBlank(def.getChartRole())) def.setChartRole("series");
                changed.add(def);
            }
        }
        return changed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
