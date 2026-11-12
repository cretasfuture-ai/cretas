package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 确认前状态核对的判定规则 ({@link ToolDispatchService#recheckPreviewState} 与铸凭据共用的
 * {@link ToolDispatchService#priorStateSnapshot})。真实入口的端到端场景在
 * {@code WriteConfirmationStateBindingPostgresContractTest}; 这里逐条钉住规则本身, 每条拒绝配一条放行。
 */
class ConfirmStateRecheckContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolDispatchService dispatch = new ToolDispatchService(
            mock(ToolRegistry.class), mapper, mock(DashScopeClient.class),
            mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
            mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
            mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
    private final AIIntentConfig intent = AIIntentConfig.builder().intentCode("X").intentName("X").build();

    @Test
    void explicitCurrentValuesEqualAfterJsonRoundTripPassIncludingNumberRepresentation() throws Exception {
        ToolExecutor tool = tool("{\"success\":true,\"data\":{\"canDo\":true,"
                + "\"currentValues\":{\"剩余数量\":12.50,\"状态\":\"可用\"}}}", false);

        var check = recheck(tool, "{\"剩余数量\":12.5,\"状态\":\"可用\"}");

        assertThat(check.proceed()).isTrue();
    }

    @Test
    void changedFieldIsNamedAndExecutionRefused() throws Exception {
        ToolExecutor tool = tool("{\"success\":true,\"data\":{\"canDo\":true,"
                + "\"currentValues\":{\"剩余数量\":9,\"状态\":\"可用\"}}}", false);

        var check = recheck(tool, "{\"剩余数量\":12.5,\"状态\":\"可用\"}");

        assertThat(check.proceed()).isFalse();
        assertThat(check.code()).isEqualTo(ToolDispatchService.ConfirmStateCheck.STATE_CHANGED);
        assertThat(check.changedFields()).containsExactly("剩余数量");
        assertThat(check.message()).contains("「剩余数量」").contains("重新预览");
    }

    @Test
    void statusTransitionToolsWithoutCurrentValuesAreComparedOnTheirCurrentPrefixedFields() throws Exception {
        String opened = "{\"success\":true,\"data\":{\"canDo\":true,\"currentStatus\":\"OPEN\",\"ruleId\":7}}";
        String acked = "{\"success\":true,\"data\":{\"canDo\":true,\"currentStatus\":\"ACKNOWLEDGED\",\"ruleId\":7}}";
        ToolExecutor tool = tool(opened, false);
        String recorded = mapper.writeValueAsString(
                dispatch.priorStateSnapshot(tool, mapper.readValue(opened, Map.class)));
        assertThat(recorded).isEqualTo("{\"currentStatus\":\"OPEN\"}");

        assertThat(recheck(tool, recorded).proceed()).as("没变: 放行").isTrue();

        ToolExecutor changed = tool(acked, false);
        var check = recheck(changed, recorded);
        assertThat(check.proceed()).isFalse();
        assertThat(check.changedFields()).containsExactly("currentStatus");
    }

    @Test
    void noComparablePriorValuesFailsClosedUnlessTheToolOnlyCreatesNewRecords() throws Exception {
        String preview = "{\"success\":true,\"data\":{\"canDo\":true,\"name\":\"新规则\",\"currentActiveProcedures\":3}}";

        var modifying = recheck(tool("{\"success\":true,\"data\":{\"canDo\":true,\"name\":\"x\"}}", false), "{}");
        assertThat(modifying.proceed()).isFalse();
        assertThat(modifying.code()).isEqualTo(ToolDispatchService.ConfirmStateCheck.NOT_COMPARABLE);
        // 无法核对的工具重新预览还是一样: 不许把用户送回同一张卡 (死循环), 只指去业务页面
        assertThat(modifying.repreviewHelps()).isFalse();
        assertThat(modifying.message()).doesNotContain("重新预览").contains("业务页面");

        ToolExecutor creator = tool(preview, true);
        assertThat(dispatch.priorStateSnapshot(creator, mapper.readValue(preview, Map.class)))
                .as("新建型不把旁证计数当改前状态").isEmpty();
        assertThat(recheck(creator, "{}").proceed()).isTrue();
    }

    @Test
    void previewThatNoLongerPassesRefusesEvenForCreateOnlyTools() throws Exception {
        ToolExecutor creator = tool("{\"success\":true,\"data\":{\"canDo\":false,\"message\":\"名称已被占用\"}}", true);

        var check = recheck(creator, "{}");

        assertThat(check.proceed()).isFalse();
        assertThat(check.code()).isEqualTo(ToolDispatchService.ConfirmStateCheck.PRECONDITION_CHANGED);
        assertThat(check.message()).contains("名称已被占用");
    }

    @Test
    void previewCrashAtConfirmTimeRefusesInsteadOfExecutingBlind() throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("x_update");
        when(tool.supportsPreview()).thenReturn(true);
        when(tool.preview(any(ToolCall.class), anyMap())).thenThrow(new IllegalStateException("db down"));

        var check = recheck(tool, "{\"状态\":\"可用\"}");

        assertThat(check.proceed()).isFalse();
        assertThat(check.code()).isEqualTo(ToolDispatchService.ConfirmStateCheck.REREAD_FAILED);
        assertThat(check.repreviewHelps()).as("一时读不到: 重新预览可以").isTrue();
    }

    @Test
    void mintedSubjectUsesTheRequiredIdParameterOrNewForCreators() throws Exception {
        ToolExecutor updater = tool("{}", false);
        when(updater.getParametersSchema()).thenReturn(Map.of("required", List.of("factoryId", "batchId", "quantity")));
        when(updater.getActionType()).thenReturn(ToolExecutor.ActionType.UPDATE);
        var subject = dispatch.previewSubject(updater, Map.of("factoryId", "F1", "batchId", "MB-9", "quantity", 1), Map.of());
        assertThat(subject).isEqualTo(new ToolDispatchService.PreviewSubject("x_update", "MB-9", "UPDATE"));

        ToolExecutor creator = tool("{}", true);
        when(creator.getParametersSchema()).thenReturn(Map.of("required", List.of("name")));
        when(creator.getActionType()).thenReturn(ToolExecutor.ActionType.WRITE);
        assertThat(dispatch.previewSubject(creator, Map.of("name", "n"), Map.of()).entityId())
                .isEqualTo(ToolDispatchService.NEW_RECORD_ENTITY_ID);
    }

    private ToolDispatchService.ConfirmStateCheck recheck(ToolExecutor tool, String recordedJson) {
        return dispatch.recheckPreviewState(tool, intent, recordedJson, Map.of("id", 1), "F001", 1L, "FACTORY_ADMIN");
    }

    private static ToolExecutor tool(String previewJson, boolean createsNewRecordOnly) throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("x_update");
        when(tool.supportsPreview()).thenReturn(true);
        when(tool.previewCreatesNewRecordOnly()).thenReturn(createsNewRecordOnly);
        when(tool.preview(any(ToolCall.class), anyMap())).thenReturn(previewJson);
        return tool;
    }
}
