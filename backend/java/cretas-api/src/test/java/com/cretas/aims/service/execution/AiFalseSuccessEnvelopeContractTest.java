package com.cretas.aims.service.execution;

import com.cretas.aims.controller.AIIntentConfigController;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 测试同学 2026-07-31 报的「[AI 综合] 什么指令都操作成功」。
 *
 * <p>三处独立的「假成功」, 都在 AI 这一头 (⛔ 不动 {@code ApiResponse.success} 本身 ——
 * 全仓接口都在用它, 改 envelope 是 repo-wide blast radius, 形态 E: 宁可窄而可信):
 *
 * <ol>
 *   <li><b>envelope 无脑成功</b>: 四个 AI 入口一律 {@code ApiResponse.success(response)},
 *       而那个重载把 message 写死成「操作成功」({@code ApiResponse:61})。
 *       status=FAILED 的结果在 envelope 上照样写「操作成功」, 而 RN 端读的正是 envelope。</li>
 *   <li><b>缺 success 键当成功</b>: {@code getOrDefault("success", true)} 把「工具没说成没成」
 *       翻译成「成功了」(形态 A¹⁰: 兜底的默认值会把「我不知道」翻译成一个假读数)。</li>
 *   <li><b>解析失败报成功</b>: catch 块原来返回 {@code status("SUCCESS") + "执行完成"} ——
 *       读不懂工具结果时对用户说"干成了"。这是本族最直接的一处。</li>
 * </ol>
 *
 * <p>外加一条投影丢失 (形态 B): {@code AbstractTool.buildErrorResult} 写的是
 * {@code {"success":false,"error":"<真实原因>"}}, 而消费端只找 {@code "message"} ——
 * 于是**每一次工具失败的真实原因都被丢掉**, 掉进通用兜底文案。
 */
@DisplayName("AI 假成功: envelope / 缺 success 键 / 解析失败 / error 键投影")
class AiFalseSuccessEnvelopeContractTest {

    private ToolDispatchService dispatch;
    private AIIntentConfigController controller;
    private AIIntentConfig intent;

