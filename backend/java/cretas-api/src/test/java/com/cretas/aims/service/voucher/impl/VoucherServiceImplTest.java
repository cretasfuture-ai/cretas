package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.dto.finance.VoucherBatchPostResultDTO;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.PaymentSourceType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.restaurant.WastageRecordRepository;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.voucher.VoucherGeneratorRegistry;
import com.cretas.aims.service.voucher.impl.SalesReceiptVoucherGenerator;
import com.cretas.aims.exception.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherServiceImplTest {
    @Test void genericVoidCannotDetachDisposalVoucherFromApprovedInventoryLoss() {
        stubPostingRowLock();
        Voucher v=Voucher.builder().id("disposal-draft").factoryId("F001").sourceBusinessType("DISPOSAL_RECORD_REVISION")
                .sourceBusinessId("5#r2").status(VoucherStatus.DRAFT).voucherDate(LocalDate.now()).build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull(v.getId(),"F001")).thenReturn(Optional.of(v));
        var ex=assertThrows(com.cretas.aims.exception.BusinessException.class,()->service.voidVoucher("F001",v.getId(),"通用作废",2L));
        assertTrue(ex.getMessage().contains("报废"));verify(voucherRepo,never()).save(any());assertEquals(VoucherStatus.DRAFT,v.getStatus());
    }

    @Mock private VoucherRepository voucherRepo;
    @Mock private PaymentRequestRepository paymentRequestRepo;
    @Mock private ArApTransactionRepository arApTransactionRepo;
    @Mock private VoucherGeneratorRegistry registry;
    @Mock private SalesOrderRepository salesOrderRepo;
    @Mock private PurchaseOrderRepository purchaseOrderRepo;
    @Mock private ReturnOrderRepository returnOrderRepo;
    @Mock private InternalTransferRepository internalTransferRepo;
    @Mock private WastageRecordRepository wastageRecordRepo;
    @Mock private PayrollRecordRepository payrollRecordRepo;
    @Mock private ProductionPlanRepository productionPlanRepo;
    @Mock private LinkArrayService linkArrayService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @Mock private com.cretas.aims.repository.DisposalRecordRepository disposalRecordRepo;
    @Mock private com.cretas.aims.service.finance.AccountingPeriodService accountingPeriodService;

    private Voucher disposalVoucher() {
        var record=new com.cretas.aims.entity.DisposalRecord();record.setId(5L);record.setFactoryId("F001");
        record.setIsApproved(true);record.setApprovalStatus("APPROVED");record.setDisposalType("SCRAP");
        record.setFinishedGoodsBatchId("FG");record.setDisposalDate(LocalDateTime.now());record.setActualLoss(new BigDecimal("4.71"));
        when(disposalRecordRepo.findByIdAndFactoryIdForUpdate(5L,"F001")).thenReturn(Optional.of(record));
        var v=Voucher.builder().id("v-disposal").factoryId("F001").voucherNumber("V-2026-0189")
                .sourceBusinessType("DISPOSAL_RECORD_REVISION").sourceBusinessId("5#r2").status(VoucherStatus.POSTED)
                .voucherDate(record.getDisposalDate().toLocalDate()).voucherType(VoucherType.EXPENSE)
                .totalDebit(new BigDecimal("4.71")).totalCredit(new BigDecimal("4.71")).build();
        v.getEntries().add(VoucherEntry.builder().subjectCode("6711").debit(new BigDecimal("4.71")).credit(BigDecimal.ZERO).build());
        v.getEntries().add(VoucherEntry.builder().subjectCode("1405").debit(BigDecimal.ZERO).credit(new BigDecimal("4.71")).build());
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull(v.getId(),"F001")).thenReturn(Optional.of(v));
        return v;
    }

    @Test void postedDisposalAlsoRejectsGenericVoidEvenWithCostCorrectionReason() {
        stubPostingRowLock();var v=Voucher.builder().id("posted").factoryId("F001").status(VoucherStatus.POSTED).sourceBusinessType("DISPOSAL_RECORD").sourceBusinessId("5").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull(v.getId(),"F001")).thenReturn(Optional.of(v));
        assertThrows(com.cretas.aims.exception.BusinessException.class,()->service.voidVoucher("F001",v.getId(),"报废成本更正",2L));
        verify(voucherRepo,never()).save(any());assertEquals(VoucherStatus.POSTED,v.getStatus());
    }

    @Test void approvedDisposalCorrectionUsesRealReversalAndKeepsOriginalAudit() {
        stubPostingRowLock();var v=disposalVoucher();
        when(voucherRepo.save(any())).thenAnswer(inv->{Voucher saved=inv.getArgument(0);if(saved.getId()==null)saved.setId("red-disposal");return saved;});
        service.reverseDisposalCostVoucher("F001",5L,v.getId(),2L);
        assertEquals(VoucherStatus.REVERSED,v.getStatus());assertEquals("red-disposal",v.getReversalVoucherId());
        verify(voucherRepo,times(2)).save(any());verify(accountingPeriodService).assertOpen("F001",v.getVoucherDate().getYear(),v.getVoucherDate().getMonthValue());
    }

    @Test void correctionRejectsWrongDisposalSourceAndChangedAmountWithoutWrites() {
        stubPostingRowLock();var v=disposalVoucher();v.setSourceBusinessId("50#r2");
        assertThrows(com.cretas.aims.exception.BusinessException.class,()->service.reverseDisposalCostVoucher("F001",5L,v.getId(),2L));
        v.setSourceBusinessId("5#r2");v.setTotalDebit(BigDecimal.TEN);
        assertThrows(com.cretas.aims.exception.BusinessException.class,()->service.reverseDisposalCostVoucher("F001",5L,v.getId(),2L));
        verify(voucherRepo,never()).save(any());
    }

    @Test void lockedCorrectionPreservesVoucherAndRejectsBeforeAnySave() {
        stubPostingRowLock();var v=disposalVoucher();
        doThrow(new com.cretas.aims.exception.BusinessException(400,"期间已结账")).when(accountingPeriodService).assertOpen(anyString(),anyInt(),anyInt());
        assertThrows(com.cretas.aims.exception.BusinessException.class,()->service.reverseDisposalCostVoucher("F001",5L,v.getId(),2L));
        assertEquals(VoucherStatus.POSTED,v.getStatus());verify(voucherRepo,never()).save(any());
    }

    @InjectMocks
    private VoucherServiceImpl service;

    private final SalesReceiptVoucherGenerator realGenerator = new SalesReceiptVoucherGenerator();

    @BeforeEach
    void setUp() {
        jakarta.persistence.Query factoryLock=mock(jakarta.persistence.Query.class,RETURNS_SELF);
        lenient().when(entityManager.createNativeQuery(startsWith("SELECT pg_advisory_xact_lock"))).thenReturn(factoryLock);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "paymentRequestRepo", paymentRequestRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "arApTransactionRepo", arApTransactionRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"disposalRecordRepo",disposalRecordRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"accountingPeriodService",accountingPeriodService);
    }

    @Test
    void createFromBusinessReturnsExistingVoucherWhenIdempotentHit() {
        Voucher existing = Voucher.builder().id("v-1").factoryId("F001").build();
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "so-1"))
                .thenReturn(Optional.of(existing));

        Voucher result = service.createFromBusiness("F001", "SALES_ORDER", "so-1");

        assertSame(existing, result);
        verifyNoInteractions(registry);
        verify(voucherRepo, never()).save(any());
    }

    @Test void postingAndVoidTakeFactoryLockBeforeLookingUpVoucherRows() {
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("missing","F001")).thenReturn(Optional.empty());
        assertThrows(com.cretas.aims.exception.EntityNotFoundException.class,()->service.post("F001","missing",2L));
        org.mockito.InOrder order=inOrder(entityManager,voucherRepo);
        order.verify(entityManager).createNativeQuery(startsWith("SELECT pg_advisory_xact_lock"));
        order.verify(voucherRepo).findByIdAndFactoryIdAndDeletedAtIsNull("missing","F001");
        clearInvocations(entityManager,voucherRepo);
        assertThrows(com.cretas.aims.exception.EntityNotFoundException.class,()->service.voidVoucher("F001","missing","本地测试",2L));
        order=inOrder(entityManager,voucherRepo);
        order.verify(entityManager).createNativeQuery(startsWith("SELECT pg_advisory_xact_lock"));
        order.verify(voucherRepo).findByIdAndFactoryIdAndDeletedAtIsNull("missing","F001");
    }

    @Test
    void createFromBusinessHappyPathGeneratesAndSaves() {
        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        order.setOrderNumber("SO-2026-0001");
        order.setFactoryId("F001");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(new BigDecimal("500.00"));

        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "so-1"))
                .thenReturn(Optional.empty());
        when(salesOrderRepo.findById("so-1")).thenReturn(Optional.of(order));
        when(registry.findByBusinessType("SALES_ORDER")).thenReturn(Optional.of(realGenerator));
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        Voucher v = service.createFromBusiness("F001", "SALES_ORDER", "so-1");

        assertEquals("V-2026-0001", v.getVoucherNumber());
        assertEquals(VoucherStatus.DRAFT, v.getStatus());
        assertEquals(new BigDecimal("500.00"), v.getTotalDebit());
        assertEquals(new BigDecimal("500.00"), v.getTotalCredit());
        verify(voucherRepo).save(any(Voucher.class));
    }

    @Test
    void createFromBusinessCallsLinkArrayServiceAfterSave() {
        SalesOrder order = new SalesOrder();
        order.setId("so-1");
        order.setOrderNumber("SO-2026-0001");
        order.setFactoryId("F001");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(new BigDecimal("500.00"));

        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "so-1"))
                .thenReturn(Optional.empty());
        when(salesOrderRepo.findById("so-1")).thenReturn(Optional.of(order));
        when(registry.findByBusinessType("SALES_ORDER")).thenReturn(Optional.of(realGenerator));
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            v.setId("v-new-1");
            return v;
        });

        service.createFromBusiness("F001", "SALES_ORDER", "so-1");

        verify(linkArrayService).link(
                eq("F001"),
                eq("VOUCHER"), eq("v-new-1"),
                eq("sale"),
                eq("SALES_ORDER"), eq("so-1"),
                any(), eq(null));
    }

    @Test
    void createFromBusinessSwallowsLinkArrayServiceException() {
        SalesOrder order = new SalesOrder();
        order.setId("so-2");
        order.setOrderNumber("SO-2026-0002");
        order.setFactoryId("F001");
        order.setOrderDate(LocalDate.of(2026, 5, 16));
        order.setTotalAmount(new BigDecimal("300.00"));

        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "so-2"))
                .thenReturn(Optional.empty());
        when(salesOrderRepo.findById("so-2")).thenReturn(Optional.of(order));
        when(registry.findByBusinessType("SALES_ORDER")).thenReturn(Optional.of(realGenerator));
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            v.setId("v-new-2");
            return v;
        });
        when(linkArrayService.link(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("link table locked"));

        Voucher v = assertDoesNotThrow(
                () -> service.createFromBusiness("F001", "SALES_ORDER", "so-2"));

        assertEquals("V-2026-0001", v.getVoucherNumber());
        verify(voucherRepo).save(any(Voucher.class));
    }

    @Test
    void createFromBusinessThrowsWhenEntityMissing() {
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("SALES_ORDER", "missing"))
                .thenReturn(Optional.empty());
        when(salesOrderRepo.findById("missing")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.createFromBusiness("F001", "SALES_ORDER", "missing"));
    }

    @Test
    void createDepreciationGeneratesFromMapInput() {
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("DEPRECATION", "DEP-202605"))
                .thenReturn(Optional.empty());
        when(registry.findByBusinessType("DEPRECATION"))
                .thenReturn(Optional.of(new DepreciationVoucherGenerator()));
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(5L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> input = Map.of(
                "businessId", "DEP-202605",
                "amount", new BigDecimal("1000.00"),
                "voucherDate", LocalDate.of(2026, 5, 31),
                "assetCategory", "厂房"
        );
        Voucher v = service.createDepreciation("F001", input);

        assertEquals("V-2026-0006", v.getVoucherNumber());
        assertEquals(new BigDecimal("1000.00"), v.getTotalDebit());
    }

    @Test
    void postTransitionsDraftToPosted() {
        Voucher v = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.DRAFT).build();
        preparePosting(v);
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));
        when(voucherRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Voucher result = service.post("F001", "v-1", 42L);

        assertEquals(VoucherStatus.POSTED, result.getStatus());
        assertEquals(42L, result.getApprovedBy());
        assertNotNull(result.getApprovedAt());
    }

    @Test
    void postRejectsAlreadyPosted() {
        Voucher v = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.POSTED).build();
        preparePosting(v);
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));

        assertThrows(IllegalStateException.class, () -> service.post("F001", "v-1", 42L));
    }

    @Test
    void postRejectsCrossTenant() {
        // 跨租户: F002 调用者过账 F001 的凭证 → findByIdAndFactoryId 命中不到 → EntityNotFoundException, 不写库。
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F002")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.post("F002", "v-1", 42L));
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void voidVoucherRejectsCrossTenant() {
        // 跨租户: F002 调用者作废 F001 的凭证 → findByIdAndFactoryId 命中不到 → EntityNotFoundException, 不写库。
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F002")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.voidVoucher("F002", "v-1", "越权作废", 42L));
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void voidVoucherDraftSetsVoidStatus() {
        stubPostingRowLock();
        // DRAFT 凭证未过账, 直接置 VOID (向后兼容, 无需红字冲销)。
        Voucher v = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.DRAFT).description("foo").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));
        when(voucherRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.voidVoucher("F001", "v-1", "录入错误", 42L);

        assertEquals(VoucherStatus.VOID, v.getStatus());
        assertTrue(v.getDescription().contains("录入错误"));
    }

    @Test
    void voidVoucherRejectsReturnRefundWithoutWritingBusinessReversal() {
        stubPostingRowLock();
        Voucher v = Voucher.builder().id("v-refund").factoryId("F001")
                .sourceBusinessType("RETURN_REFUND").sourceBusinessId("refund-1")
                .status(VoucherStatus.DRAFT).build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-refund", "F001"))
                .thenReturn(Optional.of(v));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-refund", "退款作废", 42L));

        assertEquals(409, ex.getCode());
        assertEquals("RETURN_REFUND_VOID_FORBIDDEN", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void voidPostedReturnRefundRejectsBeforeRedReversal() {
        stubPostingRowLock();
        Voucher v = Voucher.builder().id("v-refund-posted").factoryId("F001")
                .sourceBusinessType("RETURN_REFUND").sourceBusinessId("refund-1")
                .status(VoucherStatus.POSTED).build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-refund-posted", "F001"))
                .thenReturn(Optional.of(v));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-refund-posted", "退款冲销", 42L));

        assertEquals(409, ex.getCode());
        assertEquals("RETURN_REFUND_VOID_FORBIDDEN", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void voidVoucherRejectsAlreadyVoided() {
        stubPostingRowLock();
        // H-BUG-4 (2026-06-21 transcript-e2e R1): 已作废凭证不可重复作废 (幂等)。
        Voucher v = Voucher.builder().id("v-1").factoryId("F001")
                .status(VoucherStatus.VOID).description("foo [作废: 第一次]").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-1", "第二次", 42L));
        assertEquals(409, ex.getCode());
        assertEquals("VOUCHER_ALREADY_VOID", ex.getErrorCode());
        // 不应再次写库, description 不应被二次追加
        verify(voucherRepo, never()).save(any());
        assertEquals("foo [作废: 第一次]", v.getDescription());
    }

    // ==================== Feature #7 (R12): 红字冲销 ====================

    /** POSTED 凭证作废 → 生成红字冲销凭证 (借贷互换), 原凭证标 REVERSED, 双向关联。 */
    @Test
    void voidPostedVoucherGeneratesRedLetterReversal() {
        stubPostingRowLock();
        LocalDateTime originalApprovalAt = LocalDateTime.of(2026, 6, 1, 9, 0);
        Voucher original = Voucher.builder()
                .id("v-orig").factoryId("F001").voucherNumber("V-2026-0007")
                .voucherType(VoucherType.SALES_RECEIPT)
                .voucherDate(LocalDate.of(2026, 6, 1))
                .sourceBusinessType("SALES_ORDER").sourceBusinessId("so-9")
                .status(VoucherStatus.POSTED)
                .totalDebit(new BigDecimal("500.00")).totalCredit(new BigDecimal("500.00"))
                .approvedBy(100L).approvedAt(originalApprovalAt)
                .description("销售订单 SO-9")
                .build();
        // 原分录: 借 应收 500 / 贷 收入 500 (含客户辅助核算)
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(1).subjectCode("1122").subjectName("应收账款")
                .debit(new BigDecimal("500.00")).credit(BigDecimal.ZERO)
                .auxiliaryType(com.cretas.aims.entity.enums.AuxiliaryType.CUSTOMER)
                .auxiliaryEntityId("cust-1").description("销售收入挂账").build());
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(2).subjectCode("6001").subjectName("主营业务收入")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("500.00"))
                .description("销售订单 SO-9").build());

        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-orig", "F001"))
                .thenReturn(Optional.of(original));
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear("F001", "2026")).thenReturn(7L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            if (v.getId() == null) v.setId("v-reversal");
            return v;
        });

        service.voidVoucher("F001", "v-orig", "客户退单", 22L);

        // 捕获两次 save: 冲销凭证 + 原凭证
        org.mockito.ArgumentCaptor<Voucher> captor = org.mockito.ArgumentCaptor.forClass(Voucher.class);
        verify(voucherRepo, times(2)).save(captor.capture());
        Voucher reversal = captor.getAllValues().get(0);
        Voucher savedOriginal = captor.getAllValues().get(1);

        // 红字冲销凭证: POSTED, 关联原凭证, 借贷互换
        assertEquals(VoucherStatus.POSTED, reversal.getStatus());
        assertEquals(22L, reversal.getApprovedBy(), "红冲凭证审批人应为本次撤销操作人");
        assertNotNull(reversal.getApprovedAt(), "红冲凭证必须记录本次撤销时间");
        assertTrue(reversal.getApprovedAt().isAfter(originalApprovalAt), "红冲时间应晚于原过账时间");
        assertEquals("v-orig", reversal.getOriginalVoucherId());
        assertEquals("V-2026-0008", reversal.getVoucherNumber());
        assertEquals(VoucherType.SALES_RECEIPT, reversal.getVoucherType());
        assertEquals(LocalDate.of(2026, 6, 1), reversal.getVoucherDate(), "冲销凭证沿用原凭证期间");
        assertEquals(2, reversal.getEntries().size());

        // ⚠️ 红字冲销凭证 source = 合成 (VOUCHER_REVERSAL, 原凭证id): 非 null 满足 prod NOT NULL 约束,
        // 且不撞原凭证的 uk_voucher_source_business 唯一约束 (type 不同). 关联另由 originalVoucherId 承载.
        assertEquals("VOUCHER_REVERSAL", reversal.getSourceBusinessType(), "冲销凭证用合成 source type (非 null, 不撞原凭证)");
        assertEquals("v-orig", reversal.getSourceBusinessId(), "冲销凭证 sourceBusinessId = 原凭证 id (唯一, 非 null)");

        // line1: 原借 应收 500 → 冲销贷 应收 500 (方向互换, 金额正数, 辅助核算保留)
        VoucherEntry r1 = reversal.getEntries().get(0);
        assertEquals("1122", r1.getSubjectCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(r1.getDebit()));
        assertEquals(0, new BigDecimal("500.00").compareTo(r1.getCredit()));
        assertEquals(com.cretas.aims.entity.enums.AuxiliaryType.CUSTOMER, r1.getAuxiliaryType());
        assertEquals("cust-1", r1.getAuxiliaryEntityId());
        // line2: 原贷 收入 500 → 冲销借 收入 500
        VoucherEntry r2 = reversal.getEntries().get(1);
        assertEquals("6001", r2.getSubjectCode());
        assertEquals(0, new BigDecimal("500.00").compareTo(r2.getDebit()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r2.getCredit()));

        // 借贷必平: totalDebit == totalCredit == 500
        assertEquals(0, new BigDecimal("500.00").compareTo(reversal.getTotalDebit()));
        assertEquals(0, new BigDecimal("500.00").compareTo(reversal.getTotalCredit()));

        // 原凭证: REVERSED, 反向关联冲销凭证
        assertEquals(VoucherStatus.REVERSED, savedOriginal.getStatus());
        assertEquals("v-reversal", savedOriginal.getReversalVoucherId());
        assertEquals(100L, savedOriginal.getApprovedBy(), "原凭证须保留原过账审批人");
        assertEquals(originalApprovalAt, savedOriginal.getApprovedAt(), "原凭证须保留原过账审批时间");
        assertTrue(savedOriginal.getDescription().contains("红字冲销"));
        assertTrue(savedOriginal.getDescription().contains("客户退单"));
    }

    /** POSTED 凭证含销项税 3 行 — 互换后仍借贷平衡。 */
    @Test
    void voidPostedVoucherWithTaxLineBalances() {
        stubPostingRowLock();
        Voucher original = Voucher.builder()
                .id("v-tax").factoryId("F001").voucherNumber("V-2026-0010")
                .voucherType(VoucherType.SALES_RECEIPT)
                .voucherDate(LocalDate.of(2026, 6, 2))
                .sourceBusinessType("SALES_ORDER").sourceBusinessId("so-tax")
                .status(VoucherStatus.POSTED)
                .totalDebit(new BigDecimal("1130.00")).totalCredit(new BigDecimal("1130.00"))
                .build();
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(1).subjectCode("1122").subjectName("应收账款")
                .debit(new BigDecimal("1130.00")).credit(BigDecimal.ZERO).build());
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(2).subjectCode("6001").subjectName("主营业务收入")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("1000.00")).build());
        original.getEntries().add(com.cretas.aims.entity.finance.VoucherEntry.builder()
                .lineNo(3).subjectCode("2221.01").subjectName("应交税费-销项税额")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("130.00")).build());

        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-tax", "F001"))
                .thenReturn(Optional.of(original));
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear("F001", "2026")).thenReturn(10L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            if (v.getId() == null) v.setId("v-rev-tax");
            return v;
        });

        // 不抛 UnbalancedVoucherException 即证明 validateBalanced 通过
        assertDoesNotThrow(() -> service.voidVoucher("F001", "v-tax", "开票错误", 1L));

        org.mockito.ArgumentCaptor<Voucher> captor = org.mockito.ArgumentCaptor.forClass(Voucher.class);
        verify(voucherRepo, times(2)).save(captor.capture());
        Voucher reversal = captor.getAllValues().get(0);
        assertEquals(3, reversal.getEntries().size());
        // 互换: 借 1000+130=1130 (原贷两行) / 贷 1130 (原借一行)
        assertEquals(0, new BigDecimal("1130.00").compareTo(reversal.getTotalDebit()));
        assertEquals(0, new BigDecimal("1130.00").compareTo(reversal.getTotalCredit()));
    }

    /** 已 REVERSED 凭证不可再次冲销/作废 (幂等 409)。 */
    @Test
    void voidReversedVoucherRejected() {
        stubPostingRowLock();
        Voucher v = Voucher.builder().id("v-r").factoryId("F001")
                .status(VoucherStatus.REVERSED).reversalVoucherId("v-rev").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-r", "F001")).thenReturn(Optional.of(v));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-r", "重复", 1L));
        assertEquals(409, ex.getCode());
        assertEquals("VOUCHER_ALREADY_REVERSED", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    /** 红字冲销凭证本身 (original_voucher_id 非 null) 不可被作废/再冲销。 */
    @Test
    void voidReversalVoucherItselfRejected() {
        stubPostingRowLock();
        Voucher reversal = Voucher.builder().id("v-rev").factoryId("F001")
                .status(VoucherStatus.POSTED).originalVoucherId("v-orig").build();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-rev", "F001"))
                .thenReturn(Optional.of(reversal));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-rev", "x", 1L));
        assertEquals(409, ex.getCode());
        assertEquals("VOUCHER_IS_REVERSAL", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    // ==================== createCashMovementVoucher (资金段 GL, finance audit Bug 5) ====================

    /** 收款: 借 1002 银行存款 / 贷 1122 应收账款 (客户辅助核算), DRAFT, 借贷平衡。 */
    @Test
    void createCashMovementVoucher_receipt_buildsBalancedDraftWithCustomerAux() {
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("CASH_RECEIPT", "txn-1"))
                .thenReturn(Optional.empty());
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear(eq("F001"), eq("2026"))).thenReturn(0L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        java.util.List<VoucherEntry> entries = java.util.List.of(
                VoucherEntry.builder().subjectCode("1002").subjectName("银行存款")
                        .debit(new BigDecimal("100.00")).credit(BigDecimal.ZERO).description("收款-客户A").build(),
                VoucherEntry.builder().subjectCode("1122").subjectName("应收账款")
                        .debit(BigDecimal.ZERO).credit(new BigDecimal("100.00"))
                        .auxiliaryType(com.cretas.aims.entity.enums.AuxiliaryType.CUSTOMER)
                        .auxiliaryEntityId("cust-1").description("冲减应收-客户A").build());

        Voucher saved = service.createCashMovementVoucher("F001", VoucherType.CASH_RECEIPT,
                LocalDate.of(2026, 5, 16), entries, "CASH_RECEIPT", "txn-1", "收款 客户A", 300L);

        assertEquals(VoucherStatus.DRAFT, saved.getStatus(), "现金流水凭证为 DRAFT (财务手工过账)");
        assertEquals(VoucherType.CASH_RECEIPT, saved.getVoucherType());
        assertEquals(new BigDecimal("100.00"), saved.getTotalDebit());
        assertEquals(new BigDecimal("100.00"), saved.getTotalCredit());
        assertEquals(2, saved.getEntries().size());
        // 客户辅助核算挂在 1122 贷方行
        VoucherEntry ar = saved.getEntries().stream().filter(e -> "1122".equals(e.getSubjectCode())).findFirst().orElseThrow();
        assertEquals(com.cretas.aims.entity.enums.AuxiliaryType.CUSTOMER, ar.getAuxiliaryType());
        assertEquals("cust-1", ar.getAuxiliaryEntityId());
        assertEquals(1, saved.getEntries().get(0).getLineNo());
        verify(voucherRepo).save(any(Voucher.class));
    }

    /** 幂等: 同一 (CASH_RECEIPT, txnId) 已有凭证 → 直接返回, 不重复 save (监听器重投防线)。 */
    @Test
    void createCashMovementVoucher_idempotentHitReturnsExistingNoSave() {
        Voucher existing = Voucher.builder().id("v-cash-1").factoryId("F001").build();
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("CASH_PAYMENT", "txn-2"))
                .thenReturn(Optional.of(existing));

        java.util.List<VoucherEntry> entries = java.util.List.of(
                VoucherEntry.builder().subjectCode("2202").subjectName("应付账款")
                        .debit(new BigDecimal("50.00")).credit(BigDecimal.ZERO).build(),
                VoucherEntry.builder().subjectCode("1002").subjectName("银行存款")
                        .debit(BigDecimal.ZERO).credit(new BigDecimal("50.00")).build());

        Voucher result = service.createCashMovementVoucher("F001", VoucherType.CASH_PAYMENT,
                LocalDate.of(2026, 5, 16), entries, "CASH_PAYMENT", "txn-2", "付款 供应商B", 300L);

        assertSame(existing, result);
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void createCashMovementVoucher_rejectsRefundWithoutBoundLedgerBeforeSave() {
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                "RETURN_REFUND", "refund-1")).thenReturn(Optional.empty());
        when(paymentRequestRepo.findById("refund-1")).thenReturn(Optional.empty());

        List<VoucherEntry> entries = List.of(
                VoucherEntry.builder().subjectCode("1122").debit(new BigDecimal("50.00"))
                        .credit(BigDecimal.ZERO).build(),
                VoucherEntry.builder().subjectCode("1001").debit(BigDecimal.ZERO)
                        .credit(new BigDecimal("50.00")).build());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createCashMovementVoucher(
                "F001", VoucherType.CASH_PAYMENT, LocalDate.of(2026, 5, 16), entries,
                "RETURN_REFUND", "refund-1", "人工付款登记", 300L));
        assertEquals(409, ex.getCode());
        assertEquals("RETURN_REFUND_EVIDENCE_INVALID", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void createManualRejectsReturnRefundSourceForgeryBeforeSave() {
        VoucherEntrySpec entry = new VoucherEntrySpec("1122", "应收账款",
                new BigDecimal("50.00"), BigDecimal.ZERO, "伪造退款");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createManual(
                "F001", VoucherType.CASH_PAYMENT, LocalDate.of(2026, 5, 16), List.of(entry),
                "RETURN_REFUND", "refund-1", "伪造退款凭证", 300L));

        assertEquals(409, ex.getCode());
        assertEquals("RETURN_REFUND_MANUAL_FORBIDDEN", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    // ==================== batchPost (Part 2 follow-up to #1228) ====================

    private void stubTransactionManagerRunsCallback() {
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
    }

    private void preparePosting(Voucher... vouchers) {
        stubPostingRowLock();
        for (Voucher voucher : vouchers) {
            voucher.setTotalDebit(new BigDecimal("10.00"));
            voucher.setTotalCredit(new BigDecimal("10.00"));
            voucher.setEntries(new java.util.ArrayList<>(List.of(
                    VoucherEntry.builder().debit(new BigDecimal("10.00")).credit(BigDecimal.ZERO).build(),
                    VoucherEntry.builder().credit(new BigDecimal("10.00")).debit(BigDecimal.ZERO).build())));
        }
    }

    private void stubPostingRowLock() {
        jakarta.persistence.Query query = mock(jakarta.persistence.Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        final String[] selected = {null};
        when(query.setParameter(eq("id"), any())).thenAnswer(inv -> { selected[0] = inv.getArgument(1); return query; });
        when(query.getResultList()).thenAnswer(inv -> List.of(selected[0]));
    }

    @Test
    void batchPostPostsAllDraftVouchers() {
        stubTransactionManagerRunsCallback();
        Voucher v1 = Voucher.builder().id("v-1").factoryId("F001").voucherNumber("V-2026-0001").status(VoucherStatus.DRAFT).build();
        Voucher v2 = Voucher.builder().id("v-2").factoryId("F001").voucherNumber("V-2026-0002").status(VoucherStatus.DRAFT).build();
        preparePosting(v1, v2);
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(v1));
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-2", "F001")).thenReturn(Optional.of(v2));
        when(voucherRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<VoucherBatchPostResultDTO> results = service.batchPost("F001", List.of("v-1", "v-2"), 42L);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(VoucherBatchPostResultDTO::isSuccess));
        assertTrue(results.stream().noneMatch(VoucherBatchPostResultDTO::isSkipped));
        assertEquals(VoucherStatus.POSTED, v1.getStatus());
        assertEquals(VoucherStatus.POSTED, v2.getStatus());
        assertEquals(42L, v1.getApprovedBy());
        verify(voucherRepo, times(2)).save(any());
    }

    @Test
    void batchPostSkipsAlreadyPostedIdempotently() {
        stubTransactionManagerRunsCallback();
        Voucher draft = Voucher.builder().id("v-1").factoryId("F001").voucherNumber("V-2026-0001").status(VoucherStatus.DRAFT).build();
        Voucher alreadyPosted = Voucher.builder().id("v-2").factoryId("F001").voucherNumber("V-2026-0002").status(VoucherStatus.POSTED).build();
        preparePosting(draft);
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(draft));
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-2", "F001")).thenReturn(Optional.of(alreadyPosted));
        when(voucherRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<VoucherBatchPostResultDTO> results = service.batchPost("F001", List.of("v-1", "v-2"), 42L);

        VoucherBatchPostResultDTO r1 = results.stream().filter(r -> "v-1".equals(r.getVoucherId())).findFirst().orElseThrow();
        VoucherBatchPostResultDTO r2 = results.stream().filter(r -> "v-2".equals(r.getVoucherId())).findFirst().orElseThrow();
        assertTrue(r1.isSuccess());
        assertFalse(r1.isSkipped());
        assertTrue(r2.isSuccess(), "已过账凭证幂等跳过, 不视为失败");
        assertTrue(r2.isSkipped());
        // 已过账的 v-2 不应再次 save (幂等, 不重复过账动作)
        verify(voucherRepo, times(1)).save(any());
    }

    @Test
    void batchPostOneBadVoucherDoesNotBlockRest() {
        stubTransactionManagerRunsCallback();
        Voucher good = Voucher.builder().id("v-1").factoryId("F001").voucherNumber("V-2026-0001").status(VoucherStatus.DRAFT).build();
        preparePosting(good);
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(good));
        // v-missing 不存在 → 该项失败, 但不应阻断 v-1 的过账
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-missing", "F001")).thenReturn(Optional.empty());
        when(voucherRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<VoucherBatchPostResultDTO> results = service.batchPost("F001", List.of("v-missing", "v-1"), 42L);

        assertEquals(2, results.size());
        VoucherBatchPostResultDTO missing = results.stream().filter(r -> "v-missing".equals(r.getVoucherId())).findFirst().orElseThrow();
        VoucherBatchPostResultDTO ok = results.stream().filter(r -> "v-1".equals(r.getVoucherId())).findFirst().orElseThrow();
        assertFalse(missing.isSuccess());
        assertNotNull(missing.getMessage());
        assertTrue(ok.isSuccess(), "一张失败不应阻断其余凭证的过账结果");
        assertEquals(VoucherStatus.POSTED, good.getStatus());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("🔴 号段已用到 0302 而未删只剩 66 时, 下一个号必须是 0303 而不是 0067")
    void voucherNumberFollowsMaxSequenceNotRowCount() {
        // prod 实证 (F006, 2026-08-03): 2026 年凭证总 302 条 / 未删仅 66 / 最大号 V-2026-0302。
        // 旧实现按「未删条数+1」→ V-2026-0067, 那个号早被占了 → 期初建账固定 409 数据已存在,
        // 且每次算出同一个号 = 永久撞。此前被误记成「同一工厂当天只能期初建账一次」的额度。
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear(eq("F006"), eq("2026"))).thenReturn(302L);
        // 只调私有的号码生成, 不落库 —— 多 stub 一个 save 会被严格模式判为 UnnecessaryStubbing
        String generated = (String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "generateVoucherNumber", "F006", java.time.LocalDate.of(2026, 8, 3));

        org.assertj.core.api.Assertions.assertThat(generated)
                .as("必须接在已占用的最大号之后, 不能回到被软删行让出的空档")
                .isEqualTo("V-2026-0303");
    }

    @Test
    void postRejectsEmptyAndUnbalancedEntriesWithoutPersisting() {
        Voucher voucher = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.DRAFT).build();
        preparePosting(voucher);
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(voucher));
        voucher.getEntries().clear();
        assertThrows(BusinessException.class, () -> service.post("F001", "v-1", 42L));
        voucher.setEntries(List.of(VoucherEntry.builder().debit(new BigDecimal("10")).credit(BigDecimal.ZERO).build()));
        assertThrows(com.cretas.aims.exception.UnbalancedVoucherException.class,
                () -> service.post("F001", "v-1", 42L));
        assertEquals(VoucherStatus.DRAFT, voucher.getStatus());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void postRechecksStatusAfterTakingLockAndRequiresActor() {
        Voucher voucher = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.DRAFT).build();
        preparePosting(voucher);
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(voucher));
        doAnswer(inv -> {voucher.setStatus(VoucherStatus.POSTED); return null;}).when(entityManager).refresh(voucher);
        assertThrows(IllegalStateException.class, () -> service.post("F001", "v-1", 42L));
        assertThrows(BusinessException.class, () -> service.post("F001", "v-1", null));
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void voidRechecksConcurrentTerminalStatusAfterLock() {
        Voucher voucher = Voucher.builder().id("v-1").factoryId("F001").status(VoucherStatus.DRAFT).build();
        stubPostingRowLock();
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(voucher));
        doAnswer(inv -> {voucher.setStatus(VoucherStatus.REVERSED); return null;}).when(entityManager).refresh(voucher);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.voidVoucher("F001", "v-1", "并发状态已变", 42L));
        assertEquals("VOUCHER_ALREADY_REVERSED", error.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void postRetainsClosedPeriodGateAfterBalanceAndLockChecks() {
        Voucher voucher = Voucher.builder().id("v-1").factoryId("F001")
                .voucherDate(LocalDate.of(2026, 10, 16)).status(VoucherStatus.DRAFT).build();
        preparePosting(voucher);
        when(voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull("v-1", "F001")).thenReturn(Optional.of(voucher));
        com.cretas.aims.service.finance.AccountingPeriodService periods = mock(com.cretas.aims.service.finance.AccountingPeriodService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "accountingPeriodService", periods);
        BusinessException closed = new BusinessException(409, "该会计期间已结账");
        doThrow(closed).when(periods).assertOpen("F001", 2026, 10);
        assertSame(closed, assertThrows(BusinessException.class, () -> service.post("F001", "v-1", 42L)));
        assertEquals(VoucherStatus.DRAFT, voucher.getStatus());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void existingBusinessAndCashVouchersCannotBeReadAcrossFactories() {
        Voucher other = Voucher.builder().id("other").factoryId("F002").build();
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_ORDER", "return"))
                .thenReturn(Optional.of(other));
        assertThrows(BusinessException.class, () -> service.createFromBusiness("F001", "RETURN_ORDER", "return"));
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_REFUND", "refund"))
                .thenReturn(Optional.of(other));
        assertThrows(BusinessException.class, () -> service.createCashMovementVoucher("F001", VoucherType.CASH_PAYMENT,
                LocalDate.now(), List.of(new VoucherEntry()), "RETURN_REFUND", "refund", "客户退款", 42L));
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void reverseReturnRefundRejectsWhenApprovedReversalLedgerIsMissing() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.DRAFT);
        stubRefundReversal(fixture, List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reverseReturnRefund("F001", "req-1", "收回退款", 22L));

        assertEquals("RETURN_REFUND_REVERSAL_EVIDENCE_INVALID", ex.getErrorCode());
        assertEquals(VoucherStatus.DRAFT, fixture.voucher.getStatus());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void reverseReturnRefundDraftVoidsOnlyTheOriginalRefundVoucher() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.DRAFT);
        stubRefundReversal(fixture, List.of(fixture.reversal));
        stubPostingRowLock();
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        Voucher returned = service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L);

        assertSame(fixture.voucher, returned);
        assertEquals(VoucherStatus.VOID, fixture.voucher.getStatus());
        assertEquals(22L, fixture.voucher.getApprovedBy());
        assertNotNull(fixture.voucher.getApprovedAt());
        assertFalse(fixture.voucher.getApprovedAt().isBefore(fixture.reversal.getApprovedAt()));
        assertTrue(fixture.voucher.getDescription().contains("客户已收回"));
        verify(voucherRepo).save(fixture.voucher);
    }

    @Test
    void reverseReturnRefundIgnoresRejectedHistoryWhenApprovedReversalExists() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.DRAFT);
        ArApTransaction rejected = copyReversal(fixture.reversal, "rejected-ledger", ArApApprovalStatus.REJECTED, 24L);
        stubRefundReversal(fixture, List.of(rejected, fixture.reversal));
        stubPostingRowLock();
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L);

        assertEquals(VoucherStatus.VOID, fixture.voucher.getStatus());
        verify(voucherRepo).save(fixture.voucher);
    }

    @Test
    void reverseReturnRefundRejectsMultipleActiveReversals() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.DRAFT);
        ArApTransaction duplicate = copyReversal(fixture.reversal, "reversal-ledger-2", ArApApprovalStatus.APPROVED, 22L);
        stubRefundReversal(fixture, List.of(fixture.reversal, duplicate));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L));

        assertEquals("RETURN_REFUND_REVERSAL_EVIDENCE_INVALID", ex.getErrorCode());
        assertEquals(VoucherStatus.DRAFT, fixture.voucher.getStatus());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void reverseReturnRefundPostedUsesExistingRedLetterReversal() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.POSTED);
        stubRefundReversal(fixture, List.of(fixture.reversal));
        stubPostingRowLock();
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear("F001", "2026")).thenReturn(7L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId("v-refund-reversal");
            return saved;
        });

        Voucher returned = service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L);

        assertSame(fixture.voucher, returned);
        assertEquals(VoucherStatus.REVERSED, fixture.voucher.getStatus());
        assertEquals("v-refund-reversal", fixture.voucher.getReversalVoucherId());
        org.mockito.ArgumentCaptor<Voucher> captor = org.mockito.ArgumentCaptor.forClass(Voucher.class);
        verify(voucherRepo, times(2)).save(captor.capture());
        Voucher reversalVoucher = captor.getAllValues().get(0);
        assertEquals(VoucherStatus.POSTED, reversalVoucher.getStatus());
        assertEquals("v-refund", reversalVoucher.getOriginalVoucherId());
        assertEquals(22L, reversalVoucher.getApprovedBy());
        assertNotNull(reversalVoucher.getApprovedAt());
        assertFalse(reversalVoucher.getApprovedAt().isBefore(fixture.reversal.getApprovedAt()));
    }

    @Test
    void reverseReturnRefundKeepsOriginalAccountingDateAcrossApprovalMonth() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.POSTED);
        fixture.voucher.setVoucherDate(LocalDate.of(2026, 10, 31));
        fixture.refundLedger.setTransactionDate(LocalDate.of(2026, 10, 31));
        fixture.reversal.setTransactionDate(LocalDate.of(2026, 10, 31));
        fixture.reversal.setApprovedAt(LocalDateTime.of(2026, 11, 1, 9, 0));
        stubRefundReversal(fixture, List.of(fixture.reversal));
        stubPostingRowLock();
        when(voucherRepo.maxVoucherSequenceByFactoryIdAndYear("F001", "2026")).thenReturn(7L);
        when(voucherRepo.save(any(Voucher.class))).thenAnswer(inv -> {
            Voucher saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId("v-refund-reversal");
            return saved;
        });

        Voucher returned = service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L);

        assertSame(fixture.voucher, returned);
        org.mockito.ArgumentCaptor<Voucher> captor = org.mockito.ArgumentCaptor.forClass(Voucher.class);
        verify(voucherRepo, times(2)).save(captor.capture());
        Voucher mirror = captor.getAllValues().get(0);
        assertEquals(LocalDate.of(2026, 10, 31), mirror.getVoucherDate());
    }

    @Test
    void reverseReturnRefundRejectsClosedOriginalPeriodBeforeAnyWrite() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.POSTED);
        fixture.voucher.setVoucherDate(LocalDate.of(2026, 10, 31));
        fixture.refundLedger.setTransactionDate(LocalDate.of(2026, 10, 31));
        fixture.reversal.setTransactionDate(LocalDate.of(2026, 10, 31));
        stubRefundReversal(fixture, List.of(fixture.reversal));
        stubPostingRowLock();
        com.cretas.aims.service.finance.AccountingPeriodService periods =
                mock(com.cretas.aims.service.finance.AccountingPeriodService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "accountingPeriodService", periods);
        BusinessException originalClosed = new BusinessException(409, "原退款期间已结账");
        doThrow(originalClosed).when(periods).assertOpen("F001", 2026, 10);

        assertSame(originalClosed, assertThrows(BusinessException.class,
                () -> service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L)));

        verify(voucherRepo, never()).save(any());
        verify(periods).assertOpen("F001", 2026, 10);
    }

    @Test
    void reverseReturnRefundRejectsPostedVoucherMissingOriginalApprovalBeforeWrite() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.POSTED);
        fixture.voucher.setApprovedBy(null);
        fixture.voucher.setApprovedAt(null);
        stubRefundReversal(fixture, List.of(fixture.reversal));
        stubPostingRowLock();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L));

        assertEquals("RETURN_REFUND_EVIDENCE_INVALID", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void reverseReturnRefundPreservesClosedPeriodGate() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.DRAFT);
        stubRefundReversal(fixture, List.of(fixture.reversal));
        stubPostingRowLock();
        com.cretas.aims.service.finance.AccountingPeriodService periods =
                mock(com.cretas.aims.service.finance.AccountingPeriodService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "accountingPeriodService", periods);
        BusinessException closed = new BusinessException(409, "该会计期间已结账");
        doThrow(closed).when(periods).assertOpen("F001", 2026, 10);

        assertSame(closed, assertThrows(BusinessException.class,
                () -> service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L)));
        assertEquals(VoucherStatus.DRAFT, fixture.voucher.getStatus());
        verify(voucherRepo, never()).save(any());
    }

    @Test
    void reverseReturnRefundRejectsVoucherWithWrongBusinessSource() {
        RefundReversalFixture fixture = refundReversalFixture(VoucherStatus.DRAFT);
        fixture.voucher.setSourceBusinessType("RETURN_ORDER");
        stubRefundReversal(fixture, List.of(fixture.reversal));
        stubPostingRowLock();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reverseReturnRefund("F001", "req-1", "客户已收回", 22L));

        assertEquals("RETURN_REFUND_EVIDENCE_INVALID", ex.getErrorCode());
        verify(voucherRepo, never()).save(any());
    }

    private void stubRefundReversal(RefundReversalFixture fixture, List<ArApTransaction> reversalRows) {
        when(paymentRequestRepo.findById("req-1")).thenReturn(Optional.of(fixture.request));
        when(returnOrderRepo.findById("return-1")).thenReturn(Optional.of(fixture.order));
        when(voucherRepo.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_REFUND", "req-1"))
                .thenReturn(Optional.of(fixture.voucher));
        when(arApTransactionRepo.findById("refund-ledger")).thenReturn(Optional.of(fixture.refundLedger));
        when(arApTransactionRepo.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                "F001", "RETURN_REFUND_REVERSAL", List.of("req-1"), ArApTransactionType.AR_ADJUSTMENT))
                .thenReturn(reversalRows);
    }

    private RefundReversalFixture refundReversalFixture(VoucherStatus status) {
        LocalDate date = LocalDate.of(2026, 10, 16);
        LocalDateTime approvedAt = LocalDateTime.of(2026, 10, 16, 10, 0);
        ReturnOrder order = new ReturnOrder();
        order.setId("return-1");
        order.setFactoryId("F001");
        order.setReturnNumber("RO-1");
        order.setReturnType(ReturnType.SALES_RETURN);
        order.setStatus(ReturnOrderStatus.COMPLETED);
        order.setCounterpartyId("cust-1");
        order.setSourceOrderId("so-1");
        order.setReturnDate(date);
        order.setTotalAmount(new BigDecimal("10.00"));
        order.setWithGoods(true);

        PaymentRequest request = new PaymentRequest();
        request.setId("req-1");
        request.setFactoryId("F001");
        request.setSourceType(PaymentSourceType.SALES);
        request.setReturnOrderId("return-1");
        request.setSalesOrderId("so-1");
        request.setCustomerId("cust-1");
        request.setStatus(PaymentRequestStatus.PAID);
        request.setAmount(new BigDecimal("10.00"));
        request.setPaymentMethod(PaymentMethod.CASH.name());
        request.setPaymentReference("refund-ref");
        request.setCreatedBy(11L);
        request.setApprovedBy(12L);
        request.setApprovedAt(approvedAt);
        request.setPaidBy(13L);
        request.setPaidAt(approvedAt.plusHours(1));
        request.setArapTransactionId("refund-ledger");

        ArApTransaction refundLedger = new ArApTransaction();
        refundLedger.setId("refund-ledger"); refundLedger.setFactoryId("F001");
        refundLedger.setTransactionNumber("AR-RF-1"); refundLedger.setTransactionType(ArApTransactionType.AR_REFUND);
        refundLedger.setCounterpartyType(CounterpartyType.CUSTOMER); refundLedger.setCounterpartyId("cust-1");
        refundLedger.setSalesOrderId("so-1"); refundLedger.setSourceType("RETURN_REFUND");
        refundLedger.setSourceId("req-1"); refundLedger.setAmount(new BigDecimal("10.00"));
        refundLedger.setBalanceAfter(new BigDecimal("10.00")); refundLedger.setPaymentMethod(PaymentMethod.CASH);
        refundLedger.setPaymentReference("refund-ref"); refundLedger.setTransactionDate(date);
        refundLedger.setOperatedBy(13L); refundLedger.setApprovalStatus(ArApApprovalStatus.APPROVED);
        refundLedger.setApprovedBy(12L); refundLedger.setApprovedAt(approvedAt);

        ArApTransaction reversal = new ArApTransaction();
        reversal.setId("reversal-ledger"); reversal.setFactoryId("F001");
        reversal.setTransactionNumber("AR-RV-1"); reversal.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        reversal.setCounterpartyType(CounterpartyType.CUSTOMER); reversal.setCounterpartyId("cust-1");
        reversal.setSalesOrderId("so-1"); reversal.setSourceType("RETURN_REFUND_REVERSAL");
        reversal.setSourceId("req-1"); reversal.setAmount(new BigDecimal("-10.00"));
        reversal.setBalanceAfter(BigDecimal.ZERO); reversal.setPaymentMethod(PaymentMethod.CASH);
        reversal.setPaymentReference("recover-ref");
        reversal.setTransactionDate(date); reversal.setOperatedBy(13L);
        reversal.setApprovalStatus(ArApApprovalStatus.APPROVED); reversal.setApprovedBy(22L);
        reversal.setApprovedAt(approvedAt.plusHours(2)); reversal.setRemark("客户已收回");

        Voucher voucher = Voucher.builder().id("v-refund").factoryId("F001").voucherNumber("V-2026-0007")
                .voucherType(VoucherType.CASH_PAYMENT).voucherDate(date)
                .sourceBusinessType("RETURN_REFUND").sourceBusinessId("req-1").status(status)
                .totalDebit(new BigDecimal("10.00")).totalCredit(new BigDecimal("10.00"))
                .description("客户退货退款").build();
        voucher.getEntries().add(VoucherEntry.builder().lineNo(1).subjectCode("1122").subjectName("应收账款")
                .debit(new BigDecimal("10.00")).credit(BigDecimal.ZERO).auxiliaryType(AuxiliaryType.CUSTOMER)
                .auxiliaryEntityId("cust-1").voucher(voucher).build());
        voucher.getEntries().add(VoucherEntry.builder().lineNo(2).subjectCode("1001").subjectName("库存现金")
                .debit(BigDecimal.ZERO).credit(new BigDecimal("10.00")).voucher(voucher).build());
        if (status == VoucherStatus.POSTED) {
            voucher.setApprovedBy(100L);
            voucher.setApprovedAt(approvedAt);
        }
        return new RefundReversalFixture(order, request, refundLedger, reversal, voucher);
    }

    private ArApTransaction copyReversal(ArApTransaction source, String id,
                                         ArApApprovalStatus approvalStatus, Long approver) {
        ArApTransaction copy = new ArApTransaction();
        copy.setId(id); copy.setFactoryId(source.getFactoryId()); copy.setTransactionNumber(id);
        copy.setTransactionType(source.getTransactionType()); copy.setCounterpartyType(source.getCounterpartyType());
        copy.setCounterpartyId(source.getCounterpartyId()); copy.setSalesOrderId(source.getSalesOrderId());
        copy.setSourceType(source.getSourceType()); copy.setSourceId(source.getSourceId());
        copy.setAmount(source.getAmount()); copy.setBalanceAfter(source.getBalanceAfter());
        copy.setPaymentMethod(source.getPaymentMethod()); copy.setPaymentReference(source.getPaymentReference());
        copy.setTransactionDate(source.getTransactionDate()); copy.setOperatedBy(source.getOperatedBy());
        copy.setApprovalStatus(approvalStatus); copy.setApprovedBy(approver);
        copy.setApprovedAt(source.getApprovedAt()); copy.setRemark(source.getRemark());
        return copy;
    }

    private record RefundReversalFixture(ReturnOrder order, PaymentRequest request,
                                         ArApTransaction refundLedger, ArApTransaction reversal, Voucher voucher) {
    }
}
