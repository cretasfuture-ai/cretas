package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.service.WorkProcessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real public Tool preview/execute boundary for AI工艺标准字段. */
@ExtendWith(MockitoExtension.class)
class WorkProcessAiStandardsContractTest {

    private static final String FACTORY = "F006";

    @Mock
    private WorkProcessService workProcessService;

    private WorkProcessCatalogTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new WorkProcessCatalogTool(workProcessService);
        ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
    }

    @Test
    void previewUsesStandardsAndNeverWrites() throws Exception {
        PageResponse<WorkProcessDTO> empty = new PageResponse<>();
        empty.setContent(List.of());
        when(workProcessService.list(eq(FACTORY), any())).thenReturn(empty);

        String result = tool.preview(call(arguments()), context());
        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("success").asBoolean());
        assertEquals(155, json.path("data").path("customFieldSchema").get(0)
                .path("standardValue").asInt());
        assertTrue(json.path("data").path("message").asText().contains("油温"));
        assertTrue(json.path("data").path("message").asText().contains("上限=160"));
        verify(workProcessService).list(eq(FACTORY), any());
        verify(workProcessService, never()).create(anyString(), any());
        verify(workProcessService, never()).update(anyString(), anyString(), any());
    }

    @Test
    void createWithEmptySchemaDoesNotClaimToClearExistingFields() throws Exception {
        PageResponse<WorkProcessDTO> empty = new PageResponse<>();
        empty.setContent(List.of());
        when(workProcessService.list(eq(FACTORY), any())).thenReturn(empty);
        String arguments = "{\"action\":\"create\",\"processName\":\"油炸\",\"customFieldSchema\":[]}";
        JsonNode json = objectMapper.readTree(tool.preview(call(arguments), context()));
        assertTrue(json.path("success").asBoolean());
        String message = json.path("data").path("message").asText();
        assertTrue(message.contains("不配置工艺标准字段"));
        assertFalse(message.contains("清空"));
        verify(workProcessService, never()).create(anyString(), any());
    }

    @ParameterizedTest
    @MethodSource("invalidFieldTypes")
    void invalidFieldTypesFailPreviewAndExecuteBeforeAnyServiceCall(String key, Object value) throws Exception {
        Map<String, Object> field = new HashMap<>(Map.of(
                "key", "oilTemp", "label", "油温", "type", "number", "enabled", true));
        field.put(key, value);
        String arguments = objectMapper.writeValueAsString(Map.of(
                "action", "create", "processName", "油炸", "customFieldSchema", List.of(field)));
        assertFalse(objectMapper.readTree(tool.preview(call(arguments), context())).path("success").asBoolean());
        assertFalse(objectMapper.readTree(tool.execute(call(arguments), context())).path("success").asBoolean());
        verifyNoInteractions(workProcessService);
    }

    static Stream<Arguments> invalidFieldTypes() {
        return Stream.of(Arguments.of("key", 123), Arguments.of("label", 456),
                Arguments.of("type", 7), Arguments.of("enabled", "false"), Arguments.of("enabled", 0));
    }

    @Test
    void updatePreviewDisclosesWholeSchemaReplacementBeforeAnyWrite() throws Exception {
        String arguments = "{\"action\":\"update\",\"id\":\"wp-1\",\"customFieldSchema\":[]}";
        JsonNode json = objectMapper.readTree(tool.preview(call(arguments), context()));
        assertTrue(json.path("success").asBoolean());
        String message = json.path("data").path("message").asText();
        assertTrue(message.contains("确认后清空全部字段"));
        assertTrue(message.contains("未列出的字段将移除"));
        assertFalse(message.contains("已清空"));
        verify(workProcessService, never()).create(anyString(), any());
        verify(workProcessService, never()).update(anyString(), anyString(), any());
    }

    @Test
    void executeMapsTheSameStandardsAndWritesOnce() throws Exception {
        PageResponse<WorkProcessDTO> empty = new PageResponse<>();
        empty.setContent(List.of());
        when(workProcessService.list(eq(FACTORY), any())).thenReturn(empty);
        when(workProcessService.create(eq(FACTORY), any(WorkProcessDTO.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        String result = tool.execute(call(arguments()), context());
        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("success").asBoolean());
        ArgumentCaptor<WorkProcessDTO> dto = ArgumentCaptor.forClass(WorkProcessDTO.class);
        verify(workProcessService).create(eq(FACTORY), dto.capture());
        assertEquals("oilTemp", dto.getValue().getCustomFieldSchema().get(0).get("key"));
        assertEquals(155, ((Number) dto.getValue().getCustomFieldSchema().get(0).get("standardValue")).intValue());
    }

    @Test
    void malformedOrInvalidStandardsFailBeforeAnyWrite() throws Exception {
        String malformed = "{\"action\":\"create\",\"processName\":\"油炸\","
                + "\"processCategory\":\"加工\",\"customFieldSchema\":{\"key\":\"oilTemp\"}}";
        String invalid = "{\"action\":\"create\",\"processName\":\"油炸\","
                + "\"processCategory\":\"加工\",\"customFieldSchema\":["
                + "{\"key\":\"oilTemp\",\"label\":\"油温\",\"type\":\"number\","
                + "\"min\":160,\"max\":150}]}";

        assertFalse(objectMapper.readTree(tool.execute(
                ToolCall.of("bad-shape", tool.getToolName(), malformed), context())).path("success").asBoolean());
        assertFalse(objectMapper.readTree(tool.execute(
                ToolCall.of("bad-range", tool.getToolName(), invalid), context())).path("success").asBoolean());
        verify(workProcessService, never()).list(anyString(), any());
        verify(workProcessService, never()).create(anyString(), any());
        verify(workProcessService, never()).update(anyString(), anyString(), any());
    }

    private ToolCall call(String arguments) {
        return ToolCall.of("standards-contract", tool.getToolName(), arguments);
    }

    private Map<String, Object> context() {
        return Map.of("factoryId", FACTORY, "userId", 42L, "userRole", "factory_super_admin");
    }

    private String arguments() {
        return "{\"action\":\"create\",\"processName\":\"油炸\",\"processCategory\":\"加工\","
                + "\"customFieldSchema\":[{\"key\":\"oilTemp\",\"label\":\"油温\","
                + "\"type\":\"number\",\"enabled\":true,\"unit\":\"℃\","
                + "\"standardValue\":155,\"min\":150,\"max\":160}]}";
    }
}
