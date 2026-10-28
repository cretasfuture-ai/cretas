package com.cretas.aims.service.reports;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.AIEnterpriseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 经营报告页面读取 (Steve 10-29): 快照是全厂共享、存成字符串的, 读的时候按调用者遮 —— 无完整查看权的人看保密产品的
 * 逐产品毛利 / 毛利率为「已保密」, 其他产品照常; 全厂合计与利润表 (会计口径) 照旧; 负责人看全的; 他厂照旧无权访问。
 * 走真实入口 {@link AIEnterpriseService#getReportDetail} 与真实 {@link OperatingReportProjection} / 编解码。
 */
class OperatingReportSecretMaskTest {

    private final OperatingReportCodec codec = new OperatingReportCodec(new ObjectMapper().findAndRegisterModules());
    private final LocalDate day = LocalDate.of(2026, 10, 20);

    private static Map<String, Object> sqlRow(String id, String name, String revenue, BigDecimal cost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product_type_id", id);
        row.put("product_name", name);
        row.put("revenue", new BigDecimal(revenue));
        row.put("costed_revenue", new BigDecimal(revenue));
        row.put("cost", cost);
        row.put("costed_line_count", 2);
        row.put("line_count", 2);
        return row;
    }

    /** 保密产品 毛利 2222.22 / 毛利率 22.2222%; 普通产品 毛利 1765.44 / 679; 全厂已回填成本 13333.33; 利润表营业成本 99999.99。 */
    private AIAnalysisResult storedReport() {
        List<Map<String, Object>> rows = List.of(
                sqlRow(SECRET, SECRET_NAME, "9999.99", SECRET_COST),
                sqlRow(NORMAL, "普通甲", "3000.00", NORMAL_COST),
                sqlRow(NORMAL2, "普通乙", "5000.00", NORMAL2_COST));
        Map<String, Object> income = new LinkedHashMap<>();
        income.put("costDataAvailable", true);
        income.put("totalRevenue", new BigDecimal("150000.00"));
        income.put("totalCost", new BigDecimal("99999.99"));
        OperatingReportSnapshot snapshot = new OperatingReportAssembler().assemble(new OperatingReportAssembler.Input(
                "daily", day, day, "2026-10-20T20:00:00Z", List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                OperatingReportGrossMarginQuery.payload(rows, false), OperatingReportGrossMarginQuery.payload(rows, true),
                List.of(), income, List.of(), Map.of()));
        return AIAnalysisResult.builder().id(9L).factoryId(FACTORY).reportType("daily").analysisText(codec.encode(snapshot))
                .expiresAt(LocalDateTime.now().plusDays(30)).build();
    }

    private AIEnterpriseService service(AIAnalysisResult report) {
        OperatingReportProjection projection = new OperatingReportProjection(codec);
        ReflectionTestUtils.setField(projection, "secretScope", protectedScope());
        AIEnterpriseService service = new AIEnterpriseService();
        AIAnalysisResultRepository results = mock(AIAnalysisResultRepository.class);
        when(results.findById(9L)).thenReturn(Optional.of(report));
        OperatingReportGenerator operating = mock(OperatingReportGenerator.class);
        when(operating.usesLegacyReportContract(anyString())).thenReturn(false);
        ReflectionTestUtils.setField(service, "analysisResultRepository", results);
        ReflectionTestUtils.setField(service, "operatingReports", operating);
        ReflectionTestUtils.setField(service, "operatingProjection", projection);
        ReflectionTestUtils.setField(service, "operatingCodec", codec);
        return service;
    }

    private String financialTextAs(AuthenticatedActor actor) {
        AIEnterpriseService service = service(storedReport());
        try (var ignored = as(actor)) {
            MobileDTO.AICostAnalysisResponse detail = service.getReportDetail(FACTORY, 9L, true);
            return detail.getReportAnalysis();
        }
    }

    @Test
    void aColleagueWithoutGrantSeesTheProtectedProductsMarginAsSecretAndEverythingElseIntact() {
        String text = financialTextAs(sameFactoryUser());

        assertThat(text).contains(SECRET_NAME + " 毛利：" + OperatingReportCodec.PROCESS_SECRET_MASK)
                .contains(SECRET_NAME + " 毛利率：" + OperatingReportCodec.PROCESS_SECRET_MASK)
                .doesNotContain("2222.22", "22.222")
                .contains("1765.44", "679")                // 普通产品照常
                .contains("13333.33", "99999.99");         // 全厂合计 / 利润表按原样 (Steve: 会计口径不改)
    }

    @Test
    void platformAndUnknownCallersAreMaskedToo() {
        assertThat(financialTextAs(platform())).doesNotContain("2222.22").contains(OperatingReportCodec.PROCESS_SECRET_MASK);
        assertThat(financialTextAs(null)).doesNotContain("2222.22").contains(OperatingReportCodec.PROCESS_SECRET_MASK);
    }

    @Test
    void theOwnerAndAGranteeSeeTheFullReport() {
        for (AuthenticatedActor actor : List.of(owner(), grantee())) {
            assertThat(financialTextAs(actor)).as("caller %s", actor).contains("2222.22", "22.222")
                    .doesNotContain(OperatingReportCodec.PROCESS_SECRET_MASK);
        }
    }

    @Test
    void anotherFactoryStillHasNoAccessToTheReport() {
        AIEnterpriseService service = service(storedReport());
        try (var ignored = as(otherFactoryUser())) {
            assertThatThrownBy(() -> service.getReportDetail(OTHER_FACTORY, 9L, true))
                    .hasMessageContaining("无权访问此报告");
        }
    }

    @Test
    void theStoredSnapshotItselfIsNotChanged() {
        AIAnalysisResult report = storedReport();
        String before = report.getAnalysisText();
        financialTextAs(sameFactoryUser());
        assertThat(report.getAnalysisText()).isEqualTo(before).contains("2222.22");
    }

    @Test
    void unprotectedFactoryNegativeControlShowsEverything() {
        OperatingReportProjection projection = new OperatingReportProjection(codec);
        ReflectionTestUtils.setField(projection, "secretScope", unprotectedScope());
        try (var ignored = as(sameFactoryUser())) {
            assertThat(projection.detail(storedReport(), true).getReportAnalysis()).contains("2222.22")
                    .doesNotContain(OperatingReportCodec.PROCESS_SECRET_MASK);
        }
    }

    private OperatingReportSnapshot maskedFor(com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope scope,
                                              AuthenticatedActor actor) {
        OperatingReportProjection projection = new OperatingReportProjection(codec);
        ReflectionTestUtils.setField(projection, "secretScope", scope);
        try (var ignored = as(actor)) {
            return projection.maskForCaller(codec.decode(storedReport().getAnalysisText()).orElseThrow(), FACTORY);
        }
    }

    @Test
    void maskedRowsMoveToTheEndOfEveryRankingSoTheirPositionDoesNotLeakTheMargin() {
        OperatingReportSnapshot masked = maskedFor(protectedScope(), sameFactoryUser());

        for (OperatingReportSnapshot.Section section : masked.sections()) {
            if (!OperatingReportProjection.PRODUCT_RANK_SECTIONS.contains(section.id())) continue;
            List<OperatingReportSnapshot.Metric> metrics = section.metrics();
            // 保密产品原本在高毛利率榜第 2 位 (22.22% 介于 58.85% 与 13.58% 之间), 遮挡后必须排在最后两项
            assertThat(metrics.subList(metrics.size() - 2, metrics.size()))
                    .as("section %s", section.id())
                    .allSatisfy(m -> assertThat(m.id()).contains("." + SECRET + "."))
                    .allSatisfy(m -> assertThat(m.missingReason()).isEqualTo(OperatingReportCodec.PROCESS_SECRET_MASK));
            assertThat(metrics.subList(0, metrics.size() - 2)).noneSatisfy(m -> assertThat(m.id()).contains("." + SECRET + "."));
        }
    }

    @Test
    void theMaskUsesThePlanAwareScopeLikeThePromptDoes() {
        // 多产出计划锚在原料 RAW-ANCHOR 上、目标成品是保密产品: 锚点那一行 (与提示词同口径) 也遮
        com.cretas.aims.entity.ProductionPlan anchorPlan = plan("PL-MULTI", NORMAL2, null);
        anchorPlan.setTargetFinishedGoodIds(List.of(SECRET));
        OperatingReportSnapshot masked = maskedFor(scope(java.util.Set.of(SECRET), List.of(anchorPlan)), sameFactoryUser());

        List<OperatingReportSnapshot.Metric> high = masked.sections().stream()
                .filter(s -> s.id().equals("marginHigh")).findFirst().orElseThrow().metrics();
        assertThat(high).filteredOn(m -> m.id().contains("." + NORMAL2 + "."))
                .allSatisfy(m -> assertThat(m.missingReason()).isEqualTo(OperatingReportCodec.PROCESS_SECRET_MASK));
        assertThat(high).filteredOn(m -> m.id().contains("." + NORMAL + "."))
                .allSatisfy(m -> assertThat(m.value()).isNotNull());
    }
}
