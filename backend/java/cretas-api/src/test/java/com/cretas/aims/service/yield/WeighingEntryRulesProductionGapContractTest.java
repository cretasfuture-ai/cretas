package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeighingEntryRulesProductionGapContractTest {

    /** 旧字段 kg: 单位视为 kg。 */
    private static YieldReportRequest.DefectShare share(String id, String kg) {
        YieldReportRequest.DefectShare s = new YieldReportRequest.DefectShare();
        s.setDefectId(id);
        s.setKg(kg == null ? null : new BigDecimal(kg));
        return s;
    }

    private static YieldReportRequest.LaborSegment seg(String start, String end) {
        YieldReportRequest.LaborSegment s = new YieldReportRequest.LaborSegment();
        s.setStartTime(start);
        s.setEndTime(end);
        return s;
    }

    @Test
    void segmentWithSameStartAndEndIsRejectedButOvernightIsAllowed() {
        assertThatThrownBy(() -> WeighingEntryRules.assertSegmentsStartBeforeEnd(List.of(seg("08:00", "08:00"))))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> WeighingEntryRules.assertSegmentsStartBeforeEnd(
                List.of(seg("08:00", "12:00"), seg("22:00", "02:00")))).doesNotThrowAnyException();
        assertThatCode(() -> WeighingEntryRules.assertSegmentsStartBeforeEnd(null)).doesNotThrowAnyException();
    }

    private static YieldReportRequest.DefectShare share(String id, String qty, String unit) {
        YieldReportRequest.DefectShare s = new YieldReportRequest.DefectShare();
        s.setDefectId(id);
        s.setQuantity(qty == null ? null : new BigDecimal(qty));
        s.setUnit(unit);
        return s;
    }

    private static WeighingEntryRules.Qty q(String qty, String unit) {
        return new WeighingEntryRules.Qty(qty == null ? null : new BigDecimal(qty), unit);
    }

    @Test
    void defectBreakdownNeedsTotalIdsPositiveQuantityNoDuplicatesAndCap() {
        BigDecimal five = new BigDecimal("5");
        // 没填总量就分摊
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(null, "kg", List.of(share("D1", "1"))))
                .isInstanceOf(BusinessException.class);
        // 缺不良项 / 数量<=0 / 重复 / 同单位超总量
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(five, "kg", List.of(share(" ", "1"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(five, "kg", List.of(share("D1", "0"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(five, "kg",
                List.of(share("D1", "1"), share("D1", "1")))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(five, "kg",
                List.of(share("D1", "3"), share("D2", "2.01")))).isInstanceOf(BusinessException.class);
        // 刚好等于总量: 已比; 空/null 不分摊: null
        assertThat(WeighingEntryRules.assertDefectBreakdown(five, "KG", List.of(share("D1", "3"), share("D2", "2"))))
                .isEqualTo(WeighingEntryRules.CHECK_COMPARED);
        assertThat(WeighingEntryRules.assertDefectBreakdown(null, null, null)).isNull();
    }

    @Test
    void defectBreakdownComparesOnlyWhenEveryLineHasTheTotalsUnit() {
        // 复审 M4 失败场景 1: 产出单位是盒, 不良 2 盒, 分摊 3kg —— 旧实现拒绝 (3>2), kg 和盒根本比不了 ⇒ 不比, 标出
        assertThat(WeighingEntryRules.assertDefectBreakdown(new BigDecimal("2"), "盒", List.of(share("D1", "3"))))
                .isEqualTo(WeighingEntryRules.CHECK_UNIT_MISMATCH);
        // 失败场景 2: 不良 5 盒, 分摊 3kg+2kg 旧实现「通过」(看起来已核对) —— 现在同样标 UNIT_MISMATCH, 不是 COMPARED
        assertThat(WeighingEntryRules.assertDefectBreakdown(new BigDecimal("5"), "盒",
                List.of(share("D1", "3"), share("D2", "2")))).isEqualTo(WeighingEntryRules.CHECK_UNIT_MISMATCH);
        // 一行缺单位 ⇒ 也不比
        assertThat(WeighingEntryRules.assertDefectBreakdown(new BigDecimal("5"), "盒",
                List.of(share("D1", "1", "盒"), share("D2", "9", null)))).isEqualTo(WeighingEntryRules.CHECK_UNIT_MISMATCH);
        // 按盒分摊、单位一致: 照样比, 超了拒绝
        assertThat(WeighingEntryRules.assertDefectBreakdown(new BigDecimal("5"), "盒",
                List.of(share("D1", "3", "盒"), share("D2", "2", "盒")))).isEqualTo(WeighingEntryRules.CHECK_COMPARED);
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(new BigDecimal("2"), "盒",
                List.of(share("D1", "3", "盒")))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过了不良总量");
    }

    @Test
    void outputExceedsInputHintOnlyWhenComparableAndOver() {
        Map<String, Object> hint = WeighingEntryRules.outputExceedsInputHint(
                List.of(q("100", "kg")), List.of(q("101", "KG")));
        assertThat(hint).containsEntry("outputExceedsInput", true)
                .containsEntry("outputInputCheck", WeighingEntryRules.CHECK_COMPARED);
        assertThat((BigDecimal) hint.get("producedTotal")).isEqualByComparingTo("101");
        // 恰好相等 / 没有投入 / 投入为 0: null
        assertThat(WeighingEntryRules.outputExceedsInputHint(List.of(q("100", "kg")), List.of(q("100", "kg")))).isNull();
        assertThat(WeighingEntryRules.outputExceedsInputHint(List.of(), List.of(q("1", "kg")))).isNull();
        assertThat(WeighingEntryRules.outputExceedsInputHint(List.of(q("0", "kg")), List.of(q("1", "kg")))).isNull();
    }

    @Test
    void outputExceedsInputCountsEveryByproductOfEveryReportInTheSameUnit() {
        // 复审 M6: 此前那笔的副产品 (料头 6kg) 旧实现不计 ⇒ 累计 40+5+50 = 95 ≤ 100 漏提示; 全部计入 = 101 > 100
        Map<String, Object> hint = WeighingEntryRules.outputExceedsInputHint(
                List.of(q("60", "kg"), q("40", "kg")),
                List.of(q("40", "kg"), q("5", "kg"), q("6", "kg"),     // 此前一笔: 良品 / 不良 / 副产品
                        q("50", "kg"), q(null, "kg")));                // 本笔: 良品 / 没清点不良
        assertThat(hint).containsEntry("outputExceedsInput", true);
        assertThat((BigDecimal) hint.get("producedTotal")).isEqualByComparingTo("101");
        assertThat((BigDecimal) hint.get("inputQuantity")).isEqualByComparingTo("100");
    }

    @Test
    void anyUnitMismatchMeansNoHintButAnExplicitFlag() {
        // 副产品单位不同 (个): 不提示, 标 UNIT_MISMATCH —— 即使同单位部分已经超了
        Map<String, Object> byproductUnit = WeighingEntryRules.outputExceedsInputHint(
                List.of(q("100", "kg")), List.of(q("120", "kg"), q("3", "个")));
        assertThat(byproductUnit).containsEntry("outputInputCheck", WeighingEntryRules.CHECK_UNIT_MISMATCH)
                .doesNotContainKey("outputExceedsInput");
        // 投入混了两个单位: 旧实现把 kg 与 箱 直接相加、只取第一条单位 ⇒ 现在不可比
        Map<String, Object> mixedInput = WeighingEntryRules.outputExceedsInputHint(
                List.of(q("100", "kg"), q("2", "箱")), List.of(q("50", "kg")));
        assertThat(mixedInput).containsEntry("outputInputCheck", WeighingEntryRules.CHECK_UNIT_MISMATCH)
                .doesNotContainKey("outputExceedsInput");
        // 产出单位与投入不同
        assertThat(WeighingEntryRules.outputExceedsInputHint(List.of(q("100", "kg")), List.of(q("500", "个"))))
                .containsEntry("outputInputCheck", WeighingEntryRules.CHECK_UNIT_MISMATCH);
    }
}
