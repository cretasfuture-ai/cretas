package com.cretas.aims.service.reports;

import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.cretas.aims.service.reports.OperatingReportSnapshot.*;

class OperatingReportPermissionContractTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final OperatingReportCodec codec=new OperatingReportCodec(mapper);
    @Test void theSamePopulatedReportHidesFinancialTextModelAndListSummaryForNoPriceReader() throws Exception {
        OperatingReportProjection projection=new OperatingReportProjection(codec);
        var snapshot=new OperatingReportSnapshot(SCHEMA,Status.SUCCESS,"daily","2026-01-01","2026-01-01","2026-01-01T20:00:00",List.of(),
                List.of(new Section("orders","订单",false,"查询",List.of(new Metric("orders","订单数",new BigDecimal("3"),"单",null,null,"query.count"))),
                        new Section("profit","毛利",true,"查询",List.of(new Metric("profit","毛利",new BigDecimal("55321"),"元",null,BigDecimal.ONE,"query.profit")))),
                List.of(new Insight(InsightKind.REVIEW_RECORDED_VALUE,"profit",Action.REVIEW_PROFIT)),null);
        AIAnalysisResult report=AIAnalysisResult.builder().factoryId("SYNTHETIC_REPORT_PERMISSIONS").reportType("daily").analysisText(codec.encode(snapshot)).build();
        report.setId(81L);
        var finance=projection.detail(report,true);
        var warehouse=projection.detail(report,false);
        assertTrue(finance.getReportAnalysis().contains("55321元"),"Negative control requires real financial data");
        assertFalse(finance.getReportAnalysis().contains("99999"),"Model numbers cannot enter report");
        assertTrue(finance.getReportAnalysis().contains("复核已过账凭证"));
        assertNull(warehouse.getReportAnalysis());
        String detailJson=mapper.writeValueAsString(warehouse);
        String listJson=mapper.writeValueAsString(projection.summary(report,false));
        for(String output:List.of(detailJson,listJson)){
            assertFalse(output.contains("55321")); assertFalse(output.contains("99999"));
            assertFalse(output.contains("复核已过账凭证")); assertFalse(output.contains(SCHEMA));
            assertFalse(output.contains("sourcePath"));
        }
        assertTrue(warehouse.getAnalysis().contains("3单"));
        assertTrue(MobileDTO.AICostAnalysisResponse.class.getDeclaredField("reportAnalysis").isAnnotationPresent(PriceSensitive.class));
        assertTrue(MobileDTO.AIReportSummary.class.getDeclaredField("financialSummaryText").isAnnotationPresent(PriceSensitive.class));
    }
    @Test void legacyMarkdownAndCorruptEnvelopesNeverEscapePublicProjection(){
        var projection=new OperatingReportProjection(codec);
        var legacy=AIAnalysisResult.builder().analysisText("毛利55321元；建议降本").build();
        assertNull(projection.detail(legacy,false).getReportAnalysis());
        assertFalse(projection.summary(legacy,false).getSummaryText().contains("55321"));
        var corrupt=AIAnalysisResult.builder().analysisText("{\"schema\":\"CRETAS_OPERATING_REPORT_V1\",\"narrative\":\"SECRET 12345\"}").build();
        assertEquals("FAILED",projection.detail(corrupt,false).getGenerationStatus());
        assertFalse(projection.detail(corrupt,false).getAnalysis().contains("SECRET"));
    }
}
