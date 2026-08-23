package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseInvoiceChaseLogRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.impl.PurchaseInvoiceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP6 采购发票 Service 单元测试
 *
 * <p>覆盖：
 * - upload()：创建发票，设置 factoryId / purchaseOrderId / reconcileStatus=PENDING
 * - upload()：重复发票号 → BusinessException
 * - reconcile()：PENDING → MATCHED，设置 reconciledBy / reconciledAt / reconcileNote
 * - reconcile()：重复对账 → BusinessException
 * - reconcile()：不存在 → BusinessException
 * - listPendingReminder()：委托 repository 查询逾期提醒
 * - listByPurchaseOrder()：按 PO 查询
 * - OCR 结果正确存储
 * - 金额正确保存
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseInvoiceServiceImpl 单元测试 (SP6)")
class PurchaseInvoiceServiceTest {

    @Mock
    private PurchaseInvoiceRepository invoiceRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseInvoiceChaseLogRepository chaseLogRepository;

    @InjectMocks
    private PurchaseInvoiceServiceImpl purchaseInvoiceService;

    private static final String FACTORY_ID = "F006";
    private static final String PO_ID = "PO-001";
    private static final String INVOICE_ID = "INV-001";

    @BeforeEach
    void setup() {
        when(invoiceRepository.save(any(PurchaseInvoice.class)))
                .thenAnswer(inv -> {
                    PurchaseInvoice invoice = inv.getArgument(0);
                    if (invoice.getId() == null) invoice.setId(java.util.UUID.randomUUID().toString());
                    return invoice;
                });
        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setFactoryId(FACTORY_ID);
        po.setSupplierId("SUP-001");
        po.setTotalAmount(BigDecimal.valueOf(9999));
        po.setInvoiceStatus(PurchaseInvoiceStatus.NOT_RECEIVED);
        when(purchaseOrderRepository.findByIdAndFactoryId(PO_ID, FACTORY_ID))
                .thenReturn(Optional.of(po));
        lenient().when(purchaseOrderRepository.findByIdAndFactoryIdForUpdate(anyString(), anyString()))
                .thenAnswer(invocation -> purchaseOrderRepository.findByIdAndFactoryId(
                        invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(invoiceRepository.findByIdAndFactoryIdForUpdate(anyString(), anyString()))
                .thenAnswer(invocation -> invoiceRepository.findById(invocation.getArgument(0)));
        lenient().when(invoiceRepository.findByFactoryIdAndInvoiceNumberForUpdate(anyString(), anyString()))
                .thenAnswer(invocation -> invoiceRepository.findByFactoryIdAndInvoiceNumber(
                        invocation.getArgument(0), invocation.getArgument(1)));
    }

    // ───────────────────────────────────────────────────────────────────────
    // upload()
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("upload() 上传发票")
    class UploadTests {

        @Test
        @DisplayName("金额为空或非正数时拒绝登记")
        void upload_rejectsMissingOrNonPositiveAmount() {
            assertThrows(BusinessException.class, () -> purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-001", "FP-ZERO", BigDecimal.ZERO, null, 1L));
            assertThrows(BusinessException.class, () -> purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-001", "FP-NULL", null, null, 1L));
            verify(invoiceRepository, never()).save(any());
        }

        @Test
        @DisplayName("供应商必须与采购订单一致")
        void upload_rejectsSupplierMismatch() {
            when(invoiceRepository.findByFactoryIdAndInvoiceNumber(FACTORY_ID, "FP-WRONG-SUP"))
                    .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-OTHER", "FP-WRONG-SUP",
                    BigDecimal.ONE, null, 1L));
            verify(invoiceRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常上传：factoryId 正确设置")
        void upload_setsFactoryId() {
            when(invoiceRepository.findByFactoryIdAndInvoiceNumber(FACTORY_ID, "FP-2026-001"))
                    .thenReturn(Optional.empty());

            PurchaseInvoice result = purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-001", "FP-2026-001",
                    BigDecimal.valueOf(3000), null, 1L);

            assertEquals(FACTORY_ID, result.getFactoryId());
            verify(purchaseOrderRepository).findByIdAndFactoryIdForUpdate(PO_ID, FACTORY_ID);
        }

        @Test
        @DisplayName("正常上传：purchaseOrderId 正确设置")
        void upload_setsPurchaseOrderId() {
            when(invoiceRepository.findByFactoryIdAndInvoiceNumber(FACTORY_ID, "FP-2026-001"))
                    .thenReturn(Optional.empty());

            PurchaseInvoice result = purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-001", "FP-2026-001",
                    BigDecimal.valueOf(3000), null, 1L);

            assertEquals(PO_ID, result.getPurchaseOrderId());
        }

        @Test
        @DisplayName("正常上传：reconcileStatus 默认为 PENDING")
        void upload_reconcileStatusIsPending() {
            when(invoiceRepository.findByFactoryIdAndInvoiceNumber(FACTORY_ID, "FP-2026-001"))
                    .thenReturn(Optional.empty());

            PurchaseInvoice result = purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-001", "FP-2026-001",
                    BigDecimal.valueOf(3000), null, 1L);

            assertEquals("PENDING", result.getReconcileStatus());
        }

        @Test
        @DisplayName("正常上传：金额正确保存")
        void upload_amountCorrectlySaved() {
            when(invoiceRepository.findByFactoryIdAndInvoiceNumber(FACTORY_ID, "FP-2026-001"))
                    .thenReturn(Optional.empty());

            BigDecimal amount = BigDecimal.valueOf(6666.66);
            PurchaseInvoice result = purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-001", "FP-2026-001",
                    amount, null, 1L);

            assertEquals(0, amount.compareTo(result.getAmount()));
        }

        @Test
        @DisplayName("重复发票号（同工厂）→ BusinessException")
        void upload_duplicateInvoiceNumber_throwsBusinessException() {
            PurchaseInvoice existing = new PurchaseInvoice();
            existing.setId(INVOICE_ID);
            existing.setInvoiceNumber("FP-2026-001");
            when(invoiceRepository.findByFactoryIdAndInvoiceNumber(FACTORY_ID, "FP-2026-001"))
                    .thenReturn(Optional.of(existing));

            assertThrows(BusinessException.class, () ->
                    purchaseInvoiceService.upload(
                            FACTORY_ID, PO_ID, "SUP-001", "FP-2026-001",
                            BigDecimal.valueOf(3000), null, 1L));

            verify(invoiceRepository).findByFactoryIdAndInvoiceNumberForUpdate(FACTORY_ID, "FP-2026-001");
            verify(invoiceRepository, never()).save(any());
        }

        @Test
        @DisplayName("OCR 原始结果正确存储")
        void upload_ocrRawResultStored() {
            when(invoiceRepository.findByFactoryIdAndInvoiceNumber(FACTORY_ID, "FP-2026-001"))
                    .thenReturn(Optional.empty());

            String ocrRaw = "{\"invoice_number\": \"FP-2026-001\", \"amount\": 3000}";
            ArgumentCaptor<PurchaseInvoice> captor = ArgumentCaptor.forClass(PurchaseInvoice.class);

            // Call with OCR result via uploadedBy param; we expose ocrRawResult via setOcrRawResult
            PurchaseInvoice result = purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-001", "FP-2026-001",
                    BigDecimal.valueOf(3000), ocrRaw, 1L);

            assertEquals(ocrRaw, result.getOcrRawResult());
        }

