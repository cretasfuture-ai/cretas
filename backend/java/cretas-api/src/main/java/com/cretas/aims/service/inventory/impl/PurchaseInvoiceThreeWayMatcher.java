package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.PurchaseInvoiceLineRequest;
import com.cretas.aims.dto.inventory.PurchaseInvoiceMatchStatusDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.entity.inventory.PurchaseInvoiceLine;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceLineRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.unit.UnitDisplayNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 逐行三单匹配（S5-05/06/07）：采购单行 ↔ 有效收货行 ↔ 发票行。
 *
 * <p>每条采购单行的可开票量 = 已确认收货单上指向它的数量 − 未驳回的带货采购退货（折算到采购单位）。
 * 已开票量 = 「待核对 / 已核对」发票上指向它的行（「不符」的发票不占量，改开新票时释放）。
 * 新发票任何一行让累计开票量超过可开票量 → 409；指向的收货行被重复占用 → 409。
 * 量不出来（单位换算缺失）→ 422，不按 0 放行。
 *
 * <p>调用方必须已经锁住采购单行（{@code findByIdAndFactoryIdForUpdate}），同一采购单的
 * 收票因此串行，累计量不会被两张并发发票各自读旧值后超开。
 */
@Component
@RequiredArgsConstructor
public class PurchaseInvoiceThreeWayMatcher {

    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PurchaseInvoiceRepository invoiceRepository;
    private final PurchaseInvoiceLineRepository lineRepository;
    private final PurchaseOrderItemRepository orderItemRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final ReturnOrderItemRepository returnOrderItemRepository;
    private final RawMaterialTypeRepository materialTypeRepository;

    // ─── ledger ───────────────────────────────────────────────────────────────

    /** 一条采购单行的三单账。数量单位 = 采购单行 unit；null = 量不出来。 */
    static final class LineLedger {
        final PurchaseOrderItem item;
        BigDecimal received;
        BigDecimal returned;
        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal matched = BigDecimal.ZERO;
        String blocker;
        final Map<Long, PurchaseReceiveItem> receipts = new LinkedHashMap<>();
        final Map<Long, BigDecimal> invoicedByReceipt = new HashMap<>();

        LineLedger(PurchaseOrderItem item) { this.item = item; }

        BigDecimal invoiceable() {
            if (blocker != null || received == null || returned == null) return null;
            BigDecimal net = received.subtract(returned).max(BigDecimal.ZERO);
            // 超收部分不开票: 发票只认订购量以内
            return item.getQuantity() == null ? net : net.min(item.getQuantity());
        }

        BigDecimal remaining() {
            BigDecimal cap = invoiceable();
            return cap == null ? null : cap.subtract(invoiced);
        }
    }

    record Ledger(PurchaseOrder po, Map<Long, LineLedger> lines,
                  Map<Long, PurchaseReceiveRecord> receiptRecords) {}

