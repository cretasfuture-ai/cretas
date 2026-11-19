package com.cretas.aims.service.crew;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数字班组各成员页自带的推荐问题：页面文案、直连意图、成员能力三者必须一致。
 * 2026-11-18 演示前实测：推荐问题答空 / 被识别层猜错意图（采购订单→销售订单列表、毛利→利润表）。
 */
class DigitalCrewQuickQuestionsContractTest {

    private static Path vue() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            Path f = p.resolve("web-admin/src/views/crew/CrewMemberPage.vue");
            if (Files.exists(f)) return f;
        }
        throw new IllegalStateException("找不到 CrewMemberPage.vue");
    }

    @Test
    @DisplayName("前端推荐问题与后端 QUICK_QUESTIONS 逐字一致（每位员工两条，顺序一致）")
    void pageAndBackendAgree() throws IOException {
        String src = Files.readString(vue(), StandardCharsets.UTF_8);
        int start = src.indexOf("const q: Record<string, string[]> = {");
        int end = src.indexOf("};", start);
        assertThat(start).isGreaterThan(0);
        String block = src.substring(start, end);
        Pattern row = Pattern.compile("(\\w+):\\s*\\[([^\\]]*)\\]");
        Matcher m = row.matcher(block);
        int rows = 0;
        while (m.find()) {
            rows++;
            List<String> texts = new ArrayList<>();
            Matcher q = Pattern.compile("'([^']+)'").matcher(m.group(2));
            while (q.find()) texts.add(q.group(1));
            List<String> backend = DigitalCrewCatalog.QUICK_QUESTIONS.get(m.group(1)).stream()
                    .map(DigitalCrewCatalog.QuickQuestion::text).toList();
            assertThat(texts).as("成员 " + m.group(1)).isEqualTo(backend);
        }
        assertThat(rows).isEqualTo(DigitalCrewCatalog.MEMBERS.size());
    }

    @Test
    @DisplayName("按实测修路由：采购订单→PURCHASE_ORDER_LIST、毛利→REPORT_SALES_MARGIN_SUMMARY、待质检→RECEIVE_QUALITY_CHECK_TODAY")
    void routesFixed() {
        assertThat(DigitalCrewCatalog.quickIntentFor("clerk", "最近的采购订单")).contains("PURCHASE_ORDER_LIST");
        assertThat(DigitalCrewCatalog.quickIntentFor("clerk", "最近的销售订单")).contains("ORDER_LIST");
        assertThat(DigitalCrewCatalog.quickIntentFor("accountant", "这个月的毛利情况"))
                .contains("REPORT_SALES_MARGIN_SUMMARY");
        assertThat(DigitalCrewCatalog.quickIntentFor("inspector", "待质检批次")).contains("RECEIVE_QUALITY_CHECK_TODAY");
        // 用户改过的话 / 别的员工的话：不直连，照常走识别
        assertThat(DigitalCrewCatalog.quickIntentFor("clerk", "最近的采购订单有哪些")).isEmpty();
        assertThat(DigitalCrewCatalog.quickIntentFor("keeper", "最近的采购订单")).isEmpty();
        assertThat(DigitalCrewCatalog.quickIntentFor(null, null)).isEmpty();
    }

    @Test
    @DisplayName("只直连读意图：不含 CREATE/UPDATE/DELETE/APPROVE 之类写意图")
    void onlyReadIntents() {
        for (Map.Entry<String, List<DigitalCrewCatalog.QuickQuestion>> e : DigitalCrewCatalog.QUICK_QUESTIONS.entrySet()) {
            assertThat(e.getValue()).hasSize(2);
            for (DigitalCrewCatalog.QuickQuestion q : e.getValue()) {
                assertThat(q.intentCode()).doesNotMatch(".*(CREATE|UPDATE|DELETE|APPROVE|CONFIRM|SET_|CANCEL).*");
                assertThat(q.text()).doesNotContain("7 天").doesNotContain("近一周");
            }
        }
        assertThat(DigitalCrewCatalog.QUICK_QUESTIONS.keySet())
                .containsExactlyInAnyOrderElementsOf(DigitalCrewCatalog.MEMBERS.stream()
                        .map(DigitalCrewCatalog.Member::key).toList());
    }
}