        @Test
        @DisplayName("upload amount matches PO: invoice auto reconciles and PO closes loop")
        void upload_amountMatchesPo_autoReconcilesAndClosesLoop() {
            PurchaseOrder po = new PurchaseOrder();
            po.setId(PO_ID);
            po.setFactoryId(FACTORY_ID);
            po.setSupplierId("SUP-001");
            po.setTotalAmount(BigDecimal.valueOf(3000));
            po.setInvoiceStatus(PurchaseInvoiceStatus.NOT_RECEIVED);
            when(invoiceRepository.findByFactoryIdAndInvoiceNumber(FACTORY_ID, "FP-2026-009"))
                    .thenReturn(Optional.empty());
            when(purchaseOrderRepository.findByIdAndFactoryId(PO_ID, FACTORY_ID))
                    .thenReturn(Optional.of(po));

            PurchaseInvoice result = purchaseInvoiceService.upload(
                    FACTORY_ID, PO_ID, "SUP-001", "FP-2026-009",
                    BigDecimal.valueOf(3000), null, 9L);

            assertEquals("MATCHED", result.getReconcileStatus());
            assertEquals(9L, result.getReconciledBy());
            assertNotNull(result.getReconciledAt());
            assertEquals(PurchaseInvoiceStatus.RECONCILED, po.getInvoiceStatus());
            verify(purchaseOrderRepository).save(po);
            verify(chaseLogRepository).closeOpenChases(
                    eq(FACTORY_ID), eq(PO_ID), any(java.time.LocalDateTime.class));
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // reconcile()
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reconcile() 对账操作")
    class ReconcileTests {

        private PurchaseInvoice pendingInvoice() {
            PurchaseInvoice inv = new PurchaseInvoice();
            inv.setId(INVOICE_ID);
            inv.setFactoryId(FACTORY_ID);
            inv.setPurchaseOrderId(PO_ID);
            inv.setReconcileStatus("PENDING");
            inv.setAmount(BigDecimal.valueOf(3000));
            inv.setUploadedBy(1L);
            return inv;
        }

        @Test
        @DisplayName("PENDING → reconcile(MATCHED) → reconcileStatus=MATCHED")
        void reconcile_pendingToMatched() {
            PurchaseInvoice inv = pendingInvoice();
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(inv));

            purchaseInvoiceService.reconcile(INVOICE_ID, FACTORY_ID, "MATCHED", "金额核对一致", 2L);

            verify(invoiceRepository).findByIdAndFactoryIdForUpdate(INVOICE_ID, FACTORY_ID);
            verify(purchaseOrderRepository).findByIdAndFactoryIdForUpdate(PO_ID, FACTORY_ID);

            ArgumentCaptor<PurchaseInvoice> captor = ArgumentCaptor.forClass(PurchaseInvoice.class);
            verify(invoiceRepository).save(captor.capture());
            assertEquals("MATCHED", captor.getValue().getReconcileStatus());
        }

        @Test
        @DisplayName("reconcile 设置 reconciledBy")
        void reconcile_setsReconciledBy() {
            PurchaseInvoice inv = pendingInvoice();
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(inv));

            purchaseInvoiceService.reconcile(INVOICE_ID, FACTORY_ID, "MATCHED", "核对一致", 99L);

            ArgumentCaptor<PurchaseInvoice> captor = ArgumentCaptor.forClass(PurchaseInvoice.class);
            verify(invoiceRepository).save(captor.capture());
            assertEquals(99L, captor.getValue().getReconciledBy());
        }