    @BeforeEach
    void setUp() {
        // 被测的两个方法都只用到 objectMapper + 入参, 不碰其它协作者 ——
        // CALLS_REAL_METHODS 让我们跑真实逻辑而不必构造整条依赖链。
        dispatch = mock(ToolDispatchService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        ReflectionTestUtils.setField(dispatch, "objectMapper", new ObjectMapper());
        controller = mock(AIIntentConfigController.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        intent = new AIIntentConfig();
        intent.setIntentCode("MATERIAL_BATCH_QUERY");
        intent.setIntentName("物料批次查询");
        intent.setIntentCategory("DATA_QUERY");
        intent.setToolName("material_batch_query");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<ApiResponse<IntentExecuteResponse>> envelope(IntentExecuteResponse r) {
        return (ResponseEntity<ApiResponse<IntentExecuteResponse>>)
                ReflectionTestUtils.invokeMethod(controller, "envelopeByInnerStatus", r);
    }

    // ===================== ① envelope 按内层 status =====================

    @Test
    @DisplayName("内层 FAILED → envelope 必须 success=false, 且不许说「操作成功」")
    void failedInnerStatusMustNotBeEnvelopedAsSuccess() {
        IntentExecuteResponse inner = IntentExecuteResponse.builder()
                .status("FAILED")
                .message("库存不足，无法出库")
                .build();

        ApiResponse<IntentExecuteResponse> body = envelope(inner).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getSuccess()).as("内层失败, envelope 不能说成功").isFalse();
        assertThat(body.getMessage())
                .as("envelope 文案必须是内层的真实原因, ⛔ 不是「操作成功」")
                .isEqualTo("库存不足，无法出库");
        assertThat(body.getMessage()).isNotEqualTo("操作成功");
        assertThat(body.getData()).as("data 仍要带上, 前端靠 status/候选项继续流程").isSameAs(inner);
    }

    @Test
    @DisplayName("NOT_RECOGNIZED / NO_PERMISSION 同样不算成功")
    void otherFailureStatusesAreAlsoNotSuccess() {
        for (String status : new String[]{"NOT_RECOGNIZED", "NO_PERMISSION"}) {
            ApiResponse<IntentExecuteResponse> body =
                    envelope(IntentExecuteResponse.builder().status(status).build()).getBody();
            assertThat(body).isNotNull();
            assertThat(body.getSuccess()).as("%s 不能被报成成功", status).isFalse();
            assertThat(body.getMessage()).as("%s 要有具体指引, 不能是「操作成功」", status)
                    .isNotEqualTo("操作成功");
            assertThat(body.getMessage()).isNotBlank();
        }
    }

    /** 阳性对照: 真成功时 envelope 仍然是成功 —— 否则上面几条可能只是「一律 false」。 */
    @Test
    @DisplayName("阳性对照: 内层 SUCCESS 仍然 envelope 成 success=true")
    void successfulInnerStatusStaysSuccessful() {
        ApiResponse<IntentExecuteResponse> body = envelope(IntentExecuteResponse.builder()
                .status("SUCCESS")
                .message("已入库 5 件")
                .build()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getSuccess()).isTrue();
        assertThat(body.getMessage()).isEqualTo("已入库 5 件");
    }

    @Test
    @DisplayName("response 为 null 时说读不到, 不兜成成功")
    void nullResponseIsNotSuccess() {
        ApiResponse<IntentExecuteResponse> body = envelope(null).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getSuccess()).isFalse();
    }

    // ===================== ② 缺 success 键 =====================

    @Test
    @DisplayName("工具结果缺 success 键 → 按未成功处理, ⛔ 不再默认 true")
    void missingSuccessKeyIsNotTreatedAsSuccess() {
        IntentExecuteResponse r = dispatch.parseToolResultToResponse("{\"data\":{\"rows\":[]}}", intent);

        assertThat(r.getStatus())
                .as("没说成没成 = 不知道, 不能翻译成 SUCCESS (形态 A¹⁰)")
                .isEqualTo("FAILED");
    }

    /** 阳性对照: 显式 success=true 照常成功, 证明上一条不是「一律 FAILED」。 */
    @Test
    @DisplayName("阳性对照: 显式 success=true 仍然是 SUCCESS")
    void explicitSuccessStillSucceeds() {
        IntentExecuteResponse r =
                dispatch.parseToolResultToResponse("{\"success\":true,\"data\":{\"rows\":[]}}", intent);
        assertThat(r.getStatus()).isEqualTo("SUCCESS");
    }

    // ===================== ③ 解析失败 =====================

    @Test
    @DisplayName("工具结果解析不了 → FAILED, ⛔ 不是「执行完成」")
    void unparseableResultIsFailedNotSuccess() {
        IntentExecuteResponse r = dispatch.parseToolResultToResponse("这不是 JSON {{{", intent);

        assertThat(r.getStatus())
                .as("读不懂结果时对用户说「干成了」是本族最直接的一处假成功")
                .isEqualTo("FAILED");
        assertThat(r.getMessage()).isNotEqualTo("执行完成");
        assertThat(r.getMessage()).contains("没有完成");
    }

    // ===================== ④ error 键投影 =====================

    @Test
    @DisplayName("失败原因写在 error 键上时要读得到 —— AbstractTool.buildErrorResult 就是这个形状")
    void toolErrorKeyIsSurfacedAsMessage() {
        // 真实上游形状: AbstractTool.buildErrorResult 写 {"success":false,"error":"..."}
        IntentExecuteResponse r = dispatch.parseToolResultToResponse(
                "{\"success\":false,\"error\":\"批次号 MB-001 不存在\"}", intent);

        assertThat(r.getStatus()).isEqualTo("FAILED");
        assertThat(r.getMessage())
                .as("工具给了具体原因就不该掉进通用兜底 (形态 B: 产出端有、消费端找的是另一个键)")
                .isEqualTo("批次号 MB-001 不存在");
        assertThat(r.getMessage()).doesNotContain("本次查询没有获得可展示的结果");
    }

    /** message 与 error 同时存在时, message 优先 —— 不改既有优先级。 */
    @Test
    @DisplayName("message 优先于 error (既有行为不变)")
    void messageWinsOverError() {
        IntentExecuteResponse r = dispatch.parseToolResultToResponse(
                "{\"success\":false,\"message\":\"甲\",\"error\":\"乙\"}", intent);
        assertThat(r.getMessage()).isEqualTo("甲");
    }
}
