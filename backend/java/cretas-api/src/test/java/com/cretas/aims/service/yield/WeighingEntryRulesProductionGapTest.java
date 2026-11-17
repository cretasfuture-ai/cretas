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

class WeighingEntryRulesProductionGapTest {

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

    @Test
    void defectBreakdownNeedsTotalIdsPositiveKgNoDuplicatesAndCap() {
        // 没填总量就分摊
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(null, List.of(share("D1", "1"))))
                .isInstanceOf(BusinessException.class);
        // 缺不良项 / kg<=0 / 重复 / 超总量
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(new BigDecimal("5"), List.of(share(" ", "1"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(new BigDecimal("5"), List.of(share("D1", "0"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(new BigDecimal("5"),
                List.of(share("D1", "1"), share("D1", "1")))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeighingEntryRules.assertDefectBreakdown(new BigDecimal("5"),
                List.of(share("D1", "3"), share("D2", "2.01")))).isInstanceOf(BusinessException.class);
        // 刚好等于总量 OK; 空/null 不分摊 OK
        assertThatCode(() -> WeighingEntryRules.assertDefectBreakdown(new BigDecimal("5"),
                List.of(share("D1", "3"), share("D2", "2")))).doesNotThrowAnyException();
        assertThatCode(() -> WeighingEntryRules.assertDefectBreakdown(null, null)).doesNotThrowAnyException();
    }

    @Test
    void outputExceedsInputHintOnlyWhenComparableAndOver() {
        Map<String, Object> hint = WeighingEntryRules.outputExceedsInputHint(
                new BigDecimal("100"), "kg", new BigDecimal("101"), "KG", null, null, null);
        assertThat(hint).containsEntry("outputExceedsInput", true);
        // 单位不同: 不可比 → null, 不是「超了」
        assertThat(WeighingEntryRules.outputExceedsInputHint(
                new BigDecimal("100"), "kg", new BigDecimal("500"), "个", null, null, null)).isNull();
        // 恰好相等 / 投入为 0: null
        assertThat(WeighingEntryRules.outputExceedsInputHint(
                new BigDecimal("100"), "kg", new BigDecimal("100"), "kg", null, null, null)).isNull();
        assertThat(WeighingEntryRules.outputExceedsInputHint(
                BigDecimal.ZERO, "kg", new BigDecimal("1"), "kg", null, null, null)).isNull();
        // 之前已报的量计入累计
        assertThat(WeighingEntryRules.outputExceedsInputHint(
                new BigDecimal("100"), "kg", new BigDecimal("60"), "kg", null, null, new BigDecimal("41"))).isNotNull();
    }
}
