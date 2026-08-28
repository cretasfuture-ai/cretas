package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购入库自动挂应付的<b>金额口径</b>钉子 —— 应付必须是【价税合计】, 不是未税额。
 *
 * <h2>它守的是什么</h2>
 * 2026-08-28 生产实测: {@code confirmReceive} 挂 AP_INVOICE 时传的是
 * {@code record.getTotalAmount()} —— 那是 Σ 实收数量 × 单价, <b>未税</b>。
 * F006 存量 18 条 AP_INVOICE 里 13 条是未税口径, 少记税额合计 ¥249,988.54;
 * 本轮实操 {@code PO-20260828-0001} 复现: 挂 1834.86, 而供应商实欠 2000.00。
 *
 * <p>⚠️ 采购侧比销售侧更隐蔽 —— 销售侧发货会同时生成一张含税销项发票, 两条记录当场矛盾;
 * 采购收票是<b>人工上传</b>的, 且 {@code PurchaseInvoiceServiceImpl.reconcile()} 的
 * MATCHED/MISMATCHED 是人填的参数, 代码里<b>没有任何金额比对</b>
 * ⇒ 没有任何一道闸会发现这个差。
 *
 * <h2>⛔ 为什么单独建一个用例类</h2>
 * 既有的 {@code PurchaseServiceImplConfirmReceiveApIdempotentTest} 用
 * {@code any(BigDecimal.class)} 匹配金额 —— 它钉的是「调没调、调几次」,
 * <b>对金额是完全放开的</b>, 所以本缺陷从它下面穿过去了。
 *
 * <p>这里直接单测 {@code taxInclusiveReceivedValue} 这个纯函数(反射调用):
 * 不必把 {@code confirmReceive} 那一长串仓储桩重搭一遍, 断言也更锐利。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("采购入库挂应付: 金额必须是价税合计")
class PurchaseServiceImplConfirmReceiveApTaxInclusiveTest {

    private static final Long PO_ITEM_ID = 369L;

    // ── 接线用例需要的完整依赖 (镜像 PurchaseServiceImplConfirmReceiveApIdempotentTest) ──
    @Mock private com.cretas.aims.repository.AttachmentRepository attachmentRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private BomRecipeItemRepository bomItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;

