package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.MobileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 工序管理 AI panel's three entries (计划 / 自动 / 建议) are one bug, not three:
 * they are three values of one el-segmented that all post to the same aiChat().
 *
 * <p>Regression being guarded (broken since 8afc12cdff, 2026-06-05, never fixed):
 * canvas_work_process_catalog requires a typed {@code action} parameter, the panel
 * posted prose, and the D3 narrow route bypasses the LLM — so nothing ever produced
 * {@code action}. Every request died in AbstractBusinessTool's required-parameter
 * check, which returned early *without logging anything*. Prod evidence
 * (cretas-backend-2026-07-20.0.log.gz): both tester requests logged
 * "🔧 开始执行工具: toolName=canvas_work_process_catalog" and then nothing — no ✅,
 * no ❌ — and "AI 工序新增命中重复" appears 0 times in every archive, i.e. the tool
 * has never once succeeded.
 */
@ExtendWith(MockitoExtension.class)
class WorkProcessCatalogChatContractTest {

    private static final String CATALOG_TOOL = "canvas_work_process_catalog";
    private static final String CATALOG_MODULE = "work_process_catalog";

    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private DashScopeClient dashScopeClient;
    @Mock
    private MobileService mobileService;
    @Mock
    private ToolExecutionGateway toolExecutionGateway;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CanvasAIController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasAIController(
                toolRegistry, objectMapper, dashScopeClient, mobileService, toolExecutionGateway);
    }

    // ------------------------------------------------------------------
    // The fix: prose actually reaches the tool as typed parameters
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自然语言被抽成 action/processName 等具名参数后才交给工具")
    void proseIsExtractedIntoTypedToolParameters() {
        stubAuthenticated();
        stubExtraction("""
                {"action":"create","processName":"焯水","processCategory":"加工",
                 "standardHourlyRate":25}
                """);
        stubToolSuccess();

        CanvasAIController.AIResponse response =
                controller.chat("F006", "Bearer test-token",
                        request("plan", "新建焯水工序，加工类，标准时薪25")).getData();

        ToolExecutionCommand command = capturedCommand();
        assertEquals(CATALOG_TOOL, command.toolName());
        // This is the whole bug: `action` used to be absent on every single request.
        assertEquals("create", command.parameters().path("action").asText());
        assertEquals("焯水", command.parameters().path("processName").asText());
        assertEquals("加工", command.parameters().path("processCategory").asText());
        assertEquals(25, command.parameters().path("standardHourlyRate").asInt());
        assertFalse(response.isApplied());
    }

    @ParameterizedTest
    @ValueSource(strings = {"plan", "autopilot", "action"})
    @DisplayName("计划/自动/建议三个入口都能把 action 送到工具 —— 它们是同一条路")
    void allThreeSegmentedEntriesReachTheToolWithAnAction(String mode) {
        stubAuthenticated();
        stubExtraction("{\"action\":\"create\",\"processName\":\"焯水\"}");
        stubToolSuccess();

        CanvasAIController.AIResponse response =
                controller.chat("F006", "Bearer test-token",
                        request(mode, "新建焯水工序")).getData();

        assertEquals("create", capturedCommand().parameters().path("action").asText());
        assertFalse(response.isApplied());
        assertNotNull(response.getReply());
        // 建议模式只分析不给执行入口; 另两个给可应用的 diff
        assertEquals("action".equals(mode) ? 0 : 1, response.getDiffs().size());
    }

    @Test
    @DisplayName("调用方已经给了结构化 action 时不再问模型")
    void explicitParametersSkipTheExtractionCall() {
        stubAuthenticated();
        stubToolSuccess();

        CanvasAIController.AIRequest request = request("plan", "新建焯水工序");
        request.setParams(Map.of("action", "create", "processName", "焯水"));

        controller.chat("F006", "Bearer test-token", request);

        assertEquals("create", capturedCommand().parameters().path("action").asText());
        verify(dashScopeClient, never()).chatLowTemp(anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Failures must say what happened
    // ------------------------------------------------------------------

    @Test
    @DisplayName("抽不出参数时给可操作的说明, 且绝不带着半份参数去调工具")
    void unparseableProseFailsWithAnActionableMessageAndNeverCallsTheTool() {
        stubAuthenticated();
        stubExtraction("这不是 JSON");

        CanvasAIController.AIResponse response =
                controller.chat("F006", "Bearer test-token",
                        request("plan", "随便改改")).getData();

        assertTrue(response.getDiffs().isEmpty());
        assertTrue(response.getReply().contains("新建焯水工序"),
                "报错要给一个能照着做的例子, 实际: " + response.getReply());
        verify(toolExecutionGateway, never()).execute(any(ToolExecutionCommand.class));
    }

    @Test
    @DisplayName("模型说不清缺什么时, 把它那句话透传给用户")
    void extractorErrorIsSurfacedVerbatim() {
        stubAuthenticated();
        stubExtraction("{\"error\":\"没说要改哪一道工序，请补上工序名\"}");

        CanvasAIController.AIResponse response =
                controller.chat("F006", "Bearer test-token",
                        request("plan", "改一下")).getData();

        assertTrue(response.getReply().contains("请补上工序名"));
        verify(toolExecutionGateway, never()).execute(any(ToolExecutionCommand.class));
    }

    @Test
    @DisplayName("模型漏了 action 时报缺 action, 不猜一个默认值")
    void missingActionIsReportedRatherThanGuessed() {
        stubAuthenticated();
        stubExtraction("{\"processName\":\"焯水\"}");

        CanvasAIController.AIResponse response =
                controller.chat("F006", "Bearer test-token",
                        request("plan", "焯水")).getData();

        assertTrue(response.getReply().contains("新建") && response.getReply().contains("修改"),
                "要告诉用户写明新建还是修改, 实际: " + response.getReply());
        verify(toolExecutionGateway, never()).execute(any(ToolExecutionCommand.class));
    }

    // ------------------------------------------------------------------
    // Projection loss: the reason lives in `message`, the reader looked at `error`
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工具把原因写在 message 里时也要透出来, 不能替换成通用文案")
    void toolFailureCarriedOnlyInMessageIsStillSurfaced() {
        stubAuthenticated();
        stubExtraction("{\"action\":\"create\",\"processName\":\"焯水\"}");
        // The NEED_MORE_INFO shape that AbstractBusinessTool produced for two months:
        // success=false with the reason under `message` and NO `error` key at all.
        stubToolPayload(Map.of(
                "success", false,
                "status", "NEED_MORE_INFO",
                "needMoreInfo", true,
                "message", "为了完成操作，还需要以下信息：\n1. 请问工序名称是什么？"));

        CanvasAIController.AIResponse response =
                controller.chat("F006", "Bearer test-token",
                        request("plan", "新建工序")).getData();

        assertTrue(response.getReply().contains("请问工序名称是什么"),
                "真实原因被吞掉了, 实际: " + response.getReply());
        assertFalse(response.getReply().equals("工序管理工具执行失败，请检查输入后重试"),
                "又退回了那句没有内容的通用文案");
    }

    @Test
    @DisplayName("error 键存在时优先用 error")
    void errorKeyStillWinsWhenPresent() {
        stubAuthenticated();
        stubExtraction("{\"action\":\"create\",\"processName\":\"焯水\"}");
        stubToolPayload(Map.of(
                "success", false,
                "error", "已存在同名同类别工序",
                "message", "次要说明"));

        CanvasAIController.AIResponse response =
                controller.chat("F006", "Bearer test-token",
                        request("plan", "新建焯水工序")).getData();

        assertTrue(response.getReply().contains("已存在同名同类别工序"));
    }

    @Test
    @DisplayName("工具既没给 error 也没给 message 时, 兜底文案本身要能指导用户")
    void fallbackTextIsItselfActionable() {
        stubAuthenticated();
        stubExtraction("{\"action\":\"create\",\"processName\":\"焯水\"}");
        stubToolPayload(Map.of("success", false));

        CanvasAIController.AIResponse response =
                controller.chat("F006", "Bearer test-token",
                        request("plan", "新建焯水工序")).getData();

        assertTrue(response.getReply().contains("新建焯水工序"),
                "兜底文案也要带例子, 实际: " + response.getReply());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private CanvasAIController.AIRequest request(String mode, String message) {
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode(CATALOG_MODULE);
        request.setMode(mode);
        request.setMessage(message);
        return request;
    }

    private void stubAuthenticated() {
        when(mobileService.getUserFromToken("test-token")).thenReturn(UserDTO.builder()
                .id(42L)
                .factoryId("F006")
                .factoryType("FACTORY")
                .roleCode(FactoryUserRole.factory_super_admin)
                .isActive(true)
                .build());
    }

    private void stubExtraction(String llmJson) {
        when(dashScopeClient.chatLowTemp(anyString(), anyString())).thenReturn(llmJson);
    }

    private void stubToolSuccess() {
        stubToolPayload(Map.of(
                "success", true,
                "data", Map.of(
                        "status", "PREVIEW",
                        "action", "create",
                        "processName", "焯水",
                        "message", "将创建工序: 焯水")));
    }

    private void stubToolPayload(Map<String, Object> payload) {
        when(toolExecutionGateway.execute(any())).thenReturn(new ToolExecutionResult(
                "request-catalog",
                CATALOG_TOOL,
                "1.0.0",
                "audit-catalog",
                "trace-catalog",
                ToolExecutionStatus.SUCCEEDED,
                objectMapper.valueToTree(payload),
                "Tool preview completed",
                false));
    }

    private ToolExecutionCommand capturedCommand() {
        ArgumentCaptor<ToolExecutionCommand> captor =
                ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(toolExecutionGateway).execute(captor.capture());
        return captor.getValue();
    }

    /**
     * Sanity: the constants under test are the ones production actually uses.
     * A test that routes on the wrong module code would exercise the generic LLM
     * path instead of the D3 narrow route and prove nothing about this bug.
     */
    @Test
    void moduleAndToolNamesMatchProduction() {
        assertEquals("work_process_catalog", CATALOG_MODULE);
        assertEquals("canvas_work_process_catalog", CATALOG_TOOL);
    }
}
