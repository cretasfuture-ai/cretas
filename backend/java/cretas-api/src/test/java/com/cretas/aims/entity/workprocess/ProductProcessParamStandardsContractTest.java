package com.cretas.aims.entity.workprocess;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 产品标准覆盖: 画布节点参数按 label 覆盖工序 schema 同名数字字段的标准; 没配 = 旧行为。 */
class ProductProcessParamStandardsContractTest {

    private static final String NODES = """
            [
              {"id":"process:1","kind":"PROCESS","data":{"workProcessId":"WP-1","processParams":[
                 {"label":"油温","unit":"℃","min":150,"max":160}]}},
              {"id":"process:2","kind":"PROCESS","data":{"workProcessId":"WP-2"}},
              {"id":"process:3","kind":"PROCESS","data":{"workProcessId":"WP-DUP","processParams":[{"label":"油温","min":1,"max":2}]}},
              {"id":"process:4","kind":"PROCESS","data":{"workProcessId":"WP-DUP","processParams":[{"label":"油温","min":3,"max":4}]}}
            ]
            """;

    private static Map<String, Object> field(String key, String label, Object min, Object max) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("type", "number");
        f.put("enabled", true);
        f.put("min", min);
        f.put("max", max);
        return f;
    }

    @Test
    void canvasStandardOverridesTheSameNameNumberFieldOnly() {
        List<Map<String, Object>> schema = List.of(field("oil", "油温", 100, 200), field("t", "时间", 1, 2));
        List<Map<String, Object>> out = ProcessParamStandards.overlay(schema,
                ProcessParamStandards.paramsOf(NODES, "process:1", "WP-1"));
        assertEquals(150, out.get(0).get("min"));
        assertEquals(160, out.get(0).get("max"));
        assertEquals("℃", out.get(0).get("unit"));
        assertEquals(1, out.get(1).get("min"), "画布没配的字段保持原标准");
        assertEquals(100, schema.get(0).get("min"), "不改传入的 schema 对象");
    }

    @Test
    void aMissingCanvasValueKeepsTheSchemaValueNotZero() {
        Map<String, Object> onlyMax = new LinkedHashMap<>();
        onlyMax.put("label", "油温");
        onlyMax.put("max", 160);
        onlyMax.put("min", null);
        List<Map<String, Object>> out = ProcessParamStandards.overlay(
                List.of(field("oil", "油温", 100, 200)), List.of(onlyMax));
        assertEquals(100, out.get(0).get("min"));
        assertEquals(160, out.get(0).get("max"));
    }

    @Test
    void nothingConfiguredMeansTheOriginalSchemaIsReturnedUnchanged() {
        List<Map<String, Object>> schema = List.of(field("oil", "油温", 100, 200));
        assertSame(schema, ProcessParamStandards.overlay(schema, ProcessParamStandards.paramsOf(NODES, "process:2", "WP-2")));
        assertSame(schema, ProcessParamStandards.overlay(schema, ProcessParamStandards.paramsOf(null, "process:1", "WP-1")));
        assertSame(schema, ProcessParamStandards.overlay(schema, ProcessParamStandards.paramsOf("{坏json", "process:1", "WP-1")));
        assertTrue(ProcessParamStandards.paramsOf(NODES, "process:nope", "WP-1").isEmpty());
    }

    @Test
    void aTextFieldWithTheSameLabelIsNeverOverridden() {
        Map<String, Object> text = new LinkedHashMap<>(field("oil", "油温", null, null));
        text.put("type", "text");
        List<Map<String, Object>> out = ProcessParamStandards.overlay(List.of(text),
                ProcessParamStandards.paramsOf(NODES, "process:1", "WP-1"));
        assertEquals(null, out.get(0).get("min"));
    }

    @Test
    void withoutANodeIdTheProcessMustBeUniqueInTheGraphOrNothingIsGuessed() {
        assertEquals(1, ProcessParamStandards.paramsOf(NODES, null, "WP-1").size());
        assertTrue(ProcessParamStandards.paramsOf(NODES, null, "WP-DUP").isEmpty(), "同一工序在图里出现两次且没有节点 id: 不猜");
        assertEquals(1, ProcessParamStandards.paramsOf(NODES, "process:4", "WP-DUP").size());
    }
}
