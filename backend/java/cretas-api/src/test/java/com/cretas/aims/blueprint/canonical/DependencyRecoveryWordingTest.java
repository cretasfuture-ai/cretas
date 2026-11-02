package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.interview.FactoryInterviewExpansion;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyRecoveryWordingTest {

    private static CapabilityView blocked(String... missing) {
        return CapabilityView.builder().id("hazard-analysis").label("危害分析")
                .missingDependencies(List.of(missing)).build();
    }

    @Test
    void missingBomPointsBackToTheYieldQuestionByName() {
        String text = CanonicalFactoryBlueprintService.dependencyRecovery(blocked("bom"), "补齐依赖后重新保存草稿并验证");
        assertThat(text).contains("「出成率」").contains("专业复核 → 01 → 原料到成品转换").contains("数字").contains("危害分析").endsWith("补齐依赖后重新保存草稿并验证");
    }

    @Test
    void otherDependenciesKeepTheGenericRecovery() {
        assertThat(CanonicalFactoryBlueprintService.dependencyRecovery(blocked("routes"), "base")).isEqualTo("base");
        assertThat(CanonicalFactoryBlueprintService.dependencyRecovery(
                CapabilityView.builder().id("x").label("x").build(), "base")).isEqualTo("base");
    }

    @Test
    void yieldQuestionTellsWhyANumberIsNeeded() {
        @SuppressWarnings("unchecked")
        List<FactoryAssemblyConversationSession.RequiredParameter> items =
                (List<FactoryAssemblyConversationSession.RequiredParameter>)
                        ReflectionTestUtils.getField(FactoryInterviewExpansion.class, "STEP_SUB_ITEMS");
        String hint = items.stream().filter(i -> "yieldRate".equals(i.getName()))
                .findFirst().orElseThrow().getValidationHint();
        assertThat(hint).contains("数字").contains("配方/BOM").contains("危害分析").contains("关键控制点");
    }
}
