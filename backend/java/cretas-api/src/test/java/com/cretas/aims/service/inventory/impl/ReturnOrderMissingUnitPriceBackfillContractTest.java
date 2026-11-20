package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateReturnOrderRequest;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺价回填(Steve 2026-11-17): 前端退货弹窗缺价/被遮时不再发 0(#1796), 单价为 null 送到后端。
 * 创建销售退货单时按来源销售订单行(按 sourceOrderId + factoryId 查)单价回填, 行金额与总额按回填后的价算;
 * 找不到来源订单行价直接拒绝创建(422 RETURN_UNIT_PRICE_MISSING), ⛔ 不落 0。
 *
 * <p>走产品真实入口 {@link ReturnOrderServiceImpl#createReturnOrder}; 用无货退款(withGoods=false)避开批次证据校验,
 * 回填逻辑与是否带货无关。
 */
@DisplayName("销售退货缺价回填: null 按订单行价回填 / 订单行也无价拒绝 / 真 0 与正常价不变")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnOrderMissingUnitPriceBackfillContractTest {

    private static final String F = "F-RTPRICE";
    private static final String SO = "SO-RTPRICE-1";

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;

    private ReturnOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReturnOrderServiceImpl(
                returnOrderRepository, returnOrderItemRepository, arApService, applicationEventPublisher);
        ReflectionTestUtils.setField(service, "salesOrderItemRepository", salesOrderItemRepository);
        when(returnOrderRepository.save(any(ReturnOrder.class))).thenAnswer(inv -> {
            ReturnOrder o = inv.getArgument(0);
            if (o.getId() == null) o.setId("RO-1");
            return o;
        });
        when(returnOrderItemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("单价 null → 按来源订单行价 12.50 回填: 行金额 = 4 × 12.50 = 50.00, 总额同步; 另一行带价的不动")
    void nullPriceBackfilledFromSourceOrderLine() {
        orderLines(line("PT-A", "12.50"), line("PT-B", "99"));

        ReturnOrder order = service.createReturnOrder(F, request(item("PT-A", "4", null), item("PT-B", "1", "3.00")), 7L);

        List<ReturnOrderItem> saved = savedItems();
        assertThat(saved.get(0).getUnitPrice()).isEqualByComparingTo("12.50");
        assertThat(saved.get(0).getLineAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.get(1).getUnitPrice()).as("带价行不被订单价覆盖").isEqualByComparingTo("3.00");
        assertThat(saved.get(1).getLineAmount()).isEqualByComparingTo("3.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("53.00");
        verify(salesOrderItemRepository).findBySalesOrderIdAndSalesOrderFactoryId(SO, F);
    }

    @Test
    @DisplayName("单价 null 且来源订单行也没价 → 422 拒绝创建, 不保存任何退货单/明细")
    void nullPriceWithoutOrderLinePriceIsRejected() {
        orderLines(line("PT-A", null));

        assertThatThrownBy(() -> service.createReturnOrder(F, request(item("PT-A", "4", null)), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少单价")
                .hasMessageContaining("不能按 0");
        verify(returnOrderRepository, never()).save(any());
        verify(returnOrderItemRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("单价 null 且来源订单里没有这个产品(含别厂订单: 按 factoryId 查不到行) → 422 拒绝")
    void nullPriceWithoutMatchingOrderLineIsRejected() {
        orderLines(); // 别厂订单 / 没有这个产品: 带 factoryId 的查询返回空

        assertThatThrownBy(() -> service.createReturnOrder(F, request(item("PT-A", "4", null)), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("找不到这个产品的订单行");
    }

    @Test
    @DisplayName("单价 null 且同产品多行价格不一 → 422 拒绝(不猜按哪个价)")
    void ambiguousOrderLinePriceIsRejected() {
        orderLines(line("PT-A", "10"), line("PT-A", "11"));

        assertThatThrownBy(() -> service.createReturnOrder(F, request(item("PT-A", "1", null)), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单价不一致");
    }

    @Test
    @DisplayName("真 0(显式填 0) 与正常价不受影响: 不查订单行, 原价落库")
    void explicitZeroAndNormalPriceUnchanged() {
        orderLines(line("PT-A", "88"));

        ReturnOrder order = service.createReturnOrder(F, request(item("PT-A", "2", "0"), item("PT-A", "3", "5.00")), 7L);

        List<ReturnOrderItem> saved = savedItems();
        assertThat(saved.get(0).getUnitPrice()).isEqualByComparingTo("0");
        assertThat(saved.get(0).getLineAmount()).isEqualByComparingTo("0");
        assertThat(saved.get(1).getLineAmount()).isEqualByComparingTo("15.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("15.00");
        verify(salesOrderItemRepository, never()).findBySalesOrderIdAndSalesOrderFactoryId(any(), any());
    }

    private void orderLines(SalesOrderItem... lines) {
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId(SO, F))
                .thenReturn(new ArrayList<>(List.of(lines)));
    }

    @SuppressWarnings("unchecked")
    private List<ReturnOrderItem> savedItems() {
        ArgumentCaptor<List<ReturnOrderItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(returnOrderItemRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static SalesOrderItem line(String productTypeId, String unitPrice) {
        SalesOrderItem oi = new SalesOrderItem();
        oi.setProductTypeId(productTypeId);
        oi.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return oi;
    }

    private static CreateReturnOrderRequest.ReturnOrderItemDTO item(String productTypeId, String qty, String unitPrice) {
        CreateReturnOrderRequest.ReturnOrderItemDTO dto = new CreateReturnOrderRequest.ReturnOrderItemDTO();
        dto.setProductTypeId(productTypeId);
        dto.setItemName("退货-" + productTypeId);
        dto.setQuantity(new BigDecimal(qty));
        dto.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return dto;
    }

    private static CreateReturnOrderRequest request(CreateReturnOrderRequest.ReturnOrderItemDTO... items) {
        CreateReturnOrderRequest r = new CreateReturnOrderRequest();
        r.setReturnType(ReturnType.SALES_RETURN.name());
        r.setCounterpartyId("CUST-1");
        r.setSourceOrderId(SO);
        r.setReturnDate(java.time.LocalDate.now());
        r.setWithGoods(false);
        r.setItems(List.of(items));
        return r;
    }
}
