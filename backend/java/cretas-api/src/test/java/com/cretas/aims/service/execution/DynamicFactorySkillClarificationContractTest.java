package com.cretas.aims.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.skill.SkillRouterService;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Catalog-owned Skill failures must remain structured at both dynamic entry points. */
class DynamicFactorySkillClarificationContractTest {

    private DynamicToolSelectionService service;
    private SkillRouterService skillRouter;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = mock(ToolRegistry.class);
        service = new DynamicToolSelectionService(
                mock(ToolRouterService.class), registry, new ObjectMapper());
        skillRouter = mock(SkillRouterService.class);
        ReflectionTestUtils.setField(service, "skillRouterService", skillRouter);
        ReflectionTestUtils.setField(service, "toolPermissionChain", mock(ToolPermissionChain.class));
        ReflectionTestUtils.setField(service, "clarificationCandidateService",
                mock(ClarificationCandidateService.class));
        IntentConfigManagementService config = mock(IntentConfigManagementService.class);
        when(config.getAllIntents(anyString())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "configService", config);
        when(skillRouter.isSkillsEnabled()).thenReturn(true);
        when(registry.getExecutor(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void keywordEntryPreservesCatalogNeedMoreInfoPayload() {
        SkillDefinition skill = SkillDefinition.builder()
                .name("daily-customer-followup")
                .triggers(List.of("客户跟进缺参"))
                .tools(List.of("customer_followup_query"))
                .build();
        when(skillRouter.findMatchingSkills(eq("客户跟进缺参"), eq("F001")))
                .thenReturn(List.of(skill));
        Map<String, Object> data = Map.of(
                "status", "NEED_MORE_INFO",
                "needMoreInfo", true,
                "clarificationQuestions", List.of("请提供日期范围"),
                "message", "缺少日期");
        when(skillRouter.executeSkill(eq("daily-customer-followup"), any()))
                .thenReturn(SkillResult.builder().success(false).skillName("daily-customer-followup")
                        .message("缺少日期").data(data).build());

        IntentExecuteResponse response = service.trySkillRoute(
                "客户跟进缺参", "F001", 7L, "factory_manager");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(response.getResultData()).isEqualTo(data);
        assertThat(response.getClarificationQuestions()).containsExactly("请提供日期范围");
    }

    @Test
    void explicitEntryPreservesCatalogFailedPayload() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("DAILY_CUSTOMER_FOLLOWUP")
                .intentName("客户跟进")
                .intentCategory("WORKDESK")
                .build();
        Map<String, Object> data = Map.of("status", "FAILED", "message", "Skill timeout");
        when(skillRouter.executeSkill(eq("daily-customer-followup"), any()))
                .thenReturn(SkillResult.builder().success(false).skillName("daily-customer-followup")
                        .message("Skill timeout").data(data).build());

        IntentExecuteResponse response = service.tryExplicitSkillRouteForIntent(
                intent, "今日跟进", "F001", 7L, "factory_manager");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getResultData()).isEqualTo(data);
        assertThat(response.getIntentCode()).isEqualTo("DAILY_CUSTOMER_FOLLOWUP");
    }

    @Test
    void keywordEntrySurfacesCatalogExecutionExceptionInsteadOfFallingThrough() {
        SkillDefinition skill = SkillDefinition.builder()
                .name("daily-customer-followup")
                .triggers(List.of("客户跟进异常"))
                .tools(List.of("customer_followup_query"))
                .build();
        when(skillRouter.findMatchingSkills(eq("客户跟进异常"), eq("F001")))
                .thenReturn(List.of(skill));
        when(skillRouter.executeSkill(eq("daily-customer-followup"), any()))
                .thenThrow(new IllegalStateException("router boom"));

        IntentExecuteResponse response = service.trySkillRoute(
                "客户跟进异常", "F001", 7L, "factory_manager");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("router boom");
    }
}
