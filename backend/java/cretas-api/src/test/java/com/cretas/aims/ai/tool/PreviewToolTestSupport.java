package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写工具预览测试共用的小工具: 走工具的<b>真实入口</b> {@code preview(ToolCall, context)}
 * (基类的缺参校验 + doPreview + JSON 出口), 再把出口 JSON 读回来断言。
 */
public final class PreviewToolTestSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private PreviewToolTestSupport() {
    }

    /** 给 AbstractTool 的 objectMapper 字段赋值 (生产里是 Spring 注入)。 */
    public static void injectMapper(Object tool) throws Exception {
        Class<?> c = tool.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("objectMapper");
                f.setAccessible(true);
                f.set(tool, MAPPER);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("objectMapper field not found on " + tool.getClass());
    }

    public static ToolCall call(String toolName, Map<String, Object> args) throws Exception {
        return ToolCall.of("tc-1", toolName, MAPPER.writeValueAsString(args));
    }

    public static Map<String, Object> ctx(String factoryId, Long userId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
        if (userId != null) {
            ctx.put("userId", userId);
        }
        ctx.put("userRole", "operator");
        return ctx;
    }

    public static Map<String, Object> parse(String json) throws Exception {
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    /** 预览成功出口 {success:true,data:{...}} 的 data; 出口不是成功形状就当场失败并带上原文。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> data(String json) throws Exception {
        Map<String, Object> root = parse(json);
        assertThat(root.get("success")).as("预览出口: " + json).isEqualTo(true);
        return (Map<String, Object>) root.get("data");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> rows(Map<String, Object> data, String key) {
        assertThat(data).containsKey(key);
        return (Map<String, Object>) data.get(key);
    }

    /** 缺参出口: success=false + NEED_MORE_INFO, 返回追问列表; 每一句都必须是人话。 */
    @SuppressWarnings("unchecked")
    public static List<String> needMoreInfoQuestions(String json) throws Exception {
        Map<String, Object> root = parse(json);
        assertThat(root.get("success")).as("缺参出口: " + json).isEqualTo(false);
        assertThat(root.get("status")).as("缺参出口: " + json).isEqualTo("NEED_MORE_INFO");
        List<String> questions = (List<String>) root.get("clarificationQuestions");
        assertThat(questions).isNotEmpty();
        for (String q : questions) {
            assertPlainChinese(q);
        }
        return questions;
    }

    /**
     * 给一线看的话里不许出现: 英文驼峰参数名 / 全大写枚举码 / 内部 UUID。
     * (括号里的「kg」「PO-」这种单位不在此列, 所以只拦「驼峰」「≥3 位全大写词」和 UUID。)
     */
    public static void assertPlainChinese(String text) {
        assertThat(text).as("文案: " + text)
                .doesNotContainPattern("[a-z]+[A-Z][A-Za-z]*")      // camelCase
                .doesNotContainPattern("\\b[A-Z][A-Z_]{2,}\\b")      // ANNUAL / IN_PROGRESS
                .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); // UUID
    }
}
