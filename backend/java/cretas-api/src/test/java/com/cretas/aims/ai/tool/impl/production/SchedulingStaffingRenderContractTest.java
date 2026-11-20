package com.cretas.aims.ai.tool.impl.production;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.report.ReportSchedulingListTool;
import com.cretas.aims.ai.tool.impl.scheduling.SchedulingListTool;
import com.cretas.aims.dto.scheduling.LineScheduleDTO;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP 问题 44/45/37: 排产回答要给出每个产品所需人数与班次; 数量与单位紧挨; 内部产线码 (DEDICATED_LINE_A 等) 不外露;
 * 排班计划列表在流式线程 (无 Hibernate Session) 下不碰懒加载。全部走 Tool 真实入口 execute()。
 */
class SchedulingStaffingRenderContractTest {

    private static final String F = "F006";
    private static final Pattern INTERNAL_CODE = Pattern.compile("\\b[A-Z]+(_[A-Z]+)+\\b");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private SalesOrderRepository orders;
    private SalesOrderItemRepository items;
    private ProductionBatchRepository batches;
    private ProductionLineRepository lines;
    private ProductionLineCapacityConfigRepository caps;
    private ProductionDemandQueryTool demandTool;

    @BeforeEach
    void setUp() {
        orders = mock(SalesOrderRepository.class);
        items = mock(SalesOrderItemRepository.class);
        batches = mock(ProductionBatchRepository.class);
        lines = mock(ProductionLineRepository.class);
        caps = mock(ProductionLineCapacityConfigRepository.class);
        demandTool = new ProductionDemandQueryTool();
        ReflectionTestUtils.setField(demandTool, "salesOrderRepository", orders);
        ReflectionTestUtils.setField(demandTool, "salesOrderItemRepository", items);
        ReflectionTestUtils.setField(demandTool, "productionBatchRepository", batches);
        ReflectionTestUtils.setField(demandTool, "productionLineRepository", lines);
        ReflectionTestUtils.setField(demandTool, "capacityConfigRepository", caps);
        ReflectionTestUtils.setField(demandTool, "objectMapper", mapper);

        SalesOrder so = new SalesOrder();
        so.setId("SO1");
        so.setOrderNumber("SO-001");
        so.setStatus(SalesOrderStatus.CONFIRMED);
        when(orders.findByFactoryIdAndStatusInOrderByCreatedAtDesc(eq(F), any(), any())).thenReturn(List.of(so));
        when(items.findBySalesOrderId("SO1")).thenReturn(List.of(
                item("PT-1", "香辣牛肉酱", "2000", "盒"),
                item("PT-2", "藤椒鸡丁", "300", "kg"),
                item("PT-3", "红烧肉", "50", "件")));
        when(batches.findByFactoryIdAndCreatedAtAfter(eq(F), any())).thenReturn(List.of());

        // PT-1: 专用行 100 盒/小时, 产线「A 专线」标准 6 人 -> 20 小时 -> 3 班
        when(caps.findEffectiveForProduct(eq(F), eq("PT-1"), any()))
                .thenReturn(List.of(cap("L1", "PT-1", "100", ProductionLineCapacityConfig.CapacityUnit.PACKAGE, "盒")));
        // PT-2: 通用行 50 kg/小时, 产线没配标准人数 -> 6 小时 -> 1 班, 缺标准人数
        when(caps.findEffectiveForProduct(eq(F), eq("PT-2"), any()))
                .thenReturn(List.of(cap("L2", null, "50", ProductionLineCapacityConfig.CapacityUnit.KG, null)));
        // PT-3: 没有任何产能配置
        when(caps.findEffectiveForProduct(eq(F), eq("PT-3"), any())).thenReturn(List.of());
        when(lines.findByIdAndFactoryIdAndDeletedAtIsNull("L1", F)).thenReturn(Optional.of(line("L1", "DEDICATED_LINE_A", 6)));
        when(lines.findByIdAndFactoryIdAndDeletedAtIsNull("L2", F)).thenReturn(Optional.of(line("L2", "二号线", null)));
    }

