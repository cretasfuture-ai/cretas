package com.cretas.aims.service.production;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LSM-COSTBI 第 11 项: 「无法比较」只说「见各项原因」, 用户得逐行翻。现在给出主因 (出现最多的原因, 涉及几项),
 * 调料的「算不出主料标准投入」是主料派生的, 计到主料的根因上。
 */
class PlanCostVariancePrimaryCauseContractTest {

    private static PlanCostVarianceDTO.Line line(String status, String... reasons) {
        PlanCostVarianceDTO.Line l = new PlanCostVarianceDTO.Line();
        l.setStatus(status);
        l.setReasons(new ArrayList<>(List.of(reasons)));
        return l;
    }

    @Test
    void derivedSeasoningReasons_countTowardsThePrimaryMaterialRootCause() {
        List<PlanCostVarianceDTO.Line> lines = List.of(
                line(PlanCostVarianceDTO.STATUS_INCOMPARABLE, "锁定的配方没有出成率"),
                line(PlanCostVarianceDTO.STATUS_INCOMPARABLE, PlanCostVarianceCalculator.DERIVED_FROM_PRIMARY_REASON),
                line(PlanCostVarianceDTO.STATUS_INCOMPARABLE, PlanCostVarianceCalculator.DERIVED_FROM_PRIMARY_REASON),
                line(PlanCostVarianceDTO.STATUS_PARTIAL, "快照里这项没有单价"),
                line(PlanCostVarianceDTO.STATUS_PARTIAL, "快照里这项没有单价"));
        // 根因 1 + 派生 2 = 3 > 缺单价 2: 主因是出成率, 不是表面上出现次数多的那条
        assertThat(PlanCostVarianceService.primaryIncomparableCause(lines))
                .isEqualTo("锁定的配方没有出成率 (涉及 3 项)");
    }

    @Test
    void comparableLinesAreIgnored_andDuplicateReasonsOnOneLineCountOnce() {
        List<PlanCostVarianceDTO.Line> lines = List.of(
                line(PlanCostVarianceDTO.STATUS_COMPARABLE, "锁定的配方没有出成率", "锁定的配方没有出成率"),
                line(PlanCostVarianceDTO.STATUS_PARTIAL, "快照里这项没有单价", "快照里这项没有单价"));
        assertThat(PlanCostVarianceService.primaryIncomparableCause(lines))
                .isEqualTo("快照里这项没有单价 (涉及 1 项)");
    }

    @Test
    void derivedWithoutRoot_isStillNamed_notDropped() {
        List<PlanCostVarianceDTO.Line> lines = List.of(
                line(PlanCostVarianceDTO.STATUS_INCOMPARABLE, PlanCostVarianceCalculator.DERIVED_FROM_PRIMARY_REASON));
        assertThat(PlanCostVarianceService.primaryIncomparableCause(lines))
                .isEqualTo(PlanCostVarianceCalculator.DERIVED_FROM_PRIMARY_REASON + " (涉及 1 项)");
    }

    @Test
    void noReasons_returnsNull_soCallerKeepsThePointerToEachLine() {
        assertThat(PlanCostVarianceService.primaryIncomparableCause(List.of())).isNull();
        assertThat(PlanCostVarianceService.primaryIncomparableCause(
                List.of(line(PlanCostVarianceDTO.STATUS_INCOMPARABLE)))).isNull();
    }
}