        @Test
        @DisplayName("reconcile 设置 reconcileNote")
        void reconcile_setsReconcileNote() {
            PurchaseInvoice inv = pendingInvoice();
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(inv));

            purchaseInvoiceService.reconcile(INVOICE_ID, FACTORY_ID, "MISMATCHED", "金额不符", 2L);

            ArgumentCaptor<PurchaseInvoice> captor = ArgumentCaptor.forClass(PurchaseInvoice.class);
            verify(invoiceRepository).save(captor.capture());
            assertEquals("金额不符", captor.getValue().getReconcileNote());
        }

        @Test
        @DisplayName("已对账（MATCHED）→ reconcile 再次调用 → BusinessException")
        void reconcile_alreadyMatched_throwsBusinessException() {
            PurchaseInvoice inv = pendingInvoice();
            inv.setReconcileStatus("MATCHED");
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(inv));

            assertThrows(BusinessException.class, () ->
                    purchaseInvoiceService.reconcile(INVOICE_ID, FACTORY_ID, "MATCHED", "", 2L));

            verify(invoiceRepository, never()).save(any());
        }

        @Test
        @DisplayName("不存在的发票 ID → BusinessException")
        void reconcile_missingId_throwsBusinessException() {
            when(invoiceRepository.findById("MISSING")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                    purchaseInvoiceService.reconcile("MISSING", FACTORY_ID, "MATCHED", "", 2L));
        }

        @Test
        @DisplayName("跨租户发票通过 tenant-scoped lock 查不到且不写入")
        void reconcile_wrongTenant_isRejectedBeforeMutation() {
            when(invoiceRepository.findByIdAndFactoryIdForUpdate(INVOICE_ID, "F999"))
                    .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                    purchaseInvoiceService.reconcile(INVOICE_ID, "F999", "MATCHED", "", 2L));

            verify(invoiceRepository, never()).save(any());
            verify(purchaseOrderRepository, never()).findByIdAndFactoryIdForUpdate(anyString(), anyString());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // listPendingReminder() / listByPurchaseOrder()
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("查询方法")
    class QueryTests {

        @Test
        @DisplayName("listPendingReminder：委托 repository 传 factoryId + today")
        void listPendingReminder_delegatesWithCorrectParams() {
            when(invoiceRepository.findOverdueInvoicePoIds(eq(FACTORY_ID), any(LocalDate.class)))
                    .thenReturn(List.of("PO-001", "PO-002"));

            List<String> result = purchaseInvoiceService.listPendingReminder(FACTORY_ID);

            assertEquals(2, result.size());
            verify(invoiceRepository).findOverdueInvoicePoIds(eq(FACTORY_ID), any(LocalDate.class));
        }

        @Test
        @DisplayName("listByPurchaseOrder：返回该 PO 的全部发票")
        void listByPurchaseOrder_returnsInvoicesForPo() {
            PurchaseInvoice inv1 = new PurchaseInvoice();
            inv1.setId("INV-001");
            inv1.setPurchaseOrderId(PO_ID);
            PurchaseInvoice inv2 = new PurchaseInvoice();
            inv2.setId("INV-002");
            inv2.setPurchaseOrderId(PO_ID);

            when(invoiceRepository.findByFactoryIdAndPurchaseOrderId(FACTORY_ID, PO_ID))
                    .thenReturn(List.of(inv1, inv2));

            List<PurchaseInvoice> result = purchaseInvoiceService.listByPurchaseOrder(FACTORY_ID, PO_ID);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("完成且未收票的采购单自动成为待收票工作项")
        void listPendingReceiptTodos_completedOrder_returnsTodo() {
            PurchaseOrder completed = new PurchaseOrder();
            completed.setId("PO-COMPLETED");
            completed.setFactoryId(FACTORY_ID);
            completed.setOrderNumber("CG-20260823-001");
            completed.setSupplierId("SUP-001");
            completed.setSupplierName("测试供应商");
            completed.setOrderDate(LocalDate.of(2026, 8, 23));
            completed.setStatus(PurchaseOrderStatus.COMPLETED);
            completed.setInvoiceStatus(PurchaseInvoiceStatus.NOT_RECEIVED);
            completed.setTotalAmount(new BigDecimal("238.31"));
            when(purchaseOrderRepository.findInvoiceChaseCandidates(FACTORY_ID))
                    .thenReturn(List.of(completed));

            var todos = purchaseInvoiceService.listPendingReceiptTodos(FACTORY_ID);

            assertEquals(1, todos.size());
            assertEquals("CG-20260823-001", todos.get(0).getPurchaseOrderNumber());
            assertEquals("测试供应商", todos.get(0).getSupplierName());
            assertEquals(new BigDecimal("238.31"), todos.get(0).getOrderAmount());
        }

        @Test
        @DisplayName("已付款但采购未完成的候选不提前进入待收票工作项")
        void listPendingReceiptTodos_paidButOpenOrder_isExcluded() {
            PurchaseOrder open = new PurchaseOrder();
            open.setId("PO-OPEN");
            open.setFactoryId(FACTORY_ID);
            open.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
            when(purchaseOrderRepository.findInvoiceChaseCandidates(FACTORY_ID))
                    .thenReturn(List.of(open));

            assertTrue(purchaseInvoiceService.listPendingReceiptTodos(FACTORY_ID).isEmpty());
        }

        @Test
        @DisplayName("采购发票视图补齐采购单号和供应商名称")
        void listViews_enrichesOrderAndSupplierNames() {
            PurchaseInvoice invoice = new PurchaseInvoice();
            invoice.setId(INVOICE_ID);
            invoice.setPurchaseOrderId(PO_ID);
            invoice.setSupplierId("SUP-001");
            invoice.setInvoiceNumber("FP-001");
            invoice.setAmount(new BigDecimal("99.00"));
            invoice.setReconcileStatus("PENDING");
            PurchaseOrder order = new PurchaseOrder();
            order.setId(PO_ID);
            order.setFactoryId(FACTORY_ID);
            order.setOrderNumber("CG-001");
            order.setSupplierName("测试供应商");
            when(invoiceRepository.findByFactoryIdOrderByCreatedAtDesc(FACTORY_ID))
                    .thenReturn(List.of(invoice));
            when(purchaseOrderRepository.findAllById(List.of(PO_ID))).thenReturn(List.of(order));

            var rows = purchaseInvoiceService.listViews(FACTORY_ID, null);

            assertEquals(1, rows.size());
            assertEquals("CG-001", rows.get(0).getPurchaseOrderNumber());
            assertEquals("测试供应商", rows.get(0).getSupplierName());
        }
    }
}
