package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskAssignTool;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskCompleteTool;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskSpawnTool;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskStartTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.slot.RequiredSlot;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 证据 6 (prod F006 2026-10-06): 「做完了，产量0」缺任务编号时填槽追问是「请提供工序任务ID (Long)」。
 * 标签取自 Tool schema 的 description —— 修的是四个工序任务工具的 description 本身 (措辞源头)。
 * 用真实工具的 schema 走 {@code startSlotFilling}, 断言发给操作员的整段文案里没有类型名 / ID。
 */
class SlotFillingWorkProcessTaskWordingTest {

    @ParameterizedTest
    @ValueSource(strings = {"start", "complete", "assign", "spawn"})
    void workProcessTaskSlotQuestionsArePlainChinese(String which) {
        WorkProcessTaskService wps = mock(WorkProcessTaskService.class);
        ToolExecutor tool = switch (which) {
            case "start" -> new WorkProcessTaskStartTool(wps);
            case "complete" -> new WorkProcessTaskCompleteTool(wps);
            case "assign" -> new WorkProcessTaskAssignTool(wps);
            default -> new WorkProcessTaskSpawnTool(wps);
        };
        IntentSlotConfiguration slotConfiguration = mock(IntentSlotConfiguration.class);
        when(slotConfiguration.getMandatorySlots(anyString())).thenReturn(Collections.emptyList());
        when(slotConfiguration.getRequiredSlots(anyString())).thenReturn(Collections.emptyList());
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.startParameterCollection(any(), any(), any(), any(), any(), any())).thenReturn(null);
        SlotFillingServiceImpl service = new SlotFillingServiceImpl(
                slotConfiguration, registry, conversations, mock(ParameterExtractionLearningService.class));
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("WP_" + which.toUpperCase())
                .intentName("工序任务").toolName(tool.getToolName()).build();

        List<RequiredSlot> missing = service.getRequiredSlots(intent);
        IntentExecuteResponse r = service.startSlotFilling("F006", 1L, intent, missing, Map.of());
        String text = r.getFormattedText() != null ? r.getFormattedText() : r.getMessage();

        assertThat(missing).as("真的问到了必填参数 (不是空问句)").isNotEmpty();
        assertThat(text).contains("请");
        assertThat(text).doesNotContain("Long").doesNotContain("(").doesNotContain("工金");
        assertThat(text.toUpperCase()).doesNotContain("ID");
    }
}
