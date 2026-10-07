package com.cretas.aims.controller;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.TestIntentMatchingTool;
import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.impl.AIIntentServiceImpl;
import com.cretas.aims.service.impl.IntentConfigRollbackService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.IntentRecognitionPipelineService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2026-10-07: {@code POST /api/mobile/{factoryId}/ai-intents/recognize} 走到 Python
 * {@code /api/ai/intent/match} 时没带 userId, Python 侧 {@code IntentMatchRequest.userId: str}
 * 必填 ⇒ 每次 422 (而且 4xx 属于熔断器的 recordExceptions, 会拖累别的租户)。
 * App 走的流式入口带了 userId, 所以线上日志里该接口 0 次 422 —— 只有这个诊断入口漏传。
 *
 * <p><b>这里量的是线上发出去的那个东西</b>: 真实 controller → 真实 {@link AIIntentServiceImpl} →
 * 真实 {@link PythonAiMatcherClient} → 真实 Jackson 序列化, 只在 HTTP 那一层用
 * {@link MockRestServiceServer} 桩掉 Python。桩的行为刻意模仿 Python 的 pydantic:
 * 请求体里没有 {@code userId} 就回 422, 并计数。⇒ 「422 次数 == 0」是判据, 「Python 被调用了 N 次」是
 * 与之配对的「一定发生」读数 (硬约束 9): 没调到 Python 时 422==0 毫无意义。
 */
@DisplayName("recognize 入口把登录用户的 userId 带给 Python /api/ai/intent/match")
class AIIntentRecognizeUserIdWireTest {

    private static final String FACTORY = "F001";
    private static final String PYTHON_URL = "http://python.test";
    private static final String MATCH_URL = PYTHON_URL + "/api/ai/intent/match";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 桩收到的每个请求体 (原样 JSON 文本) —— 「Python 被调用了几次」的读数。 */
    private final List<String> pythonBodies = new ArrayList<>();
    /** 桩按 pydantic 规则回了几次 422 (请求体缺 userId)。 */
    private int python422Count;

    private AIIntentServiceImpl intentService;
    private IntentRecognitionPipelineService pipeline;
    private SimpleMeterRegistry meters;
    private JwtUtil jwtUtil;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer python = MockRestServiceServer.bindTo(restTemplate).build();
        python.expect(ExpectedCount.manyTimes(), requestTo(MATCH_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
                    pythonBodies.add(body);
                    JsonNode sent = JSON.readTree(body);
                    if (!sent.hasNonNull("userId")) {
                        python422Count++;
                        MockClientHttpResponse r = new MockClientHttpResponse(
                                "{\"detail\":[{\"loc\":[\"body\",\"userId\"],\"msg\":\"Field required\"}]}"
                                        .getBytes(StandardCharsets.UTF_8),
                                HttpStatus.UNPROCESSABLE_ENTITY);
                        r.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        return r;
                    }
                    // 合法请求: 回「没匹配上」, 让流程继续落到 legacy (mock)。
                    MockClientHttpResponse r = new MockClientHttpResponse(
                            "{\"success\":true,\"data\":{\"userInput\":\"q\"},\"message\":\"OK\"}"
                                    .getBytes(StandardCharsets.UTF_8),
                            HttpStatus.OK);
                    r.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return r;
                });

