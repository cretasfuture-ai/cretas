package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link BatchTraceByCustomerDateTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class BatchTraceByCustomerDateToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String CUSTOMER_ID = "cust-001";

    @InjectMocks
    private BatchTraceByCustomerDateTool tool;

    @Mock
    private ShipmentRecordRepository shipmentRecordRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private com.cretas.aims.service.TraceabilityService traceabilityService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-BTCD-01: metadata + tool name")
    void metadata() {
        assertEquals("batch_trace_by_customer_date", tool.getToolName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("反查"));
        assertEquals(List.of("date"), tool.getRequiredParameters());
    }

    // ── BP 问题 34: 出货日期 + 客户名 + 产品 → 出货 → 成品批次 → 原料批次 ──

    private static final String DEMO_CUSTOMER_ID = "cust-sh-chain";
    private static final String DEMO_CUSTOMER_NAME = "上海连锁餐饮有限公司";

    private Customer demoCustomer() {
        Customer c = new Customer();
        c.setId(DEMO_CUSTOMER_ID);
        c.setName(DEMO_CUSTOMER_NAME);
        return c;
    }

    private ShipmentRecord demoShipment(LocalDate d, String product, String batch) {
        ShipmentRecord s = new ShipmentRecord();
        s.setShipmentNumber("SH-" + d);
        s.setBatchNumber(batch);
        s.setProductName(product);
        s.setShipmentDate(d);
        s.setQuantity(new BigDecimal("30"));
        s.setUnit("kg");
        return s;
    }

    private String executeJson(Map<String, Object> params) throws Exception {
        com.cretas.aims.ai.dto.ToolCall call = com.cretas.aims.ai.dto.ToolCall.builder()
                .id("t1")
                .function(com.cretas.aims.ai.dto.ToolCall.FunctionCall.builder()
                        .name(tool.getToolName())
                        .arguments(objectMapper.writeValueAsString(params))
                        .build())
                .build();
        return tool.execute(call, ctx());
    }

    @Test
    @DisplayName("UT-BTCD-05: 原话「11月5号发给上海连锁餐饮的酱卤牛肉」: 只给客户名不反问客户, 走到原料批次")
    @SuppressWarnings("unchecked")
    void customerNameInQuestionIsUsedAndTracesToRawMaterial() throws Exception {
        when(customerRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(demoCustomer()));
        LocalDate d = LocalDate.of(2026, 11, 5);
        when(shipmentRecordRepository.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(
                FACTORY_ID, DEMO_CUSTOMER_ID))
                .thenReturn(List.of(demoShipment(d, "酱卤牛肉", "FG-20261105-01"),
                        demoShipment(d, "卤猪蹄", "FG-20261105-02")));
        TraceabilityDTO.MaterialInfo mat = new TraceabilityDTO.MaterialInfo();
        mat.setMaterialName("牛腱");
        mat.setBatchNumber("RM-20261101-07");
        mat.setSupplierName("某牧业");
        TraceabilityDTO.FullTraceResponse trace = new TraceabilityDTO.FullTraceResponse();
        trace.setMaterials(List.of(mat));
        when(traceabilityService.getFullTrace(FACTORY_ID, "FG-20261105-01")).thenReturn(trace);

        Map<String, Object> params = new HashMap<>();
        params.put("customerName", "上海连锁餐饮");
        params.put("productName", "酱卤牛肉");
        params.put("date", "11月5号");
        String json = executeJson(params);

        assertFalse(json.contains("NEED_MORE_INFO"), "客户已在问句里, 不得反问: " + json);
        assertTrue(json.contains("\\\"success\\\":true") || json.contains("\"success\":true"), json);
        assertTrue(json.contains("RM-20261101-07"), "要给出原料批次: " + json);
        assertTrue(json.contains("牛腱"), json);
        assertFalse(json.contains("FG-20261105-02"), "产品过滤: 卤猪蹄批次不应出现: " + json);
        org.mockito.Mockito.verify(traceabilityService, org.mockito.Mockito.never())
                .getFullTrace(FACTORY_ID, "FG-20261105-02");
    }

    @Test
    @DisplayName("UT-BTCD-06: 原话「9月20号」: 该日没有出货时如实说按哪个日期/客户/产品查的, 不反问客户")
    void emptyDayStatesWhatWasQueried() throws Exception {
        when(customerRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(demoCustomer()));
        when(shipmentRecordRepository.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(
                FACTORY_ID, DEMO_CUSTOMER_ID))
                .thenReturn(List.of(demoShipment(LocalDate.of(2026, 11, 5), "酱卤牛肉", "FG-20261105-01")));

        Map<String, Object> params = new HashMap<>();
        params.put("customerName", "上海连锁餐饮");
        params.put("productName", "酱卤牛肉");
        params.put("date", "9月20号");
        String json = executeJson(params);

        assertFalse(json.contains("NEED_MORE_INFO"), json);
        assertTrue(json.contains("-09-20"), "回复要写明查的日期: " + json);
        assertTrue(json.contains(DEMO_CUSTOMER_NAME) && json.contains("酱卤牛肉"), json);
        assertTrue(json.contains("没有"), json);
        assertTrue(json.contains("2026-11-05"), "给出该客户最近有出货的日期: " + json);
    }

    @Test
    @DisplayName("UT-BTCD-07: 阴性对照 — 问句没有客户时才追问客户")
    void missingCustomerStillAsksForCustomer() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("productName", "酱卤牛肉");
        params.put("date", "2026-11-05");
        String json = executeJson(params);

        assertTrue(json.contains("NEED_MORE_INFO"), json);
        assertTrue(json.contains("哪个客户"), json);
    }

    @Test
    @DisplayName("UT-BTCD-08: 找不到名称接近的客户 — 如实说没找到, 不挑一个不相干的客户")
    void unknownCustomerIsReportedNotGuessed() throws Exception {
        when(customerRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(demoCustomer()));
        Map<String, Object> params = new HashMap<>();
        params.put("customerName", "北京火锅店");
        params.put("date", "2026-11-05");
        String json = executeJson(params);

        assertTrue(json.contains("没有找到"), json);
        org.mockito.Mockito.verify(shipmentRecordRepository, org.mockito.Mockito.never())
                .findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(anyString(), anyString());
    }

    @Test
    @DisplayName("UT-BTCD-09: 日期宽松解析 — 缺年份取今年, 晚于今天取上一年")
    void lenientDateParsing() {
        LocalDate today = LocalDate.of(2026, 11, 19);
        assertEquals(LocalDate.of(2026, 9, 20), BatchTraceByCustomerDateTool.parseDate("9月20号", today));
        assertEquals(LocalDate.of(2026, 11, 5), BatchTraceByCustomerDateTool.parseDate("11月5日", today));
        assertEquals(LocalDate.of(2026, 11, 5), BatchTraceByCustomerDateTool.parseDate("11/5", today));
        assertEquals(LocalDate.of(2025, 12, 1), BatchTraceByCustomerDateTool.parseDate("12月1号", today));
        assertEquals(LocalDate.of(2026, 5, 18), BatchTraceByCustomerDateTool.parseDate("2026-05-18", today));
        assertNull(BatchTraceByCustomerDateTool.parseDate("BAD-DATE", today));
    }

    @Test
    @DisplayName("UT-BTCD-02: doExecute — finds shipments on date + extracts batch numbers")
    @SuppressWarnings("unchecked")
    void findsShipmentsOnDate() throws Exception {
        LocalDate target = LocalDate.of(2026, 5, 18);

        Customer cust = new Customer();
        cust.setId(CUSTOMER_ID);
        cust.setName("鲜湘缘餐厅");
        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(cust));

        ShipmentRecord s1 = new ShipmentRecord();
        s1.setShipmentNumber("SH-001");
        s1.setBatchNumber("B-20260518-A03");
        s1.setProductName("卤猪蹄");
        s1.setQuantity(new BigDecimal("50"));
        s1.setUnit("kg");
        s1.setShipmentDate(target);

        ShipmentRecord s2 = new ShipmentRecord();
        s2.setShipmentNumber("SH-002");
        s2.setBatchNumber("B-20260517-A01"); // 不同日期, 应被过滤
        s2.setShipmentDate(target.minusDays(1));

        when(shipmentRecordRepository.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(
                FACTORY_ID, CUSTOMER_ID))
                .thenReturn(List.of(s1, s2));

        Map<String, Object> params = Map.of("customerId", CUSTOMER_ID, "date", target.toString());
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("shipmentCount"));
        List<String> batchNumbers = (List<String>) data.get("batchNumbers");
        assertEquals(1, batchNumbers.size());
        assertEquals("B-20260518-A03", batchNumbers.get(0));
        assertEquals("鲜湘缘餐厅", data.get("customerName"));
    }

    @Test
    @DisplayName("UT-BTCD-03: doExecute — no shipments on date returns empty + actionHint")
    @SuppressWarnings("unchecked")
    void noShipmentsReturnsEmpty() throws Exception {
        Customer known = new Customer();
        known.setId(CUSTOMER_ID);
        known.setName("鲜湘缘餐厅");
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.of(known));
        when(shipmentRecordRepository.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(
                anyString(), anyString()))
                .thenReturn(List.of());

        Map<String, Object> params = Map.of("customerId", CUSTOMER_ID, "date", "2026-05-18");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("shipmentCount"));
        assertNotNull(data.get("actionHint"));
        assertTrue(data.get("actionHint").toString().contains(CUSTOMER_ID));
    }

    @Test
    @DisplayName("UT-BTCD-04: doExecute — invalid date format throws IllegalArgumentException")
    void invalidDateThrows() {
        Map<String, Object> params = Map.of("customerId", CUSTOMER_ID, "date", "BAD-DATE");
        assertThrows(IllegalArgumentException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
    }

    // ── helpers ──
    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), name);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, factoryId, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            clazz = clazz.getSuperclass();
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }
}