    private PurchaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository, purchaseOrderItemRepository, receiveRecordRepository,
                supplierRepository, materialTypeRepository, materialBatchRepository,
                bomItemRepository, arApService, applicationEventPublisher, materialBatchService);
        ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));
        ReflectionTestUtils.setField(service, "attachmentRepository", attachmentRepository);
        org.mockito.Mockito.lenient().when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                any(), eq(com.cretas.aims.entity.Attachment.EntityType.PURCHASE_RECEIPT), any()))
                .thenReturn(1L);
    }

    private PurchaseReceiveRecord receipt(String qty, String unitPrice, String netTotal, Long poItemId) {
        PurchaseReceiveRecord r = new PurchaseReceiveRecord();
        r.setId("RCV-TAX-001");
        r.setFactoryId("F006");
        r.setReceiveNumber("RCV-20260828-1480");
        r.setStatus(PurchaseReceiveStatus.DRAFT);
        PurchaseReceiveItem it = new PurchaseReceiveItem();
        it.setMaterialTypeId("RMT-1");
        it.setMaterialName("2015胸肉");
        it.setReceivedQuantity(new BigDecimal(qty));
        it.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        it.setUnit("kg");
        it.setPurchaseOrderItemId(poItemId);
        r.getItems().add(it);
        r.setTotalAmount(new BigDecimal(netTotal));
        return r;
    }

    private PurchaseOrder orderWithTaxRate(String rate) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId("PO-TAX-001");
        o.setFactoryId("F006");
        o.setSupplierId("SUP-1");
        o.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        PurchaseOrderItem poItem = new PurchaseOrderItem();
        ReflectionTestUtils.setField(poItem, "id", PO_ITEM_ID);
        poItem.setMaterialTypeId("RMT-1");
        poItem.setUnit("kg");
        poItem.setQuantity(new BigDecimal("100"));
        poItem.setUnitPrice(new BigDecimal("18.3486"));
        if (rate != null) poItem.setTaxRate(new BigDecimal(rate));
        o.setItems(new java.util.ArrayList<>(List.of(poItem)));
        return o;
    }

    private BigDecimal call(PurchaseOrder order, PurchaseReceiveRecord record) {
        PurchaseServiceImpl svc = org.mockito.Mockito.mock(PurchaseServiceImpl.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        return (BigDecimal) ReflectionTestUtils.invokeMethod(svc, "taxInclusiveReceivedValue", order, record);
    }

    /**
     * 生产实测那一单: PO-20260828-0001, 100kg × ¥18.3486 未税, 税率 9%。
     * 未税 1834.86 → 价税合计 2000.00 (= purchase_order_items.tax_inclusive_amount_snapshot)。
     */
    @Test
    @DisplayName("100kg × 18.3486 @9% → 应付 2000.00 (不是未税的 1834.86)")
    void receiptValueIsTaxInclusive() {
        BigDecimal v = call(orderWithTaxRate("9.00"), receipt("100", "18.3486", "1834.86", PO_ITEM_ID));
        assertThat(v)
                .as("应付挂账金额必须是价税合计, 与供应商开来的发票一致")
                .isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    /**
     * ⛔ 保住 2026-07-03 那次修复的语义: 超收要按【实收】挂账, 不是 PO 计划量。
     * 实收 130kg (计划 100kg) × 18.3486 = 2385.32 → ×1.09 = 2600.00
     */
    @Test
    @DisplayName("超收 130kg 仍按实收算, 且含税: 2600.00 (不回退成按计划量)")
    void overReceiptStillUsesActualQuantity() {
        BigDecimal v = call(orderWithTaxRate("9.00"), receipt("130", "18.3486", "2385.32", PO_ITEM_ID));
        assertThat(v).isEqualByComparingTo(new BigDecimal("2600.00"));
    }

    /** 零税率不受影响 —— 回归护栏, 证明这条改动不会让无税单据漂移。 */
    @Test
    @DisplayName("零税率订单: 价税合计 == 未税额, 不漂移")
    void zeroTaxRateUnchanged() {
        BigDecimal v = call(orderWithTaxRate("0.00"), receipt("100", "18.3486", "1834.86", PO_ITEM_ID));
        assertThat(v).isEqualByComparingTo(new BigDecimal("1834.86"));
    }

    /**
     * 诚实降级: 收货行挂不上 PO 行(税率查不到) → 只算未税额, ⛔ 不猜税率。
     * 宁可少算, 也不凭空造一笔税。
     */
    @Test
    @DisplayName("查不到对应 PO 行 → 按 0 税率(只算未税), 不猜一个税率")
    void unmatchedLineFallsBackToNetNotGuessedTax() {
        BigDecimal v = call(orderWithTaxRate("9.00"), receipt("100", "18.3486", "1834.86", 999L));
        assertThat(v).isEqualByComparingTo(new BigDecimal("1834.86"));
    }

    /**
     * 缺价的行贡献 0 —— 与旧 {@code totalAmount} 同口径, 让
     * {@code recordPayableIfAbsent} 对 0 诚实跳过, 不伪造应付。
     */
    @Test
    @DisplayName("行无单价 → 该行贡献 0, 让上游诚实跳过挂账")
    void missingPriceContributesZero() {
        BigDecimal v = call(orderWithTaxRate("9.00"), receipt("100", null, "0.00", PO_ITEM_ID));
        assertThat(v).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 接线用例: 证明 confirmReceive **真的**把价税合计传给了挂账
    //
    // ⛔ 上面 5 条只钉了 taxInclusiveReceivedValue 这个纯函数。纯函数对了不等于
    //    调用点用了它 —— 那正是本轮反复撞到的「机制在、没接上」。这一条补上那一半。
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("接线: confirmReceive 传给 recordPayableIfAbsent 的是 2000.00, 不是 1834.86")
    void confirmReceivePassesTaxInclusiveAmountToPayable() {
        PurchaseOrder order = orderWithTaxRate("9.00");
        order.setStatus(PurchaseOrderStatus.PARTIAL_RECEIVED);
        PurchaseReceiveRecord record = receipt("100", "18.3486", "1834.86", PO_ITEM_ID);
        record.setPurchaseOrderId(order.getId());
        record.setSupplierId("SUP-1");
        record.setWarehouseId("WH-RAW");
        record.setReceiveDate(LocalDate.of(2026, 8, 28));
        record.setReceivedBy(9L);

        PurchaseOrderItem poItem = order.getItems().get(0);
        poItem.setReceivedQuantity(BigDecimal.ZERO);

        when(receiveRecordRepository.findByIdAndFactoryIdForUpdate("RCV-TAX-001", "F006"))
                .thenReturn(Optional.of(record));
        when(purchaseOrderItemRepository.findByPurchaseOrderId(order.getId())).thenReturn(List.of(poItem));
        when(purchaseOrderRepository.findByIdAndFactoryIdForUpdate(order.getId(), "F006"))
                .thenReturn(Optional.of(order));
        when(purchaseOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(materialTypeRepository.findById("RMT-1")).thenReturn(Optional.of(rawMaterial()));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> {
            MaterialBatch b = inv.getArgument(0);
            b.setId("BATCH-TAX-1");
            return b;
        });
        when(receiveRecordRepository.save(any(PurchaseReceiveRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(arApService.recordPayableIfAbsent(eq("F006"), eq("SUP-1"), eq(order.getId()),
                eq("PURCHASE_RECEIVE"), eq("RCV-TAX-001"),
                any(BigDecimal.class), any(LocalDate.class), any(), anyString()))
                .thenReturn(new ArApTransaction());

        service.confirmReceive("F006", "RCV-TAX-001", 9L);

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(arApService).recordPayableIfAbsent(eq("F006"), eq("SUP-1"), eq(order.getId()),
                eq("PURCHASE_RECEIVE"), eq("RCV-TAX-001"),
                amount.capture(), any(LocalDate.class), any(), anyString());
        assertThat(amount.getValue())
                .as("挂应付的金额必须是价税合计 —— 纯函数对了还不够, 调用点得真的用它")
                .isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    private RawMaterialType rawMaterial() {
        RawMaterialType m = new RawMaterialType();
        m.setId("RMT-1");
        m.setFactoryId("F006");
        m.setName("2015胸肉");
        m.setUnit("kg");
        return m;
    }
}
