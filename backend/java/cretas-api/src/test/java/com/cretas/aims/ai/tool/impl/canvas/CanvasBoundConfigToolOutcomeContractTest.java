package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.config.CanvasConfigCommandService;
import com.cretas.aims.service.config.CanvasConfigPlanBindingService;
import com.cretas.aims.service.config.CanvasConfigStateReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real tool serialization must distinguish a proven zero-write stale plan from uncertainty. */
class CanvasBoundConfigToolOutcomeContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    static Stream<AbstractBoundCanvasConfigTool> writers() {
        return Stream.of(new CanvasToggleModuleTool(), new CanvasUpdateFieldTool(),
                new CanvasApplyTemplateTool(), new CanvasToggleToolTool(),
                new CanvasToggleSkillTool(), new CanvasUpdateTriggerChainTool());
    }

    @ParameterizedTest @MethodSource("writers")
    void onlyDedicatedWriteFreeStaleExceptionProducesTheDefiniteFailureEnvelope(AbstractBoundCanvasConfigTool tool)
            throws Exception {
        CanvasConfigStateReader reader = mock(CanvasConfigStateReader.class);
        when(reader.read(anyString(), anyString(), anyMap())).thenReturn(
                new CanvasConfigStateReader.Snapshot("config:7", "private-before", Map.of()),
                new CanvasConfigStateReader.Snapshot("config:7", "private-after", Map.of()));
        CanvasConfigPlanBindingService bindings = new CanvasConfigPlanBindingService(reader, mapper);
        Map<String, Object> bound = bindings.issue("LOCAL_CANVAS", tool.getToolName(), params());
        CanvasConfigPlanBindingService.StalePlanException stale;
        try {
            bindings.verifyBeforeWrite("LOCAL_CANVAS", tool.getToolName(), bound);
            throw new AssertionError("Fixture must produce the actual prewrite stale exception");
        } catch (CanvasConfigPlanBindingService.StalePlanException expected) {
            stale = expected;
        }
        JsonNode result = executeFailure(tool, stale);
        assertThat(result.get("success").isBoolean()).isTrue();
        assertThat(result.get("success").booleanValue()).isFalse();
        assertThat(result.path("status").asText()).isEqualTo("NEED_MORE_INFO");
        assertThat(result.path("errorCode").intValue()).isEqualTo(409);
        assertThat(result.path("requiresNewPreview").booleanValue()).isTrue();
        assertThat(result.path("error").asText()).isNotBlank().isEqualTo(result.path("message").asText());
        assertThat(result.toString()).doesNotContain("private-before", "private-after", "__canvas_binding");
    }

    @ParameterizedTest @MethodSource("writers")
    void ordinary409CannotClaimThatNoWriteHappened(AbstractBoundCanvasConfigTool tool) throws Exception {
        assertUncertain(executeFailure(tool, new BusinessException(409, "普通写入冲突")));
    }

    @ParameterizedTest @MethodSource("writers")
    void commitOrReadbackFailureDoesNotOfferANewPreviewAsSafeRetry(AbstractBoundCanvasConfigTool tool)
            throws Exception {
        assertUncertain(executeFailure(tool, new IllegalStateException("private commit/readback failure")));
    }

    private JsonNode executeFailure(AbstractBoundCanvasConfigTool tool, RuntimeException failure) throws Exception {
        CanvasConfigCommandService commands = mock(CanvasConfigCommandService.class);
        ReflectionTestUtils.setField(tool, "commandExecutor", commands);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        when(commands.execute(anyString(), eq(tool.getToolName()), anyMap(), anyMap())).thenThrow(failure);
        JsonNode result = mapper.readTree(tool.execute(
                ToolCall.of("canvas-outcome-contract", tool.getToolName(), mapper.writeValueAsString(params())),
                Map.of("factoryId", "LOCAL_CANVAS", "userId", 7L)));
        verify(commands).execute(eq("LOCAL_CANVAS"), eq(tool.getToolName()), anyMap(), anyMap());
        return result;
    }

    private void assertUncertain(JsonNode result) {
        assertThat(result.get("success").isBoolean()).isTrue();
        assertThat(result.get("success").booleanValue()).isFalse();
        assertThat(result.path("status").asText()).isNotEqualTo("NEED_MORE_INFO");
        assertThat(result.has("requiresNewPreview")).isFalse();
        assertThat(result.path("error").asText()).isNotBlank();
    }

    private Map<String, Object> params() {
        return Map.of("moduleCode", "inventory", "fieldCode", "lot", "property", "label", "value", "批次",
                "templateCode", "FOOD", "toolName", "sample_tool", "skillName", "sample_skill",
                "chainCode", "sample_chain", "enabled", false);
    }
}
