package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Tool Calling 功能测试
 *
 * 测试 DashScopeClient 的 OpenAI Function Calling 支持
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolExecutionTest {

    @Autowired
    private DashScopeClient dashScopeClient;

    @Autowired
    private ObjectMapper objectMapper;

    private List<Tool> testTools;

    @BeforeEach
    void setUp() {
        // 定义测试工具
        testTools = List.of(
                Tool.of(
                        "search_materials",
                        "搜索原料信息，返回匹配的原料列表",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "keyword", Map.of("type", "string", "description", "搜索关键词")
                                ),
                                "required", List.of("keyword")
                        )
                ),
                Tool.of(
                        "get_inventory_stats",
                        "获取库存统计数据",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "category", Map.of("type", "string", "description", "原料类别")
                                ),
                                "required", List.of()
                        )
                )
        );
    }

    // ══════════════════════════════════════════════════════════════
    // 三态: 量到了 / 没量到 / 量到了且有问题
    //
    // 🔴 2026-08-28: 本类的三个联网用例连挡了四个 PR (#284 #286 #288 + 一次重跑),
    // 而红的原因与被测代码无关 —— 它取决于**哪台自建 runner 接了活**:
    //   接得到 Python LLM 服务的那台 → isAvailable() = true → 不跳过 → 上游调用失败 → 红
    //   接不到的那台                 → isAvailable() = false → 跳过 → 绿
    //
    // ⚠️ isAvailable() 量的不是它名字说的那件事: 它只 ping Python 服务的 /health
    // (PythonLLMClient#isAvailable), 证明**那个服务活着**, 不证明背后有可用的 LLM 供应商。
    // 于是「服务在、供应商不在」这一格就落进了「不跳过 → 断言 → 红」。
    //
    // ⛔ 旧写法 `if (!isAvailable()) { println; return; }` 还有第二个毛病:
    // `return` 让用例**通过**。「没量到」被折叠进「没问题」—— 一个连不上任何 LLM 的 CI
    // 会安安静静天天绿, 而它一次都没真正调用过。
    //
    // ⇒ 改成显式三态。跳过用 Assumptions.abort(), 它在报告里是 **skipped 并带原因**,
    //   不是 passed。覆盖率没有被收窄, 只是把「没能做决定」从「决定没问题」里拆了出来。
    // ══════════════════════════════════════════════════════════════

    /** {@code PythonLLMClient#createErrorResponse} 给传输层失败套的前缀。 */
    private static final String TRANSPORT_FAILURE_PREFIX = "Python LLM ";

    /**
     * 对端到底答没答上来。
     *
     * <p>🔴 2026-08-28 实测订正: 第一版把整个 {@code "Python LLM "} 前缀都当成「没量到」,
     * 结果本机跑出来的
     * {@code Python LLM tool-call 失败: HTTP 500: {"code":"INTERNAL_ERROR"}}
     * 被判成 skipped —— 那是 Python 服务**答了**、而且答的是 500,
     * 是货真价实的故障读数, 被我折叠进了「没量到」。⇒ 与我本来要避免的错误正好相反。
     *
     * <p>{@code PythonLLMClient#post} 在非 2xx 时抛 {@code IOException("HTTP " + code + ": " + body)},
     * 而连不上/超时/DNS 失败抛的 IOException 里**没有** HTTP 状态码。
     * ⇒ 「消息里有没有 HTTP 三位状态码」就是「对端答没答上来」的判据。
     *
     * <p>⛔ 不用正则: 转义经过多层工具容易被吃掉, 本仓栽过。
     */
    static boolean upstreamAnswered(String message) {
        int i = message.indexOf("HTTP ");
        if (i < 0) {
            return false;
        }
        int d = i + "HTTP ".length();
        return d + 2 < message.length()
                && Character.isDigit(message.charAt(d))
                && Character.isDigit(message.charAt(d + 1))
                && Character.isDigit(message.charAt(d + 2));
    }

    /**
     * 联网用例的入口闸: 仪器不可用就 abort(skipped), 不是 return(passed)。
     */
    private void requireLlmReachable() {
        if (!dashScopeClient.isAvailable()) {
            abort("LLM_NOT_MEASURED: Python LLM 服务 /health 不可达, 本用例没有产生读数 "
                    + "(⚠️ 这是 skipped 不是 passed —— 不要读成「LLM 工具调用没问题」)");
        }
    }

    /**
     * 拿到响应之后判读三态。
     *
     * <p><b>没量到</b>: 错误来自我们这侧送不出去(上游不可达/超时/被拒) → abort → skipped。
     * <p><b>量到且有问题</b>: 其它任何错误(含序列化失败这种**我们自己的缺陷**) → fail → 红。
     * <p><b>量到且没问题</b>: 返回, 由调用方继续断言。
     */
    private void requireMeasurableResponse(ChatCompletionResponse response, String what) {
        judgeLlmResponse(response, what);
    }

    /**
     * 三态判读本体 —— <b>刻意做成 package-private static</b>, 好让
     * {@code ToolExecutionThreeStateTest} 能把三态各构造一次。
     *
     * <p>⚠️ 三态的全部价值在第三态(红), 而第三态在正常 CI 上永远不会自然发生 ——
     * 不构造就不知道它到底到不到得了。本仓栽过这个: 「闸从没红过」被读成「没问题」。
     */
    static void judgeLlmResponse(ChatCompletionResponse response, String what) {
        assertNotNull(response, what + ": 客户端返回了 null, 这是被测代码的缺陷");
        if (!response.hasError()) {
            return;
        }
        String message = response.getErrorMessage() == null ? "" : response.getErrorMessage();
        // ⛔ 只有「压根没答上来」才算没量到。对端答了 4xx/5xx 是真读数, 必须往下走到 fail。
        if (message.startsWith(TRANSPORT_FAILURE_PREFIX) && !upstreamAnswered(message)) {
            abort("LLM_NOT_MEASURED: " + what + " 未能送达上游 (" + message + ") "
                    + "—— 本用例没有产生读数 (skipped, 不是 passed)");
        }
        // ⛔ 走到这里说明请求送出去了、对端给了结构化错误, 或者是我们自己的序列化缺陷
        //    (createErrorResponse("序列化失败: ...") 同样是 client_error)。两种都是真读数, 必须红。
        fail(what + ": LLM 调用返回错误 —— " + message);
    }

    /**
     * 测试基础 Tool Calling
     */
    @Test
    void testBasicToolCalling() {
        requireLlmReachable();

        // 调用 LLM
        ChatCompletionResponse response = dashScopeClient.chatWithTools(
                "你是一个仓库管理助手，可以帮助用户查询原料和库存信息。",
                "帮我查找包含'面粉'的原料",
                testTools
        );

        // 验证响应 (三态: 送不到=skipped / 对端报错=红 / 正常=继续断言)
        requireMeasurableResponse(response, "基础 Tool Calling");

        // 检查是否包含工具调用
        if (dashScopeClient.hasToolCalls(response)) {
            ToolCall toolCall = dashScopeClient.getFirstToolCall(response);
            assertNotNull(toolCall, "应该包含工具调用");
            assertNotNull(toolCall.getId(), "工具调用应该有 ID");
            assertEquals("function", toolCall.getType(), "工具类型应该是 function");

            System.out.println("LLM 请求调用工具: " + toolCall.getFunction().getName());
            System.out.println("参数: " + toolCall.getFunction().getArguments());
        } else {
            System.out.println("LLM 直接返回文本: " + response.getContent());
        }
    }

    /**
     * 测试多轮对话 Tool Calling
     */
    @Test
    void testMultiTurnToolCalling() throws Exception {
        requireLlmReachable();

        List<ChatMessage> conversation = new java.util.ArrayList<>();
        conversation.add(ChatMessage.system("你是一个仓库管理助手。"));
        conversation.add(ChatMessage.user("帮我查找面粉原料"));

        // Round 1: LLM 请求调用工具
        ChatCompletionResponse response1 = dashScopeClient.chatCompletionWithTools(
                conversation,
                testTools,
                "auto"
        );

        requireMeasurableResponse(response1, "多轮 Tool Calling 第 1 轮");

        if (dashScopeClient.hasToolCalls(response1)) {
            ToolCall toolCall = dashScopeClient.getFirstToolCall(response1);

            System.out.println("Round 1 - LLM 请求工具: " + toolCall.getFunction().getName());

            // 将工具调用添加到对话历史
            conversation.add(ChatMessage.assistant(null, List.of(toolCall)));

            // 模拟工具执行结果
            String toolResult = objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "results", List.of(
                            Map.of("id", "M001", "name", "高筋面粉", "quantity", 100, "unit", "kg"),
                            Map.of("id", "M002", "name", "低筋面粉", "quantity", 50, "unit", "kg")
                    )
            ));

            // 将工具结果添加到对话历史
            conversation.add(ChatMessage.tool(toolResult, toolCall.getId()));

            // Round 2: LLM 处理工具结果
            ChatCompletionResponse response2 = dashScopeClient.chatCompletionWithTools(
                    conversation,
                    testTools,
                    "auto"
            );

            requireMeasurableResponse(response2, "多轮 Tool Calling 第 2 轮");

            String finalAnswer = response2.getContent();
            System.out.println("Round 2 - LLM 最终回答: " + finalAnswer);

            assertNotNull(finalAnswer, "应该返回最终答案");
            assertFalse(finalAnswer.isEmpty(), "最终答案不应为空");
        } else {
            System.out.println("LLM 直接返回: " + response1.getContent());
        }
    }

    /**
     * 测试强制工具调用。
     *
     * <p>🔴 2026-08-28 生产实测: <b>当前供应商不支持指定工具名</b>。同一个端点:
     * <pre>
     *   tool_choice = "auto"                  -> HTTP 200, 正常返回 tool_calls
     *   tool_choice = "get_inventory_stats"   -> HTTP 500 INTERNAL_ERROR
     * </pre>
     * Python 侧日志给出了真因:
     * {@code [llm_router] aistore/DeepSeek-V4-Flash-A error status=400
     * InvalidParameter: tool_choice is one of the strings that should be ["none", "auto"]}
     * —— 供应商只认 none/auto, 具名工具是 OpenAI 风格的能力, 路由把它原样透传拿到 400,
     * 兜底链走完之后对外吐一个不说人话的 500。
     *
     * <p>⛔ 为什么是 {@code @Disabled} 而不是删掉、也不是改成断言 500:
     * 断言 500 等于<b>把缺陷写成期望</b>, 以后修好了反而变红; 删掉则是静默丢覆盖。
     * 登记是留痕, 不是豁免 —— 供应商支持具名 tool_choice 或路由给出明确 4xx 之后, 打开它。
     *
     * <p>⚠️ 顺带说明它是怎么藏了这么久的: 旧写法在拿到错误响应时走 else 分支
     * {@code println("LLM 返回: " + response.getContent())} 然后<b>通过</b> ——
     * 一条生产缺陷被一个恒绿的断言盖住了。
     */
    @Test
    @Disabled("生产实测: 供应商只支持 tool_choice=none/auto, 具名工具返回 400 → 对外 500。"
            + " 见方法 javadoc; 供应商支持后或路由改出明确错误后再打开")
    void testForcedToolChoice() {
        requireLlmReachable();

        List<ChatMessage> messages = List.of(
                ChatMessage.system("你是一个仓库管理助手。"),
                ChatMessage.user("显示库存统计")
        );

        // 强制调用 get_inventory_stats 工具
        ChatCompletionResponse response = dashScopeClient.chatCompletionWithTools(
                messages,
                testTools,
                "get_inventory_stats"
        );

        // 验证响应 (三态判读, 与另外两个联网用例同口径)
        requireMeasurableResponse(response, "强制工具调用");

        if (dashScopeClient.hasToolCalls(response)) {
            ToolCall toolCall = dashScopeClient.getFirstToolCall(response);
            assertEquals("get_inventory_stats", toolCall.getFunction().getName(),
                    "应该调用指定的工具");
            System.out.println("强制调用工具成功: " + toolCall.getFunction().getName());
        } else {
            System.out.println("LLM 返回: " + response.getContent());
        }
    }

    /**
     * 测试工具调用辅助方法
     */
    @Test
    void testToolCallHelperMethods() {
        // 创建模拟响应
        ChatCompletionResponse response = new ChatCompletionResponse();

        ChatCompletionResponse.Message message = new ChatCompletionResponse.Message();
        message.setRole("assistant");
        message.setToolCalls(List.of(
                ToolCall.of("call_123", "search_materials", "{\"keyword\": \"面粉\"}"),
                ToolCall.of("call_456", "get_inventory_stats", "{\"category\": \"原料\"}")
        ));

        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
        choice.setMessage(message);

        response.setChoices(List.of(choice));

        // 测试辅助方法
        assertTrue(dashScopeClient.hasToolCalls(response), "应该包含工具调用");

        ToolCall firstCall = dashScopeClient.getFirstToolCall(response);
        assertNotNull(firstCall);
        assertEquals("call_123", firstCall.getId());
        assertEquals("search_materials", firstCall.getFunction().getName());

        List<ToolCall> allCalls = dashScopeClient.getAllToolCalls(response);
        assertEquals(2, allCalls.size(), "应该有 2 个工具调用");
    }

    /**
     * 测试 Tool 定义构建
     */
    @Test
    void testToolDefinitionBuilding() throws Exception {
        Tool tool = Tool.of(
                "test_function",
                "测试函数",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "param1", Map.of("type", "string", "description", "参数1")
                        ),
                        "required", List.of("param1")
                )
        );

        // 验证工具定义
        assertEquals("function", tool.getType());
        assertEquals("test_function", tool.getFunction().getName());
        assertEquals("测试函数", tool.getFunction().getDescription());
        assertNotNull(tool.getFunction().getParameters());

        // 验证 JSON 序列化
        String json = objectMapper.writeValueAsString(tool);
        System.out.println("Tool JSON: " + json);

        assertTrue(json.contains("\"type\":\"function\""));
        assertTrue(json.contains("\"name\":\"test_function\""));
    }

    /**
     * 测试 ChatMessage 工具消息构建
     */
    @Test
    void testChatMessageToolSupport() throws Exception {
        // 测试 assistant 消息带 tool_calls
        ToolCall toolCall = ToolCall.of("call_123", "search", "{\"q\":\"test\"}");
        ChatMessage assistantMsg = ChatMessage.assistant(null, List.of(toolCall));

        assertEquals("assistant", assistantMsg.getRole());
        assertNotNull(assistantMsg.getToolCalls());
        assertEquals(1, assistantMsg.getToolCalls().size());

        // 测试 tool 消息
        ChatMessage toolMsg = ChatMessage.tool("{\"result\":\"success\"}", "call_123");

        assertEquals("tool", toolMsg.getRole());
        assertEquals("{\"result\":\"success\"}", toolMsg.getContent());
        assertEquals("call_123", toolMsg.getToolCallId());

        // 验证 JSON 序列化
        String json = objectMapper.writeValueAsString(toolMsg);
        System.out.println("Tool Message JSON: " + json);

        assertTrue(json.contains("\"role\":\"tool\""));
        assertTrue(json.contains("\"tool_call_id\":\"call_123\""));
    }
}
