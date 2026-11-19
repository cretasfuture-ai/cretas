package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolParameterGroups;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.foodsafety.BatchTraceByCustomerDateTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BP 问题 34 (11-19 F006): 按「日期 + 客户」问出货来源仍反问「哪个客户」。
 *
 * <p>走对话真实使用的 {@link ToolDispatchService#executeWithTool} + 真工具
 * {@link BatchTraceByCustomerDateTool}。前一步 (填槽) 已把日期放进请求上下文时, schema.required 只有 date,
 * 「必填已齐」就不再叫抽参模型, 客户名到不了工具, 工具反问「请问是发给哪个客户的」。
 * 声明了「客户编号或客户名称至少一个」组之后, 组里一个都没有就仍要抽。
 */
@DisplayName("至少给一个 参数组: 必填已齐也抽组内参数 (BP 34)")
class AnyOfParameterExtractionDispatchContractTest {

    private static final String F = "F006";
    private static final String SAID = "9月20号发给上海连锁餐饮的酱卤牛肉是哪批原料做的";

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeClient llm;
    private ToolDispatchService dispatch;
    private BatchTraceByCustomerDateTool tool;
    private ShipmentRecordRepository shipments;
    private CustomerRepository customers;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        dispatch = new ToolDispatchService(registry, mapper, llm, redundancy,
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());

        tool = new BatchTraceByCustomerDateTool();
        shipments = mock(ShipmentRecordRepository.class);
        customers = mock(CustomerRepository.class);
        ReflectionTestUtils.setField(tool, "shipmentRecordRepository", shipments);
        ReflectionTestUtils.setField(tool, "customerRepository", customers);
        ReflectionTestUtils.setField(tool, "traceabilityService", mock(TraceabilityService.class));
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        Customer c = new Customer();
        c.setId("cust-sh");
        c.setName("上海连锁餐饮有限公司");
        when(customers.findByFactoryId(F)).thenReturn(List.of(c));
        ShipmentRecord s = new ShipmentRecord();
        s.setShipmentNumber("SH-1");
        s.setBatchNumber("FG-0920-01");
        s.setProductName("酱卤牛肉");
        s.setShipmentDate(LocalDate.of(2026, 9, 20));
        when(shipments.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(F, "cust-sh")).thenReturn(List.of(s));
    }

    private void llmReturns(String argumentsJson) {
        ChatCompletionResponse resp = mock(ChatCompletionResponse.class);
        when(llm.chatWithTools(anyString(), anyString(), anyList())).thenReturn(resp);
        when(llm.hasToolCalls(resp)).thenReturn(true);
        when(llm.getFirstToolCall(resp)).thenReturn(ToolCall.of("c1", "extract_parameters", argumentsJson));
    }

    private IntentExecuteResponse run(Map<String, Object> context) throws Exception {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("BATCH_TRACE_BY_CUSTOMER").intentCategory("QUERY")
                .intentName("按客户日期追溯").toolName(tool.getToolName()).build();
        return dispatch.executeWithTool(tool, F,
                IntentExecuteRequest.builder().userInput(SAID).context(context).build(),
                intent, 1L, "factory_super_admin", null);
    }

    @Test
    @DisplayName("日期已由填槽带来 → 仍叫抽参模型抽客户名, 工具带着客户去查, 不反问哪个客户")
    void customerNameIsExtractedEvenWhenTheRequiredDateIsAlreadyThere() throws Exception {
        llmReturns("{\"customerName\":\"上海连锁餐饮\",\"productName\":\"酱卤牛肉\"}");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("date", "2026-09-20");

        IntentExecuteResponse r = run(ctx);

        verify(llm).chatWithTools(anyString(), anyString(), anyList());   // 抽参真的被叫了 (硬约束 9)
        assertThat(String.valueOf(r.getMessage()) + r.getResultData())
                .as("客户名在原话里, 不得反问: " + r.getMessage()).doesNotContain("哪个客户");
        assertThat(r.getStatus()).isEqualTo("SUCCESS");
        verify(shipments).findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(F, "cust-sh");
    }

    @Test
    @DisplayName("对照: 客户名已经在参数里 → 组已满足, 不为它多叫一次模型")
    void noExtractionWhenTheGroupIsAlreadySatisfied() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("date", "2026-09-20");
        ctx.put("customerName", "上海连锁餐饮");

        run(ctx);

        verify(llm, never()).chatWithTools(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("没声明组的只读工具, 必填齐了照旧不叫模型 (抽参开销不变)")
    void toolsWithoutGroupsKeepTheOldBehaviour() {
        assertThat(ToolParameterGroups.groups(Map.of("type", "object", "required", List.of("date")))).isEmpty();
        assertThat(ToolParameterGroups.missingMembers(tool.getParametersSchema(), k -> k.equals("customerId")))
                .as("组内有一个就不缺").isEmpty();
        assertThat(ToolParameterGroups.missingMembers(tool.getParametersSchema(), k -> false))
                .containsExactly("customerId", "customerName");
    }
}
