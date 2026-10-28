package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.dto.inventory.PurchaseInvoiceLineRequest;
import com.cretas.aims.dto.inventory.PurchaseInvoiceLineViewDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceMatchStatusDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceTodoDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceViewDTO;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.entity.inventory.PurchaseInvoiceLine;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseInvoiceChaseLogRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceLineRepository;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 采购发票 Service 实现（SP6）
 *
 * <p>上传发票 → 对账（PENDING/MATCHED/MISMATCHED）→ 逾期提醒。
 *
 * <p>S5-05/06/07: 自动对账只看逐行三单（{@link PurchaseInvoiceThreeWayMatcher}），
 * 不再比较「发票总额 == 订单总额」—— 同金额但错料、少收、分票都会被那条规则误判相符。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseInvoiceServiceImpl implements PurchaseInvoiceService {

    private final PurchaseInvoiceRepository invoiceRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseInvoiceChaseLogRepository chaseLogRepository;
    private final PurchaseInvoiceLineRepository lineRepository;
    private final PurchaseInvoiceThreeWayMatcher threeWayMatcher;

    // ─── upload ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PurchaseInvoice upload(String factoryId, String poId, String supplierId,
                                  String invoiceNumber, BigDecimal amount,
                                  String ocrRawResult, Long uploadedBy,
                                  List<PurchaseInvoiceLineRequest> lines) {

        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new BusinessException(400, "发票号码不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "发票金额必须大于 0");
        }
        // 开票方必须说出来: 不传就默认成订单的供应商, 「来票供应商与订单不符」就永远查不出来
        if (supplierId == null || supplierId.isBlank()) {
            throw new BusinessException(400, "发票供应商不能为空");
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
        invoice.setReconcileStatus("PENDING");

        // 行先校验再落发票: 超开 409 时不能留下一张孤立的表头发票。
        List<PurchaseInvoiceLineRequest> requested = lines == null ? List.of() : lines;
        PurchaseInvoiceThreeWayMatcher.Ledger ledger = threeWayMatcher.load(factoryId, po);
        List<PurchaseInvoiceLine> builtLines = threeWayMatcher.buildLines(factoryId, ledger, null, requested);

        String blockingNote = autoMatchBlocker(builtLines, amount);
        if (blockingNote == null) {
            invoice.setReconcileStatus("MATCHED");
            invoice.setReconcileNote("AUTO_MATCHED_LINE_LEVEL");
            invoice.setReconciledBy(uploadedBy);
            invoice.setReconciledAt(LocalDateTime.now());
        } else {
            invoice.setReconcileNote(blockingNote);
        }

        PurchaseInvoice saved = invoiceRepository.save(invoice);
        for (PurchaseInvoiceLine line : builtLines) {
            line.setInvoiceId(saved.getId());
        }
        if (!builtLines.isEmpty()) {
            lineRepository.saveAll(builtLines);
        }
        refreshOrderInvoiceStatus(factoryId, po);
        purchaseOrderRepository.save(po);
        chaseLogRepository.closeOpenChases(factoryId, poId, LocalDateTime.now());
        log.info("[SP6] 上传采购发票 invoiceNumber={} poId={} amount={} lines={} status={} by userId={}",
                invoiceNumber, poId, amount, builtLines.size(), saved.getReconcileStatus(), uploadedBy);
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
        if ("MATCHED".equals(reconcileStatus)) {
            List<String> differences = Optional.ofNullable(lineRepository.findByFactoryIdAndInvoiceIdInOrderByIdAsc(
                            factoryId, List.of(invoiceId))).orElse(List.of()).stream()
                    .filter(l -> PurchaseInvoiceLine.MATERIAL_MISMATCH.equals(l.getMatchStatus())
                            || PurchaseInvoiceLine.PRICE_MISMATCH.equals(l.getMatchStatus()))
                    .map(l -> l.getMatchNote() != null ? l.getMatchNote() : l.getMatchStatus())
                    .toList();
            if (!differences.isEmpty()) {
                // 行级差异还在, 人工「相符」会把错料 / 错价盖成三单相符 (S5-06)
                throw new BusinessException(409, "发票存在行级差异，不能核对为相符：" + String.join("；", differences))
                        .withCode("PURCHASE_INVOICE_LINE_DIFFERENCE")
                        .withHint("请核对为「不符」并让供应商重开，或更正采购单后重新登记");
            }
        }

        invoice.setReconcileStatus(reconcileStatus);
        invoice.setReconcileNote(note);
        invoice.setReconciledBy(reconciledBy);
        invoice.setReconciledAt(LocalDateTime.now());
        invoiceRepository.save(invoice);

        purchaseOrderRepository.findByIdAndFactoryIdForUpdate(invoice.getPurchaseOrderId(), factoryId)
                .ifPresent(po -> {
                    refreshOrderInvoiceStatus(factoryId, po);
                    purchaseOrderRepository.save(po);
                    if (po.getInvoiceStatus() == PurchaseInvoiceStatus.RECONCILED) {
                        chaseLogRepository.closeOpenChases(factoryId, po.getId(), LocalDateTime.now());
                    }
                });

        log.info("[SP6] 发票 {} 对账完成 status={} by userId={}", invoiceId, reconcileStatus, reconciledBy);
    }

    /**
     * 自动判相符的唯一条件: 有行级资料、每行物料/金额都与采购单行一致、行合计 = 发票总额。
     * 返回 null 表示可自动相符, 否则返回留给核对员看的原因。
     */
    private String autoMatchBlocker(List<PurchaseInvoiceLine> lines, BigDecimal invoiceAmount) {
        if (lines.isEmpty()) {
            return "缺行级资料（未逐行对应采购单行/收货行），不能自动判三单相符，需人工核对";
        }
        List<String> problems = new java.util.ArrayList<>();
        for (PurchaseInvoiceLine line : lines) {
            if (!PurchaseInvoiceLine.MATCHED.equals(line.getMatchStatus())) {
                problems.add(line.getMatchNote() != null ? line.getMatchNote() : line.getMatchStatus());
            }
        }
        BigDecimal lineTotal = lines.stream().map(PurchaseInvoiceLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (lineTotal.subtract(invoiceAmount).abs().compareTo(new BigDecimal("0.01")) > 0) {
            problems.add("发票总额 " + invoiceAmount.stripTrailingZeros().toPlainString()
                    + " ≠ 行合计 " + lineTotal.stripTrailingZeros().toPlainString());
        }
        return problems.isEmpty() ? null : "行级差异：" + String.join("；", problems);
    }

    /**
     * 采购单收票状态: 只有每条可开票行都被「已核对」发票开满、且没有待核对发票才算 RECONCILED。
     * 没有任何行级发票的历史单沿用旧口径（有一张人工核对相符的发票即视为已核对）。
     */
    private void refreshOrderInvoiceStatus(String factoryId, PurchaseOrder po) {
        List<PurchaseInvoice> invoices = Optional.ofNullable(
                invoiceRepository.findByFactoryIdAndPurchaseOrderId(factoryId, po.getId())).orElse(List.of());
        boolean hasLineLevel = !Optional.ofNullable(
                        lineRepository.findByFactoryIdAndPurchaseOrderIdOrderByIdAsc(factoryId, po.getId()))
                .orElse(List.of()).isEmpty();
        boolean reconciled;
        if (hasLineLevel) {
            reconciled = threeWayMatcher.fullyMatched(threeWayMatcher.load(factoryId, po))
                    && invoices.stream().noneMatch(i -> "PENDING".equals(i.getReconcileStatus()));
        } else {
            reconciled = invoices.stream().anyMatch(i -> "MATCHED".equals(i.getReconcileStatus()));
        }
        if (reconciled) {
            po.setInvoiceStatus(PurchaseInvoiceStatus.RECONCILED);
        } else if (!invoices.isEmpty()) {
            po.setInvoiceStatus(PurchaseInvoiceStatus.RECEIVED);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseInvoiceMatchStatusDTO matchStatus(String factoryId, String poId) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndFactoryId(poId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "采购订单不存在或不属于工厂: " + poId));
        return threeWayMatcher.toStatus(threeWayMatcher.load(factoryId, po));
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
        Map<String, List<PurchaseInvoiceLineViewDTO>> linesByInvoice = invoices.isEmpty() ? Map.of()
                : Optional.ofNullable(lineRepository.findByFactoryIdAndInvoiceIdInOrderByIdAsc(
                                factoryId, invoices.stream().map(PurchaseInvoice::getId).toList()))
                        .orElse(List.of()).stream()
                        .collect(Collectors.groupingBy(PurchaseInvoiceLine::getInvoiceId,
                                Collectors.mapping(PurchaseInvoiceLineViewDTO::from, Collectors.toList())));
        return invoices.stream().map(invoice -> {
            var order = orders.get(invoice.getPurchaseOrderId());
            return PurchaseInvoiceViewDTO.from(
                    invoice,
                    order != null ? order.getOrderNumber() : null,
                    order != null ? order.getSupplierName() : null,
                    linesByInvoice.getOrDefault(invoice.getId(), List.of()));
        }).toList();
    }
}
