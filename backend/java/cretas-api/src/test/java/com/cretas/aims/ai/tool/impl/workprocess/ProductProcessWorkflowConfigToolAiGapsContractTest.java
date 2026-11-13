package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺口 20: AI 画布补丁路能带上 主料份额 / 回料规则 / 副产 / CCP 控制点, 且都写画布节点自己的 data。
 * 每条正例配一个阴性对照 —— 只测"放行"的闸对"约束根本没生效"完全沉默。
 */
class ProductProcessWorkflowConfigToolAiGapsContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolExecutor tool;

    @BeforeEach
    void setUp() {
        tool = new ProductProcessWorkflowConfigTool(objectMapper, new ProductProcessWorkflowValidator());
    }

    @Test
    void inputShareOnInputPortIsAcceptedButOutOfRangeIsRejected() throws Exception {
        assertTrue(ok(preview(def(), List.of(setField("process:1", "ports", List.of(
                port("input:1", "INPUT", "raw", 0, 30d), port("output:1", "OUTPUT", "semi", 1, null)))))));
        // 阴性对照: 越界拒绝, 不截断成 100
        assertFalse(ok(preview(def(), List.of(setField("process:1", "ports", List.of(
                port("input:1", "INPUT", "raw", 0, 130d), port("output:1", "OUTPUT", "semi", 1, null)))))));
        // 份额只属于投入端口
        assertFalse(ok(preview(def(), List.of(setField("process:1", "ports", List.of(
                port("input:1", "INPUT", "raw", 0, null), port("output:1", "OUTPUT", "semi", 1, 30d)))))));
    }

    @Test
    void fixedCostShareUsesOutputPortsOnlyAndNeverAcceptsOutOfRangeValues() throws Exception {
        assertTrue(ok(preview(def(), List.of(
                setField("process:1", "costAllocationMethod", "FIXED_RATIO")))));

        Map<String, Object> validOutput = port("output:1", "OUTPUT", "semi", 1, null);
        validOutput.put("fixedCostShare", 100d);
        assertTrue(ok(preview(def(), List.of(setField("process:1", "ports", List.of(
                port("input:1", "INPUT", "raw", 0, null), validOutput))))));

        Map<String, Object> negativeOutput = new LinkedHashMap<>(validOutput);
        negativeOutput.put("fixedCostShare", -1d);
        assertFalse(ok(preview(def(), List.of(setField("process:1", "ports", List.of(
                port("input:1", "INPUT", "raw", 0, null), negativeOutput))))));

        Map<String, Object> inputShare = port("input:1", "INPUT", "raw", 0, null);
        inputShare.put("fixedCostShare", 50d);
        assertFalse(ok(preview(def(), List.of(setField("process:1", "ports", List.of(
                inputShare, port("output:1", "OUTPUT", "semi", 1, null)))))));

        assertFalse(ok(preview(def(), List.of(
                setField("process:1", "costAllocationMethod", "SALES_VALUE")))));
    }

    @Test
    void fixedRatioTwoOutputActualIoPreviewPreservesNamedShares() throws Exception {
        // ACTUAL_IO draft preview accepts the new fixedCostShare carrier; the
        // runtime compiler contract owns publish-time total/missing-share rejection.
        Map<String, Object> valid = definitionWithTwoOutputs(80d, 20d);
        assertTrue(ok(preview(valid, List.of(
                setField("process:1", "costAllocationMethod", "FIXED_RATIO")))));
    }

    @Test
    void reworkPolicyMustPointAtARealReworkCell() throws Exception {
        Map<String, Object> policy = Map.of("sources",
                List.of(Map.of("reworkNodeId", "scrap", "inputRatioPercent", 8.5d)));
        // 阴性: scrap 还没标成回料产出
        Map<String, Object> rejected = preview(def(), List.of(setField("process:1", "reworkPolicy", policy)));
        assertFalse(ok(rejected));
        assertTrue(String.valueOf(rejected.get("error")).contains("回料"), String.valueOf(rejected.get("error")));
        // 阳性: 同一条规则, 先把 scrap 标成回料
        assertTrue(ok(preview(def(), List.of(
                setField("scrap", "isRework", true),
                setField("process:1", "reworkPolicy", policy)))));
        // 比例越界拒绝
        assertFalse(ok(preview(def(), List.of(
                setField("scrap", "isRework", true),
                setField("process:1", "reworkPolicy",
                        Map.of("sources", List.of(Map.of("reworkNodeId", "scrap", "inputRatioPercent", 250d))))))));
    }

    @Test
    void byproductFlagWritesOnTheSameNodeDataTheCanvasUses() throws Exception {
        Map<String, Object> envelope = preview(def(), List.of(setField("scrap", "isByproduct", true)));
        assertTrue(ok(envelope));
        Map<?, ?> patch = (Map<?, ?>) ((List<?>) ((Map<?, ?>) envelope.get("data")).get("patches")).get(0);
        assertEquals("isByproduct", patch.get("path"));
        // 阴性: 工序节点没有副产标记
        assertFalse(ok(preview(def(), List.of(setField("process:1", "isByproduct", true)))));
    }

    @Test
    void ccpWithUnknownLimitsIsAcceptedAsPendingButInventedGarbageIsNot() throws Exception {
        Map<String, Object> pendingPoint = new LinkedHashMap<>();
        pendingPoint.put("id", "ccp-1");
        pendingPoint.put("name", "中心温度");
        pendingPoint.put("hazardType", "BIOLOGICAL");
        pendingPoint.put("criticalLimitMin", null);
        pendingPoint.put("criticalLimitMax", null);
        pendingPoint.put("unit", null);
        assertTrue(ok(preview(def(), List.of(setField("process:1", "ccpPoints", List.of(pendingPoint))))));

        Map<String, Object> inverted = new LinkedHashMap<>(pendingPoint);
        inverted.put("criticalLimitMin", 95d);
        inverted.put("criticalLimitMax", 75d);
        assertFalse(ok(preview(def(), List.of(setField("process:1", "ccpPoints", List.of(inverted))))));

        Map<String, Object> badHazard = new LinkedHashMap<>(pendingPoint);
        badHazard.put("hazardType", "MAGIC");
        assertFalse(ok(preview(def(), List.of(setField("process:1", "ccpPoints", List.of(badHazard))))));

        Map<String, Object> unknownKey = new LinkedHashMap<>(pendingPoint);
        unknownKey.put("workProcessId", "WP-OTHER");
        assertFalse(ok(preview(def(), List.of(setField("process:1", "ccpPoints", List.of(unknownKey))))));
    }

    @Test
    void anAiPatchCanNeverCarryTheOverwriteConfirmation() throws Exception {
        // 「确认覆盖工序已有标准」只能由人勾。AI 补丁带 true 要被丢弃(参数本身仍可进入待确认)
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("label", "油温");
        param.put("unit", "℃");
        param.put("min", 150);
        param.put("max", 170);
        param.put("overwriteConfirmed", true);
        param.put("confirmedFor", Map.of("unit", "℃", "min", 150, "max", 170));
        Map<String, Object> envelope = preview(def(), List.of(setField("process:1", "processParams", List.of(param))));
        assertTrue(ok(envelope), "参数本身合法, 补丁应通过: " + envelope);
        String out = objectMapper.writeValueAsString(envelope);
        assertTrue(out.contains("油温"), "参数应保留: " + out);
        assertFalse(out.contains("overwriteConfirmed"), "AI 带来的确认必须被丢弃: " + out);
        assertFalse(out.contains("confirmedFor"), "AI 带来的确认快照必须被丢弃: " + out);
    }

    @Test
    void pendingConfirmationsAreAcceptedOnlyAsCodeAndLabel() throws Exception {
        assertTrue(ok(preview(def(), List.of(setField("process:1", "pendingConfirmations",
                List.of(Map.of("code", "SHARE", "label", "主料份额待核对")))))));
        assertFalse(ok(preview(def(), List.of(setField("process:1", "pendingConfirmations",
                List.of("主料份额待核对"))))));
    }

    // ───────────────────────── helpers ─────────────────────────

    private boolean ok(Map<String, Object> envelope) {
        return Boolean.TRUE.equals(envelope.get("success"));
    }

    private Map<String, Object> preview(Map<String, Object> definition, List<Map<String, Object>> patches)
            throws Exception {
        String arguments = objectMapper.writeValueAsString(Map.of("definition", definition, "patches", patches));
        return objectMapper.readValue(
                tool.preview(ToolCall.of("preview", tool.getToolName(), arguments), Map.of("factoryId", "F006")),
                new TypeReference<>() {});
    }

    private Map<String, Object> setField(String nodeId, String path, Object value) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("op", "SET_NODE_FIELD");
        patch.put("nodeId", nodeId);
        patch.put("path", path);
        patch.put("value", value);
        return patch;
    }

    private Map<String, Object> def() {
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("workProcessId", "WP-1");
        processData.put("processName", "卤制");
        processData.put("inputUnit", "kg");
        processData.put("outputUnit", "kg");
        processData.put("ports", List.of(
                port("input:1", "INPUT", "raw", 0, null), port("output:1", "OUTPUT", "semi", 1, null)));
        processData.put("conversionRule", Map.of("mode", "ACTUAL_WEIGHT"));
        processData.put("reportingRequired", true);
        return Map.of(
                "schemaVersion", 1, "status", "DRAFT", "version", 1,
                "nodes", List.of(
                        materialNode("raw", "RAW_MATERIAL"),
                        Map.of("id", "process:1", "kind", "PROCESS",
                                "position", Map.of("x", 256, "y", 32), "data", processData),
                        materialNode("semi", "SEMI_FINISHED"),
                        materialNode("scrap", "SEMI_FINISHED")),
                "edges", List.of(
                        edge("e1", "raw", "output", "process:1", "input:1"),
                        edge("e2", "process:1", "output:1", "semi", "input")),
                "viewport", Map.of("x", 0, "y", 0, "zoom", 1));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> definitionWithTwoOutputs(
            Double mainFixedShare, Double coProductFixedShare) {
        Map<String, Object> definition = objectMapper.convertValue(def(), new TypeReference<>() {});
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) definition.get("nodes");
        Map<String, Object> process = nodes.stream()
                .filter(node -> "process:1".equals(node.get("id")))
                .findFirst().orElseThrow();
        Map<String, Object> data = (Map<String, Object>) process.get("data");
        data.put("reportingSelectionMode", "ACTUAL_IO");
        List<Map<String, Object>> ports = new ArrayList<>();
        Map<String, Object> input = port("input:1", "INPUT", "raw", 0, null);
        Map<String, Object> main = port("output:1", "OUTPUT", "semi", 1, null);
        main.put("materialName", "主品");
        main.put("fixedCostShare", mainFixedShare);
        Map<String, Object> coProduct = port("output:2", "OUTPUT", "scrap", 2, null);
        coProduct.put("materialName", "副品");
        coProduct.put("fixedCostShare", coProductFixedShare);
        ports.add(input);
        ports.add(main);
        ports.add(coProduct);
        data.put("ports", ports);
        List<Map<String, Object>> edges = (List<Map<String, Object>>) definition.get("edges");
        edges.add(edge("e3", "process:1", "output:2", "scrap", "input"));
        return definition;
    }

    private Map<String, Object> materialNode(String id, String kind) {
        return Map.of("id", id, "kind", kind, "position", Map.of("x", 0, "y", 0),
                "data", Map.of("name", id, "skuId", "SKU-" + id, "baseUnit", "kg", "bound", true));
    }

    private Map<String, Object> port(String id, String direction, String materialNodeId, int ordinal, Double share) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("unit", "kg");
        port.put("ordinal", ordinal);
        if (share != null) port.put("inputShareRatio", share);
        return port;
    }

    private Map<String, Object> edge(String id, String source, String sh, String target, String th) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("sourceHandle", sh);
        edge.put("target", target);
        edge.put("targetHandle", th);
        return edge;
    }
}
