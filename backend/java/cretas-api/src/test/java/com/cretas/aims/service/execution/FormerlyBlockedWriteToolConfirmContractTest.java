package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.workdesk.OpportunityTransitionStageTool;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.SalesOpportunityService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审阅阻断 1: 改动已有记录、预览却给不出改前值的写工具, 确认时被一律拒绝, 重新预览还是一样 —— 用户永远确认不了。
 * 用真实工具 (商机阶段推进) 走「预览记快照 → 确认前重跑预览比对」: 没被改过必须放行 (不打转), 被改过必须拒绝。
 */
class FormerlyBlockedWriteToolConfirmContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolDispatchService dispatch = new ToolDispatchService(
            mock(ToolRegistry.class), mapper, mock(DashScopeClient.class),
            mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
            mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
            mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
    private final AIIntentConfig intent = AIIntentConfig.builder().intentCode("OPPORTUNITY_TRANSITION").build();

    @Test
    void opportunityStagePreviewRecordsPriorStateSoAnUnchangedRecordConfirms() throws Exception {
        SalesOpportunityService opportunities = mock(SalesOpportunityService.class);
        OpportunityTransitionStageTool tool = opportunityTool(opportunities);
        when(opportunities.get(anyString(), anyString())).thenReturn(opp(OpportunityStage.QUALIFIED));
        Map<String, Object> params = Map.of("id", "opp-1", "newStage", "DEMO");

        String recorded = recordedSnapshot(tool, params);
        assertThat(recorded).contains("商机阶段");

        var unchanged = dispatch.recheckPreviewState(tool, intent, recorded, params, "F006", 7L, "sales");
        assertThat(unchanged.proceed()).as("没人动过: 放行, 不能让用户原地打转").isTrue();

        when(opportunities.get(anyString(), anyString())).thenReturn(opp(OpportunityStage.LEAD));
        var changed = dispatch.recheckPreviewState(tool, intent, recorded, params, "F006", 7L, "sales");
        assertThat(changed.proceed()).as("预览之后阶段被改: 不按旧预览推进").isFalse();
        assertThat(changed.repreviewHelps()).isTrue();
    }

    private String recordedSnapshot(OpportunityTransitionStageTool tool, Map<String, Object> params) throws Exception {
        Map<String, Object> args = new HashMap<>(params);
        Map<String, Object> context = new HashMap<>(Map.of("factoryId", "F006", "userId", 7L));
        String json = tool.preview(ToolCall.of("p", tool.getToolName(), mapper.writeValueAsString(args)), context);
        Map<String, Object> preview = mapper.readValue(json, Map.class);
        return mapper.writeValueAsString(dispatch.priorStateSnapshot(tool, preview));
    }

    private OpportunityTransitionStageTool opportunityTool(SalesOpportunityService opportunities) {
        OpportunityTransitionStageTool tool = new OpportunityTransitionStageTool();
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(tool, "salesOpportunityService", opportunities);
        ReflectionTestUtils.setField(tool, "customerRepository", customers);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        return tool;
    }

    private static SalesOpportunity opp(OpportunityStage stage) {
        return SalesOpportunity.builder().id("opp-1").factoryId("F006").customerId("c1").title("新客户试单")
                .stage(stage).probability(stage.getDefaultProbability()).ownerId(99L).createdBy(1L).build();
    }
}
