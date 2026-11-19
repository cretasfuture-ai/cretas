package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolParameterGroups;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.foodsafety.BatchTraceByCustomerDateTool;
import com.cretas.aims.ai.tool.impl.shipment.TraceBatchTool;
import com.cretas.aims.ai.tool.impl.shipment.TraceFullTool;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.trace.SalesOrderTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BP deck 11-19 问题 39 / 34: 填槽 (SlotFilling, 流式管线 7b) 与工具一起走的真实入口。
 *
 * <p>39: 「追溯销售单 DEMO-20261105-SO-001 的来源 …」被反问「请提供生产批次号（销售单号不要填这里）」。
 * 成因: 槽位由 schema.required 推出, 只有 batchNumber, 标签就是它的参数描述「…销售单号不要填这里」;
 * 销售单号参数从来没有进过抽参。34: 必填只有 date, date 抽到后「参数齐了」, 问句里的客户名没人抽。
 *
 * <p>LLM 桩按真实模型的行为写: 只回提示词槽位表里列出的参数, 并遵守槽位标签里的禁令 (标签说「销售单号不要填这里」
 * 就不把销售单号填进去) —— 这样修前的槽位设置才会暴露缺陷, 而不是桩替它圆回来。
 */
@DisplayName("追溯 / 按日期客户追溯: 填槽抽参接上销售单号与客户名 (BP 39 / 34)")
class TraceSlotExtractionContractTest {

    private static final String F = "F006";
    private static final String SO_NO = "DEMO-20261105-SO-001";
    private static final String SAID_39 = "追溯销售单 " + SO_NO + " 的来源：原料批次、生产批次、质检和出货";
    private static final String SAID_34 = "9月20号发给上海连锁餐饮的酱卤牛肉是哪批原料做的";
    private static final Pattern SLOT_LINE = Pattern.compile("(?m)^- (\\w+) \\(");

    private final ObjectMapper mapper = new ObjectMapper();
    private SlotFillingServiceImpl service;
    private PythonLLMClient llm;
    private ToolRegistry registry;
    private final TraceFullTool fullTool = new TraceFullTool();
    private final TraceBatchTool batchTool = new TraceBatchTool();
    private final BatchTraceByCustomerDateTool customerDateTool = new BatchTraceByCustomerDateTool();
    /** 每次 LLM 兜底抽参拿到的系统提示词 (硬约束 9: 兜底真的被叫过 / 没被叫过要看得见)。 */
    private final java.util.List<String> llmPrompts = new java.util.ArrayList<>();

    private static AIIntentConfig intent(String code, String tool) {
        return AIIntentConfig.builder().intentCode(code).intentName(code).toolName(tool).build();
    }

    private static IntentExecuteRequest request(String userInput) {
        return IntentExecuteRequest.builder().userInput(userInput).context(new HashMap<>()).build();
    }

    @BeforeEach
    void setUp() {
        IntentSlotConfiguration slotConfiguration = new IntentSlotConfiguration();
        slotConfiguration.init();
        registry = mock(ToolRegistry.class);
        when(registry.getExecutor("trace_full")).thenReturn(Optional.of(fullTool));
        when(registry.getExecutor("trace_batch")).thenReturn(Optional.of(batchTool));
        when(registry.getExecutor("batch_trace_by_customer_date")).thenReturn(Optional.of(customerDateTool));
        service = new SlotFillingServiceImpl(slotConfiguration, registry, mock(ConversationService.class),
                mock(ParameterExtractionLearningService.class));
        llm = mock(PythonLLMClient.class);
        when(llm.chatLowTemp(anyString(), anyString())).thenAnswer(inv -> obedientModel(inv.getArgument(0), inv.getArgument(1)));
        service.setPythonLLMClient(llm);
    }

