package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.config.CanvasConfigCommandService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Six bound writers preserve the authenticated actor and cannot use a legacy write path. */
class CanvasToolActorGuardTest {
    static Stream<AbstractBoundCanvasConfigTool> writers() {
        return Stream.of(new CanvasToggleModuleTool(), new CanvasUpdateFieldTool(),
                new CanvasApplyTemplateTool(), new CanvasToggleToolTool(),
                new CanvasToggleSkillTool(), new CanvasUpdateTriggerChainTool());
    }

    @ParameterizedTest @MethodSource("writers")
    void authenticatedActorAndIssuedParametersReachOnlyTheBoundWriter(AbstractBoundCanvasConfigTool tool) {
        CanvasConfigCommandService commands = mock(CanvasConfigCommandService.class);
        ReflectionTestUtils.setField(tool, "commandExecutor", commands);
        Map<String, Object> params = Map.of("issued", "immutable-fixture");
        Map<String, Object> context = Map.of("factoryId", "LOCAL_CANVAS", "userId", 7L);
        when(commands.execute("LOCAL_CANVAS", tool.getToolName(), params, context))
                .thenReturn(Map.of("revision", "config:7"));
        assertThat(tool.doExecute("LOCAL_CANVAS", params, context)).isNotEmpty();
        verify(commands).execute("LOCAL_CANVAS", tool.getToolName(), params, context);
        verifyNoMoreInteractions(commands);
    }

    @ParameterizedTest @MethodSource("writers")
    void missingActorFailsBeforeAnyWriter(AbstractBoundCanvasConfigTool tool) {
        CanvasConfigCommandService commands = mock(CanvasConfigCommandService.class);
        ReflectionTestUtils.setField(tool, "commandExecutor", commands);
        assertThatThrownBy(() -> tool.doExecute("LOCAL_CANVAS", Map.of(), Map.of("factoryId", "LOCAL_CANVAS")))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(401));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest @MethodSource("writers")
    void missingMandatoryBoundWriterHasNoLegacyFallback(AbstractBoundCanvasConfigTool tool) {
        assertThatThrownBy(() -> tool.doExecute("LOCAL_CANVAS", Map.of(), Map.of("userId", 7L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bound writer unavailable");
    }
}
