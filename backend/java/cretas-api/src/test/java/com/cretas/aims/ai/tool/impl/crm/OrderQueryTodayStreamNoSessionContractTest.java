package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.SalesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hibernate.collection.spi.PersistentBag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 同 OrderListStreamNoSessionContractTest: 流式线程无 Session, order_query / order_today 不能把含 LAZY items 的实体放进 data。 */
class OrderQueryTodayStreamNoSessionContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static SalesOrder lazyOrder() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-ID-1");
        order.setOrderNumber("SO-001");
        order.setCustomerName("测试客户");
        order.setOrderDate(LocalDate.now());
        order.setTotalAmount(new BigDecimal("88.80"));
        order.setItems(new PersistentBag<>(null));
        return order;
    }

    private static JsonNode run(com.cretas.aims.ai.tool.AbstractTool tool, String name, String args) throws Exception {
        ReflectionTestUtils.setField(tool, "objectMapper", MAPPER);
        ToolCall call = new ToolCall();
        ToolCall.FunctionCall function = new ToolCall.FunctionCall();
        function.setName(name);
        function.setArguments(args);
        call.setFunction(function);
        return MAPPER.readTree(tool.execute(call, Map.of("factoryId", "F001", "userId", 42L)));
    }

    @Test
    void orderQueryListDetailAndTodaySerializeWithoutSession() throws Exception {
        SalesService salesService = mock(SalesService.class);
        SalesOrderRepository repository = mock(SalesOrderRepository.class);
        SalesOrder order = lazyOrder();
        when(salesService.getSalesOrders(eq("F001"), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(order), 1, 20, 1L));
        when(salesService.getSalesOrderById("F001", "SO-ID-1")).thenReturn(order);
        when(repository.findByFactoryIdAndDateRange(eq("F001"), eq(LocalDate.now()), eq(LocalDate.now())))
                .thenReturn(List.of(order));
        OrderQueryTool tool = new OrderQueryTool(salesService, repository,
                mock(com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository.class));

        JsonNode page = run(tool, "order_query", "{}");
        assertThat(page.path("success").asBoolean()).as(page.toString()).isTrue();
        assertThat(page.path("data").path("orders").path(0).path("orderNumber").asText()).isEqualTo("SO-001");

        JsonNode detail = run(tool, "order_query", "{\"orderId\":\"SO-ID-1\"}");
        assertThat(detail.path("success").asBoolean()).as(detail.toString()).isTrue();
        assertThat(detail.path("data").path("order").path("totalAmount").decimalValue()).isEqualByComparingTo("88.80");

        JsonNode today = run(tool, "order_query", "{\"queryType\":\"ORDER_TODAY\"}");
        assertThat(today.path("success").asBoolean()).as(today.toString()).isTrue();
        assertThat(today.path("data").path("orders").path(0).path("customerName").asText()).isEqualTo("测试客户");
    }

    @Test
    void orderTodaySerializesWithoutSession() throws Exception {
        SalesOrderRepository repository = mock(SalesOrderRepository.class);
        when(repository.findByFactoryIdAndDateRange(eq("F001"), eq(LocalDate.now()), eq(LocalDate.now())))
                .thenReturn(List.of(lazyOrder()));

        JsonNode response = run(new OrderTodayTool(repository), "order_today", "{}");

        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        assertThat(response.path("data").path("orders").path(0).path("orderNumber").asText()).isEqualTo("SO-001");
        assertThat(response.path("data").path("orders").path(0).has("items")).isFalse();
    }
}
