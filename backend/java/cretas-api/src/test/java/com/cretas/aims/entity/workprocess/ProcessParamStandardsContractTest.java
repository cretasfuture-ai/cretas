package com.cretas.aims.entity.workprocess;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("工艺标准参数: schema 形状校验 + 报工值比对")
class ProcessParamStandardsContractTest {

    private static Map<String, Object> field(String key, String type, Object... kv) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", key + "-label");
        f.put("type", type);
        f.put("enabled", true);
        for (int i = 0; i < kv.length; i += 2) {
            f.put((String) kv[i], kv[i + 1]);
        }
        return f;
    }

    private static String problem(Map<String, Object> f) {
        return WorkProcessCustomFieldSchema.firstProblemMessage(List.of(f));
    }

    @Test
    @DisplayName("合法: 数字字段带单位/标准值/上下限; 旧的四键字段照常通过")
    void validStandards() {
        assertThat(problem(field("oilTemp", "number", "unit", "℃", "standardValue", 155, "min", 150, "max", 160)))
                .isNull();
        assertThat(problem(field("note", "text"))).isNull();
        // 只有下限 / 数字串 / 清空成 null 都可以
        assertThat(problem(field("moisture", "number", "min", "8.5"))).isNull();
        Map<String, Object> cleared = field("x", "number");
        cleared.put("min", null);
        cleared.put("unit", null);
        assertThat(problem(cleared)).isNull();
    }

    @Test
    @DisplayName("拒绝: 文本字段挂上下限 / 下限大于上限 / 标准值越界 / 非数字 / 单位过长")
    void invalidStandards() {
        assertThat(problem(field("note", "text", "min", 1))).contains("数字字段");
        assertThat(problem(field("t", "number", "min", 160, "max", 150))).contains("下限").contains("大于上限");
        assertThat(problem(field("t", "number", "standardValue", 170, "min", 150, "max", 160)))
                .contains("不在上下限范围内");
        assertThat(problem(field("t", "number", "max", "高"))).contains("必须是数字");
        assertThat(problem(field("t", "number", "max", true))).contains("必须是数字");
        assertThat(problem(field("t", "number", "unit", "一二三四五六七八九十一二三四五六七八九十一"))).contains("单位");
        assertThat(problem(field("t", "number", "maxx", 1))).contains("无法识别的属性");

        assertThatThrownBy(() -> WorkProcessCustomFieldSchema.validated(
                List.of(field("t", "number", "min", 160, "max", 150))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("比对: 低于下限 LOW / 高于上限 HIGH / 边界值算合格 / 无范围、空值、非数字不判")
    void evaluate() {
        List<Map<String, Object>> schema = List.of(
                field("oilTemp", "number", "unit", "℃", "standardValue", 155, "min", 150, "max", 160),
                field("minutes", "number", "min", 20, "max", 30),
                field("moisture", "number", "max", 12),
                field("aperture", "number", "standardValue", 6),
                field("note", "text"),
                field("blank", "number", "min", 1, "max", 2));
        Map<String, Object> values = new HashMap<>();
        values.put("oilTemp", 165);
        values.put("minutes", new BigDecimal("20"));
        values.put("moisture", "8");
        values.put("aperture", 9);
        values.put("note", "ok");
        values.put("blank", null);

        List<Map<String, Object>> checks = ProcessParamCheck.evaluate(schema, values);

        assertThat(checks).extracting(c -> c.get("key")).containsExactly("oilTemp", "minutes", "moisture");
        assertThat(checks).extracting(c -> c.get("status")).containsExactly("HIGH", "OK", "OK");
        assertThat(checks.get(0).get("unit")).isEqualTo("℃");
        assertThat((BigDecimal) checks.get(0).get("standardValue")).isEqualByComparingTo("155");
        assertThat(ProcessParamCheck.hasDeviation(checks)).isTrue();

        values.put("oilTemp", 149.9);
        assertThat(ProcessParamCheck.evaluate(schema, values).get(0).get("status")).isEqualTo("LOW");
        values.put("oilTemp", 150);
        assertThat(ProcessParamCheck.hasDeviation(ProcessParamCheck.evaluate(schema, values))).isFalse();
        values.put("oilTemp", "不是数字");
        assertThat(ProcessParamCheck.evaluate(schema, values)).extracting(c -> c.get("key"))
                .doesNotContain("oilTemp");

        assertThat(ProcessParamCheck.evaluate(null, values)).isEmpty();
        assertThat(ProcessParamCheck.evaluate(schema, null)).isEmpty();
    }
}