    Ledger load(String factoryId, PurchaseOrder po) {
        List<PurchaseOrderItem> items = Optional.ofNullable(orderItemRepository.findByPurchaseOrderId(po.getId()))
                .orElse(List.of());
        Map<Long, LineLedger> lines = new LinkedHashMap<>();
        for (PurchaseOrderItem item : items) {
            if (item.getId() != null) lines.put(item.getId(), new LineLedger(item));
        }

        // 有效收货: 只认已确认收货单, 且必须指向本采购单行; 单位不同不猜换算。
        List<PurchaseReceiveRecord> confirmed = Optional.ofNullable(
                lineRepository.findConfirmedReceipts(factoryId, po.getId())).orElse(List.of());
        Map<String, PurchaseReceiveRecord> recordById = confirmed.stream()
                .collect(Collectors.toMap(PurchaseReceiveRecord::getId, Function.identity(), (a, b) -> a));
        Map<Long, PurchaseReceiveRecord> receiptRecordByItem = new HashMap<>();
        for (LineLedger l : lines.values()) l.received = BigDecimal.ZERO;
        if (!recordById.isEmpty()) {
            for (PurchaseReceiveItem ri : Optional.ofNullable(lineRepository.findReceiveItems(recordById.keySet()))
                    .orElse(List.of())) {
                LineLedger l = ri.getPurchaseOrderItemId() == null ? null : lines.get(ri.getPurchaseOrderItemId());
                if (l == null || ri.getReceivedQuantity() == null) continue;
                receiptRecordByItem.put(ri.getId(), recordById.get(ri.getReceiveRecordId()));
                l.receipts.put(ri.getId(), ri);
                if (!sameUnit(ri.getUnit(), l.item.getUnit())) {
                    l.blocker = "收货单位「" + UnitDisplayNames.display(ri.getUnit()) + "」与采购单位「" + UnitDisplayNames.display(l.item.getUnit())
                            + "」不同，无法核验可开票量";
                    continue;
                }
                l.received = l.received.add(ri.getReceivedQuantity());
            }
        }

        applyReturns(factoryId, po, lines);

        // 已开票: 「不符」的发票不占量。
        Map<String, String> invoiceStatus = Optional.ofNullable(
                        invoiceRepository.findByFactoryIdAndPurchaseOrderId(factoryId, po.getId())).orElse(List.of())
                .stream().collect(Collectors.toMap(PurchaseInvoice::getId,
                        i -> i.getReconcileStatus() == null ? "PENDING" : i.getReconcileStatus(), (a, b) -> a));
        for (PurchaseInvoiceLine il : Optional.ofNullable(
                lineRepository.findByFactoryIdAndPurchaseOrderIdOrderByIdAsc(factoryId, po.getId())).orElse(List.of())) {
            String status = invoiceStatus.get(il.getInvoiceId());
            if (status == null || "MISMATCHED".equals(status)) continue;
            LineLedger l = lines.get(il.getPurchaseOrderItemId());
            if (l == null) continue;
            l.invoiced = l.invoiced.add(il.getQuantity());
            if ("MATCHED".equals(status)) l.matched = l.matched.add(il.getQuantity());
            if (il.getReceiveItemId() != null) {
                l.invoicedByReceipt.merge(il.getReceiveItemId(), il.getQuantity(), BigDecimal::add);
            }
        }
        return new Ledger(po, lines, receiptRecordByItem);
    }

