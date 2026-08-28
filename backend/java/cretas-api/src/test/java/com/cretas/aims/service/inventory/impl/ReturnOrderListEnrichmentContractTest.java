package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退货单**列表**必须把对方名称 / 源销售单号解析出来，⛔ 不能把 UUID 甩给用户。
 *
 * <h4>🔴 2026-08-28 生产实测抓到</h4>
 *
 * 「销售退货」列表的「客户」「源订单」两列显示的是裸 UUID：
 * <pre>
 *   客户      3e8cc819-0d5b-4e4c-b327-c1fe50fd4c9e
 *   源订单    89967b19-e5a6-447c-bf88-5b2c073a545c
 * </pre>
 *
 * 前端**没写错**（它写的是 {@code counterpartyName || customerName || counterpartyId}），
 * 是后端把 {@code counterpartyName} / {@code sourceOrderNumber} 返成了 {@code null}。
 *
 * 成因是「机制在、没接上」：{@code getReturnOrderDetail} 早就会 enrich 这几个字段，
 * 而**列表这条路从来不调它** —— 详情页看得到名字，列表页看不到。
 *
 * <h4>⚠️ 这道闸同时守两件事</h4>
 *
 * <ol>
 *   <li><b>名字要填上</b> —— 否则用户又看到 UUID</li>
 *   <li><b>必须批量查</b> —— 列表一页 200 行，逐行 {@code findById} 就是 600 次查询。
 *       ⛔ 「把名字填上」如果靠 N+1 换来，那是修出另一个问题。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("退货单列表 — 展示字段 enrich 契约")
class ReturnOrderListEnrichmentContractTest {

    @Mock ReturnOrderRepository returnOrderRepository;
    @Mock ReturnOrderItemRepository returnOrderItemRepository;
    @Mock ArApService arApService;
    @Mock ApplicationEventPublisher applicationEventPublisher;
    @Mock CustomerRepository customerRepository;
    @Mock SalesOrderRepository salesOrderRepository;

    private ReturnOrderServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String CUSTOMER_A = "3e8cc819-0d5b-4e4c-b327-c1fe50fd4c9e";
    private static final String CUSTOMER_B = "d39cd351-3c40-4ccd-8729-c76cacec00c1";
    private static final String ORDER_A = "89967b19-e5a6-447c-bf88-5b2c073a545c";

    @BeforeEach
    void setUp() {
        service = new ReturnOrderServiceImpl(
                returnOrderRepository, returnOrderItemRepository, arApService, applicationEventPublisher);
        ReflectionTestUtils.setField(service, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(service, "salesOrderRepository", salesOrderRepository);
    }

    private static ReturnOrder salesReturn(String id, String counterpartyId, String sourceOrderId) {
        ReturnOrder o = new ReturnOrder();
        o.setId(id);
        o.setFactoryId(FACTORY);
        o.setReturnType(ReturnType.SALES_RETURN);
        o.setCounterpartyId(counterpartyId);
        o.setSourceOrderId(sourceOrderId);
        return o;
    }

    private static Customer customer(String id, String name) {
        Customer c = new Customer();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private static SalesOrder salesOrder(String id, String number) {
        SalesOrder so = new SalesOrder();
        so.setId(id);
        so.setOrderNumber(number);
        return so;
    }

    @Test
    @DisplayName("列表把客户名与源订单号解析出来（⛔ 不是把 UUID 甩给用户）")
    void listResolvesCounterpartyNameAndSourceOrderNumber() {
        List<ReturnOrder> rows = List.of(
                salesReturn("r1", CUSTOMER_A, ORDER_A),
                salesReturn("r2", CUSTOMER_B, null));
        when(returnOrderRepository.findByFactoryIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));
        when(customerRepository.findAllById(any()))
                .thenReturn(List.of(customer(CUSTOMER_A, "六膳门优选"), customer(CUSTOMER_B, "叮咚好食光")));
        when(salesOrderRepository.findAllById(any()))
                .thenReturn(List.of(salesOrder(ORDER_A, "SO-20260820-0007")));

        PageResponse<ReturnOrder> page = service.getReturnOrders(FACTORY, null, null, 1, 20);

        assertThat(page.getContent()).extracting(ReturnOrder::getCounterpartyName)
                .as("客户名没解析出来 —— 用户会重新看到裸 UUID")
                .containsExactly("六膳门优选", "叮咚好食光");
        assertThat(page.getContent().get(0).getSourceOrderNumber())
                .as("源订单号没解析出来").isEqualTo("SO-20260820-0007");
        // 阴性对照: 没有 sourceOrderId 的那一行不该被塞上别人的单号。
        assertThat(page.getContent().get(1).getSourceOrderNumber())
                .as("无源订单的行被填上了单号 —— 那是编数据").isNull();
    }

    @Test
    @DisplayName("⛔ 批量查，不是逐行 findById（列表 200 行不许打 600 次查询）")
    void enrichmentIsBatchedNotPerRow() {
        List<ReturnOrder> rows = List.of(
                salesReturn("r1", CUSTOMER_A, ORDER_A),
                salesReturn("r2", CUSTOMER_B, ORDER_A),
                salesReturn("r3", CUSTOMER_A, ORDER_A));
        when(returnOrderRepository.findByFactoryIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));
        when(customerRepository.findAllById(any()))
                .thenReturn(List.of(customer(CUSTOMER_A, "六膳门优选"), customer(CUSTOMER_B, "叮咚好食光")));
        when(salesOrderRepository.findAllById(any()))
                .thenReturn(List.of(salesOrder(ORDER_A, "SO-20260820-0007")));

        service.getReturnOrders(FACTORY, null, null, 1, 20);

        verify(customerRepository, times(1)).findAllById(any());
        verify(salesOrderRepository, times(1)).findAllById(any());
        // ⚠️ 阴性断言一律用 any() —— eq(...) 在实参为 null 时不匹配, 会让断言恒真。
        verify(customerRepository, never()).findById(any());
        verify(salesOrderRepository, never()).findById(any());
    }

    @Test
    @DisplayName("fail-soft：解析失败只影响名字，⛔ 不阻断列表")
    void enrichmentFailureDoesNotBreakTheList() {
        List<ReturnOrder> rows = List.of(salesReturn("r1", CUSTOMER_A, ORDER_A));
        when(returnOrderRepository.findByFactoryIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));
        when(customerRepository.findAllById(any())).thenThrow(new RuntimeException("db down"));
        when(salesOrderRepository.findAllById(any()))
                .thenReturn(List.of(salesOrder(ORDER_A, "SO-20260820-0007")));

        PageResponse<ReturnOrder> page = service.getReturnOrders(FACTORY, null, null, 1, 20);

        assertThat(page.getContent()).as("名称解析失败把整个列表也弄没了").hasSize(1);
        assertThat(page.getContent().get(0).getCounterpartyName()).isNull();
        // 一类失败不该拖垮另一类。
        assertThat(page.getContent().get(0).getSourceOrderNumber()).isEqualTo("SO-20260820-0007");
    }
}
