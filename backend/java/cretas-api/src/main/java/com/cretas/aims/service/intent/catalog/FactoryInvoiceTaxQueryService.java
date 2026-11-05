package com.cretas.aims.service.intent.catalog;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.InvoiceRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only query used by the factory intent catalog tax-group invoice entry.
 *
 * <p>The order lookup is deliberately performed before the invoice lookup.  It
 * proves that the order belongs to the requested factory and prevents a
 * cross-tenant invoice id from becoming observable through this route.</p>
 */
@Service
public class FactoryInvoiceTaxQueryService {

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private InvoiceRecordRepository invoiceRecordRepository;

    /**
     * Return the current automatic-invoice state for one factory-scoped order.
     * No amount is manufactured when the automatic flow has not produced a row.
     */
    public Map<String, Object> query(String factoryId, String salesOrderId) {
        if (factoryId == null || factoryId.isBlank()
                || salesOrderId == null || salesOrderId.isBlank()) {
            return pending(salesOrderId, "需要工厂和销售订单信息才能查询自动开票状态");
        }

        // This scoped proof must happen before querying invoice_records.
        var orderOptional = salesOrderRepository.findByIdAndFactoryId(salesOrderId, factoryId);
        if (orderOptional.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "NOT_FOUND");
            result.put("salesOrderId", salesOrderId);
            result.put("message", "销售订单不存在或不属于当前工厂");
            return result;
        }

        List<InvoiceRecord> records = invoiceRecordRepository
                .findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        factoryId, salesOrderId);
        if (records == null || records.isEmpty()) {
            return pending(orderOptional.get(), salesOrderId);
        }

        List<Map<String, Object>> invoices = new ArrayList<>();
        for (InvoiceRecord record : records) {
            invoices.add(toSummary(record));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "GENERATED");
        result.put("salesOrderId", salesOrderId);
        result.put("invoices", invoices);
        result.put("taxBreakdown", invoices.stream()
                .flatMap(invoice -> ((List<?>) invoice.getOrDefault("taxBreakdown", List.of())).stream())
                .toList());
        result.put("message", "已找到自动生成的发票记录");
        return result;
    }

    private Map<String, Object> pending(String salesOrderId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PENDING");
        if (salesOrderId != null && !salesOrderId.isBlank()) {
            result.put("salesOrderId", salesOrderId);
        }
        result.put("generated", false);
        result.put("message", message);
        // Deliberately omit amount/tax totals: zero would be an invented value.
        return result;
    }

    private Map<String, Object> pending(SalesOrder order, String salesOrderId) {
        String orderStatus = order.getStatus() == null ? null : order.getStatus().name();
        String todoStatus = order.getInvoiceTodoStatus();
        String status;
        String message;
        if ("CANCELLED".equals(orderStatus)) {
            status = "CANCELLED";
            message = "销售订单已取消，未生成自动开票记录";
        } else if (!"COMPLETED".equals(orderStatus)) {
            status = "NOT_READY";
            message = "销售订单尚未完成自动开票所需的订单流程";
        } else if (SalesOrder.INVOICE_TODO_FAILED.equals(todoStatus)) {
            status = "FAILED";
            message = "自动开票记录生成失败，等待处理";
        } else {
            status = "PENDING";
            message = "销售订单已完成，但自动开票记录尚未生成";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("salesOrderId", salesOrderId);
        result.put("orderStatus", orderStatus);
        result.put("generated", false);
        if (todoStatus != null && !todoStatus.isBlank()) result.put("invoiceTodoStatus", todoStatus);
        if (order.getInvoiceTodoAttempts() != null) result.put("invoiceTodoAttempts", order.getInvoiceTodoAttempts());
        if (order.getInvoiceTodoLastError() != null && !order.getInvoiceTodoLastError().isBlank()) {
            result.put("invoiceTodoLastError", order.getInvoiceTodoLastError());
        }
        result.put("message", message);
        return result;
    }

    private Map<String, Object> toSummary(InvoiceRecord record) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("invoiceId", record.getId());
        summary.put("invoiceNumber", record.getInvoiceNumber());
        summary.put("salesOrderId", record.getSalesOrderId());
        summary.put("status", record.getStatus() == null ? null : record.getStatus().name());
        summary.put("invoiceType", record.getInvoiceType() == null ? null : record.getInvoiceType().name());
        summary.put("amount", record.getAmount());
        summary.put("taxAmount", record.getTaxAmount());
        summary.put("totalAmount", record.getTotalAmount());
        summary.put("taxBreakdown", record.getTaxBreakdown() == null ? List.of() : record.getTaxBreakdown());
        summary.put("requestedAt", record.getRequestedAt() == null ? null : record.getRequestedAt().toString());
        summary.put("reviewedAt", record.getReviewedAt() == null ? null : record.getReviewedAt().toString());
        summary.put("issuedAt", record.getIssuedAt() == null ? null : record.getIssuedAt().toString());
        summary.put("createdAt", record.getCreatedAt() == null ? null : record.getCreatedAt().toString());
        return summary;
    }
}
