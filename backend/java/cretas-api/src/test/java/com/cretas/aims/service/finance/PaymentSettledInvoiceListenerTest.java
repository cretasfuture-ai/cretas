package com.cretas.aims.service.finance;

import com.cretas.aims.event.SalesOrderSettledEvent;
import com.cretas.aims.service.finance.impl.PaymentSettledInvoiceListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E-10 / #754: PaymentSettledInvoiceListener 单元测试.
 *
 * <p>结清事件仅保留兼容 no-op 日志；自动开票唯一由订单完成待办处理器负责。
 */
@ExtendWith(MockitoExtension.class)
class PaymentSettledInvoiceListenerTest {

    private PaymentSettledInvoiceListener listener;

    private static final String FACTORY_ID = "F006";
    private static final String ORDER_ID = "SO-TEST-001";

    @BeforeEach
    void setUp() {
        listener = new PaymentSettledInvoiceListener();
    }

    private SalesOrderSettledEvent event() {
        return new SalesOrderSettledEvent(this, FACTORY_ID, ORDER_ID, new BigDecimal("113.00"));
    }

    @Test
    @DisplayName("E-10-01: settlement event is a compatibility no-op")
    void settled_noOp_neverRequestsInvoice() {
        listener.onSalesOrderSettled(event());
    }

    @Test
    @DisplayName("E-10-02: null event remains a no-op")
    void nullEvent_noOp() {
        listener.onSalesOrderSettled(null);
    }
}
