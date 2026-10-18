package com.cretas.aims.service.indicator;

import com.cretas.aims.blueprint.interview.FactoryBuildApplyService;
import com.cretas.aims.blueprint.interview.FactoryBuildInterviewService;
import com.cretas.aims.blueprint.interview.FactoryBuildPlanService;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitResult;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.FactoryAssemblyConversationService.RequiredParameter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactoryIndicatorOptOutContractTest {
    @Test void everyRealInterviewChoicePreviewsAndDisablesItsExactTenantCode() {
        Object parameters = ReflectionTestUtils.getField(FactoryBuildInterviewService.class, "FACTORY_BUILD_PARAMS");
        assertThat(parameters).isInstanceOf(List.class);
        RequiredParameter question = ((List<?>) parameters).stream()
                .filter(RequiredParameter.class::isInstance).map(RequiredParameter.class::cast)
                .filter(item -> item.getName().equals("indicatorOptOut")).findFirst().orElseThrow();
        assertThat(question.getOptions()).containsExactly("实际出成率", "生产良品率（按净重）",
                "完整单位成本（每公斤合格品）", "完整生产成本", "都要看");
        List<String> codes = List.of("FACTORY_LU_YIELD_RATE", "FACTORY_GOOD_OUTPUT_RATE",
                "FACTORY_LU_UNIT_COST", "FACTORY_ACTUAL_TOTAL_COST");
        for (int i = 0; i < codes.size(); i++) {
            String label = question.getOptions().get(i), code = codes.get(i);
            IndicatorRepository rows = mock(IndicatorRepository.class);
            Indicator indicator = new Indicator(); indicator.setName(label); indicator.setCode(code);
            indicator.setFactoryId("NEW");
            when(rows.findByFactoryIdAndCode("NEW", code)).thenReturn(Optional.of(indicator));
            FactoryBuildPlanService planner = new FactoryBuildPlanService();
            FactoryBuildApplyService apply = new FactoryBuildApplyService();
            ReflectionTestUtils.setField(planner, "indicatorRepository", rows);
            ReflectionTestUtils.setField(apply, "indicatorRepository", rows);
            ApplyAIInitRequest request = request(List.of(label));
            var preview = planner.planChanges("NEW", request, false);
            assertThat(preview).hasSize(1);
            assertThat(preview.get(0).getAction()).isEqualTo("停用");
            assertThat(preview.get(0).getKey()).isEqualTo(label);
            ApplyAIInitResult result = new ApplyAIInitResult();
            ReflectionTestUtils.invokeMethod(apply, "applyIndicatorOptOut", "NEW", request, result);
            assertThat(indicator.getIsActive()).isFalse();
            assertThat(result.getUnknownIndicatorCodes()).isEmpty();
            verify(rows, times(2)).findByFactoryIdAndCode("NEW", code);
            verify(rows).save(indicator);
        }
    }

    @Test void allMetricsSentinelDoesNothingAndUnknownLabelsAreNotGuessed() {
        assertThat(FactoryIndicatorProvisioner.optOutCode("都要看")).isNull();
        assertThat(FactoryIndicatorProvisioner.optOutCode("  FACTORY_ACTUAL_TOTAL_COST "))
                .isEqualTo("FACTORY_ACTUAL_TOTAL_COST");
        assertThat(FactoryIndicatorProvisioner.optOutCode("成本" )).isEqualTo("成本");
        IndicatorRepository rows = mock(IndicatorRepository.class);
        FactoryBuildPlanService planner = new FactoryBuildPlanService();
        FactoryBuildApplyService apply = new FactoryBuildApplyService();
        ReflectionTestUtils.setField(planner, "indicatorRepository", rows);
        ReflectionTestUtils.setField(apply, "indicatorRepository", rows);
        ApplyAIInitRequest request = request(List.of("都要看", " "));
        assertThat(planner.planChanges("NEW", request, false)).isEmpty();
        ReflectionTestUtils.invokeMethod(apply, "applyIndicatorOptOut", "NEW", request, new ApplyAIInitResult());
        verifyNoInteractions(rows);
    }

    private static ApplyAIInitRequest request(List<String> choices) {
        ApplyAIInitRequest request = new ApplyAIInitRequest();
        ApplyAIInitRequest.SuggestedData data = new ApplyAIInitRequest.SuggestedData();
        data.setIndicatorOptOut(choices); request.setSuggestedData(data); return request;
    }
}
