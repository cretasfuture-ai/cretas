package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.quality.QualityStatsQueryTool;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.execution.IntentExecutionOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 2026-11-15 演示题单第二轮复跑 (Q12 / Q15 / Q19) 的回归断言: 跑在真实工具入口 / 真实短语表 / 真实拒答守卫上。 */
class AiBiDemoRerunContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private final ObjectMapper mapper = new ObjectMapper();

    // ---- Q15: 批次数 + 合格率按本月回答, 没质检数据如实说缺什么, 不暴露内部参数名 ----
    private QualityStatsQueryTool qualityTool(List<QualityInspection> inspections, List<ProductionBatch> batches) {
        QualityInspectionRepository qrepo = mock(QualityInspectionRepository.class);
        ProductionBatchRepository brepo = mock(ProductionBatchRepository.class);
        when(qrepo.findByFactoryIdAndDateRange(eq("F1"), eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 15))))
                .thenReturn(inspections);
        when(qrepo.calculateFirstPassRate(eq("F1"), any(), any())).thenReturn(
                inspections.isEmpty() ? 0.0 : inspections.stream().filter(i -> "PASS".equals(i.getResult())).count() * 100.0 / inspections.size());
        when(brepo.findByFactoryIdAndEndTimeGreaterThanEqualAndEndTimeLessThan(eq("F1"),
                eq(LocalDate.of(2026, 11, 1).atStartOfDay()), eq(LocalDate.of(2026, 11, 16).atStartOfDay()))).thenReturn(batches);
        QualityStatsQueryTool t = new QualityStatsQueryTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ReflectionTestUtils.setField(t, "qualityInspectionRepository", qrepo);
        ReflectionTestUtils.setField(t, "productionBatchRepository", brepo);
        ReflectionTestUtils.setField(t, "objectMapper", mapper);
        return t;
    }

    private static ProductionBatch batch(ProductionBatchStatus st) {
        ProductionBatch b = new ProductionBatch();
        b.setStatus(st);
        return b;
    }

    private static QualityInspection inspection(String result) {
        QualityInspection q = new QualityInspection();
        q.setResult(result);
        return q;
    }

    private String message(QualityStatsQueryTool t) throws Exception {
        JsonNode r = mapper.readTree(t.execute(ToolCall.of("t1", "quality_stats_query",
                "{\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-15\"}"), Map.of("factoryId", "F1", "userId", 1L)));
        return r.get("data").get("message").asText();
    }

    @Test
    void thisMonth_answersBatchCountAndPassRate_forTheSaidRange() throws Exception {
        List<ProductionBatch> batches = List.of(batch(ProductionBatchStatus.COMPLETED), batch(ProductionBatchStatus.COMPLETED),
                batch(ProductionBatchStatus.IN_PROGRESS));
        List<QualityInspection> ins = new ArrayList<>(List.of(inspection("PASS"), inspection("PASS"), inspection("PASS"), inspection("FAIL")));
        String msg = message(qualityTool(ins, batches));
        assertTrue(msg.contains("2026-11-01 至 2026-11-15"), msg);
        assertTrue(msg.contains("已完成生产批次 2 个"), msg);          // 进行中的不算
        assertTrue(msg.contains("质检 4 次") && msg.contains("合格率 75.0%"), msg);
    }

    @Test
    void noInspections_saysWhatIsMissing_notZeroPercent_andNeverLeaksInternalParameterNames() throws Exception {
        String msg = message(qualityTool(List.of(), List.of(batch(ProductionBatchStatus.COMPLETED))));
        assertTrue(msg.contains("已完成生产批次 1 个"), msg);
        assertTrue(msg.contains("没有质检记录") && msg.contains("合格率算不出"), msg);
        assertFalse(msg.contains("0.0%") || msg.contains("indicator_code"), msg);
    }

    @Test
    void qualityStatsDeclaresTheDateKeysTheDispatcherFills_soTimeIsNotSilentlyDropped() {
        Map<?, ?> props = (Map<?, ?>) new QualityStatsQueryTool().getParametersSchema().get("properties");
        assertTrue(props.containsKey("startDate") && props.containsKey("endDate"));
        assertEquals("date", ((Map<?, ?>) props.get("startDate")).get("format"));
    }

    // ---- Q12 / Q15: 同一句话两个角色必须同一意图 (短语表) ----
    @Test
    void q12AndQ15PhrasesRouteToOneIntent() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        Map<String, String> p = kb.getPhraseToIntentMapping();
        assertEquals("QUALITY_STATS", p.get("本月生产了多少批次，合格率多少"));
        // Q12: 不得用短语把采购涨价问句路由到无权限声明的 SUPPLIER_PRICE_ALERT 去绕过 price_history_query 的权限
        for (String q : new String[]{"原料采购价最近涨得最多的是哪个", "采购价最近涨得最多的是哪个", "哪个原料涨价最多", "原料采购价涨得最多"}) {
            assertNotEquals("SUPPLIER_PRICE_ALERT", p.get(q), q);
        }
    }

    // Q12: 被拒时的文案要说清需要哪项权限、找谁开
    @Test
    void permissionDenialNamesTheRequiredPermissionAndWhoToAsk() {
        ToolRbacGuard guard = new ToolRbacGuard();
        String msg = guard.denyMessage(Map.of("userRole", "finance_manager"), "price_history_query", "procurement:read");
        assertTrue(msg.contains("采购查看") && msg.contains("工厂管理员"), msg);
        // 一线用户看不懂内部标识: 不显示工具名 / 权限码
        assertFalse(msg.contains("price_history_query") || msg.contains("procurement:read"), msg);
        assertFalse(guard.denyMessage(Map.of("userRole", "x"), "some_unmapped_tool", "x:y").contains("some_unmapped_tool"));
        assertFalse(guard.denyMessage(Map.of("userRole", "x"), "t").contains("需要的权限"));
    }

    // ---- Q19: 个人工资明细拒答, 汇总 / 规则问法不拒 ----
    @ParameterizedTest
    @ValueSource(strings = {"每个工人的工资是多少", "每位员工的工资", "所有员工的薪资明细", "工人工资是多少", "员工的月薪多少"})
    void personalPayQuestionsAreRefused(String text) {
        assertTrue(PersonalPayRefusalGuard.refuses(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"工资策略有哪些", "员工工资计算规则", "本月人工成本是多少", "人工成本占总成本多少", "工资总额是多少", "客户收入趋势", "本月销售额和毛利是多少"})
    void aggregateOrPolicyPayQuestionsAreNotRefused(String text) {
        assertFalse(PersonalPayRefusalGuard.refuses(text), text);
    }

    @Test
    void refusalResponseIsExplicit_andPointsToAggregateAskings() {
        IntentExecuteResponse r = IntentExecutionOrchestrator.personalPayRefusalResponse();
        assertEquals(PersonalPayRefusalGuard.STATUS, r.getStatus());
        assertTrue(r.getMessage().contains("不提供") && r.getMessage().contains("总人工成本"), r.getMessage());
        assertFalse(r.getMessage().contains("factory default"), r.getMessage());
    }

    // 接线 (形态 B): 守卫必须在识别之前被入口调用, 否则财务号仍会落进「资产负债表 / 会计科目」选项
    @Test
    void orchestratorEntryCallsTheGuardBeforeIntentRecognition() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java"));
        int body = src.indexOf("private IntentExecuteResponse executeInScope(");
        int guard = src.indexOf("PersonalPayRefusalGuard.refuses(request.getUserInput())", body);
        int recognise = src.indexOf("FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute", body);
        assertTrue(body > 0 && guard > body && guard < recognise, "guard=" + guard + " recognise=" + recognise);
    }

    // R-M1: 组合句里前半是个人工资, 后半有汇总词 —— 整句有汇总词不能放行整句 (子句级判断)
    @ParameterizedTest
    @ValueSource(strings = {"每个工人的工资多少，本月人工成本多少", "本月人工成本多少，另外每位员工的工资是多少", "工资策略是什么以及每个员工的薪资",
            // 复审 R-M1: 「怎么算」不能豁免逐人 / 问数额的句子; 子句要能按空格 / 和 / 及 / 冒号 / 斜杠 / 然后切开
            "每个工人的工资怎么算出来是多少", "每个员工的提成怎么算的，各拿了多少", "每个工人的工资多少 本月人工成本多少",
            "每个工人的工资和本月人工成本", "每个工人的工资及人工成本", "每个工人的工资/本月人工成本", "每个工人的工资：多少 然后本月人工成本",
            "每位员工的工资汇总", "每个员工的工资总额是多少"})
    void compositeQuestionWithAPersonalPayClauseIsRefused(String text) {
        assertTrue(PersonalPayRefusalGuard.refuses(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"工人工资怎么算", "员工奖金怎么算", "本月人工成本多少，工资策略是什么", "员工工资计算规则", "员工工资总额是多少"})
    void rulesAndAggregatesAreStillNotRefused(String text) {
        assertFalse(PersonalPayRefusalGuard.refuses(text), text);
    }

    // 复审 R-M5: 拒答文案不露角色码 / 工具名 / 权限码; 写权限不说成「查看权限」
    @Test
    void denialShowsChineseRoleNames_andWritePermissionIsNotCalledViewing() {
        assertFalse(RoleDisplay.of("finance_manager").contains("finance_manager"));
        assertEquals("相应角色", RoleDisplay.of("no_such_role"));
        String list = RoleDisplay.listOf("[factory_super_admin, no_such_role]");
        assertFalse(list.contains("factory_super_admin") || list.contains("["), list);
        ToolRbacGuard guard = new ToolRbacGuard();
        String msg = guard.denyMessage(Map.of("userRole", "finance_manager"), "x_tool", "quality:read_write");
        assertFalse(msg.contains("finance_manager") || msg.contains("quality:read_write") || msg.contains("x_tool"), msg);
        assertTrue(msg.contains("对应模块的操作权限") && !msg.contains("查看权限"), msg);
        // 第 3 轮表 C: 断言整句, 写权限后面不能再跟「可以查看」
        assertEquals("您当前的角色 (财务主管) 没有权限使用「该功能」。此操作涉及敏感数据/库存/资金, 需要授权角色。"
                + "需要的权限: 对应模块的操作权限（拥有其中任一项权限的角色可以使用）。"
                + "建议: 请在系统对应页面由有权限的角色 (如仓储主管/财务/管理员) 操作, 或联系工厂管理员为您的账号开通权限。", msg);
        assertFalse(msg.contains("查看"), msg);
    }
}
