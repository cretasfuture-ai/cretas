package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.ai.tool.*;
import com.cretas.aims.ai.tool.impl.report.ReportTrendsTool;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.*;
import com.cretas.aims.service.calibration.*;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReportTrendsDisplayContractTest {
    ObjectMapper mapper = new ObjectMapper();
    DashScopeClient llm;
    ReportService reports;
    ReportTrendsTool tool;
    ToolDispatchService dispatch;
    Map<String,Object> last;

    @BeforeEach void setup() {
        llm = mock(DashScopeClient.class);
        reports = mock(ReportService.class);
        tool = new ReportTrendsTool();
        ReflectionTestUtils.setField(tool,"reportService",reports);
        ReflectionTestUtils.setField(tool,"objectMapper",mapper);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(),anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(registry,mapper,llm,mock(ToolCallRedundancyService.class),
            mock(SelfCorrectionService.class),mock(CorrectionAgentService.class),mock(ExternalVerifierService.class),
            mock(ToolResultValidatorService.class),mock(ParameterExtractionLearningService.class));
        ToolRbacEnforcer rbac=mock(ToolRbacEnforcer.class);
        when(rbac.check(any(),anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch,"toolRbacEnforcer",rbac);
        ReflectionTestUtils.setField(dispatch,"writeGuardService",new WriteGuardService());
        when(reports.getTrendsDashboard(anyString(),anyString(),anyString(),anyInt()))
            .thenReturn(Map.of("trendData",List.of(Map.of("value",10),Map.of("value",12))));
        when(reports.getPeriodComparisonReport(anyString(),any(),any(),any(),any()))
            .thenAnswer(inv -> comparison(10,12,20));
    }

    Map<String,Object> comparison(Integer base,Integer current,Integer rate) {
        Map<String,Object> changes=new HashMap<>();
        changes.put("outputChangeRate",rate); changes.put("costChangeRate",10);
        return new HashMap<>(Map.of("period1",Map.of("output",base,"cost",100),
            "period2",Map.of("output",current,"cost",110),"comparison",changes));
    }

    String run(String text,String extracted) throws Exception {
        ChatCompletionResponse resp=mock(ChatCompletionResponse.class);
        when(llm.chatWithTools(anyString(),eq(text),anyList())).thenReturn(resp);
        when(llm.hasToolCalls(resp)).thenReturn(true);
        when(llm.getFirstToolCall(resp)).thenReturn(ToolCall.of("extract","extract_parameters",extracted));
        var intent=AIIntentConfig.builder().intentCode("REPORT_TRENDS").intentCategory("REPORT")
            .intentName("趋势报告").toolName("report_trends").build();
        var response=dispatch.executeWithTool(tool,"F006",IntentExecuteRequest.builder().userInput(text).build(),
            intent,1309L,"factory_super_admin",null);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        last=mapper.convertValue(response.getResultData(),Map.class);
        String result=new ResultFormatterServiceImpl().format(response);
        System.out.println("OBS input="+text+" data="+last+" display="+result);
        return result;
    }

    @Test void weekMomUsesOptionalExtractionAndDisplaysBothPeriods() throws Exception {
        String text=run("这周产量环比怎么样","{\"period\":\"week\",\"comparisonType\":\"MOM\",\"days\":88,\"startDate\":\"2020-01-01\"}");
        verify(llm).chatWithTools(anyString(),eq("这周产量环比怎么样"),anyList());
        assertThat(last).containsEntry("period","week").containsEntry("days",30);
        assertThat(((Map<?,?>)last.get("queryConditions")).containsKey("startDate")).isFalse();
        Map<?,?> comp=(Map<?,?>)last.get("comparison");
        assertThat(comp.get("currentPeriodStart")).isEqualTo(LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString());
        assertThat(text).contains("本期 12","上期 10","环比变化率 20%","数据出处：");
        var gate=new FactoryNumberProvenance();
        var passed=gate.enforce("REPORT_TRENDS",text,null,last);
        assertThat(passed.decision()).isEqualTo(FactoryNumberProvenance.Decision.PASS);
        assertThat(gate.enforce("REPORT_TRENDS","本期产量77",null,last).decision())
            .isEqualTo(FactoryNumberProvenance.Decision.REDACTED);
    }

    @Test void monthYoyUsesOriginalInputEvenIfLlmSaysMom() throws Exception {
        String text=run("这个月产量同比","{\"period\":\"month\",\"comparisonType\":\"MOM\"}");
        assertThat(((Map<?,?>)last.get("comparison")).get("comparisonType")).isEqualTo("YOY");
        assertThat(text).contains("同比变化率 20%","本期 12","上期 10");
    }

    @Test void dailyOnlyStillReturnsSeriesAndNeverQueriesComparison() throws Exception {
        String text=run("只要最近30天产量日序列，不比较","{\"days\":30,\"comparisonType\":\"MOM\"}");
        assertThat(last).containsEntry("days",30).doesNotContainKey("comparison");
        verify(reports).getTrendsDashboard("F006","month","PRODUCTION",30);
        verify(reports,never()).getPeriodComparisonReport(anyString(),any(),any(),any(),any());
        assertThat(text).contains("起始: 10","最新: 12").doesNotContain("变化率");
    }

    @Test void zeroBaseExplanationSurvivesFormatterWithoutInventedRate() throws Exception {
        when(reports.getPeriodComparisonReport(anyString(),any(),any(),any(),any())).thenAnswer(inv -> comparison(0,12,null));
        String text=run("这周产量环比怎么样","{\"period\":\"week\"}");
        assertThat(text).contains("本期 12","上期 0","上期无数据，无法计算环比").doesNotContain("产量：本期 12，上期 0；环比变化率");
    }

    @Test void explicitChineseRangeIsGroundedWithoutInventingAnAdditionalDate() throws Exception {
        run("2026年10月5日至8日产量环比","{\"startDate\":\"2026-10-05\",\"endDate\":\"2026-10-08\",\"days\":99}");
        assertThat((Map<String,Object>)last.get("queryConditions"))
            .containsEntry("startDate","2026-10-05").containsEntry("endDate","2026-10-08").containsEntry("days",30);
        assertThat(((Map<?,?>)last.get("comparison")).get("basePeriodStart")).isEqualTo("2026-10-01");
    }
}
