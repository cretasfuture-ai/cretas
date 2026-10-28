package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.dto.inventory.PurchaseInvoiceLineRequest;
import com.cretas.aims.dto.inventory.PurchaseInvoiceMatchStatusDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceTodoDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceViewDTO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购发票 Service（SP6）
 *
 * <p>上传发票 → 对账（PENDING/MATCHED/MISMATCHED）→ 逾期提醒。
 */
public interface PurchaseInvoiceService {

    /**
     * 上传发票。
     * 幂等：同一工厂下 invoiceNumber 重复则抛 BusinessException。
     *
     * @param factoryId    工厂 ID
     * @param poId         采购订单 ID
     * @param supplierId   供应商 ID
     * @param invoiceNumber 发票号
     * @param amount       发票金额
     * @param ocrRawResult OCR 原始结果（JSON，可 null）
     * @param uploadedBy   上传人 userId
     * @return 创建的发票实体（reconcileStatus=PENDING）
     */
    default PurchaseInvoice upload(
            String factoryId,
            String poId,
            String supplierId,
            String invoiceNumber,
            BigDecimal amount,
            String ocrRawResult,
            Long uploadedBy) {
        return upload(factoryId, poId, supplierId, invoiceNumber, amount, ocrRawResult, uploadedBy, List.of());
    }

    /**
     * 上传发票并逐行登记（S5-05/06/07）。
     *
     * <p>每行指向采购单行（及可选收货行）。任何一行让累计开票量超过「有效收货 − 退货」→ 409；
     * 收货行被重复开满 → 409。只有全部行物料/金额一致且行合计 = 发票总额才自动相符，
     * 没有行级资料一律留待人工核对。
     */
    PurchaseInvoice upload(
            String factoryId,
            String poId,
            String supplierId,
            String invoiceNumber,
            BigDecimal amount,
            String ocrRawResult,
            Long uploadedBy,
            List<PurchaseInvoiceLineRequest> lines);

    /** 采购单逐行三单状态：有效收货、退货抵减、已开票、剩余可开票。 */
    PurchaseInvoiceMatchStatusDTO matchStatus(String factoryId, String poId);

    /**
     * 对账操作（PENDING → MATCHED 或 MISMATCHED）。
     * 已对账（非 PENDING）再次调用抛 BusinessException。
     *
     * @param invoiceId      发票 ID
     * @param factoryId      工厂 ID（隔离校验）
     * @param reconcileStatus 目标状态（MATCHED / MISMATCHED）
     * @param note           对账备注
     * @param reconciledBy   操作人 userId
     */
    void reconcile(
            String invoiceId,
            String factoryId,
            String reconcileStatus,
            String note,
            Long reconciledBy);

    /**
     * 查询超期未开票的采购订单 ID 列表（逾期提醒）。
     *
     * <p>条件：PO.orderDate + COALESCE(invoiceReminderDays, 30) <= today
     * 且该 PO 在 purchase_invoices 中无记录。
     */
    List<String> listPendingReminder(String factoryId);

    /** 完成/关闭且仍未收到供应商发票的采购单，自动形成待收票工作项。 */
    List<PurchaseInvoiceTodoDTO> listPendingReceiptTodos(String factoryId);

    /** 查询某采购订单的全部发票 */
    List<PurchaseInvoice> listByPurchaseOrder(String factoryId, String poId);

    /** 查询本工厂全部发票 (采购发票管理页直接进入, 不带 poId 时列全量, 按创建时间倒序) */
    List<PurchaseInvoice> listByFactory(String factoryId);

    /** 财务工作台列表视图，补齐采购单号和供应商名称。 */
    List<PurchaseInvoiceViewDTO> listViews(String factoryId, String poId);
}