    @Test
    void staffingAndShiftsAppearPerProductWithoutInternalCodes() throws Exception {
        JsonNode r = run(demandTool, "production_demand_query", "{}");
        assertThat(r.path("success").asBoolean()).as(r.toString()).isTrue();
        String text = r.toString();

        JsonNode beef = product(r, "香辣牛肉酱");
        assertThat(beef.path("requiredWorkersPerShift").asInt()).isEqualTo(6);
        assertThat(beef.path("requiredShifts").asInt()).isEqualTo(3);
        assertThat(beef.path("recommendedLineName").asText()).isEqualTo("A 专线");

        JsonNode chicken = product(r, "藤椒鸡丁");
        assertThat(chicken.path("requiredShifts").asInt()).isEqualTo(1);
        assertThat(chicken.has("requiredWorkersPerShift")).isFalse();
        assertThat(chicken.path("staffingMissing").toString()).contains("标准人数");

        JsonNode pork = product(r, "红烧肉");
        assertThat(pork.has("requiredShifts")).isFalse();
        assertThat(pork.path("staffingMissing").toString()).contains("小时产能");

        String message = r.path("data").path("message").asText();
        assertThat(summaryRow(message, "香辣牛肉酱")).contains("2000 盒", "3 个班", "每班 6 人");
        assertThat(summaryRow(message, "藤椒鸡丁")).contains("300 kg", "1 个班", "人数待定");
        assertThat(summaryRow(message, "红烧肉")).contains("50 件", "班次待估", "人数待定");
        assertThat(productDetail(message, "藤椒鸡丁")).contains("缺", "标准人数");
        assertThat(productDetail(message, "红烧肉")).contains("缺", "小时产能");
        assertThat(message).contains("扣除发货量和在产量", "人数按产线配置", "每班 8 小时");
        assertThat(text).doesNotContain("DEDICATED_LINE_A");
        Matcher m = INTERNAL_CODE.matcher(text);
        assertThat(m.find()).as("internal code leaked: " + (m.find(0) ? m.group() : "") + " in " + message).isFalse();
    }

    @Test
    void reportsWhenOnlySomeMatchingProductsAreListed() throws Exception {
        JsonNode limited = run(demandTool, "production_demand_query", "{\"maxRows\":1,\"productNames\":[\"香辣牛肉酱\",\"藤椒鸡丁\"]}");
        assertThat(limited.path("data").path("message").asText())
                .contains("2 个产品", "本次列出 1 项", "香辣牛肉酱").doesNotContain("藤椒鸡丁", "红烧肉");

        JsonNode complete = run(demandTool, "production_demand_query", "{\"maxRows\":1,\"productNames\":[\"藤椒鸡丁\"]}");
        assertThat(complete.path("data").path("message").asText()).contains("藤椒鸡丁").doesNotContain("本次列出");
    }

    @Test
    void productNamesCarryOverFromPreviousTurnAndUnmatchedListsCandidates() throws Exception {
        JsonNode some = run(demandTool, "production_demand_query", "{\"productNames\":[\"香辣牛肉酱\",\"红烧肉\"]}");
        assertThat(some.toString()).contains("香辣牛肉酱").contains("红烧肉").doesNotContain("藤椒鸡丁");

        JsonNode none = run(demandTool, "production_demand_query", "{\"productNames\":[\"不存在的产品\"]}");
        assertThat(none.toString()).contains("最近待排产的产品有").contains("藤椒鸡丁").contains("请告诉我是哪几个");
    }

    @Test
    void batchCreatePreviewLabelDoesNotExposeLineCode() {
        assertThat(ProductionLineLabels.display("DEDICATED_LINE_A")).isEqualTo("A 专线");
        assertThat(ProductionLineLabels.display("SOME_NEW_LINE")).doesNotMatch(INTERNAL_CODE.pattern());
        assertThat(ProductionLineLabels.display("包装二线")).isEqualTo("包装二线");
    }