    /** 未驳回的带货采购退货按物料抵减; 物料对应多条采购单行或单位换算缺失时如实挂 blocker。 */
    private void applyReturns(String factoryId, PurchaseOrder po, Map<Long, LineLedger> lines) {
        Map<String, List<LineLedger>> byMaterial = lines.values().stream()
                .filter(l -> l.item.getMaterialTypeId() != null)
                .collect(Collectors.groupingBy(l -> l.item.getMaterialTypeId()));
        Map<String, BigDecimal> returnedBase = new HashMap<>();
        for (ReturnOrder order : Optional.ofNullable(returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        factoryId, ReturnType.PURCHASE_RETURN, po.getId())).orElse(List.of())) {
            if (order == null || order.getStatus() == ReturnOrderStatus.REJECTED
                    || !Boolean.TRUE.equals(order.getWithGoods())) continue;
            for (ReturnOrderItem item : Optional.ofNullable(
                    returnOrderItemRepository.findByReturnOrderId(order.getId())).orElse(List.of())) {
                if (item.getMaterialTypeId() != null && item.getQuantity() != null) {
                    returnedBase.merge(item.getMaterialTypeId(), item.getQuantity(), BigDecimal::add);
                }
            }
        }
        for (LineLedger l : lines.values()) l.returned = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : returnedBase.entrySet()) {
            List<LineLedger> targets = byMaterial.getOrDefault(e.getKey(), List.of());
            if (targets.size() != 1) {
                // 退货只记到物料, 物料在本单有多行时分不清抵哪一行 → 这些行全部量不出来
                for (LineLedger l : targets) {
                    l.blocker = "采购退货只按物料登记，本单该物料有多行，无法确定抵减哪一行";
                }
                continue;
            }
            LineLedger l = targets.get(0);
            BigDecimal converted = baseToOrderUnit(factoryId, l.item, e.getValue());
            if (converted == null) {
                l.blocker = "退货数量无法换算到采购单位「" + UnitDisplayNames.display(l.item.getUnit()) + "」，无法核验可开票量";
            } else {
                l.returned = converted;
            }
        }
    }

    /** 退货数量是库存基本单位; 换回采购单行单位。换不了返回 null。 */
    private BigDecimal baseToOrderUnit(String factoryId, PurchaseOrderItem item, BigDecimal baseQty) {
        String base = hasText(item.getInventoryBaseUnitSnapshot()) ? item.getInventoryBaseUnitSnapshot()
                : materialTypeRepository.findByIdAndFactoryId(item.getMaterialTypeId(), factoryId)
                        .map(RawMaterialType::getUnit).orElse(null);
        if (base != null && sameUnit(base, item.getUnit())) return baseQty;
        BigDecimal factor = item.getPackageToBaseFactorSnapshot();
        if (base != null && factor != null && factor.signum() > 0
                && sameUnit(base, item.getInventoryBaseUnitSnapshot())) {
            return baseQty.divide(factor, 4, RoundingMode.HALF_UP);
        }
        return null;
    }

    // ─── build + validate new invoice lines ───────────────────────────────────

    /**
     * 校验并生成新发票的行（未保存）。超开 → 409；引用不属于本单 → 400；量不出来 → 422。
     */
    List<PurchaseInvoiceLine> buildLines(String factoryId, Ledger ledger, String invoiceId,
                                         List<PurchaseInvoiceLineRequest> requests) {
        Map<Long, BigDecimal> requestedByLine = new LinkedHashMap<>();
        Map<Long, BigDecimal> requestedByReceipt = new LinkedHashMap<>();
        List<PurchaseInvoiceLine> result = new ArrayList<>();
        int index = 0;
        for (PurchaseInvoiceLineRequest req : requests) {
            index++;
            String label = "第 " + index + " 行";
            if (req == null || req.purchaseOrderItemId() == null) {
                throw new BusinessException(400, label + "未指定采购单行").withCode("PURCHASE_INVOICE_LINE_PO_ITEM_REQUIRED");
            }
            LineLedger l = ledger.lines().get(req.purchaseOrderItemId());
            if (l == null) {
                throw new BusinessException(400, label + "指向的采购单行不属于采购单 " + ledger.po().getOrderNumber())
                        .withCode("PURCHASE_INVOICE_LINE_PO_ITEM_FOREIGN");
            }
            if (req.quantity() == null || req.quantity().signum() <= 0) {
                throw new BusinessException(400, label + "开票数量必须大于 0").withHintTarget("quantity");
            }
            if (req.amount() == null || req.amount().signum() < 0) {
                throw new BusinessException(400, label + "金额不能为空或为负").withHintTarget("amount");
            }
            if (req.receiveItemId() != null) {
                PurchaseReceiveItem ri = l.receipts.get(req.receiveItemId());
                if (ri == null) {
                    throw new BusinessException(400, label + "指向的收货行不是本采购单行的已确认收货")
                            .withCode("PURCHASE_INVOICE_LINE_RECEIPT_FOREIGN");
                }
                requestedByReceipt.merge(req.receiveItemId(), req.quantity(), BigDecimal::add);
            }
            requestedByLine.merge(req.purchaseOrderItemId(), req.quantity(), BigDecimal::add);

            PurchaseInvoiceLine line = new PurchaseInvoiceLine();
            line.setFactoryId(factoryId);
            line.setInvoiceId(invoiceId);
            line.setPurchaseOrderId(ledger.po().getId());
            line.setPurchaseOrderItemId(req.purchaseOrderItemId());
            line.setReceiveItemId(req.receiveItemId());
            line.setMaterialTypeId(hasText(req.materialTypeId()) ? req.materialTypeId().trim() : null);
            line.setQuantity(req.quantity());
            line.setUnit(l.item.getUnit());
            line.setUnitPrice(req.unitPrice());
            line.setAmount(req.amount().setScale(2, RoundingMode.HALF_UP));
            classify(line, l.item);
            result.add(line);
        }

        // 累计量守恒: 采购单行
        for (Map.Entry<Long, BigDecimal> e : requestedByLine.entrySet()) {
            LineLedger l = ledger.lines().get(e.getKey());
            String name = materialLabel(l.item);
            if (l.invoiceable() == null) {
                throw new BusinessException(422, name + "：" + (l.blocker != null ? l.blocker : "可开票量量不出来"))
                        .withCode("PURCHASE_INVOICE_QUANTITY_UNVERIFIABLE");
            }
            BigDecimal remaining = l.remaining();
            if (e.getValue().compareTo(remaining) > 0) {
                throw new BusinessException(409, name + " 超开：本次 " + plain(e.getValue()) + UnitDisplayNames.display(l.item.getUnit())
                        + "，最多还可开 " + plain(remaining.max(BigDecimal.ZERO)) + UnitDisplayNames.display(l.item.getUnit()))
                        .withCode("PURCHASE_INVOICE_OVER_INVOICED")
                        .withHint("有效收货 " + plain(l.received) + " − 退货 " + plain(l.returned)
                                + "（不超过订购 " + plain(l.item.getQuantity()) + "）"
                                + " − 已开票 " + plain(l.invoiced) + " = 剩余 " + plain(remaining.max(BigDecimal.ZERO))
                                + UnitDisplayNames.display(l.item.getUnit()) + "；请按实收拆票或先补收货")
                        .withHintTarget("quantity");
            }
        }
        // 累计量守恒: 收货行（一条收货不能被两张票重复占用）
        for (Map.Entry<Long, BigDecimal> e : requestedByReceipt.entrySet()) {
            LineLedger l = ledger.lines().values().stream()
                    .filter(x -> x.receipts.containsKey(e.getKey())).findFirst().orElseThrow();
            PurchaseReceiveItem ri = l.receipts.get(e.getKey());
            BigDecimal used = l.invoicedByReceipt.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal left = ri.getReceivedQuantity().subtract(used);
            if (e.getValue().compareTo(left) > 0) {
                PurchaseReceiveRecord record = ledger.receiptRecords().get(e.getKey());
                throw new BusinessException(409, "收货行（" + (record != null ? record.getReceiveNumber() : e.getKey())
                        + " / " + materialLabel(l.item) + "）已被开票 " + plain(used) + "，本次 " + plain(e.getValue())
                        + " 超过剩余 " + plain(left.max(BigDecimal.ZERO)) + UnitDisplayNames.display(l.item.getUnit()))
                        .withCode("PURCHASE_INVOICE_RECEIPT_OVER_INVOICED")
                        .withHint("同一收货行不能被多张发票重复开满，请改指其它收货行或减少数量")
                        .withHintTarget("receiveItemId");
            }
        }
        return result;
    }

    private void classify(PurchaseInvoiceLine line, PurchaseOrderItem item) {
        List<String> notes = new ArrayList<>();
        String status = PurchaseInvoiceLine.MATCHED;
        if (line.getMaterialTypeId() == null) {
            // 发票没写物料 ≠ 物料一致: 不能自动相符, 留人工对票面
            status = PurchaseInvoiceLine.MATERIAL_UNKNOWN;
            notes.add("发票行未写物料，无法核对是否与采购单行一致");
        } else if (!Objects.equals(line.getMaterialTypeId(), item.getMaterialTypeId())) {
            status = PurchaseInvoiceLine.MATERIAL_MISMATCH;
            notes.add("发票物料 " + line.getMaterialTypeId() + " ≠ 采购单行物料 " + item.getMaterialTypeId());
        }
        BigDecimal expected = expectedAmount(item, line.getQuantity());
        line.setExpectedAmount(expected);
        if (expected == null) {
            if (PurchaseInvoiceLine.MATCHED.equals(status)) status = PurchaseInvoiceLine.PRICE_UNKNOWN;
            notes.add(item.getUnitPrice() == null ? "采购单行无单价，无法核对金额" : "采购单行税率未知，无法核对含税金额");
        } else if (line.getAmount().subtract(expected).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
            if (PurchaseInvoiceLine.MATCHED.equals(status)) status = PurchaseInvoiceLine.PRICE_MISMATCH;
            notes.add("发票行金额 " + plain(line.getAmount()) + " ≠ 应开 " + plain(expected));
        }
        line.setMatchStatus(status);
        line.setMatchNote(notes.isEmpty() ? null : String.join("；", notes));
    }

    /**
     * 与 {@link PurchaseOrderItem#getLineAmountWithTax()} 同一口径，只把数量换成本次开票量。
     * 税率为空 = 未知（不是 0%）→ 返回 null，按含税金额核不了。
     */
    public static BigDecimal expectedAmount(PurchaseOrderItem item, BigDecimal quantity) {
        if (item.getUnitPrice() == null || item.getTaxRate() == null || quantity == null) return null;
        BigDecimal factor = item.getQuantityToPriceFactor() != null ? item.getQuantityToPriceFactor() : BigDecimal.ONE;
        BigDecimal untaxed = quantity.multiply(factor).multiply(item.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
        if (item.getTaxRate().signum() == 0) return untaxed;
        BigDecimal multiplier = BigDecimal.ONE.add(item.getTaxRate().divide(HUNDRED, 6, RoundingMode.HALF_UP));
        return untaxed.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    // ─── status view ──────────────────────────────────────────────────────────

    boolean fullyMatched(Ledger ledger) {
        boolean anyInvoiceable = false;
        for (LineLedger l : ledger.lines().values()) {
            BigDecimal cap = l.invoiceable();
            if (cap == null) return false;
            if (cap.signum() == 0) continue;
            anyInvoiceable = true;
            if (l.matched.compareTo(cap) < 0) return false;
        }
        return anyInvoiceable;
    }

    PurchaseInvoiceMatchStatusDTO toStatus(Ledger ledger) {
        List<PurchaseInvoiceMatchStatusDTO.Line> lines = new ArrayList<>();
        for (LineLedger l : ledger.lines().values()) {
            BigDecimal cap = l.invoiceable();
            BigDecimal remaining = l.remaining();
            String status;
            if (cap == null) status = "BLOCKED";
            else if (l.received.signum() == 0) status = "NOT_RECEIVED";
            else if (l.invoiced.signum() == 0) status = "UNINVOICED";
            else if (remaining.signum() > 0) status = "PARTIAL";
            else status = "INVOICED";
            List<PurchaseInvoiceMatchStatusDTO.Receipt> receipts = new ArrayList<>();
            for (PurchaseReceiveItem ri : l.receipts.values()) {
                BigDecimal used = l.invoicedByReceipt.getOrDefault(ri.getId(), BigDecimal.ZERO);
                PurchaseReceiveRecord record = ledger.receiptRecords().get(ri.getId());
                receipts.add(PurchaseInvoiceMatchStatusDTO.Receipt.builder()
                        .receiveItemId(ri.getId())
                        .receiveRecordId(ri.getReceiveRecordId())
                        .receiveNumber(record != null ? record.getReceiveNumber() : null)
                        .receivedQuantity(ri.getReceivedQuantity())
                        .invoicedQuantity(used)
                        .remainingQuantity(ri.getReceivedQuantity().subtract(used))
                        .build());
            }
            lines.add(PurchaseInvoiceMatchStatusDTO.Line.builder()
                    .purchaseOrderItemId(l.item.getId())
                    .materialTypeId(l.item.getMaterialTypeId())
                    .materialName(l.item.getMaterialName())
                    .unit(l.item.getUnit())
                    .orderedQuantity(l.item.getQuantity())
                    .receivedQuantity(l.blocker != null && l.blocker.startsWith("收货单位") ? null : l.received)
                    .returnedQuantity(l.returned)
                    .invoiceableQuantity(cap)
                    .invoicedQuantity(l.invoiced)
                    .matchedQuantity(l.matched)
                    .remainingQuantity(remaining)
                    .expectedUnitAmount(expectedAmount(l.item, BigDecimal.ONE))
                    .status(status)
                    .blocker(l.blocker)
                    .receipts(receipts)
                    .build());
        }
        return PurchaseInvoiceMatchStatusDTO.builder()
                .purchaseOrderId(ledger.po().getId())
                .purchaseOrderNumber(ledger.po().getOrderNumber())
                .fullyMatched(fullyMatched(ledger))
                .lines(lines)
                .build();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static String materialLabel(PurchaseOrderItem item) {
        return hasText(item.getMaterialName()) ? item.getMaterialName() : item.getMaterialTypeId();
    }

    private static boolean sameUnit(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }
}
