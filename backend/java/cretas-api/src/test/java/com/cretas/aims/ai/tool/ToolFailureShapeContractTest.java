package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool result that says {@code success=false} must always carry its reason under
 * {@code error}.
 *
 * <p>It did not. {@code buildErrorResult} wrote {@code error}; the
 * required-parameter early-return wrote only {@code message}. Two shapes for one
 * event ("this tool failed") meant every consumer that read {@code error} — including
 * both Canvas narrow routes — silently replaced the real reason with a generic
 * sentence. That is how "工序管理工具执行失败，请检查输入后重试" reached production
 * with nothing behind it.
 */
class ToolFailureShapeContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Minimal concrete tool: one required parameter, never reached in these tests. */
    private static class StubTool extends AbstractBusinessTool {
        @Override
        public String getToolName() {
            return "stub_required_param_tool";
        }

        @Override
        public String getDescription() {
            return "test double";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        protected List<String> getRequiredParameters() {
            return List.of("action");
        }

        @Override
        protected Map<String, Object> doExecute(
                String factoryId, Map<String, Object> params, Map<String, Object> context) {
            throw new AssertionError("must not be reached: required param is missing");
        }

        @Override
        protected Map<String, Object> doPreview(
                String factoryId, Map<String, Object> params, Map<String, Object> context) {
            throw new AssertionError("must not be reached: required param is missing");
        }
    }

    /**
     * AbstractTool#objectMapper is {@code @Autowired}; a hand-built tool leaves it
     * null, every serialisation throws, and the result degrades to the hardcoded
     * {@code {"success":false,"error":"操作失败"}} string. That string happens to
     * contain an {@code error} key, so a test on an unwired stub would go green
     * without ever exercising buildNeedMoreInfoResult. Wire it explicitly.
     */
    private StubTool wiredTool() {
        StubTool tool = new StubTool();
        tool.objectMapper = objectMapper;
        return tool;
    }

    private JsonNode runExecute() throws Exception {
        return objectMapper.readTree(
                wiredTool().execute(toolCall(), Map.of("factoryId", "F006", "userId", "42")));
    }

    private JsonNode runPreview() throws Exception {
        return objectMapper.readTree(
                wiredTool().preview(toolCall(), Map.of("factoryId", "F006", "userId", "42")));
    }

    private ToolCall toolCall() {
        // Empty arguments ⇒ the required `action` is missing ⇒ the early return fires.
        return ToolCall.of("call-1", "stub_required_param_tool", "{}");
    }

    @Test
    @DisplayName("execute 缺必需参数: success=false 时 error 必须在")
    void executeMissingRequiredParamCarriesErrorKey() throws Exception {
        JsonNode result = runExecute();

        assertFalse(result.path("success").asBoolean(), "这是一次失败");
        assertTrue(result.has("error"),
                "success=false 却没有 error 键 —— 读 error 的消费方只能退回通用文案");
        assertFalse(result.path("error").asText().isBlank());
    }

    @Test
    @DisplayName("preview 缺必需参数: 同一个失败契约")
    void previewMissingRequiredParamCarriesErrorKey() throws Exception {
        JsonNode result = runPreview();

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.has("error"),
                "preview 是 Canvas narrow route 走的那条路, 它更不能丢原因");
        assertFalse(result.path("error").asText().isBlank());
    }

    @Test
    @DisplayName("error 与 message 内容一致, 不是两句不同的话")
    void errorAndMessageAgree() throws Exception {
        JsonNode result = runExecute();

        assertEquals(result.path("message").asText(), result.path("error").asText(),
                "同一次失败给出两种说法, 就是下一次漂移的起点");
    }

    @Test
    @DisplayName("澄清语义保留: 仍然是 NEED_MORE_INFO 而不是笼统报错")
    void clarificationSemanticsArePreserved() throws Exception {
        JsonNode result = runExecute();

        // Adding `error` must not flatten a clarification request into an opaque
        // failure — consumers that can ask a follow-up still need to recognise it.
        assertEquals("NEED_MORE_INFO", result.path("status").asText());
        assertTrue(result.path("needMoreInfo").asBoolean());
        assertTrue(result.path("missingParameters").isArray());
        assertEquals("action", result.path("missingParameters").get(0).asText());
        assertTrue(result.path("error").asText().contains("还需要以下信息"),
                "原因要是人话, 不是参数名清单");
    }
}