    @Test
    void schedulingListToolsSerializeWithoutTouchingLazyAssociationsOnStreamThread() throws Exception {
        SchedulingService service = mock(SchedulingService.class);
        SchedulingPlanDTO plan = new SchedulingPlanDTO() {
            @Override
            public List<LineScheduleDTO> getLineSchedules() {
                throw new LazyInitializationException("no Session on intent-sse thread");
            }
        };
        plan.setId("P1");
        plan.setPlanName("11 月 18 日排产");
        plan.setPlanDate(LocalDate.of(2026, 11, 18));
        plan.setStatus("confirmed");
        when(service.getPlans(anyString(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(plan)));

        SchedulingListTool list = new SchedulingListTool();
        ReportSchedulingListTool report = new ReportSchedulingListTool();
        for (Object tool : new Object[]{list, report}) {
            ReflectionTestUtils.setField(tool, "schedulingService", service);
            ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        }
        for (var entry : Map.of("scheduling_list", list, "report_scheduling_list", report).entrySet()) {
            JsonNode r = run((com.cretas.aims.ai.tool.AbstractBusinessTool) entry.getValue(), entry.getKey(), "{}");
            assertThat(r.path("success").asBoolean()).as(entry.getKey() + ": " + r).isTrue();
            assertThat(r.toString()).contains("已确认").doesNotContain("confirmed").doesNotContain("lineSchedules");
        }
    }

    // ===== helpers =====

    private String summaryRow(String message, String product) {
        return message.lines().filter(line -> line.startsWith("| " + product + " |"))
                .findFirst().orElseThrow(() -> new AssertionError("排产表缺少 " + product + "：" + message));
    }

    private String productDetail(String message, String product) {
        return message.lines().filter(line -> line.startsWith(product + "："))
                .findFirst().orElseThrow(() -> new AssertionError("排产明细缺少 " + product + "：" + message));
    }

    private JsonNode run(com.cretas.aims.ai.tool.AbstractBusinessTool tool, String name, String args) throws Exception {
        ToolCall call = new ToolCall();
        ToolCall.FunctionCall fn = new ToolCall.FunctionCall();
        fn.setName(name);
        fn.setArguments(args);
        call.setFunction(fn);
        return mapper.readTree(tool.execute(call, Map.of("factoryId", F, "userId", 42L)));
    }

    private JsonNode product(JsonNode r, String name) {
        for (JsonNode p : r.path("data").path("products")) {
            if (name.equals(p.path("productName").asText())) return p;
        }
        for (JsonNode p : r.path("data").path("data").path("products")) {
            if (name.equals(p.path("productName").asText())) return p;
        }
        throw new AssertionError("product not found: " + name + " in " + r);
    }

    private SalesOrderItem item(String id, String name, String qty, String unit) {
        SalesOrderItem i = new SalesOrderItem();
        i.setProductTypeId(id);
        i.setProductName(name);
        i.setQuantity(new BigDecimal(qty));
        i.setDeliveredQuantity(BigDecimal.ZERO);
        i.setUnit(unit);
        return i;
    }

    private ProductionLineCapacityConfig cap(String lineId, String productId, String perHour,
                                             ProductionLineCapacityConfig.CapacityUnit unit, String pkg) {
        ProductionLineCapacityConfig c = new ProductionLineCapacityConfig();
        c.setProductionLineId(lineId);
        c.setProductTypeId(productId);
        c.setCapacityPerHour(new BigDecimal(perHour));
        c.setCapacityUnit(unit);
        c.setPackageUnit(pkg);
        return c;
    }

    private ProductionLine line(String id, String name, Integer standardWorkers) {
        ProductionLine l = new ProductionLine();
        l.setId(id);
        l.setName(name);
        l.setStandardWorkers(standardWorkers);
        return l;
    }
}
