package com.cretas.aims.service.smartbi;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyAiAnalysisGuardContractTest {
    private static Map<String, Object> data(Object... facts) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < facts.length; i += 2) {
            values.put((String) facts[i], facts[i + 1]);
        }
        return Map.of("analysisFacts", values);
    }

    @Test
    void rejectsPieceClaimWhenSourceUnitIsKg() {
        var result = LegacyAiAnalysisGuard.validate(
                data("unitValues", List.of("kg"), "unitComplete", true,
                        "hasExplicitWasteCost", false, "hasBenchmark", false),
                "总产出 200件。");

        assertThat(result.usable()).isFalse();
        assertThat(result.text()).contains("分析不可用").doesNotContain("200件");
    }

    @Test
    void rejectsLossAmountAndUnsourcedTargetButKeepsValidText() {
        var facts = data("unitValues", List.of("kg"), "unitComplete", true,
                "hasExplicitWasteCost", false, "hasBenchmark", false);
        for (String text : List.of("不良 8kg，占比4%。隐性损耗约100元。",
                "不良 8kg，损耗100元。", "损耗金额￥100。")) {
            assertThat(LegacyAiAnalysisGuard.validate(facts, text).usable()).isFalse();
        }
        assertThat(LegacyAiAnalysisGuard.validate(facts, "材料成本占已记录成本64%，需结合预算核查合理性。")
                .usable()).isTrue();
        for (String text : List.of("成本结构失衡，目标降到50%。",
                "建议材料成本低于40%。", "建议控制在三成。")) {
            assertThat(LegacyAiAnalysisGuard.validate(facts, text).usable()).isFalse();
        }
        assertThat(LegacyAiAnalysisGuard.validate(facts, "材料成本64%偏高，应调整至50%。").usable()).isFalse();
        assertThat(LegacyAiAnalysisGuard.validate(facts,
                "material cost is too high; bring it down to 50%.").usable()).isFalse();
    }

    @Test
    void incompleteUnitCoverageDoesNotPermitPieceGuess() {
        var result = LegacyAiAnalysisGuard.validate(
                data("unitValues", List.of("kg"), "unitComplete", false,
                        "hasExplicitWasteCost", false, "hasBenchmark", false),
                "产出 200件。");
        assertThat(result.usable()).isFalse();
    }

    @Test
    void missingUnitFactsAndMixedUnitsFailClosed() {
        assertThat(LegacyAiAnalysisGuard.validate(
                data("hasExplicitWasteCost", false, "hasBenchmark", false),
                "产出 200盒。").usable()).isFalse();
        assertThat(LegacyAiAnalysisGuard.validate(
                data("unitValues", List.of("kg", "盒"), "unitComplete", true,
                        "hasExplicitWasteCost", false, "hasBenchmark", false),
                "总产出合计 300。").usable()).isFalse();
        assertThat(LegacyAiAnalysisGuard.validate(
                data("unitValues", List.of("kg"), "unitComplete", false,
                        "explicitWasteAmounts", List.of(), "benchmarkValues", List.of()),
                "产出10kg，另有20件。").usable()).isFalse();
    }

    @Test
    void lossAmountRequiresExactAuthorizedValueAndLossContext() {
        var noAmount = data("unitValues", List.of("kg"), "unitComplete", true,
                "explicitWasteAmounts", List.of(), "benchmarkValues", List.of());
        assertThat(LegacyAiAnalysisGuard.validate(noAmount, "营业收入￥1000，产出200kg。").usable()).isTrue();
        assertThat(LegacyAiAnalysisGuard.validate(noAmount, "损耗8kg，营业收入￥1000。").usable()).isTrue();
        assertThat(LegacyAiAnalysisGuard.validate(noAmount, "隐性损耗约100元。").usable()).isFalse();
        var authorized = data("unitValues", List.of("kg"), "unitComplete", true,
                "explicitWasteAmounts", List.of(100.0), "explicitWasteCurrency", "CNY", "benchmarkValues", List.of());
        assertThat(LegacyAiAnalysisGuard.validate(authorized, "显式损耗金额100元，隐性损耗约100元。").usable()).isTrue();
        assertThat(LegacyAiAnalysisGuard.validate(authorized, "隐性损耗约999元。").usable()).isFalse();
        assertThat(LegacyAiAnalysisGuard.validate(authorized, "营业收入100元。隐性损耗999元。").usable()).isFalse();
        assertThat(LegacyAiAnalysisGuard.validate(authorized, "产出100kg，隐性损耗999元。").usable()).isFalse();
        assertThat(LegacyAiAnalysisGuard.validate(authorized, "loss cost CNY 1,000; waste cost $999.").usable()).isFalse();
        var twoAmounts = data("unitValues", List.of("kg"), "unitComplete", true,
                "explicitWasteAmounts", List.of(100.0, 999.0), "explicitWasteCurrency", "CNY", "benchmarkValues", List.of());
        assertThat(LegacyAiAnalysisGuard.validate(twoAmounts,
                "损耗金额100元，另有隐性损耗888元。").usable()).isFalse();
    }

    @Test
    void eachQuantityClaimRequiresItsOwnSourceUnit() {
        var facts = data("unitValues", List.of("kg"), "unitComplete", true);
        for (String text : List.of("产出10kg，总产出200。", "产出10kg，另有20件。",
                "总产出200。", "不良8。", "output 200 pieces.", "产出200 units.")) {
            assertThat(LegacyAiAnalysisGuard.validate(facts, text).usable()).as(text).isFalse();
        }
        assertThat(LegacyAiAnalysisGuard.validate(facts, "产出10kg，总产出200kg。").usable()).isTrue();
    }

    @Test
    void currencyAndExactAmountMustBothMatchExplicitSource() {
        var cny = data("unitValues", List.of("kg"), "unitComplete", true,
                "explicitWasteAmounts", List.of(100), "explicitWasteCurrency", "CNY");
        for (String text : List.of("损耗金额100美元。", "waste cost $100.", "损耗金额-100元。",
                "损耗金额100.00001元。", "营业收入100元。隐性损耗999元。",
                "loss cost CNY 1,000; waste cost $999.", "损耗金额USD100元。",
                "损耗金额100。", "loss cost 100.", "损耗金额100元、999元。")) {
            assertThat(LegacyAiAnalysisGuard.validate(cny, text).usable()).as(text).isFalse();
        }
        assertThat(LegacyAiAnalysisGuard.validate(cny, "损耗金额100元。").usable()).isTrue();
        var usd = data("explicitWasteAmounts", List.of(1000), "explicitWasteCurrency", "USD");
        assertThat(LegacyAiAnalysisGuard.validate(usd, "waste cost $1,000.").usable()).isTrue();
        assertThat(LegacyAiAnalysisGuard.validate(
                data("explicitWasteAmounts", List.of(100)), "损耗金额100元。").usable()).isFalse();
    }

    @Test
    void acceptsOriginalLegacyUnitsWithoutGuessingConversions() {
        for (String unit : List.of("g", "box", "case", "kg", "盒", "袋", "箱")) {
            assertThat(LegacyAiAnalysisGuard.validate(
                    data("unitValues", List.of(unit), "unitComplete", true),
                    "产出10" + unit + "。").usable()).as(unit).isTrue();
        }
        assertThat(LegacyAiAnalysisGuard.validate(
                data("unitValues", List.of("g"), "unitComplete", true),
                "产出1kg。").usable()).isFalse();
    }

    @Test
    void keepsGroundedResponseWhenNoForbiddenClaimIsPresent() {
        var result = LegacyAiAnalysisGuard.validate(
                data("unitValues", List.of("kg"), "unitComplete", true,
                        "hasExplicitWasteCost", false, "hasBenchmark", false),
                "产出 200 kg，良品 192 kg，不良 8 kg；材料成本占已记录成本64%，合理性需结合预算核查。");
        assertThat(result.usable()).isTrue();
    }
}
