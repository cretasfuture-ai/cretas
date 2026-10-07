package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FactoryFollowUpServiceTest {
    private final AIIntentService intents = mock(AIIntentService.class);
    private final ToolRegistry registry = new ToolRegistry();
    private final AiFrontlineIntentGrantRepository grants = mock(AiFrontlineIntentGrantRepository.class);
    private final FrontlineAiWriteGrantService frontline = new FrontlineAiWriteGrantService(
            grants, new WriteGuardService(), mock(IntentConfigManagementService.class), registry);
    private FactoryFollowUpService service;

    @BeforeEach
    void setUp() {
        toolMap().clear();
        service = new FactoryFollowUpService(intents, frontline, registry);
    }

    @Test
    void completedProcessWriteOffersOnlyConfiguredReadableFollowUp() {
        ToolExecutor writeTool = tool("work_process_task_complete");
        ToolExecutor readTool = tool("production_status_query");
        register(writeTool, readTool);
        AIIntentConfig completed = intent("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete", "DATA_OP");
        AIIntentConfig read = intent("PRODUCTION_STATUS_QUERY", "production_status_query", "ANALYSIS");
        when(intents.getIntentByCode("F001", "PRODUCTION_STATUS_QUERY")).thenReturn(Optional.of(read));
        when(intents.hasPermission("F001", "PRODUCTION_STATUS_QUERY", "operator")).thenReturn(true);

        var followUps = service.suggest("F001", "operator", completed, "COMPLETED");

        assertThat(followUps).singleElement().satisfies(f -> {
            assertThat(f.getLabel()).isEqualTo("查询生产进度");
            assertThat(f.getQuestion()).isEqualTo("查询当前生产进度");
            assertThat(f.getIntentCode()).isEqualTo("PRODUCTION_STATUS_QUERY");
        });
    }

    @Test
    void unavailableOrUnauthorizedReadProducesEmptyFollowUps() {
        register(tool("work_process_task_complete"));
        AIIntentConfig completed = intent("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete", "DATA_OP");
        when(intents.getIntentByCode("F001", "PRODUCTION_STATUS_QUERY")).thenReturn(Optional.empty());

        assertThat(service.suggest("F001", "operator", completed, "SUCCESS")).isEmpty();
        verify(intents, never()).hasPermission(anyString(), eq("PRODUCTION_STATUS_QUERY"), anyString());
    }

    @Test
    void writeResultNeverGetsReadFollowUpWhenStatusIsNotCompleted() {
        register(tool("work_process_task_complete"), tool("production_status_query"));
        AIIntentConfig completed = intent("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete", "DATA_OP");
        assertThat(service.suggest("F001", "operator", completed, "WRITE_CONFIRM_REQUIRED")).isEmpty();
        verifyNoInteractions(intents);
    }

    @Test
    void frontlineAudienceRemovesWarehouseOnlyReadFromOperator() {
        register(tool("material_batch_use"), tool("material_batch_query"), tool("receiving_query"));
        AIIntentConfig completed = intent("MATERIAL_BATCH_USE", "material_batch_use", "DATA_OP");
        AIIntentConfig inventory = intent("MATERIAL_BATCH_QUERY", "material_batch_query", "ANALYSIS");
        AIIntentConfig receiving = intent("MATERIAL_TODAY_RECEIVING_QUERY", "receiving_query", "ANALYSIS");
        when(intents.getIntentByCode("F001", "MATERIAL_BATCH_QUERY")).thenReturn(Optional.of(inventory));
        when(intents.getIntentByCode("F001", "MATERIAL_TODAY_RECEIVING_QUERY")).thenReturn(Optional.of(receiving));
        when(intents.hasPermission("F001", "MATERIAL_BATCH_QUERY", "operator")).thenReturn(true);
        when(intents.hasPermission("F001", "MATERIAL_TODAY_RECEIVING_QUERY", "operator")).thenReturn(true);

        assertThat(service.suggest("F001", "operator", completed, "COMPLETED"))
                .extracting(IntentExecuteResponse.FollowUpSuggestion::getIntentCode)
                .containsExactly("MATERIAL_BATCH_QUERY");
    }

    private void register(ToolExecutor... tools) {
        Map<String, ToolExecutor> map = toolMap();
        for (ToolExecutor tool : tools) map.put(tool.getToolName(), tool);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ToolExecutor> toolMap() {
        return (Map<String, ToolExecutor>) ReflectionTestUtils.getField(registry, "toolMap");
    }

    private static AIIntentConfig intent(String code, String tool, String category) {
        return AIIntentConfig.builder().intentCode(code).intentName(code).toolName(tool)
                .intentCategory(category).sensitivityLevel("LOW").isActive(true).build();
    }

    private static ToolExecutor tool(String name) {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn(name);
        when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(tool.getAccessMode()).thenReturn(
                name.endsWith("_query") || name.equals("receiving_query")
                        ? ToolExecutor.AccessMode.READ : ToolExecutor.AccessMode.WRITE);
        return tool;
    }
}
