package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.inventory.SalesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.cretas.aims.ai.dto.ToolCall;
import org.hibernate.collection.spi.PersistentBag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式入口 (线程 intent-sse) 没有 Hibernate Session: ORDER_LIST 把 JPA 实体放进 data, 序列化到懒加载的
 * SalesOrder.items 抛 LazyInitializationException, 用户看到「结果处理失败」(2026-11-18 prod, 演示 B8)。
 * 这里用没有 Session 的未初始化 PersistentBag 复现, 走产品真实入口 execute() -> buildSuccessResult。
 */
class OrderListStreamNoSessionContractTest {

    @Test
    void executeSerializesOrdersWithoutTouchingLazyItems() throws Exception {
        SalesService salesService = mock(SalesService.class);
        SalesOrder order = new SalesOrder();
        order.setId("SO-ID-1");
        order.setOrderNumber("SO-001");
        order.setCustomerId("C1");
        order.setCustomerName("测试客户");
        order.setOrderDate(LocalDate.of(2026, 11, 18));
        order.setTotalAmount(new BigDecimal("123.45"));
        // 无 Session 的未初始化集合: 任何访问都抛 LazyInitializationException (= 流式线程上的 items)
        order.setItems(new PersistentBag<>(null));
        when(salesService.getSalesOrders(eq("F001"), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(order), 1, 20, 1L));
        when(salesService.getSalesOrdersFiltered(eq("F001"), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(order), 1, 20, 1L));

        OrderListTool tool = new OrderListTool(salesService);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);

        ToolCall call = new ToolCall();
        ToolCall.FunctionCall function = new ToolCall.FunctionCall();
        function.setName("order_list");
        function.setArguments("{}");
        call.setFunction(function);

        JsonNode response = mapper.readTree(tool.execute(call, Map.of("factoryId", "F001", "userId", 42L)));

        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        JsonNode first = response.path("data").path("orders").path(0);
        assertThat(first.path("orderNumber").asText()).isEqualTo("SO-001");
        assertThat(first.path("customerName").asText()).isEqualTo("测试客户");
        assertThat(first.path("totalAmount").decimalValue()).isEqualByComparingTo("123.45");
        assertThat(first.has("items")).isFalse();
    }
}