        pipeline = mock(IntentRecognitionPipelineService.class);
        when(pipeline.recognizeIntentWithConfidence(anyString(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        IntentResultCache cache = mock(IntentResultCache.class);
        when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        meters = new SimpleMeterRegistry();
        intentService = new AIIntentServiceImpl(pipeline, configService, null);
        ReflectionTestUtils.setField(intentService, "pythonClient",
                new PythonAiMatcherClient(restTemplate, PYTHON_URL, "test-secret"));
        ReflectionTestUtils.setField(intentService, "intentResultCache", cache);
        ReflectionTestUtils.setField(intentService, "usePythonMatcher", true);
        ReflectionTestUtils.setField(intentService, "meterRegistry", meters);

        jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.getUserIdFromToken("jwt-token")).thenReturn(12L);
        when(jwtUtil.getRoleFromToken("jwt-token")).thenReturn("FACTORY_ADMIN");
        // 一个有 token、但解不出 userId 的情形 (过期/无 userId 声明)
        when(jwtUtil.getUserIdFromToken("no-user-token")).thenReturn(null);
        when(jwtUtil.getRoleFromToken("no-user-token")).thenReturn("FACTORY_ADMIN");

        AIIntentConfigController controller = new AIIntentConfigController(
                intentService,
                mock(IntentExecutorService.class),
                mock(KeywordEffectivenessService.class),
                mock(IntentConfigRollbackService.class),
                mock(ParameterExtractionLearningService.class),
                jwtUtil,
                null);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private JsonNode soleSentBody() throws Exception {        assertEquals(1, pythonBodies.size(), "Python 必须被调用恰好 1 次 (配对的「一定发生」读数)");
        return JSON.readTree(pythonBodies.get(0));
    }

    @Test
    @DisplayName("Bearer token: 发给 Python 的请求体带 userId=12 / role, Python 不再 422")
    void bearerToken_userIdAndRoleReachPython() throws Exception {
        mvc.perform(post("/api/mobile/{f}/ai-intents/recognize", FACTORY)
                        .header("Authorization", "Bearer jwt-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userInput\":\"查库存\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        JsonNode sent = soleSentBody();
        assertEquals("12", sent.path("userId").asText(), "userId 必须来自 token, 且是 Python 要的字符串");
        assertTrue(sent.path("userId").isTextual(), "Python 侧 userId: str");
        assertEquals("FACTORY_ADMIN", sent.path("role").asText());
        assertEquals(FACTORY, sent.path("factoryId").asText());
        assertEquals("查库存", sent.path("query").asText());
        assertEquals(0, python422Count, "桩按 pydantic 规则数到的 422 次数");
    }

    @Test
    @DisplayName("只有 cookie (web-admin) 也一样带上 userId")
    void cookieToken_userIdReachesPython() throws Exception {
        mvc.perform(post("/api/mobile/{f}/ai-intents/recognize", FACTORY)
                        .cookie(new Cookie(CookieAuthHelper.ACCESS_TOKEN_COOKIE, "jwt-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userInput\":\"查库存\"}"))
                .andExpect(status().isOk());

        assertEquals("12", soleSentBody().path("userId").asText());
        assertEquals(0, python422Count);
    }

    @Test
    @DisplayName("客户端在请求体里自报 userId 不生效: 发给 Python 的永远是 token 里的人")
    void clientSelfReportedUserId_isIgnored() throws Exception {
        mvc.perform(post("/api/mobile/{f}/ai-intents/recognize", FACTORY)
                        .header("Authorization", "Bearer jwt-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userInput\":\"查库存\",\"userId\":999,\"role\":\"platform_admin\"}"))
                .andExpect(status().isOk());

        JsonNode sent = soleSentBody();
        assertEquals("12", sent.path("userId").asText());
        assertEquals("FACTORY_ADMIN", sent.path("role").asText());
        assertFalse(pythonBodies.get(0).contains("999"), "自报的 999 不得出现在发给 Python 的任何字段里");
    }

    @Test
    @DisplayName("没有 token / 解不出 userId: 401 拒绝, 不调 Python, 不用 0 / null 顶替")
    void noResolvableUser_isRejected_andPythonIsNeverCalled() throws Exception {
        // 对照 (一定发生): 同一个入口带上能解析的 token 时 Python 会被调用 —— 见上面三条。
        mvc.perform(post("/api/mobile/{f}/ai-intents/recognize", FACTORY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userInput\":\"查库存\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mobile/{f}/ai-intents/recognize", FACTORY)
                        .header("Authorization", "Bearer no-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userInput\":\"查库存\"}"))
                .andExpect(status().isUnauthorized());

        assertEquals(0, pythonBodies.size(), "拒绝发生在调 Python 之前");
        verify(pipeline, never()).recognizeIntentWithConfidence(
                anyString(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("服务层: 没有 userId 时跳过 Python 走旧路径并计数; 有 userId 时照常调 Python")
    void service_withoutUserId_skipsPython_withUserId_callsPython() throws Exception {
        // 没有调用者身份 (别的入口以后再漏传也一样): 不发必 422 的请求
        IntentMatchResult noUser = intentService.recognizeIntentWithConfidence(
                "查库存", FACTORY, 1, null, "FACTORY_ADMIN");
        assertEquals(0, pythonBodies.size(), "userId=null 不得发请求给 Python");
        assertEquals(0, python422Count);
        verify(pipeline, times(1)).recognizeIntentWithConfidence(
                anyString(), any(), anyInt(), any(), any(), any());
        assertEquals(1.0, meters.counter("intent.match.stage.hit", "stage", "PYTHON_SKIPPED_NO_USER").count(),
                "跳过必须可观测, 不是静默");
        assertFalse(noUser.hasMatch());

        // 配对的「一定发生」: 有 userId 的同一调用会到 Python, 且带着 userId
        intentService.recognizeIntentWithConfidence("查库存", FACTORY, 1, 22L, "FACTORY_ADMIN");
        assertEquals("22", soleSentBody().path("userId").asText());
        assertEquals(0, python422Count);
    }

    @Test
    @DisplayName("test_intent_matching 工具: 上下文里的 userId/role 带给 Python")
    void testIntentMatchingTool_passesContextUserToPython() throws Exception {
        TestIntentMatchingTool tool = new TestIntentMatchingTool();
        ReflectionTestUtils.setField(tool, "aiIntentService", intentService);
        ReflectionTestUtils.setField(tool, "objectMapper", JSON);   // AbstractTool 里 @Autowired 的 mapper
        ToolCall call = ToolCall.builder()
                .id("call-1")
                .function(ToolCall.FunctionCall.builder()
                        .name("test_intent_matching")
                        .arguments("{\"userInput\":\"查库存\"}")
                        .build())
                .build();
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", FACTORY);
        context.put("userId", 12L);
        context.put("userRole", "FACTORY_ADMIN");

        tool.execute(call, context);

        JsonNode sent = soleSentBody();
        assertEquals("12", sent.path("userId").asText());
        assertEquals("FACTORY_ADMIN", sent.path("role").asText());
        assertEquals(0, python422Count);
    }
}
