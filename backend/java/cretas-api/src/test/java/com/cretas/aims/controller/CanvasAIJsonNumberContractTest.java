package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CanvasAIJsonNumberContractTest {
    private static final String FACTORY = "LOCAL_B05";
    private static final long USER_ID = 7L;
    private static final String TOKEN = "canvas-number-proof";
    private static final String TOOL = "canvas_work_process_catalog";
    private static final String VERSION = "1.1.0";

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolRegistry registry;
    private ToolExecutor executor;
    private PreviewTokenService previewTokens;
    private ToolExecutionGateway gateway;
    private CanvasAIController controller;
    private PreviewTokenService.BoundTokenRequest issued;
    private String issuedDigest;
    private JsonNode previewPayload;

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);
        executor = mock(ToolExecutor.class);
        previewTokens = mock(PreviewTokenService.class);
        gateway = mock(ToolExecutionGateway.class);
        MobileService mobile = mock(MobileService.class);
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setFactoryId(FACTORY);
        user.setFactoryType("FACTORY");
        user.setRoleCode(FactoryUserRole.permission_admin);
        when(mobile.getUserFromToken("local-token")).thenReturn(user);
        when(registry.getExecutor(TOOL)).thenReturn(Optional.of(executor));
        when(executor.getVersion()).thenReturn(VERSION);
        previewPayload = mapper.createObjectNode().put("success", true).set("data", mapper.valueToTree(Map.of(
                "action", "create", "processName", "数值工序", "processCategory", "加工",
                "standardHourlyRate", new BigDecimal("25.00"),
                "customFieldSchema", List.of(Map.of("key", "a"), Map.of("key", "b")))));
        when(gateway.execute(any(ToolExecutionCommand.class))).thenAnswer(invocation -> {
            ToolExecutionCommand command = invocation.getArgument(0);
            JsonNode payload = command.mode() == ToolExecutionMode.PREVIEW
                    ? previewPayload
                    : mapper.readTree("{\"success\":true,\"data\":{\"status\":\"SUCCESS\"}}");
            return new ToolExecutionResult(command.requestId(), command.toolName(), command.expectedDescriptorVersion(),
                    "audit-" + command.requestId(), command.traceId(), ToolExecutionStatus.SUCCEEDED,
                    payload, "ok", false);
        });
        controller = new CanvasAIController(registry, mapper, mock(DashScopeClient.class), mobile, gateway);
        ReflectionTestUtils.setField(controller, "previewTokenService", previewTokens);
        when(previewTokens.createBoundToken(any())).thenAnswer(invocation -> {
            issued = invocation.getArgument(0);
            issuedDigest = ToolCommandDigest.commandDigest(
                    issued.factoryId(), issued.userId(), issued.toolName(), issued.descriptorVersion(),
                    issued.executionMode(), mapper.valueToTree(issued.parameters()));
            return IntentPreviewToken.builder().token(TOKEN).factoryId(FACTORY).tenantId(FACTORY)
                    .userId(USER_ID).intentCode("CANVAS_DIFF_123e4567-e89b-12d3-a456-426614174000")
                    .entityType("CANVAS_DIFF").entityId(TOOL).operation("UPDATE")
                    .toolName(TOOL).descriptorVersion(VERSION).executionMode(ToolExecutionMode.EXECUTE)
                    .commandDigest(issuedDigest).expiresAt(LocalDateTime.now().plusMinutes(5))
                    .status(IntentPreviewToken.TokenStatus.PENDING).build();
        });
        when(previewTokens.readBoundSnapshot(eq(TOKEN), eq(FACTORY), eq(USER_ID)))
                .thenAnswer(invocation -> Optional.of(new PreviewTokenService.BoundTokenSnapshot(
                        issued.intentCode(), issued.entityType(), issued.entityId(), issued.operation(),
                        issued.toolName(), issued.descriptorVersion(), issued.executionMode(), issuedDigest,
                        LocalDateTime.now().plusMinutes(5), IntentPreviewToken.TokenStatus.PENDING)));
    }

    @Test
    void publicPreviewRoundTripsBrowserIntegralNumberAndAppliesWithSameDigest() throws Exception {
        CanvasAIController.AIRequest request = request(Map.of(
                "action", "create", "processName", "数值工序", "processCategory", "加工"));
        CanvasAIController.AIResponse preview = controller.chat(
                FACTORY, "Bearer local-token", request).getData();
        assertThat(preview.getDiffs()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> issuedParams = (Map<String, Object>) preview.getDiffs().get(0).get("params");
        assertThat(new BigDecimal(issuedParams.get("standardHourlyRate").toString()))
                .isEqualByComparingTo("25");
        assertThat(preview.getDiffs().get(0)).containsKey("proof");

        List<Map<String, Object>> browserPayload = mapper.readValue(
                mapper.writeValueAsString(preview.getDiffs()), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> browserParams = (Map<String, Object>) browserPayload.get(0).get("params");
        assertThat(browserParams.get("standardHourlyRate")).isInstanceOf(Number.class);
        CanvasAIController.ApplyDiffsResult receipt = controller.applyDiffsWithProtocol(
                FACTORY, "Bearer local-token", "3", browserPayload).getData();
        assertThat(receipt.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("SUCCESS");
        ArgumentCaptor<ToolExecutionCommand> commands = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway, times(2)).execute(commands.capture());
        ToolExecutionCommand apply = commands.getAllValues().get(1);
        assertThat(apply.parameters().get("standardHourlyRate").decimalValue())
                .isEqualByComparingTo("25");
    }

    @Test
    void persistedJsonReadbackKeepsDigestForSmallExponentNegativeZeroAndNestedArrays() throws Exception {
        previewPayload = mapper.createObjectNode().put("success", true).set("data", mapper.valueToTree(
                Map.of("action", "create", "processName", "持久化数值工序", "processCategory", "加工",
                        "standardHourlyRate", Double.valueOf(-0.0),
                        "customFieldSchema", List.of(
                                Map.of("values", List.of(Double.valueOf(0.0000001), Double.valueOf(-0.0))),
                                Map.of("values", List.of(Double.valueOf(25.5)))))));

        CanvasAIController.AIResponse preview = controller.chat(
                FACTORY, "Bearer local-token", request(Map.of(
                        "action", "create", "processName", "持久化数值工序", "processCategory", "加工"))).getData();
        assertThat(preview.getDiffs()).singleElement()
                .satisfies(diff -> assertThat(diff).containsKey("proof"));
        assertThat(issued).isNotNull();

        Map<String, Object> persistedReadback = mapper.readValue(
                mapper.writeValueAsString(issued.parameters()), new TypeReference<>() {});
        assertThat(issued.parameters().get("standardHourlyRate"))
                .isInstanceOf(Integer.class);
        assertThat(((Number) issued.parameters().get("standardHourlyRate")).doubleValue())
                .isEqualTo(0.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schema = (List<Map<String, Object>>) issued.parameters().get("customFieldSchema");
        @SuppressWarnings("unchecked")
        List<Object> firstValues = (List<Object>) schema.get(0).get("values");
        assertThat(firstValues.get(0)).isInstanceOf(Double.class);
        assertThat(((Number) firstValues.get(0)).doubleValue()).isEqualTo(0.0000001);
        assertThat(firstValues.get(1)).isInstanceOf(Integer.class);
        assertThat(((Number) firstValues.get(1)).doubleValue()).isEqualTo(0.0);
        @SuppressWarnings("unchecked")
        List<Object> secondValues = (List<Object>) schema.get(1).get("values");
        assertThat(secondValues.get(0)).isInstanceOf(Double.class);
        assertThat(((Number) secondValues.get(0)).doubleValue()).isEqualTo(25.5);
        assertThat(persistedReadback.get("standardHourlyRate")).isInstanceOf(Integer.class);
        assertThat(ToolCommandDigest.parametersHash(mapper.valueToTree(issued.parameters())))
                .isEqualTo(ToolCommandDigest.parametersHash(mapper.valueToTree(persistedReadback)));
        assertThat(ToolCommandDigest.commandDigest(
                issued.factoryId(), issued.userId(), issued.toolName(), issued.descriptorVersion(),
                issued.executionMode(), mapper.valueToTree(issued.parameters())))
                .isEqualTo(ToolCommandDigest.commandDigest(
                        issued.factoryId(), issued.userId(), issued.toolName(), issued.descriptorVersion(),
                        issued.executionMode(), mapper.valueToTree(persistedReadback)));

        List<Map<String, Object>> browserPayload = mapper.readValue(
                mapper.writeValueAsString(preview.getDiffs()), new TypeReference<>() {});
        CanvasAIController.ApplyDiffsResult receipt = controller.applyDiffsWithProtocol(
                FACTORY, "Bearer local-token", "3", browserPayload).getData();
        assertThat(receipt.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("SUCCESS");
        ArgumentCaptor<ToolExecutionCommand> commands = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway, times(2)).execute(commands.capture());
        JsonNode applied = commands.getAllValues().get(1).parameters();
        assertThat(applied.get("standardHourlyRate").numberValue()).isInstanceOf(Integer.class);
        JsonNode appliedValue = applied.at("/customFieldSchema/0/values/0");
        assertThat(appliedValue.numberValue()).isInstanceOf(Double.class);
        assertThat(appliedValue.doubleValue()).isEqualTo(0.0000001);
        assertThat(applied).isEqualTo(mapper.valueToTree(persistedReadback));
    }

    @Test
    void precisionLossOrUnsafeIntegerDoesNotMintProofOrReachApplyGateway() {
        previewPayload = mapper.createObjectNode().put("success", true).set("data", mapper.valueToTree(
                Map.of("action", "create", "processName", "精度工序", "processCategory", "加工",
                        "standardHourlyRate", new BigDecimal("0.100000000000000005"))));
        CanvasAIController.AIResponse precisionPreview = controller.chat(
                FACTORY, "Bearer local-token", request(Map.of(
                        "action", "create", "processName", "精度工序", "processCategory", "加工"))).getData();
        assertThat(precisionPreview.getDiffs()).singleElement()
                .satisfies(diff -> assertThat(diff).doesNotContainKey("proof"));
        verify(previewTokens, never()).createBoundToken(any());

        previewPayload = mapper.createObjectNode().put("success", true).set("data", mapper.valueToTree(
                Map.of("action", "create", "processName", "大整数工序", "processCategory", "加工",
                        "standardHourlyRate", 9_007_199_254_740_993L)));
        CanvasAIController.AIResponse unsafePreview = controller.chat(
                FACTORY, "Bearer local-token", request(Map.of(
                        "action", "create", "processName", "大整数工序", "processCategory", "加工"))).getData();
        assertThat(unsafePreview.getDiffs()).singleElement()
                .satisfies(diff -> assertThat(diff).doesNotContainKey("proof"));
        verify(previewTokens, never()).createBoundToken(any());
        verify(gateway, times(2)).execute(any(ToolExecutionCommand.class));
    }

    @Test
    void changedStringExtraMissingOrReorderedArrayCannotApplyTheIssuedProof() {
        CanvasAIController.AIResponse preview = controller.chat(
                FACTORY, "Bearer local-token", request(Map.of(
                        "action", "create", "processName", "数值工序", "processCategory", "加工"))).getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) preview.getDiffs().get(0).get("params");
        List<Map<String, Object>> tampered = List.of(
                with(original, "standardHourlyRate", 26),
                with(original, "standardHourlyRate", "25"),
                with(original, "extra", true),
                without(original, "processCategory"),
                with(original, "customFieldSchema", List.of(Map.of("key", "b"), Map.of("key", "a"))));
        for (Map<String, Object> params : tampered) {
            Map<String, Object> diff = new LinkedHashMap<>(preview.getDiffs().get(0));
            diff.put("params", params);
            CanvasAIController.ApplyDiffsResult receipt = controller.applyDiffsWithProtocol(
                    FACTORY, "Bearer local-token", "3", List.of(diff)).getData();
            assertThat(receipt.items()).singleElement()
                    .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        }
        verify(gateway, times(1)).execute(any(ToolExecutionCommand.class));
        verify(previewTokens, never()).claimToken(any(), any(), anyLong(), any());
    }

    private CanvasAIController.AIRequest request(Map<String, Object> params) {
        CanvasAIController.AIRequest request = new CanvasAIController.AIRequest();
        request.setModuleCode("work_process_catalog");
        request.setMode("plan");
        request.setMessage("新建数值工序，加工类，标准时薪25");
        request.setParams(new LinkedHashMap<>(params));
        return request;
    }

    private Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private Map<String, Object> without(Map<String, Object> source, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove(key);
        return copy;
    }
}
