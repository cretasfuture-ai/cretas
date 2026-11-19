package com.cretas.aims.ai.tool.impl.helpercontrol;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;

import java.util.Map;

/**
 * Positive control for GatedDataToolAiPathPermissionTest: a READ tool with no gate whose own code touches no
 * data owner, and reads the balance sheet only through {@link HelperReadingBalanceSheet}. Lives in test-classes,
 * so the production scan never registers it; the gate test feeds it to the classifier by hand and expects red.
 */
public class UngatedHelperDelegatingTool implements ToolExecutor {

    private HelperReadingBalanceSheet helper;

    @Override public String getToolName() { return "helper_control_ungated"; }
    @Override public String getDescription() { return "positive control"; }
    @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
    @Override public AccessMode getAccessMode() { return AccessMode.READ; }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) {
        return String.valueOf(helper.read("F006", 2026, 11));
    }
}