    /** 听话的模型: 只填槽位表里有的参数; 标签禁止的不填; 句子里没有的给 null (不编)。 */
    private String obedientModel(String systemPrompt, String userInput) {
        llmPrompts.add(systemPrompt);
        Matcher m = SLOT_LINE.matcher(systemPrompt);
        Map<String, Object> out = new HashMap<>();
        while (m.find()) {
            String slot = m.group(1);
            switch (slot) {
                case "date" -> out.put(slot, userInput.contains("9月20号") ? "2026-09-20" : null);
                case "customerName" -> {
                    // 外发前客户名会被换成代号 (LlmEgressGuard), 模型照抄「发给 X 的」里的 X, 回来再换回真值
                    Matcher who = Pattern.compile("发给(.+?)的").matcher(userInput);
                    out.put(slot, who.find() ? who.group(1) : null);
                }
                case "salesOrderNo" -> out.put(slot, userInput.contains(SO_NO) ? SO_NO : null);
                case "batchNumber" -> {
                    // 听话: 销售单号不填批次号 (标签禁止); 句子里没有批次号就是 null
                    boolean mentioned = userInput.contains("PB-20261105-001");
                    boolean forbidden = userInput.contains(SO_NO)
                            && systemPrompt.contains("销售单号不要填这里") && !systemPrompt.contains("salesOrderNo");
                    out.put(slot, mentioned && !forbidden ? "PB-20261105-001" : null);
                }
                default -> out.put(slot, null);
            }
        }
        try {
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 问题 39 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("39 入口: 追溯销售单 DEMO-…-SO-001 → 不反问批次号, 销售单号整串(含 DEMO- 前缀)进请求上下文")
    void salesOrderQuestionIsNotAskedForABatchNumber() {
        IntentExecuteRequest req = request(SAID_39);

        IntentExecuteResponse resp = service.checkAndStartSlotFilling(F, 1L, intent("TRACE_FULL", "trace_full"), req, null);

        assertThat(resp).as("销售单号在原话里, 不该反问「请提供生产批次号」: "
                + (resp == null ? "" : resp.getMessage())).isNull();
        assertThat(req.getContext()).containsEntry("salesOrderNo", SO_NO);
        assertThat(req.getContext()).as("销售单号不能被当批次号").doesNotContainKey("batchNumber");
    }

    @Test
    @DisplayName("39 入口(TRACE_BATCH 同样): 追溯销售单也走 TRACE_BATCH 时同样不反问")
    void sameForTraceBatchIntent() {
        IntentExecuteRequest req = request("溯源 " + SO_NO);

        IntentExecuteResponse resp = service.checkAndStartSlotFilling(F, 1L, intent("TRACE_BATCH", "trace_batch"), req, null);

        assertThat(resp).isNull();
        assertThat(req.getContext()).containsEntry("salesOrderNo", SO_NO);
    }

    @Test
    @DisplayName("39 阴性对照: 说的是生产批次号 → 批次号走 LLM 抽到 batchNumber, 不误当销售单号; 什么都没说 → 交给工具追问")
    void batchNumberQuestionStillGoesToBatchNumberAndEmptyIsLeftToTheTool() {
        IntentExecuteRequest req = request("追溯批次 PB-20261105-001 的来源");
        IntentExecuteResponse resp = service.checkAndStartSlotFilling(F, 1L, intent("TRACE_FULL", "trace_full"), req, null);
        assertThat(resp).isNull();
        assertThat(req.getContext()).containsEntry("batchNumber", "PB-20261105-001");
        assertThat(req.getContext()).doesNotContainKey("salesOrderNo");
        assertThat(llmPrompts).as("批次号没有规则可抽, LLM 兜底必须被叫到").hasSize(1);
        assertThat(llmPrompts.get(0)).contains("salesOrderNo").contains("batchNumber");

        // 销售单号规则只认带 SO 段的编号: 普通批次号不会被它截走
        assertThat(Pattern.compile(slotPattern("TRACE_FULL", "salesOrderNo")).matcher("批次 DEMO-20261105-001").find())
                .isFalse();
    }

    @Test
    @DisplayName("39 链路: 填槽出来的参数交给 trace_full 真工具 → 按销售单查到整条链(原料/生产/质检/出货), 不再 NEED_MORE_INFO")
    void extractedSalesOrderNoDrivesTheRealToolToTheWholeChain() throws Exception {
        IntentExecuteRequest req = request(SAID_39);
        assertThat(service.checkAndStartSlotFilling(F, 1L, intent("TRACE_FULL", "trace_full"), req, null)).isNull();

        SalesOrderRepository salesOrderRepository = mock(SalesOrderRepository.class);
        SalesService salesService = mock(SalesService.class);
        ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
        TraceabilityService traceability = mock(TraceabilityService.class);
        SalesOrderTraceService trace = new SalesOrderTraceService(salesOrderRepository, salesService, planRepository,
                batchRepository, traceability);
        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        order.setOrderNumber(SO_NO);
        order.setCustomerName("上海示例超市");
        when(salesOrderRepository.findByFactoryIdAndOrderNumber(F, SO_NO)).thenReturn(Optional.of(order));
        when(salesService.getSalesOrderById(F, "so-1")).thenReturn(order);
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-1");
        plan.setPlanNumber("PLAN-001");
        when(planRepository.findByFactoryIdAndSourceOrderIdExact(F, "so-1")).thenReturn(List.of(plan));
        when(planRepository.findByFactoryIdAndSourceOrderIdsContaining(anyString(), anyString())).thenReturn(List.of());
        ProductionBatch pb = new ProductionBatch();
        pb.setBatchNumber("PB-001");
        pb.setProductionPlanId("plan-1");
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(F, List.of("plan-1"))).thenReturn(List.of(pb));
        when(batchRepository.findByFactoryIdAndBatchNumber(F, SO_NO)).thenReturn(Optional.empty());
        when(traceability.getFullTrace(F, "PB-001")).thenReturn(TraceabilityDTO.FullTraceResponse.builder()
                .production(TraceabilityDTO.ProductionInfo.builder().batchNumber("PB-001").productName("酱牛肉").build())
                .materials(List.of(TraceabilityDTO.MaterialInfo.builder()
                        .batchNumber("MB-100").materialName("牛腱").supplierName("北方牧业").build()))
                .qualityInspections(List.of(TraceabilityDTO.QualityInfo.builder().result("PASS").build()))
                .shipments(List.of(TraceabilityDTO.ShipmentInfo.builder()
                        .shipmentNumber("SH-001").customerName("上海示例超市").build()))
                .build());
        ReflectionTestUtils.setField(fullTool, "traceabilityService", traceability);
        ReflectionTestUtils.setField(fullTool, "salesOrderTraceService", trace);
        ReflectionTestUtils.setField(fullTool, "objectMapper", mapper);

        Map<String, Object> params = new HashMap<>();
        req.getContext().forEach((k, v) -> params.put(k, v));
        String json = fullTool.execute(ToolCall.of("t", "trace_full", mapper.writeValueAsString(params)),
                Map.of("factoryId", F, "userId", 1L));

        JsonNode r = mapper.readTree(json);
        assertThat(json).doesNotContain("NEED_MORE_INFO");
        assertThat(r.path("success").asBoolean()).as(json).isTrue();
        assertThat(json).contains("MB-100").contains("PB-001").contains("SH-001");
    }

    // ── 问题 34 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("34 入口: 「9月20号发给上海连锁餐饮的酱卤牛肉是哪批原料做的」→ 日期之外客户名也进请求上下文, 不反问哪个客户")
    void customerNameIsExtractedTogetherWithTheDate() {
        IntentExecuteRequest req = request(SAID_34);

        IntentExecuteResponse resp = service.checkAndStartSlotFilling(F, 1L,
                intent("BATCH_TRACE_BY_CUSTOMER", "batch_trace_by_customer_date"), req, null);

        assertThat(resp).isNull();
        assertThat(req.getContext()).containsEntry("date", "2026-09-20");
        assertThat(req.getContext()).as("客户名在原话里, 必须到得了工具").containsEntry("customerName", "上海连锁餐饮");
        assertThat(llmPrompts).hasSize(1);
        assertThat(llmPrompts.get(0)).contains("customerName");
    }

    @Test
    @DisplayName("34 对照: 请求上下文已经带了客户名 → 不再为它多叫一次 LLM, 也不覆盖")
    void customerAlreadyGivenIsNotExtractedAgain() {
        IntentExecuteRequest req = request(SAID_34);
        req.getContext().put("customerName", "某某食品");
        req.getContext().put("date", "2026-09-20");

        IntentExecuteResponse resp = service.checkAndStartSlotFilling(F, 1L,
                intent("BATCH_TRACE_BY_CUSTOMER", "batch_trace_by_customer_date"), req, null);

        assertThat(resp).isNull();
        assertThat(req.getContext()).containsEntry("customerName", "某某食品");
        verify(llm, never()).chatLowTemp(anyString(), anyString());
    }

    // ── 声明本身 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("三个工具的 schema 声明了「至少给一个」组, 与各自 validateRequiredParams 的判据一致")
    void toolsDeclareTheAnyOfGroupsTheirValidationUses() {
        assertThat(ToolParameterGroups.groups(fullTool.getParametersSchema()))
                .containsExactly(List.of("batchNumber", "salesOrderNo"));
        assertThat(ToolParameterGroups.groups(batchTool.getParametersSchema()))
                .containsExactly(List.of("batchNumber", "salesOrderNo"));
        assertThat(ToolParameterGroups.groups(customerDateTool.getParametersSchema()))
                .containsExactly(List.of("customerId", "customerName"));
    }

    private String slotPattern(String intentCode, String slotName) {
        IntentSlotConfiguration config = new IntentSlotConfiguration();
        config.init();
        return config.getRequiredSlots(intentCode).stream().filter(s -> s.getName().equals(slotName))
                .findFirst().orElseThrow().getPattern();
    }
}
