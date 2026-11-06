package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanvasConfigWriteGovernanceContractTest {
    @Test
    void allCanvasConfigurationWritersDeclareTheSameFailClosedGovernance() {
        List<ToolExecutor> tools = List.of(
                new CanvasToggleModuleTool(), new CanvasToggleToolTool(), new CanvasToggleSkillTool(),
                new CanvasUpdateFieldTool(), new CanvasUpdateTriggerChainTool(), new CanvasApplyTemplateTool());

        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.getToolName()).startsWith("canvas_");
            assertThat(tool.getActionType()).isEqualTo(ToolExecutor.ActionType.UPDATE);
            assertThat(tool.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.LOW);
            assertThat(tool.getAccessMode()).isEqualTo(ToolExecutor.AccessMode.WRITE);
            assertThat(tool.requiresPermission()).isTrue();
            assertThat(tool.hasPermission("factory_super_admin")).isTrue();
            assertThat(tool.hasPermission("permission_admin")).isTrue();
            assertThat(tool.hasPermission("operator")).isFalse();
            assertThat(tool.supportsPreview()).isFalse();
            assertThat(tool.getVersion()).isEqualTo("1.0.0");
            assertThat(tool.getDomainTags()).containsExactly("canvas");
        });
    }
}
