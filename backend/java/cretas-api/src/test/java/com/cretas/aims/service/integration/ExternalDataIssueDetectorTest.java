package com.cretas.aims.service.integration;

import com.cretas.aims.service.integration.ExternalDataIssueDetector.Issue;
import com.cretas.aims.service.integration.ExternalDataIssueDetector.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 黑湖报工数据需修正检测。样本形态取自 2026-11-18 23:09 快照 (11-05~11-18, 531 行) 实测的几条。 */
class ExternalDataIssueDetectorTest {

    static Row row(String date, String product, String process, String qty, String unit) {
        return new Row(LocalDate.parse(date), product, process, qty == null ? null : new BigDecimal(qty), unit);
    }

    static List<Issue> of(List<Issue> all, String type) {
        return all.stream().filter(i -> i.type().equals(type)).toList();
    }

    @Test
    void compoundUnitIsFlaggedWithTheMainUnitOfThatProcess() {
        List<Row> rows = new ArrayList<>();
        for (int d = 10; d <= 15; d++) rows.add(row("2026-11-" + d, "香嫩牛肉丝", "砍排修油", "500", "kg"));
        rows.add(row("2026-11-16", "香嫩牛肉丝", "砍排修油", "1771.73", "kg/盒"));
        List<Issue> out = ExternalDataIssueDetector.detect(rows);
        assertThat(out).hasSize(1);
        Issue i = out.get(0);
        assertThat(i.type()).isEqualTo("COMPOUND_UNIT");
        assertThat(i.reportDate()).isEqualTo(LocalDate.parse("2026-11-16"));
        assertThat(i.unit()).isEqualTo("kg/盒");
        assertThat(i.qty()).isEqualByComparingTo("1771.73");
        assertThat(i.suggestion()).contains("kg/盒").contains("「kg」");
        assertThat(i.owner()).contains("车间");
    }

    @Test
    void compoundUnitWithNoMainUnitAsksForASingleUnit() {
        List<Issue> out = ExternalDataIssueDetector.detect(List.of(row("2026-11-14", "酱鸭腿", "真空包装", "143", "kg/包")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo("COMPOUND_UNIT");
        assertThat(out.get(0).suggestion()).contains("单一单位").doesNotContain("平时用的单位");
    }

    @Test
    void minorityUnitDaysAreListedAgainstTheMajorityUnit() {
        List<Row> rows = new ArrayList<>();
        for (int d = 5; d <= 7; d++) rows.add(row("2026-11-0" + d, "嫩滑牛肉片", "滚揉", "300", "kg"));
        rows.add(row("2026-11-08", "嫩滑牛肉片", "滚揉", "20", "盒"));
        rows.add(row("2026-11-09", "嫩滑牛肉片", "滚揉", "20", "盒"));
        rows.add(row("2026-11-10", "嫩滑牛肉片", "滚揉", "20", "盒"));
        for (int d = 11; d <= 13; d++) rows.add(row("2026-11-" + d, "嫩滑牛肉片", "滚揉", "300", "kg"));
        List<Issue> out = ExternalDataIssueDetector.detect(rows);
        assertThat(out).hasSize(3);
        assertThat(of(out, "MIXED_UNIT")).hasSize(3).allSatisfy(i -> {
            assertThat(i.unit()).isEqualTo("盒");
            assertThat(i.suggestion()).contains("多数天用「kg」").contains("填错");
        });
    }

    @Test
    void aTieNeverPicksAMainUnit() {
        List<Row> rows = List.of(
                row("2026-11-10", "香嫩牛肉丝", "拆包", "80", "盒"),
                row("2026-11-11", "香嫩牛肉丝", "拆包", "90", "kg"));
        List<Issue> out = ExternalDataIssueDetector.detect(rows);
        assertThat(of(out, "MIXED_UNIT")).hasSize(2)
                .allSatisfy(i -> assertThat(i.suggestion()).contains("判不出主单位"));
    }

    @Test
    void zeroAndNegativeAreFlaggedAndMissingIsNotZero() {
        List<Row> rows = List.of(
                row("2026-11-07", "虎皮鸡爪", "熟制", "0", "kg"),
                row("2026-11-17", "叮咚牛肋条（真空包）", "真空包装", "0", "包"),
                row("2026-11-17", "叮咚好食光牛肉冻 200g", "出料", "-3", "kg"),
                row("2026-11-17", "叮咚好食光牛肉冻 200g", "切块", null, "kg"));
        List<Issue> out = ExternalDataIssueDetector.detect(rows);
        assertThat(of(out, "ZERO_QTY")).hasSize(3)
                .allSatisfy(i -> assertThat(i.suggestion()).contains("漏填"));
        assertThat(of(out, "MISSING_QTY")).hasSize(1);
        assertThat(of(out, "MISSING_QTY").get(0).qty()).isNull();
    }

    @Test
    void outputProcessInDifferentUnitFromItsOtherDaysIsAMixedUnitIssue() {
        List<Row> rows = new ArrayList<>();
        for (int d = 10; d <= 12; d++) rows.add(row("2026-11-" + d, "叮咚好食光纸片牛腱肉 80g", "出料", "100", "kg"));
        rows.add(row("2026-11-13", "叮咚好食光纸片牛腱肉 80g", "出料", "141.32", "盒"));
        List<Issue> out = ExternalDataIssueDetector.detect(rows);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo("MIXED_UNIT");
        assertThat(out.get(0).processName()).isEqualTo("出料");
    }

    @Test
    void consistentDataReportsNothing() {
        List<Row> rows = new ArrayList<>();
        for (int d = 10; d <= 16; d++) {
            rows.add(row("2026-11-" + d, "酱鸭腿", "真空包装", "120.5", "kg"));
            rows.add(row("2026-11-" + d, "酱鸭腿", "腌制", "130", "KG"));
            rows.add(row("2026-11-" + d, "酱鸭腿", "腌制", "5", "公斤"));
        }
        assertThat(ExternalDataIssueDetector.detect(rows)).isEmpty();
    }

    @Test
    void differentProductsOrProcessesDoNotVoteAgainstEachOther() {
        List<Row> rows = List.of(
                row("2026-11-10", "A", "拆包", "1", "盒"),
                row("2026-11-10", "A", "切块", "1", "kg"),
                row("2026-11-10", "B", "拆包", "1", "kg"));
        assertThat(ExternalDataIssueDetector.detect(rows)).isEmpty();
    }
}
