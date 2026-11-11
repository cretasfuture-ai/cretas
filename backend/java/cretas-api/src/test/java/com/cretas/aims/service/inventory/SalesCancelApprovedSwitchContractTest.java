package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.ApprovedSalesOrderCancellation;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SalesServiceImpl.cancelOrder 在工厂开关 sales.order.cancel_after_approval 两侧的走向:
 * 关 (默认) ⇒ 财务已批准订单仍 409 且一行不改; 开 ⇒ 经 {@link ApprovedSalesOrderCancellation} 连带取消后置为已取消。
 * 草稿等本来就能取消的状态不经过开关。
 */
@DisplayName("销售单财务批准后取消: 开关两侧")
class SalesCancelApprovedSwitchContractTest {

    private static final String F = "F006";
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final ApprovedSalesOrderCancellation cancellation = mock(ApprovedSalesOrderCancellation.class);
    private SalesServiceImpl service;
    private SalesOrder order;

    @BeforeEach
    void setUp() {
        service = new SalesServiceImpl(orders, mock(SalesOrderItemRepository.class),
                mock(SalesDeliveryRecordRepository.class), mock(FinishedGoodsBatchRepository.class),
                mock(CustomerRepository.class), mock(ProductTypeRepository.class), mock(ArApService.class),
                mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "approvedSalesOrderCancellation", cancellation);
        order = new SalesOrder();
        order.setId("SO-1");
        order.setFactoryId(F);
        order.setOrderNumber("SO-20261111-001");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        when(orders.findById("SO-1")).thenReturn(Optional.of(order));
        when(orders.save(any(SalesOrder.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("开关关 (默认): 财务已批准仍 409, 不连带、不保存")
    void switchOffKeepsTheOldRefusal() {
        when(cancellation.enabled(F)).thenReturn(false);

        assertThatThrownBy(() -> service.cancelOrder(F, "SO-1", "客户撤单", 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("驳回/退款");
                });
        verify(cancellation, never()).cancelDownstream(anyString(), anyString(), any(), any());
        verify(orders, never()).save(any());
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.FINANCE_APPROVED);
    }

    @Test
    @DisplayName("开关开: 连带取消后置为已取消, 备注留痕, 回执带连带说明")
    void switchOnCancelsThroughTheDownstreamCascade() {
        when(cancellation.enabled(F)).thenReturn(true);
        when(cancellation.cancelDownstream(F, "SO-1", "客户撤单", 7L)).thenReturn(
                new ApprovedSalesOrderCancellation.Outcome(order, List.of("PP-A"), 2, 0, List.of(), false));

        SalesOrder saved = service.cancelOrder(F, "SO-1", "客户撤单", 7L);

        assertThat(saved.getStatus()).isEqualTo(SalesOrderStatus.CANCELLED);
        assertThat(saved.getRemark()).contains("[取消原因: 客户撤单]").contains("[财务批准后取消]");
        assertThat(service.lastCancelSummary()).contains("PP-A").contains("2 条未开工");
        assertThat(service.lastCancelSummary()).as("读一次就清掉, 不串到下一个请求").isNull();
    }

    @Test
    @DisplayName("开关开但连带取消被拦: 订单不改不保存")
    void aRefusedCascadeLeavesTheOrderAlone() {
        when(cancellation.enabled(F)).thenReturn(true);
        when(cancellation.cancelDownstream(F, "SO-1", "客户撤单", 7L))
                .thenThrow(new BusinessException(409, "生产计划 PP-A 已完成, 不能随订单取消"));

        assertThatThrownBy(() -> service.cancelOrder(F, "SO-1", "客户撤单", 7L)).isInstanceOf(BusinessException.class);
        verify(orders, never()).save(any());
        assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.FINANCE_APPROVED);
    }

    @Test
    @DisplayName("草稿本来就能取消: 不问开关, 不连带")
    void draftDoesNotTouchTheSwitch() {
        order.setStatus(SalesOrderStatus.DRAFT);

        assertThat(service.cancelOrder(F, "SO-1", "客户撤单", 7L).getStatus()).isEqualTo(SalesOrderStatus.CANCELLED);
        verify(cancellation, never()).enabled(anyString());
        verify(cancellation, never()).cancelDownstream(anyString(), anyString(), any(), any());
        assertThat(service.lastCancelSummary()).isNull();
    }

    @Test
    @DisplayName("开关开也只对财务已批准: 生产中 / 已发货照旧 409")
    void otherNonCancellableStatesStillRefuse() {
        when(cancellation.enabled(F)).thenReturn(true);
        order.setStatus(SalesOrderStatus.PROCESSING);
        assertThatThrownBy(() -> service.cancelOrder(F, "SO-1", "客户撤单", 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        verify(cancellation, never()).cancelDownstream(anyString(), anyString(), any(), any());
    }
}
