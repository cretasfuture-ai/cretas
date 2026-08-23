package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.entity.enums.InvoiceType;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.InvoiceRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.finance.impl.InvoiceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceImplAutoTodoTest {

    @Mock InvoiceRecordRepository invoiceRecordRepository;
    @Mock SalesOrderRepository salesOrderRepository;
    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock CustomerRepository customerRepository;
    @Mock OssService ossService;
    @Mock ApplicationEventPublisher eventPublisher;

    private InvoiceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InvoiceServiceImpl(invoiceRecordRepository, salesOrderRepository,
                salesOrderItemRepository, customerRepository, ossService, eventPublisher);
    }

    @Test
    void completedOrderWithExistingInvoicedAmount_isNotDuplicated() {
        SalesOrder order = new SalesOrder();
        order.setFactoryId("F006");
        order.setStatus(SalesOrderStatus.COMPLETED);
        order.setInvoicedAmount(new BigDecimal("100.00"));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-001", "F006"))
                .thenReturn(Optional.of(order));
        when(invoiceRecordRepository.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("SO-001"), anyList()))
                .thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId("SO-001"))
                .thenReturn(List.of(item("1", "100", "0")));

        assertThatThrownBy(() -> service.requestInvoiceFromOrder(
                "F006", "SO-001", "NORMAL", null, "auto"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不会重复生成");
    }

    @Test
    void partialInvoicedOrderUsesNonTerminalSemanticCode() {
        SalesOrder order = completedOrder(new BigDecimal("100.00"), new BigDecimal("20.00"));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-PARTIAL", "F006"))
                .thenReturn(Optional.of(order));
        when(invoiceRecordRepository.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("SO-PARTIAL"), anyList()))
                .thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId("SO-PARTIAL"))
                .thenReturn(List.of(item("1", "100", "0")));

        assertThatThrownBy(() -> service.requestInvoiceFromOrder(
                "F006", "SO-PARTIAL", null, null,
                InvoiceServiceImpl.AUTOMATIC_INVOICE_REMARK))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("AUTO_INVOICE_PARTIAL_CONFLICT"));
    }

    @Test
    void fullyInvoicedOrderUsesTerminalSemanticCode() {
        SalesOrder order = completedOrder(new BigDecimal("100.00"), new BigDecimal("100.00"));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-FULL", "F006"))
                .thenReturn(Optional.of(order));
        when(invoiceRecordRepository.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("SO-FULL"), anyList()))
                .thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId("SO-FULL"))
                .thenReturn(List.of(item("1", "100", "0")));

        assertThatThrownBy(() -> service.requestInvoiceFromOrder(
                "F006", "SO-FULL", null, null,
                InvoiceServiceImpl.AUTOMATIC_INVOICE_REMARK))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("AUTO_INVOICE_ALREADY_FULL"));
    }

    @Test
    void exactActiveAutomaticRecordIsReturnedForCrashReplay() {
        SalesOrder order = completedOrder(BigDecimal.ZERO, BigDecimal.ZERO);
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-REPLAY", "F006"))
                .thenReturn(Optional.of(order));
        InvoiceRecord existing = new InvoiceRecord();
        existing.setStatus(InvoiceStatus.REQUESTED);
        existing.setRemark(InvoiceServiceImpl.AUTOMATIC_INVOICE_REMARK);
        existing.setAmount(new BigDecimal("100.00"));
        existing.setTaxAmount(BigDecimal.ZERO);
        existing.setTotalAmount(new BigDecimal("100.00"));
        existing.setInvoiceType(InvoiceType.NORMAL);
        when(invoiceRecordRepository.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("SO-REPLAY"), anyList()))
                .thenReturn(List.of(existing));
        when(salesOrderItemRepository.findBySalesOrderId("SO-REPLAY"))
                .thenReturn(List.of(item("1", "100", "0")));

        InvoiceRecord replay = service.requestInvoiceFromOrder(
                "F006", "SO-REPLAY", null, null,
                InvoiceServiceImpl.AUTOMATIC_INVOICE_REMARK);

        assertThat(replay).isSameAs(existing);
        org.mockito.Mockito.verify(invoiceRecordRepository, never()).save(any(InvoiceRecord.class));
    }

    @Test
    void completedOrder_createsRequestedTodoUsingOrderInvoiceTypeAndTaxGroups() {
        SalesOrder order = new SalesOrder();
        order.setFactoryId("F006");
        order.setStatus(SalesOrderStatus.COMPLETED);
        order.setDefaultInvoiceType(InvoiceType.SPECIAL);
        order.setInvoicedAmount(BigDecimal.ZERO);

        SalesOrderItem rawMaterial = item("5", "20", "9");
        SalesOrderItem processingFee = item("2", "50", "13");

        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-002", "F006"))
                .thenReturn(Optional.of(order));
        when(invoiceRecordRepository.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("SO-002"), anyList()))
                .thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId("SO-002"))
                .thenReturn(List.of(rawMaterial, processingFee));
        when(invoiceRecordRepository.save(any(InvoiceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceRecord result = service.requestInvoiceFromOrder(
                "F006", "SO-002", null, null, "全量发货后自动生成");

        assertThat(result.getStatus()).isEqualTo(InvoiceStatus.REQUESTED);
        assertThat(result.getInvoiceType()).isEqualTo(InvoiceType.SPECIAL);
        assertThat(result.getTaxBreakdown()).hasSize(2);
        assertThat(result.getTaxBreakdown()).extracting(InvoiceRecord.TaxBreakdownEntry::getTaxRate)
                .containsExactly(new BigDecimal("9.00"), new BigDecimal("13.00"));
        assertThat(result.getAmount()).isEqualByComparingTo("200.00");
        assertThat(result.getTaxAmount()).isEqualByComparingTo("22.00");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("222.00");
    }

    @Test
    void completedOrder_withoutOrderDefault_usesCustomerSpecialInvoiceType() {
        SalesOrder order = new SalesOrder();
        order.setFactoryId("F006");
        order.setCustomerId("CUSTOMER-001");
        order.setStatus(SalesOrderStatus.COMPLETED);
        order.setInvoicedAmount(BigDecimal.ZERO);
        Customer customer = new Customer();
        customer.setName("专票客户");
        customer.setDefaultInvoiceType(InvoiceType.SPECIAL);

        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("SO-003", "F006"))
                .thenReturn(Optional.of(order));
        when(invoiceRecordRepository.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("SO-003"), anyList()))
                .thenReturn(List.of());
        when(salesOrderItemRepository.findBySalesOrderId("SO-003"))
                .thenReturn(List.of(item("1", "100", "13")));
        when(customerRepository.findById("CUSTOMER-001")).thenReturn(Optional.of(customer));
        when(invoiceRecordRepository.save(any(InvoiceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceRecord result = service.requestInvoiceFromOrder(
                "F006", "SO-003", null, null, "全量发货后自动生成");

        assertThat(result.getInvoiceType()).isEqualTo(InvoiceType.SPECIAL);
        assertThat(result.getCustomerName()).isEqualTo("专票客户");
    }

    private SalesOrderItem item(String deliveredQuantity, String unitPrice, String taxRate) {
        SalesOrderItem item = new SalesOrderItem();
        item.setQuantity(new BigDecimal(deliveredQuantity));
        item.setDeliveredQuantity(new BigDecimal(deliveredQuantity));
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setDiscountRate(BigDecimal.ZERO);
        item.setTaxRate(new BigDecimal(taxRate));
        return item;
    }

    private SalesOrder completedOrder(BigDecimal total, BigDecimal invoiced) {
        SalesOrder order = new SalesOrder();
        order.setFactoryId("F006");
        order.setStatus(SalesOrderStatus.COMPLETED);
        order.setTotalAmount(total);
        order.setInvoicedAmount(invoiced);
        return order;
    }
}
