package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ChatCompletionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ToolExecutionTest#judgeLlmResponse} 的三态对照。
 *
 * <p>🔴 背景: {@code ToolExecutionTest} 的三个联网用例在 2026-08-28 连挡了四个 PR，
 * 而红的原因与被测代码无关 —— 取决于哪台自建 runner 接了活。旧写法有两个毛病:
 * <ol>
 *   <li>{@code isAvailable()} 只 ping Python 服务的 /health，证明**服务活着**，
 *       不证明背后有可用的 LLM 供应商 ⇒ 「服务在、供应商不在」落进「不跳过 → 断言 → 红」；</li>
 *   <li>跳过用的是 {@code return}，让用例**通过** ⇒ 「没量到」被折叠进「没问题」，
 *       一个连不上任何 LLM 的 CI 会安静地天天绿。</li>
 * </ol>
 *
 * <p>⚠️ 本类存在的唯一理由是<b>把第三态构造出来</b>。三态的价值全在「红」那一格，
 * 而它在正常 CI 上永远不会自然发生 —— 不构造就不知道它到底到不到得了。
 * 本仓记过这个形状: 「闸从没红过」被读成「没问题」。
 */
class ToolExecutionThreeStateTest {

    private ChatCompletionResponse withError(String message, String type) {
        ChatCompletionResponse response = new ChatCompletionResponse();
        ChatCompletionResponse.Error error = new ChatCompletionResponse.Error();
        error.setMessage(message);
        error.setType(type);
        response.setError(error);
        return response;
    }

    @Test
    @DisplayName("态一 · 量到了且没问题 —— 正常返回, 由调用方继续断言")
    void passesThrough() {
        ChatCompletionResponse ok = new ChatCompletionResponse();
        assertThat(ok.hasError()).isFalse();
        assertThatCode(() -> ToolExecutionTest.judgeLlmResponse(ok, "正常"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("态二 · 没量到 —— 送不到上游时 abort(skipped), ⛔ 不是 passed 也不是 failed")
    void abortsWhenUpstreamUnreachable() {
        // PythonLLMClient#createErrorResponse 在送不出去时给的就是这个形状
        ChatCompletionResponse unreachable = withError(
                "Python LLM tool-call 失败: Failed to connect to /127.0.0.1:8083", "client_error");

        assertThatThrownBy(() -> ToolExecutionTest.judgeLlmResponse(unreachable, "基础 Tool Calling"))
                .as("送不到上游必须是 skipped, 否则 CI 会把「没量到」读成「没问题」")
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("LLM_NOT_MEASURED");
    }

    @Test
    @DisplayName("态三 · 量到了且有问题 —— 对端返回结构化错误必须变红")
    void failsOnUpstreamProtocolError() {
        ChatCompletionResponse rejected = withError(
                "model not found: qwen-nonexistent", "invalid_request_error");

        assertThatThrownBy(() -> ToolExecutionTest.judgeLlmResponse(rejected, "基础 Tool Calling"))
                .as("请求送到了、对端给了错误 —— 这是真读数, 不许被跳过吞掉")
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("model not found");
    }

    @Test
    @DisplayName("⛔ 阴性对照 · 序列化失败是**我们自己的缺陷**, 不许被当成「没量到」放过")
    void failsOnOurOwnSerializationBug() {
        // createErrorResponse("序列化失败: ...") 同样是 client_error —— 只按 type 分类会把它误判成跳过
        ChatCompletionResponse ourBug = withError(
                "序列化失败: No serializer found for class Tool", "client_error");

        assertThatThrownBy(() -> ToolExecutionTest.judgeLlmResponse(ourBug, "基础 Tool Calling"))
                .as("type 同为 client_error, 但这条是我们的 bug, 必须红")
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("序列化失败");
    }

    @Test
    @DisplayName("🔴 订正对照 · 对端答了 HTTP 500 是真读数, ⛔ 不许被当成「没量到」跳过")
    void failsWhenUpstreamAnsweredWithServerError() {
        // 2026-08-28 本机实测到的真实形状 —— 第一版把它判成 skipped, 那是错的
        ChatCompletionResponse http500 = withError(
                "Python LLM tool-call 失败: HTTP 500: {\"success\":false,\"data\":null,"
                        + "\"message\":\"Internal server error\",\"code\":\"INTERNAL_ERROR\"}",
                "client_error");

        assertThatThrownBy(() -> ToolExecutionTest.judgeLlmResponse(http500, "强制工具调用"))
                .as("Python 服务答了 500 —— 服务是活的、答案是错的, 这是故障不是「没量到」")
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("判据本身: 有三位状态码=答了, 没有=没答上来")
    void upstreamAnsweredDiscriminates() {
        assertThat(ToolExecutionTest.upstreamAnswered("Python LLM tool-call 失败: HTTP 500: {}")).isTrue();
        assertThat(ToolExecutionTest.upstreamAnswered("Python LLM tool-call 失败: HTTP 404: {}")).isTrue();
        assertThat(ToolExecutionTest.upstreamAnswered(
                "Python LLM tool-call 失败: Failed to connect to /127.0.0.1:8083")).isFalse();
        assertThat(ToolExecutionTest.upstreamAnswered(
                "Python LLM tool-call 失败: timeout")).isFalse();
        // ⛔ 「HTTP」这个词出现但后面不是状态码, 不算答了
        assertThat(ToolExecutionTest.upstreamAnswered("Python LLM tool-call 失败: HTTP proxy refused")).isFalse();
    }

    @Test
    @DisplayName("⛔ 阴性对照 · 客户端返回 null 是缺陷, 必须红而不是跳过")
    void failsOnNullResponse() {
        assertThatThrownBy(() -> ToolExecutionTest.judgeLlmResponse(null, "基础 Tool Calling"))
                .isInstanceOf(AssertionFailedError.class);
    }
}
