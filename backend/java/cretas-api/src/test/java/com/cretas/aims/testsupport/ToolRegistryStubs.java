package com.cretas.aims.testsupport;

import com.cretas.aims.ai.tool.ToolRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具注册表桩。裸 {@code mock(ToolRegistry.class)} 的 {@code isToolEnabledForFactory} 回 false = 「本厂关了这个工具」;
 * 执行出口与预览出口 (PR-3b 终审 M1 起) 都按开关拒 TOOL_DISABLED。用例要测的不是开关时, 用这个: 本厂工具都开着。
 */
public final class ToolRegistryStubs {

    private ToolRegistryStubs() {
    }

    public static ToolRegistry allToolsEnabled() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(any(), any())).thenReturn(true);
        return registry;
    }
}
