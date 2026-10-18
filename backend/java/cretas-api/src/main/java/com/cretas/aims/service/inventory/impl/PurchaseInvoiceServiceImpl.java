package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.dto.inventory.PurchaseInvoiceTodoDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceViewDTO;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseInvoiceChaseLogRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.PurchaseInvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 采购发票 Service 实现（SP6）
 *
 * <p>上传发票 → 对账（PENDING/MATCHED/MISMATCHED）→ 逾期提醒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseInvoiceServiceImpl implements PurchaseInvoiceService {

    private final PurchaseInvoiceRepository invoiceRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseInvoiceChaseLogRepository chaseLogRepository;

    // ─── upload ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PurchaseInvoice upload(String factoryId, String poId, String supplierId,
                                  String invoiceNumber, BigDecimal amount,
                                  String ocrRawResult, Long uploadedBy) {

        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new BusinessException(400, "发票号码不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "发票金额必须大于 0");
        }

        // 幂等性检查：同一工厂内发票号唯一
        if (invoiceNumber != null && !invoiceNumber.isBlank()) {
            invoiceRepository.findByFactoryIdAndInvoiceNumberForUpdate(factoryId, invoiceNumber)
                    .ifPresent(existing -> {
                        throw new BusinessException(409,
                                "发票号 " + invoiceNumber + " 在工厂 " + factoryId +
                                        " 下已存在（ID=" + existing.getId() + "）");
                    });
        }

        // Serialize receipt/invoice transitions on the tenant-scoped PO row.
        // The database unique index remains the final cross-PO invoice-number
        // guard, while this lock prevents two tabs from mutating one PO loop.
        PurchaseOrder po = purchaseOrderRepository.findByIdAndFactoryIdForUpdate(poId, factoryId)
                .orElseThrow(() -> new BusinessException(404,
                    "采购订单不存在或不属于工厂: " + poId));
        if (supplierId != null && !supplierId.isBlank()
                && !java.util.Objects.equals(supplierId, po.getSupplierId())) {
            throw new BusinessException(400, "发票供应商与采购订单供应商不一致");
        }

        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setFactoryId(factoryId);
        invoice.setPurchaseOrderId(poId);
        invoice.setSupplierId(po.getSupplierId());
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setAmount(amount);
        invoice.setOcrRawResult(ocrRawResult);
        invoice.setUploadedBy(uploadedBy);
        if (canAutoReconcile(po, amount)) {
            invoice.setReconcileStatus("MATCHED");
            invoice.setReconcileNote("AUTO_MATCHED_ON_UPLOAD");
            invoice.setReconciledBy(uploadedBy);
            invoice.setReconciledAt(LocalDateTime.now());
            po.setInvoiceStatus(PurchaseInvoiceStatus.RECONCILED);
        } else {
            invoice.setReconcileStatus("PENDING");
            po.setInvoiceStatus(PurchaseInvoiceStatus.RECEIVED);
        }

        PurchaseInvoice saved = invoiceRepository.save(invoice);
        purchaseOrderRepository.save(po);
        chaseLogRepository.closeOpenChases(factoryId, poId, LocalDateTime.now());
        log.info("[SP6] 上传采购发票 invoiceNumber={} poId={} amount={} by userId={}",
                invoiceNumber, poId, amount, uploadedBy);
        return saved;
    }

    // ─── reconcile ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void reconcile(String invoiceId, String factoryId, String reconcileStatus,
                          String note, Long reconciledBy) {

        PurchaseInvoice invoice = invoiceRepository.findByIdAndFactoryIdForUpdate(invoiceId, factoryId)
                .orElseThrow(() -> new BusinessException("采购发票不存在: " + invoiceId));

        // 幂等性保护：已对账不允许重复操作
        if (!"PENDING".equals(invoice.getReconcileStatus())) {
            throw new BusinessException("发票已完成对账（当前状态: " +
                    invoice.getReconcileStatus() + "），无法重复操作");
        }

        invoice.setReconcileStatus(reconcileStatus);
        invoice.setReconcileNote(note);
        invoice.setReconciledBy(reconciledBy);
        invoice.setReconciledAt(LocalDateTime.now());
        invoiceRepository.save(invoice);

        purchaseOrderRepository.findByIdAndFactoryIdForUpdate(invoice.getPurchaseOrderId(), factoryId)
                .ifPresent(po -> {
                    if ("MATCHED".equals(reconcileStatus)) {
                        po.setInvoiceStatus(PurchaseInvoiceStatus.RECONCILED);
                        purchaseOrderRepository.save(po);
                        chaseLogRepository.closeOpenChases(factoryId, po.getId(), LocalDateTime.now());
                    } else if (po.getInvoiceStatus() == PurchaseInvoiceStatus.NOT_RECEIVED) {
                        po.setInvoiceStatus(PurchaseInvoiceStatus.RECEIVED);
                        purchaseOrderRepository.save(po);
                    }
                });

        log.info("[SP6] 发票 {} 对账完成 status={} by userId={}", invoiceId, reconcileStatus, reconciledBy);
    }

    private boolean canAutoReconcile(PurchaseOrder po, BigDecimal invoiceAmount) {
        BigDecimal gross = invoiceOrderAmount(po);
        return gross != null
                && invoiceAmount != null
                && gross.compareTo(invoiceAmount) == 0;
    }

    private BigDecimal invoiceOrderAmount(PurchaseOrder po) {
        return po.getTotalAmount() == null ? null
                : po.getTotalAmount().add(po.getTaxAmount() != null ? po.getTaxAmount() : BigDecimal.ZERO);
    }

    // ─── listPendingReminder ──────────────────────────────────────────────────

    @Override
    public List<String> listPendingReminder(String factoryId) {
        return invoiceRepository.findOverdueInvoicePoIds(factoryId, LocalDate.now());
    }

    @Override
    public List<PurchaseInvoiceTodoDTO> listPendingReceiptTodos(String factoryId) {
        return purchaseOrderRepository.findInvoiceChaseCandidates(factoryId).stream()
                .filter(po -> po.getStatus() == PurchaseOrderStatus.COMPLETED
                        || po.getStatus() == PurchaseOrderStatus.CLOSED)
                .map(po -> PurchaseInvoiceTodoDTO.builder()
                        .purchaseOrderId(po.getId())
                        .purchaseOrderNumber(po.getOrderNumber())
                        .supplierId(po.getSupplierId())
                        .supplierName(po.getSupplierName())
                        .orderAmount(invoiceOrderAmount(po))
                        .orderDate(po.getOrderDate())
                        .invoiceStatus(po.getInvoiceStatus())
                        .build())
                .toList();
    }

    // ─── listByPurchaseOrder ──────────────────────────────────────────────────

    @Override
    public List<PurchaseInvoice> listByPurchaseOrder(String factoryId, String poId) {
        return invoiceRepository.findByFactoryIdAndPurchaseOrderId(factoryId, poId);
    }

    // ─── listByFactory (采购发票管理页直接进入, 不带 poId 列全量) ─────────────────
    @Override
    public List<PurchaseInvoice> listByFactory(String factoryId) {
        return invoiceRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId);
    }

    @Override
    public List<PurchaseInvoiceViewDTO> listViews(String factoryId, String poId) {
        List<PurchaseInvoice> invoices = (poId == null || poId.isBlank())
                ? listByFactory(factoryId)
                : listByPurchaseOrder(factoryId, poId);
        Map<String, com.cretas.aims.entity.inventory.PurchaseOrder> orders = purchaseOrderRepository
                .findAllById(invoices.stream().map(PurchaseInvoice::getPurchaseOrderId).distinct().toList())
                .stream()
                .filter(order -> factoryId.equals(order.getFactoryId()))
                .collect(Collectors.toMap(
                        com.cretas.aims.entity.inventory.PurchaseOrder::getId,
                        Function.identity()));
        return invoices.stream().map(invoice -> {
            var order = orders.get(invoice.getPurchaseOrderId());
            return PurchaseInvoiceViewDTO.from(
                    invoice,
                    order != null ? order.getOrderNumber() : null,
                    order != null ? order.getSupplierName() : null);
        }).toList();
    }
}
