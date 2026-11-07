package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.cretas.aims.utils.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ERP-S6-MSG-TEXT-PRICE-LEAK-001: 对话回复里, 文字与字段对同一个人必须同一个可见性。
 * acc-s6 线上复测 (F006): REPORT_FINANCE 给非财务角色的字段 totalCost / equipmentCost / otherCost 已是 null,
 * 同一条回复的文字却照样写着「净利润: ¥-3.00 / 成本构成: 设备¥2.00 | 其他¥1.00」—— 文字是在遮蔽之前从原始数据渲染的。
 *
 * <p>真实链路: 真实 {@link PermissionServiceImpl} (F006 形态的 L2 配置) → 真实 {@link PriceFieldResponseAdvice}
 * (字段: HTTP 响应层; 文字: 同一个判定的 {@code maskedViewFor}) → 真实 {@link ResultFormatterServiceImpl#formatAndSet}
 * → SSE 推送 ({@code SseStreamingService.sendSseEvent}, 不经过 HTTP 响应层)。
 */
class ChatReplyPriceTextContractTest {

    private static final String F = "F006";
    private static final Map<String, Long> USERS = Map.of(
            "factory_super_admin", 1L, "finance_manager", 2L,
            "production_manager", 3L, "sales_manager", 4L, "procurement_manager", 5L);
    /** 线上读到的那组数 —— 任何一个出现在非财务角色的文字里都是泄漏。 */
    private static final List<String> AMOUNTS = List.of("100.00", "-3.00", "3.00", "2.00", "1.00", "¥");

    private final PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
    private final FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private PriceFieldResponseAdvice advice;
    private ResultFormatterServiceImpl formatter;

    @BeforeEach
    void setUp() {
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", l1);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", l2);
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        Map<String, Map<String, String>> override = new HashMap<>();
        override.put("finance_manager", Map.of("finance", "rw"));
        override.put("production_manager", Map.of("finance", "-", "production", "rw"));
        override.put("sales_manager", Map.of("finance", "-", "sales", "rw"));
        override.put("procurement_manager", Map.of("finance", "-", "procurement", "rw"));
        when(l2.findByFactoryIdAndConfigVersion(F, 1)).thenAnswer(inv -> {
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(F);
            cfg.setRoleModuleOverride(override);
            return List.of(cfg);
        });
        USERS.forEach((role, id) -> {
            User u = new User();
            u.setId(id);
            u.setFactoryId(F);
            u.setUsername("leak-" + role);
            u.setRoleCode(role);
            u.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(u));
        });

        advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ReflectionTestUtils.setField(advice, "objectMapper", json);
        formatter = new ResultFormatterServiceImpl();
        ReflectionTestUtils.setField(formatter, "priceView", advice);
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void tearDown() {
        PriceSensitiveContext.clear();
    }

    @ParameterizedTest(name = "{0}: 字段与文字都没有金额")
    @ValueSource(strings = {"production_manager", "sales_manager", "procurement_manager"})
    void nonFinance_noAmountInFieldsNorInText(String role) throws Exception {
        long userId = USERS.get(role);

        JsonNode fields = restFields(userId);
        for (String key : List.of("totalRevenue", "totalProfit", "totalCost", "materialCost", "equipmentCost", "otherCost")) {
            assertThat(fields.path(key).isNull()).as("字段 %s (role=%s)", key, role).isTrue();
        }

        IntentExecuteResponse response = financeReport();
        formatter.formatAndSet(response, userId);
        String text = response.getFormattedText();
        // 必然产出: 回复照样渲染出来了 (不是因为没渲染才没金额)
        assertThat(text).as("role=%s 的回复文字", role).isNotBlank();
        for (String amount : AMOUNTS) {
            assertThat(text).as("role=%s 文字不能含 %s: %s", role, amount, text).doesNotContain(amount);
        }
        // 原始数据没被改 (遮蔽作用在副本上)
        assertThat(innerData(response).get("equipmentCost")).isEqualTo(new BigDecimal("2.00"));

        JsonNode sse = sseResult(response, userId);
        assertThat(sse.at("/resultData/data/equipmentCost").isNull()).as("SSE 字段 role=%s", role).isTrue();
        assertThat(sse.at("/resultData/data/totalProfit").isNull()).isTrue();
        assertThat(sse.path("formattedText").asText()).doesNotContain("¥");
    }

    @ParameterizedTest(name = "{0}: 字段与文字都看得到 (对照)")
    @ValueSource(strings = {"factory_super_admin", "finance_manager"})
    void financeOrAdmin_seesBoth(String role) throws Exception {
        long userId = USERS.get(role);

        JsonNode fields = restFields(userId);
        assertThat(fields.path("equipmentCost").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(fields.path("totalProfit").decimalValue()).isEqualByComparingTo("-3.00");

        IntentExecuteResponse response = financeReport();
        formatter.formatAndSet(response, userId);
        assertThat(response.getFormattedText())
                .contains("净利润: ¥-3.00")
                .contains("设备¥2.00")
                .contains("其他¥1.00");

        JsonNode sse = sseResult(response, userId);
        assertThat(sse.at("/resultData/data/equipmentCost").decimalValue()).isEqualByComparingTo("2.00");
    }

    @ParameterizedTest(name = "viewer unknown ({0}) ⇒ 按全部不可见")
    @ValueSource(longs = {-1L, 999L})
    void unknownViewer_failsClosed(long userId) {
        IntentExecuteResponse response = financeReport();
        formatter.formatAndSet(response, userId < 0 ? null : userId);
        assertThat(response.getFormattedText()).isNotBlank().doesNotContain("¥");
    }

    /** 工具自己写文字 (AbstractTool.replyShowsAmount): 与同名字段同一个判定 —— 字段被遮, 文字也不写金额。 */
    @ParameterizedTest(name = "{0}: 工具文字跟字段走")
    @ValueSource(strings = {"production_manager", "sales_manager", "procurement_manager", "finance_manager", "factory_super_admin"})
    void toolWrittenText_followsTheSameFieldDecision(String role) throws Exception {
        long userId = USERS.get(role);
        AmountTextTool tool = new AmountTextTool();
        ReflectionTestUtils.setField(tool, "priceView", advice);
        Map<String, Object> context = Map.of("userId", userId);

        boolean fieldVisible = restFields(userId).path("totalCost").isNumber();
        String text = tool.reply(context);
        assertThat(text.contains("¥3.00")).as("role=%s 文字 (%s) 与 totalCost 字段可见性 (%s) 一致", role, text, fieldVisible)
                .isEqualTo(fieldVisible);
        boolean finance = role.equals("finance_manager") || role.equals("factory_super_admin");
        assertThat(fieldVisible).as("对照: 只有财务 / 超管看得到成本").isEqualTo(finance);

        // 没装配判定 ⇒ 按看不到
        AmountTextTool unwired = new AmountTextTool();
        assertThat(unwired.reply(context)).doesNotContain("¥");
    }

    /** 最小的工具: 像 CommissionPendingTotalTool 那样把一个金额字段写进文字。 */
    static final class AmountTextTool extends com.cretas.aims.ai.tool.AbstractTool {
        String reply(Map<String, Object> context) {
            return replyShowsAmount(context, "totalCost") ? "总成本 ¥3.00" : "总成本 (金额按权限不显示)";
        }

        @Override
        public String getToolName() {
            return "amount_text_probe";
        }

        @Override
        public String getDescription() {
            return "probe";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of();
        }

        @Override
        public String execute(com.cretas.aims.ai.dto.ToolCall toolCall, Map<String, Object> context) {
            return reply(context);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** REPORT_FINANCE 工具结果, 数字与 acc-s6 线上读到的一致。 */
    private static IntentExecuteResponse financeReport() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalRevenue", new BigDecimal("100.00"));
        data.put("totalProfit", new BigDecimal("-3.00"));
        data.put("totalCost", new BigDecimal("3.00"));
        data.put("equipmentCost", new BigDecimal("2.00"));
        data.put("otherCost", new BigDecimal("1.00"));
        data.put("materialCost", null);
        data.put("orderCount", 4);
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("reportType", "finance");
        resultData.put("period", "month");
        resultData.put("data", data);
        return IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode("REPORT_FINANCE").intentCategory("REPORT")
                .status("COMPLETED").message("财务报告已生成").resultData(resultData).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> innerData(IntentExecuteResponse response) {
        return (Map<String, Object>) ((Map<String, Object>) response.getResultData()).get("data");
    }

    /** 字段: 与 /execute 同一条 HTTP 响应层 (ApiResponse → PriceFieldResponseAdvice)。 */
    private JsonNode restFields(long userId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", userId);
        Object body = advice.beforeBodyWrite(ApiResponse.success(financeReport()), null,
                MediaType.APPLICATION_JSON, null, new ServletServerHttpRequest(request), null);
        return json.valueToTree(body).at("/data/resultData/data");
    }

    /** SSE: 不经过 HTTP 响应层, 推送的 result 由 sendSseEvent 自己按看的人遮蔽。 */
    private JsonNode sseResult(IntentExecuteResponse response, long userId) throws Exception {
        SseStreamingService sse = mock(SseStreamingService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(sse, "objectMapper", json);
        ReflectionTestUtils.setField(sse, "priceView", advice);
        ReflectionTestUtils.setField(sse, "lastStreamedResult", new HashMap<>());
        ReflectionTestUtils.setField(sse, "streamedCompleteStatus", new HashMap<>());
        List<Object> sent = new ArrayList<>();
        SseEmitter emitter = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                for (var item : builder.build()) {
                    sent.add(item.getData());
                }
            }
        };
        try (SecurityUtils.Scope ignored = SecurityUtils.trustedUserIdScope(userId)) {
            sse.sendSseEvent(emitter, "result", response);
        }
        // SseEmitter's mock builder also exposes the wire control segment
        // ("event:result\\ndata:") as an item.  It is not the payload.  Find
        // the first actual typed JSON object, while retaining compatibility
        // with a legal pre-serialized JSON string.
        for (Object payload : sent) {
            if (payload instanceof String text) {
                String trimmed = text.trim();
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    continue;
                }
                return json.readTree(trimmed);
            }
            JsonNode candidate = json.valueToTree(payload);
            if (candidate.isObject() || candidate.isArray()) {
                return candidate;
            }
        }
        throw new AssertionError("SSE result payload not captured: " + sent);
    }
}
