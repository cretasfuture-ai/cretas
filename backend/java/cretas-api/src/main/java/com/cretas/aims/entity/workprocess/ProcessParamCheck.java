package com.cretas.aims.entity.workprocess;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工艺标准参数比对: 拿报工时录的实际值 (油温、炒制时间、绞碎孔径、水分…) 对照工序上配置的
 * 上下限, 产出一份<b>当时标准的快照</b>。
 *
 * <h2>为什么存快照而不是读的时候再比</h2>
 *
 * 标准会改 (研发验证后收窄范围是常态)。拿今天的标准去评判上个月的报工, 结论会悄悄变 ——
 * 追溯时要回答的是「当时按什么标准判的」。所以落库时把标准值、上下限、单位连同判定一起写进去,
 * 与 HACCP 监控记录快照上下限是同一个道理。
 *
 * <h2>只比有范围的数字字段</h2>
 *
 * 只配了标准值没配上下限的字段不判 (标准值本身不是合格线); 没填值、填的不是数字的不判 ——
 * 不猜, 不补 0。
 */
public final class ProcessParamCheck {

    private ProcessParamCheck() {
    }

    public static final String STATUS_OK = "OK";
    public static final String STATUS_LOW = "LOW";
    public static final String STATUS_HIGH = "HIGH";

    /**
     * @param schema 工序 customFieldSchema (可空)
     * @param values 报工提交的自定义字段值 (可空)
     * @return 每个「配了上下限且填了数字」的字段一条快照; 没有可判的字段返回空列表
     */
    public static List<Map<String, Object>> evaluate(List<Map<String, Object>> schema,
                                                     Map<String, Object> values) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (schema == null || values == null || values.isEmpty()) {
            return checks;
        }
        for (Map<String, Object> field : schema) {
            if (field == null || !"number".equals(field.get("type"))) {
                continue;
            }
            BigDecimal min = lenientDecimal(field.get("min"));
            BigDecimal max = lenientDecimal(field.get("max"));
            if (min == null && max == null) {
                continue;
            }
            String key = String.valueOf(field.get("key"));
            BigDecimal value = lenientDecimal(values.get(key));
            if (value == null) {
                continue;
            }
            String status = STATUS_OK;
            if (min != null && value.compareTo(min) < 0) {
                status = STATUS_LOW;
            } else if (max != null && value.compareTo(max) > 0) {
                status = STATUS_HIGH;
            }
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("key", key);
            check.put("label", field.get("label"));
            check.put("unit", field.get("unit"));
            check.put("value", value);
            check.put("standardValue", lenientDecimal(field.get("standardValue")));
            check.put("min", min);
            check.put("max", max);
            check.put("status", status);
            checks.add(check);
        }
        return checks;
    }

    /** 是否有任何一项超出范围。 */
    public static boolean hasDeviation(List<Map<String, Object>> checks) {
        return checks != null && checks.stream().anyMatch(c -> !STATUS_OK.equals(c.get("status")));
    }

    /**
     * 严格解析: {@code null}/空串 → null; 数字或数字串 → BigDecimal; 其它抛 IllegalArgumentException。
     * 给 schema 校验用 (配置里的非数字必须拒)。
     */
    static BigDecimal decimalOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean) {
            throw new IllegalArgumentException("not a number: " + raw);
        }
        if (raw instanceof Number || raw instanceof String) {
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: " + raw, e);
            }
        }
        throw new IllegalArgumentException("not a number: " + raw);
    }

    /** 宽松解析: 解析不了就当没填 (报工值不在这里校验, 只是不判)。 */
    private static BigDecimal lenientDecimal(Object raw) {
        try {
            return decimalOrNull(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
