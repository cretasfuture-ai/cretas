package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.ReturnRefundDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.PaymentSourceType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.service.voucher.VoucherService;
import com.cretas.aims.service.voucher.ReturnRefundEvidence;
import com.cretas.aims.service.voucher.ReturnRefundReversalEvidence;
import com.cretas.aims.entity.finance.Voucher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Local, source-bound customer refund workflow; it never calls an external gateway. */
@Service
@RequiredArgsConstructor
public class ReturnRefundService {
    private static final String RETURN_SOURCE = "RETURN_REFUND";
    private static final List<PaymentRequestStatus> RESERVED = List.of(
            PaymentRequestStatus.PENDING, PaymentRequestStatus.FINANCE_REVIEW,
            PaymentRequestStatus.APPROVED, PaymentRequestStatus.PAID);

    private final ReturnOrderRepository returnOrderRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final ArApTransactionRepository arApTransactionRepository;
    private final CustomerRepository customerRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherService voucherService;
    private final UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.finance.FinancePaymentReferenceLock paymentReferenceLock;
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public ReturnRefundDTO get(String factoryId, String returnOrderId) {
        ReturnOrder order = loadOrder(factoryId, returnOrderId);
        PaymentRequest request = latestRequest(factoryId, returnOrderId);
        return viewOf(factoryId, order, request);
    }

    private ReturnRefundDTO viewOf(String factoryId, ReturnOrder order, PaymentRequest request) {
        if (request != null) assertRequestSource(order, request);
        Eligibility eligibility;
        try {
            SalesOrder salesOrder = loadSalesOrder(factoryId, order.getSourceOrderId(), false);
            if (request != null && request.getStatus() == PaymentRequestStatus.PAID) {
                eligibility = new Eligibility("PAID", null, BigDecimal.ZERO);
            } else {
                eligibility = evaluate(factoryId, order, salesOrder, request == null ? null : request.getId());
                if (request != null && request.getStatus() == PaymentRequestStatus.CANCELLED) {
                    eligibility = new Eligibility("REVERSED", null, eligibility.refundableAmount());
                }
            }
        } catch (BusinessException ex) {
            eligibility = new Eligibility("BLOCKED", ex.getMessage(), BigDecimal.ZERO);
        }
        return toView(order, request, eligibility);
    }

    @Transactional(readOnly = true)
    public ReturnRefundDTO getByRequest(String factoryId, String requestId) {
        PaymentRequest request = paymentRequestRepository.findById(requestId)
                .filter(row -> row.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(404, "退款申请不存在"));
        if (!Objects.equals(factoryId, request.getFactoryId())) {
            throw new BusinessException(403, "退款申请不属于当前工厂");
        }
        if (request.getSourceType() != PaymentSourceType.SALES
                || request.getReturnOrderId() == null || request.getReturnOrderId().isBlank()) {
            throw conflict("该付款申请缺少明确的销售退货来源，不能猜测资金归属")
                    .withCode("RETURN_REFUND_SOURCE_REQUIRED");
        }
        return viewOf(factoryId, loadOrder(factoryId, request.getReturnOrderId()), request);
    }

    @Transactional
    public PaymentRequest create(String factoryId, String returnOrderId,
                                 ReturnRefundDTO.CreateRequest command, Long actorId) {
        requireActor(actorId);
        if (command == null || command.getAmount() == null || command.getAmount().signum() <= 0
                || command.getRemark() == null || command.getRemark().isBlank()) {
            throw new BusinessException(400, "退款金额和说明不能为空且金额必须大于0")
                    .withCode("RETURN_REFUND_REQUEST_INVALID");
        }
        ReturnOrder order = loadOrder(factoryId, returnOrderId);
        SalesOrder salesOrder = loadSalesOrder(factoryId, order.getSourceOrderId(), true);
        lockOrder(order, factoryId);
        Customer customer = lockCustomer(factoryId, order.getCounterpartyId());
        ensureCustomerMatches(order, salesOrder, customer);
        PaymentRequest existing = latestRequest(factoryId, returnOrderId);
        if (existing != null) assertRequestSource(order, existing);
        if (existing != null && existing.getStatus() == PaymentRequestStatus.CANCELLED) {
            // Reapplication must not hide a broken or only partially reversed historical refund.
            toView(order, existing, new Eligibility("REVERSED", null, BigDecimal.ZERO));
        }
        if (existing != null && isLive(existing.getStatus())) {
            if (existing.getAmount().compareTo(command.getAmount()) == 0) return existing;
            throw conflict("该退货已有退款申请，金额不一致，请打开原申请核对");
        }
        Eligibility eligibility = evaluate(factoryId, order, salesOrder, null);
        if (command.getAmount().compareTo(eligibility.refundableAmount()) != 0) {
            throw conflict("请按本次核定可退金额一次结清，暂不支持拆分付款；本次应退 "
                    + eligibility.refundableAmount())
                    .withCode("RETURN_REFUND_AMOUNT_MUST_SETTLE_IN_FULL");
        }
        PaymentRequest request = new PaymentRequest();
        request.setFactoryId(factoryId);
        request.setRequestNumber("RF-" + LocalDate.now().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        request.setSourceType(PaymentSourceType.SALES);
        request.setSalesOrderId(salesOrder.getId());
        request.setCustomerId(order.getCounterpartyId());
        request.setReturnOrderId(order.getId());
        request.setAmount(command.getAmount());
        request.setStatus(PaymentRequestStatus.PENDING);
        request.setCreatedBy(actorId);
        request.setRemark(command.getRemark().trim());
        return paymentRequestRepository.save(request);
    }

    @Transactional
    public PaymentRequest submit(String factoryId, String returnOrderId, Long actorId) {
        requireActor(actorId);
        PaymentRequest request = loadRequest(factoryId, returnOrderId, true);
        requireStatus(request, PaymentRequestStatus.PENDING, "只有待提交的退款申请可以提交");
        request.setStatus(PaymentRequestStatus.FINANCE_REVIEW);
        return paymentRequestRepository.save(request);
    }

    @Transactional
    public PaymentRequest approve(String factoryId, String returnOrderId, Long actorId, String note) {
        requireActor(actorId);
        PaymentRequest request = loadRequest(factoryId, returnOrderId, false);
        ReturnOrder order = loadOrder(factoryId, returnOrderId);
        SalesOrder salesOrder = loadSalesOrder(factoryId, order.getSourceOrderId(), true);
        lockOrder(order, factoryId);
        request = lockRequest(factoryId, returnOrderId, request.getId());
        requireStatus(request, PaymentRequestStatus.FINANCE_REVIEW, "只有财务审核中的退款申请可以批准");
        if (Objects.equals(actorId, request.getCreatedBy())) {
            throw conflict("申请人不能审批自己的退款申请").withCode("RETURN_REFUND_FOUR_EYES");
        }
        Customer customer = lockCustomer(factoryId, order.getCounterpartyId());
        ensureCustomerMatches(order, salesOrder, customer);
        Eligibility eligibility = evaluate(factoryId, order, salesOrder, request.getId());
        if (request.getAmount().compareTo(eligibility.refundableAmount()) != 0) {
            throw conflict("请按本次核定可退金额一次结清，暂不支持拆分付款；当前应退 "
                    + eligibility.refundableAmount())
                    .withCode("RETURN_REFUND_AMOUNT_MUST_SETTLE_IN_FULL");
        }
        request.setApprovedBy(actorId);
        request.setApprovedAt(LocalDateTime.now());
        request.setApproveNote(note == null ? null : note.trim());
        request.setStatus(PaymentRequestStatus.APPROVED);
        return paymentRequestRepository.save(request);
    }

    @Transactional
    public PaymentRequest markPaid(String factoryId, String returnOrderId, Long actorId,
                                   ReturnRefundDTO.MarkPaidRequest command) {
        requireActor(actorId);
        if (command == null || command.getPaymentMethod() == null || command.getPaymentReference() == null
                || command.getPaymentReference().isBlank() || command.getRemark() == null
                || command.getRemark().isBlank()) {
            throw new BusinessException(400, "付款方式、非空凭证号和付款说明均不能为空")
                    .withCode("RETURN_REFUND_PAYMENT_EVIDENCE_REQUIRED");
        }
        PaymentMethod method = parseRefundMethod(command.getPaymentMethod());
        String reference = paymentReferenceLock.acquire(factoryId, command.getPaymentReference());
        PaymentRequest request = loadRequest(factoryId, returnOrderId, false);
        ReturnOrder order = loadOrder(factoryId, returnOrderId);
        SalesOrder salesOrder = loadSalesOrder(factoryId, order.getSourceOrderId(), true);
        lockOrder(order, factoryId);
        request = lockRequest(factoryId, returnOrderId, request.getId());
        requireStatus(request, PaymentRequestStatus.APPROVED, "只有已批准的退款申请可以登记付款");
        if (Objects.equals(actorId, request.getCreatedBy())) {
            throw conflict("申请人不能登记自己的退款付款").withCode("RETURN_REFUND_FOUR_EYES");
        }
        if (request.getApprovedBy() == null || request.getApprovedAt() == null
                || Objects.equals(request.getApprovedBy(), request.getCreatedBy())) {
            throw conflict("退款申请缺少独立财务审批证据，付款未登记")
                    .withCode("RETURN_REFUND_APPROVAL_EVIDENCE_MISSING");
        }
        Customer customer = lockCustomer(factoryId, order.getCounterpartyId());
        ensureCustomerMatches(order, salesOrder, customer);
        Eligibility eligibility = evaluate(factoryId, order, salesOrder, request.getId());
        if (request.getAmount().compareTo(eligibility.refundableAmount()) != 0) {
            throw conflict("请按本次核定可退金额一次结清，暂不支持拆分付款；当前应退 "
                    + eligibility.refundableAmount()).withCode("RETURN_REFUND_AMOUNT_MUST_SETTLE_IN_FULL");
        }

        request.setPaymentMethod(method.name());
        request.setPaymentReference(reference);
        request.setRemark(command.getRemark().trim());
        request.setPaidBy(actorId);
        LocalDateTime paidAt = LocalDateTime.now();
        request.setPaidAt(paidAt);

        if (arApTransactionRepository
                .findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                        factoryId, RETURN_SOURCE, request.getId(), ArApTransactionType.AR_REFUND)
                .isPresent()) {
            throw conflict("该退款申请已有退款台账，请先核对后再登记").withCode("RETURN_REFUND_LEDGER_EXISTS");
        }
        if (arApTransactionRepository.existsByFactoryIdAndPaymentReference(
                factoryId, request.getPaymentReference())) {
            throw conflict("付款凭证号已在本工厂使用，请填写唯一凭证号").withCode("RETURN_REFUND_PAYMENT_REFERENCE_DUPLICATE");
        }
        ArApTransaction transaction = createRefundTransaction(
                factoryId, request, order, salesOrder, customer, method, actorId, paidAt.toLocalDate());
        request.setArapTransactionId(transaction.getId());
        customer.setCurrentBalance(transaction.getBalanceAfter());
        customerRepository.save(customer);

        List<VoucherEntry> entries = new ArrayList<>();
        entries.add(VoucherEntry.builder().subjectCode("1122").subjectName("应收账款")
                .debit(request.getAmount()).credit(BigDecimal.ZERO)
                .auxiliaryType(AuxiliaryType.CUSTOMER).auxiliaryEntityId(customer.getId())
                .description("客户退货退款结清 " + order.getReturnNumber()).build());
        entries.add(VoucherEntry.builder().subjectCode(method == PaymentMethod.CASH ? "1001" : "1002")
                .subjectName(method == PaymentMethod.CASH ? "库存现金" : "银行存款")
                .debit(BigDecimal.ZERO).credit(request.getAmount())
                .description("客户退货退款付款 " + request.getPaymentReference()).build());
        voucherService.createCashMovementVoucher(factoryId, VoucherType.CASH_PAYMENT, paidAt.toLocalDate(), entries,
                RETURN_SOURCE, request.getId(), "客户退货退款（人工付款登记）", actorId);
        request.setStatus(PaymentRequestStatus.PAID);
        return paymentRequestRepository.save(request);
    }

    @Transactional
    public PaymentRequest reject(String factoryId, String returnOrderId, Long actorId, String reason) {
        requireActor(actorId);
        if (reason == null || reason.isBlank()) throw new BusinessException(400, "拒绝原因不能为空");
        PaymentRequest request = loadRequest(factoryId, returnOrderId, true);
        if (request.getStatus() != PaymentRequestStatus.PENDING
                && request.getStatus() != PaymentRequestStatus.FINANCE_REVIEW) {
            throw conflict("当前退款状态不可拒绝");
        }
        if (Objects.equals(actorId, request.getCreatedBy())) {
            throw conflict("申请人不能拒绝自己的退款申请").withCode("RETURN_REFUND_FOUR_EYES");
        }
        request.setStatus(PaymentRequestStatus.REJECTED);
        request.setRejectReason(reason.trim());
        return paymentRequestRepository.save(request);
    }

    @Transactional
    public PaymentRequest requestReversal(String factoryId, String returnOrderId, Long actorId,
                                         ReturnRefundDTO.ReversalRequest command) {
        requireActor(actorId);
        if (command == null || command.getRequestId() == null || command.getRequestId().isBlank()
                || command.getPaymentReference() == null || command.getPaymentReference().isBlank()
                || command.getPaymentReference().trim().length() > 100
                || command.getReason() == null || command.getReason().isBlank()
                || command.getReason().trim().length() > 400) {
            throw new BusinessException(400, "请选择原退款，填写撤销原因（最多400字）和款项收回凭据（最多100字）");
        }
        String reference = paymentReferenceLock.acquire(factoryId, command.getPaymentReference());
        LockedRefund locked = lockExactRefund(factoryId, returnOrderId, command.getRequestId());
        PaymentRequest request = locked.request();
        ArApTransaction existing = reversalFor(locked.order(), request);
        if (existing != null && existing.getApprovalStatus() != ArApApprovalStatus.REJECTED) {
            if (!Objects.equals(existing.getPaymentReference(), command.getPaymentReference().trim())
                    || !Objects.equals(existing.getRemark(), command.getReason().trim())) {
                throw conflict("该退款已有撤销申请，请打开原申请核对，不可重复登记");
            }
            toView(locked.order(), request, new Eligibility("PAID", null, BigDecimal.ZERO));
            return request;
        }
        requireStatus(request, PaymentRequestStatus.PAID, "只有已登记付款的退款可以申请撤销");
        OriginalRefund originalRefund = requireOriginalRefund(locked.order(), request);
        if (arApTransactionRepository.existsByFactoryIdAndPaymentReference(factoryId, reference)) {
            throw conflict("该款项收回凭据已在本工厂使用，请核对后填写唯一凭据")
                    .withCode("RETURN_REFUND_REVERSAL_REFERENCE_DUPLICATE");
        }
        ArApTransaction tx = new ArApTransaction();
        tx.setFactoryId(factoryId);
        tx.setTransactionNumber("AR-RFREV-" + LocalDate.now().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        tx.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        tx.setCounterpartyType(CounterpartyType.CUSTOMER);
        tx.setCounterpartyId(request.getCustomerId());
        tx.setCounterpartyName(locked.customer().getName());
        tx.setSalesOrderId(request.getSalesOrderId());
        tx.setSourceType(ReturnRefundReversalEvidence.SOURCE);
        tx.setSourceId(request.getId());
        tx.setAmount(request.getAmount().negate());
        tx.setBalanceAfter(locked.customer().getCurrentBalance() == null
                ? BigDecimal.ZERO : locked.customer().getCurrentBalance());
        tx.setPaymentMethod(parseRefundMethod(request.getPaymentMethod()));
        tx.setPaymentReference(reference);
        tx.setTransactionDate(originalRefund.accountingDate());
        tx.setOperatedBy(actorId);
        tx.setRemark(command.getReason().trim());
        tx.setApprovalStatus(ArApApprovalStatus.PENDING);
        ReturnRefundReversalEvidence.requireReversal(locked.order(), request, tx);
        arApTransactionRepository.save(tx);
        return request;
    }

    @Transactional
    public PaymentRequest approveReversal(String factoryId, String returnOrderId, Long actorId,
                                         ReturnRefundDTO.ReversalDecision command) {
        requireActor(actorId);
        requireDecision(command);
        LockedRefund locked = lockExactRefund(factoryId, returnOrderId, command.getRequestId());
        PaymentRequest request = locked.request();
        ArApTransaction tx = exactReversal(locked.order(), request, command.getReversalTransactionId());
        if (Objects.equals(actorId, tx.getOperatedBy())) {
            throw conflict("撤销申请人不能审批自己的申请，请由另一位财务人员确认")
                    .withCode("RETURN_REFUND_REVERSAL_FOUR_EYES");
        }
        if (request.getStatus() == PaymentRequestStatus.CANCELLED
                && tx.getApprovalStatus() == ArApApprovalStatus.APPROVED) {
            toView(locked.order(), request, new Eligibility("REVERSED", null, BigDecimal.ZERO));
            return request;
        }
        requireStatus(request, PaymentRequestStatus.PAID, "原退款当前状态不可撤销，请重新读取");
        if (tx.getApprovalStatus() != ArApApprovalStatus.PENDING) throw conflict("只有待审核的退款撤销可以确认");
        requireOriginalRefund(locked.order(), request);
        BigDecimal balance = locked.customer().getCurrentBalance();
        if (balance == null) throw conflict("客户余额缺失，退款撤销未执行，请先核对");
        tx.setBalanceAfter(balance.add(tx.getAmount()));
        tx.setApprovalStatus(ArApApprovalStatus.APPROVED);
        tx.setApprovedBy(actorId);
        tx.setApprovedAt(LocalDateTime.now());
        ReturnRefundReversalEvidence.requireApprovedReversal(locked.order(), request, tx);
        arApTransactionRepository.save(tx);
        locked.customer().setCurrentBalance(tx.getBalanceAfter());
        customerRepository.save(locked.customer());
        // Same transaction: an accounting/period failure rolls back the ledger and customer as well.
        voucherService.reverseReturnRefund(factoryId, request.getId(), tx.getRemark(), actorId);
        request.setStatus(PaymentRequestStatus.CANCELLED);
        request.setCancelledAt(tx.getApprovedAt());
        paymentRequestRepository.save(request);
        toView(locked.order(), request, new Eligibility("REVERSED", null, BigDecimal.ZERO));
        return request;
    }

    @Transactional
    public PaymentRequest rejectReversal(String factoryId, String returnOrderId, Long actorId,
                                        ReturnRefundDTO.ReversalDecision command) {
        requireActor(actorId);
        requireDecision(command);
        if (command.getReason() == null || command.getReason().isBlank()
                || command.getReason().trim().length() > 80) throw new BusinessException(400, "请填写驳回说明（最多80字）");
        LockedRefund locked = lockExactRefund(factoryId, returnOrderId, command.getRequestId());
        PaymentRequest request = locked.request();
        requireStatus(request, PaymentRequestStatus.PAID, "原退款当前状态不可驳回撤销申请");
        ArApTransaction tx = exactReversal(locked.order(), request, command.getReversalTransactionId());
        if (tx.getApprovalStatus() != ArApApprovalStatus.PENDING) throw conflict("只有待审核的退款撤销可以驳回");
        if (Objects.equals(actorId, tx.getOperatedBy())) throw conflict("撤销申请人不能审核自己的申请");
        requireOriginalRefund(locked.order(), request);
        tx.setApprovalStatus(ArApApprovalStatus.REJECTED);
        tx.setApprovedBy(actorId);
        tx.setApprovedAt(LocalDateTime.now());
        tx.setRemark(tx.getRemark() + " [驳回：" + command.getReason().trim() + "]");
        arApTransactionRepository.save(tx);
        return request;
    }

    private static void requireDecision(ReturnRefundDTO.ReversalDecision command) {
        if (command == null || command.getRequestId() == null || command.getRequestId().isBlank()
                || command.getReversalTransactionId() == null || command.getReversalTransactionId().isBlank()) {
            throw new BusinessException(400, "原退款申请和撤销流水不能为空，请重新打开原记录");
        }
    }

    private LockedRefund lockExactRefund(String factoryId, String returnOrderId, String requestId) {
        ReturnOrder order = loadOrder(factoryId, returnOrderId);
        SalesOrder sale = loadSalesOrder(factoryId, order.getSourceOrderId(), true);
        lockOrder(order, factoryId);
        PaymentRequest request = lockRequest(factoryId, returnOrderId, requestId);
        assertRequestSource(order, request);
        Customer customer = lockCustomer(factoryId, request.getCustomerId());
        entityManager.refresh(customer);
        ensureCustomerMatches(order, sale, customer);
        return new LockedRefund(order, request, customer);
    }

    private ArApTransaction exactReversal(ReturnOrder order, PaymentRequest request, String id) {
        ArApTransaction tx = reversalFor(order, request);
        if (tx == null || !Objects.equals(id, tx.getId())) {
            throw conflict("撤销流水与原退款不一致或记录已变化，请重新打开原记录")
                    .withCode("RETURN_REFUND_REVERSAL_SOURCE_MISMATCH");
        }
        return tx;
    }

    private OriginalRefund requireOriginalRefund(ReturnOrder order, PaymentRequest request) {
        if (request.getArapTransactionId() == null || request.getArapTransactionId().isBlank()) {
            throw reversalEvidenceInvalid("已付款退款缺少应收流水证据");
        }
        ArApTransaction ledger = arApTransactionRepository.findById(request.getArapTransactionId())
                .orElseThrow(() -> reversalEvidenceInvalid("已付款退款的应收流水不存在"));
        ReturnRefundEvidence.requireMatchingLedger(order, request, ledger);
        Voucher voucher = voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                RETURN_SOURCE, request.getId()).orElse(null);
        ReturnRefundEvidence.requireMatchingVoucher(order.getFactoryId(), request, voucher);
        requireMatchingAccountingDate(ledger, voucher);
        return new OriginalRefund(ledger, voucher, ledger.getTransactionDate());
    }

    private static void requireMatchingAccountingDate(ArApTransaction ledger, Voucher voucher) {
        if (ledger == null || voucher == null || ledger.getTransactionDate() == null
                || voucher.getVoucherDate() == null
                || !Objects.equals(ledger.getTransactionDate(), voucher.getVoucherDate())) {
            throw reversalEvidenceInvalid("原退款流水与凭证会计日期不一致，不能撤销");
        }
    }

    private static BusinessException reversalEvidenceInvalid(String message) {
        return conflict(message).withCode("RETURN_REFUND_REVERSAL_EVIDENCE_INVALID");
    }

    private ArApTransaction reversalFor(ReturnOrder order, PaymentRequest request) {
        List<ArApTransaction> rows = arApTransactionRepository
                .findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                        order.getFactoryId(), ReturnRefundReversalEvidence.SOURCE,
                        List.of(request.getId()), ArApTransactionType.AR_ADJUSTMENT);
        for (ArApTransaction tx : rows) ReturnRefundReversalEvidence.requireReversal(order, request, tx);
        List<ArApTransaction> active = rows.stream()
                .filter(tx -> tx.getApprovalStatus() != ArApApprovalStatus.REJECTED).toList();
        if (active.size() > 1) throw conflict("该退款存在重复撤销记录，请由财务核对");
        if (!active.isEmpty()) return active.get(0);
        return rows.stream().max(Comparator.comparing(ArApTransaction::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ArApTransaction::getId, Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
    }

    private record LockedRefund(ReturnOrder order, PaymentRequest request, Customer customer) { }

    private record OriginalRefund(ArApTransaction ledger, Voucher voucher, LocalDate accountingDate) { }

    private ArApTransaction createRefundTransaction(String factoryId, PaymentRequest request,
                                                    ReturnOrder order, SalesOrder salesOrder,
                                                    Customer customer, PaymentMethod method, Long actorId,
                                                    LocalDate accountingDate) {
        BigDecimal after = (customer.getCurrentBalance() == null ? BigDecimal.ZERO : customer.getCurrentBalance())
                .add(request.getAmount());
        ArApTransaction tx = new ArApTransaction();
        tx.setFactoryId(factoryId);
        tx.setTransactionNumber("AR-REFUND-" + accountingDate.toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        tx.setTransactionType(ArApTransactionType.AR_REFUND);
        tx.setCounterpartyType(CounterpartyType.CUSTOMER);
        tx.setCounterpartyId(customer.getId());
        tx.setCounterpartyName(customer.getName());
        tx.setSalesOrderId(salesOrder.getId());
        tx.setSourceType(RETURN_SOURCE);
        tx.setSourceId(request.getId());
        tx.setAmount(request.getAmount());
        tx.setBalanceAfter(after);
        tx.setPaymentMethod(method);
        tx.setPaymentReference(request.getPaymentReference());
        tx.setTransactionDate(accountingDate);
        tx.setOperatedBy(actorId);
        tx.setApprovalStatus(ArApApprovalStatus.APPROVED);
        tx.setApprovedBy(request.getApprovedBy());
        tx.setApprovedAt(request.getApprovedAt());
        tx.setRemark("客户退货退款结清-" + order.getReturnNumber());
        return arApTransactionRepository.save(tx);
    }

    private Eligibility evaluate(String factoryId, ReturnOrder current, SalesOrder salesOrder, String requestId) {
        if (current.getStatus() != ReturnOrderStatus.COMPLETED || !Boolean.TRUE.equals(current.getWithGoods())
                || current.getReturnType() != ReturnType.SALES_RETURN || current.getTotalAmount() == null
                || current.getTotalAmount().signum() <= 0) {
            throw conflict("仅已完成、有货的销售退货可以申请退款").withCode("RETURN_REFUND_NOT_ELIGIBLE");
        }
        if (!Objects.equals(current.getCounterpartyId(), salesOrder.getCustomerId())) {
            throw conflict("退货客户与源销售单客户不一致").withCode("RETURN_REFUND_SOURCE_MISMATCH");
        }
        if (salesOrder.getTotalAmount() == null || salesOrder.getTotalAmount().signum() < 0) {
            throw conflict("源销售单金额缺失或非法，不能计算可退金额")
                    .withCode("RETURN_REFUND_SOURCE_AMOUNT_INVALID");
        }
        List<ReturnOrder> returns = returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        factoryId, ReturnType.SALES_RETURN, salesOrder.getId()).stream()
                .filter(row -> row.getStatus() == ReturnOrderStatus.COMPLETED && Boolean.TRUE.equals(row.getWithGoods()))
                .toList();
        if (returns.stream().anyMatch(row -> row.getId() == null)) {
            throw conflict("同一销售单的退货来源存在重复记录，暂不能登记退款")
                    .withCode("RETURN_REFUND_RETURN_EVIDENCE_INCONSISTENT");
        }
        Map<String, ReturnOrder> returnById = returns.stream().collect(Collectors.toMap(ReturnOrder::getId,
                Function.identity(), (a, b) -> a));
        if (returns.size() != new HashSet<>(returnById.keySet()).size()) {
            throw conflict("同一销售单的退货来源存在重复记录，暂不能登记退款")
                    .withCode("RETURN_REFUND_RETURN_EVIDENCE_INCONSISTENT");
        }
        if (!returnById.containsKey(current.getId())) {
            throw conflict("当前退货缺少已完成且已批准的应收调整证据，不能登记退款")
                    .withCode("RETURN_REFUND_SOURCE_MISSING");
        }
        List<ArApTransaction> adjustmentRows = returnById.isEmpty() ? List.of()
                : arApTransactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                factoryId, "RETURN_ORDER", new ArrayList<>(returnById.keySet()), ArApTransactionType.AR_ADJUSTMENT);
        Map<String, List<ArApTransaction>> adjustments = adjustmentRows.stream()
                .collect(Collectors.groupingBy(ArApTransaction::getSourceId));
        BigDecimal returned = BigDecimal.ZERO;
        for (ReturnOrder row : returns) {
            if (!Objects.equals(row.getCounterpartyId(), salesOrder.getCustomerId())
                    || row.getTotalAmount() == null || row.getTotalAmount().signum() <= 0) {
                throw conflict("同一销售单存在客户或金额不一致的已完成退货，暂不能登记退款")
                        .withCode("RETURN_REFUND_RETURN_EVIDENCE_INCONSISTENT");
            }
            if (!"APPROVED".equals(ReturnAdjustmentEvidence.state(row,
                    adjustments.getOrDefault(row.getId(), List.of())))) {
                throw conflict("同一销售单存在退货应收调整缺失或未批准，暂不能登记退款")
                        .withCode("RETURN_REFUND_AR_EVIDENCE_INCONSISTENT");
            }
            returned = returned.add(row.getTotalAmount());
        }
        if (returned.compareTo(salesOrder.getTotalAmount()) > 0) {
            throw conflict("同一销售单已完成退货金额超过销售单金额，暂不能登记退款")
                    .withCode("RETURN_REFUND_RETURN_AMOUNT_EXCEEDS_ORDER");
        }
        List<PaymentRecord> receipts = paymentRecordRepository
                .findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, salesOrder.getId());
        BigDecimal verified = BigDecimal.ZERO;
        for (PaymentRecord receipt : receipts) {
            if (receipt.getStatus() == PaymentRecordStatus.VERIFIED) {
                if (receipt.getAmount() == null || receipt.getAmount().signum() < 0) {
                    throw conflict("源销售单存在金额非法的已核收款，暂不能登记退款")
                            .withCode("RETURN_REFUND_RECEIPT_EVIDENCE_INCONSISTENT");
                }
                verified = verified.add(receipt.getAmount());
            }
        }
        BigDecimal paid = salesOrder.getPaidAmount() == null ? BigDecimal.ZERO : salesOrder.getPaidAmount();
        BigDecimal arPaid = arApTransactionRepository.sumArPaymentsBySalesOrderId(factoryId, salesOrder.getId());
        if (paid.signum() < 0 || arPaid == null || arPaid.signum() < 0
                || verified.compareTo(paid) != 0 || verified.compareTo(arPaid) != 0) {
            throw conflict("源销售单已收款证据与应收台账不一致，请先核对收款")
                    .withCode("RETURN_REFUND_RECEIPT_EVIDENCE_INCONSISTENT");
        }
        List<PaymentRequest> genericSales = paymentRequestRepository.findGenericSalesBySalesOrderId(
                factoryId, salesOrder.getId(), RESERVED);
        if (!genericSales.isEmpty()) {
            throw conflict("源销售单已有未区分来源的销售付款申请，请先核对后再登记退货退款")
                    .withCode("RETURN_REFUND_GENERIC_SALES_CONFLICT");
        }
        List<PaymentRequest> refundRequests = paymentRequestRepository.findSalesRefundsBySalesOrderId(
                factoryId, salesOrder.getId(), RESERVED);
        Set<String> refundIds = new HashSet<>();
        BigDecimal reserved = BigDecimal.ZERO;
        for (PaymentRequest refund : refundRequests) {
            if (refund.getId() == null || !refundIds.add(refund.getId())
                    || refund.getReturnOrderId() == null || refund.getAmount() == null
                    || refund.getAmount().signum() <= 0
                    || !Objects.equals(factoryId, refund.getFactoryId())
                    || !Objects.equals(salesOrder.getId(), refund.getSalesOrderId())) {
                throw conflict("销售单存在金额或来源重复的退款占用，暂不能登记退款")
                        .withCode("RETURN_REFUND_RESERVATION_INCONSISTENT");
            }
            if (!Objects.equals(refund.getId(), requestId)) reserved = reserved.add(refund.getAmount());
        }
        BigDecimal available = paid.subtract(salesOrder.getTotalAmount().subtract(returned)).subtract(reserved);
        if (available.signum() < 0) available = BigDecimal.ZERO;
        return new Eligibility("ELIGIBLE", null,
                current.getTotalAmount().min(available).max(BigDecimal.ZERO));
    }

    private ReturnRefundDTO toView(ReturnOrder order, PaymentRequest request, Eligibility eligibility) {
        ReturnRefundDTO.LedgerView ledgerView = null;
        ReturnRefundDTO.VoucherView voucherView = null;
        ReturnRefundDTO.ReversalView reversalView = null;
        if (request != null) {
            var voucher = voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull(
                    RETURN_SOURCE, request.getId()).orElse(null);
            if (voucher != null && !Objects.equals(order.getFactoryId(), voucher.getFactoryId())) {
                throw new BusinessException(403, "退款凭证不属于当前工厂")
                        .withCode("RETURN_REFUND_VOUCHER_SCOPE_MISMATCH");
            }
            if (request.getStatus() == PaymentRequestStatus.PAID
                    || request.getStatus() == PaymentRequestStatus.CANCELLED) {
                ledgerView = ledgerView(order, request);
                ArApTransaction reversal = reversalFor(order, request);
                Voucher mirror = voucher == null || voucher.getReversalVoucherId() == null ? null
                        : voucherRepository.findById(voucher.getReversalVoucherId()).orElse(null);
                if (request.getStatus() == PaymentRequestStatus.CANCELLED) {
                    ArApTransaction original = arApTransactionRepository.findById(request.getArapTransactionId()).orElse(null);
                    ReturnRefundReversalEvidence.requireCompletedReversal(order, request, original, reversal, voucher, mirror);
                } else {
                    ReturnRefundEvidence.requireMatchingVoucher(order.getFactoryId(), request, voucher);
                    if (reversal != null && reversal.getApprovalStatus() == ArApApprovalStatus.APPROVED) {
                        throw conflict("退款撤销流水已批准，但原退款状态未同步，请由财务核对");
                    }
                }
                if (reversal != null) {
                    reversalView = ReturnRefundDTO.ReversalView.builder()
                            .status(reversal.getApprovalStatus().name()).reason(reversal.getRemark())
                            .requestedAt(reversal.getCreatedAt())
                            .ledger(movementView(reversal, reversal.getApprovalStatus() == ArApApprovalStatus.APPROVED))
                            .voucher(voucherView(mirror)).build();
                }
            }
            if (voucher != null) {
                voucherView = ReturnRefundDTO.VoucherView.builder().id(voucher.getId())
                        .voucherNumber(voucher.getVoucherNumber())
                        .status(voucher.getStatus() == null ? null : voucher.getStatus().name()).build();
            }
        }
        ReturnRefundDTO.RequestView view = request == null ? null : ReturnRefundDTO.RequestView.builder()
                .id(request.getId()).requestNumber(request.getRequestNumber()).status(request.getStatus().name())
                .amount(request.getAmount()).paymentMethod(request.getPaymentMethod()).createdBy(request.getCreatedBy())
                .approvedBy(request.getApprovedBy()).paidBy(request.getPaidBy()).paidAt(request.getPaidAt())
                .paymentReference(request.getPaymentReference()).arapTransactionId(request.getArapTransactionId())
                .ledger(ledgerView)
                .voucher(voucherView).reversal(reversalView).build();
        return ReturnRefundDTO.builder().returnOrderId(order.getId()).returnNumber(order.getReturnNumber())
                .eligibilityStatus(eligibility.status()).reason(eligibility.reason())
                .refundableAmount(eligibility.refundableAmount()).request(view).build();
    }

    private ReturnRefundDTO.LedgerView ledgerView(ReturnOrder order, PaymentRequest request) {
        if (request.getArapTransactionId() == null || request.getArapTransactionId().isBlank()) {
            throw conflict("已付款退款缺少应收流水证据，不能展示为已结清")
                    .withCode("RETURN_REFUND_LEDGER_EVIDENCE_MISSING");
        }
        ArApTransaction tx = arApTransactionRepository.findById(request.getArapTransactionId())
                .orElseThrow(() -> conflict("已付款退款的应收流水不存在，不能展示为已结清")
                        .withCode("RETURN_REFUND_LEDGER_EVIDENCE_MISSING"));
        if (request.getStatus() == PaymentRequestStatus.CANCELLED) {
            ReturnRefundEvidence.requireHistoricalLedger(order, request, tx);
        } else {
            ReturnRefundEvidence.requireMatchingLedger(order, request, tx);
        }
        return movementView(tx, true);
    }

    private ReturnRefundDTO.LedgerView movementView(ArApTransaction tx, boolean applied) {
        Map<Long, String> names = actorNames(tx.getFactoryId(), tx.getApprovedBy(), tx.getOperatedBy());
        return ReturnRefundDTO.LedgerView.builder().id(tx.getId())
                .transactionNumber(tx.getTransactionNumber())
                .transactionType(tx.getTransactionType().name()).sourceType(tx.getSourceType()).sourceId(tx.getSourceId())
                .amount(tx.getAmount())
                .balanceAfter(applied ? tx.getBalanceAfter() : null)
                .balanceBefore(applied ? tx.getBalanceAfter().subtract(tx.getAmount()) : null)
                .paymentMethod(tx.getPaymentMethod() == null ? null : tx.getPaymentMethod().name())
                .paymentReference(tx.getPaymentReference()).approvedBy(tx.getApprovedBy())
                .approvedByName(names.get(tx.getApprovedBy()))
                .approvedAt(tx.getApprovedAt()).operatedBy(tx.getOperatedBy())
                .operatedByName(names.get(tx.getOperatedBy()))
                .transactionDate(tx.getTransactionDate()).build();
    }

    private ReturnRefundDTO.VoucherView voucherView(Voucher voucher) {
        return voucher == null ? null : ReturnRefundDTO.VoucherView.builder().id(voucher.getId())
                .voucherNumber(voucher.getVoucherNumber())
                .status(voucher.getStatus() == null ? null : voucher.getStatus().name()).build();
    }

    private Map<Long, String> actorNames(String factoryId, Long approvedBy, Long operatedBy) {
        Set<Long> ids = new HashSet<>();
        if (approvedBy != null) ids.add(approvedBy);
        if (operatedBy != null) ids.add(operatedBy);
        Map<Long, String> names = new java.util.HashMap<>();
        if (ids.isEmpty()) return names;
        for (var user : userRepository.findAllById(ids)) {
            if (!Objects.equals(factoryId, user.getFactoryId())) continue;
            String name = user.getFullName();
            if (name == null || name.isBlank()) name = user.getUsername();
            if (name != null && !name.isBlank()) names.put(user.getId(), name);
        }
        return names;
    }

    private PaymentRequest latestRequest(String factoryId, String returnOrderId) {
        List<PaymentRequest> requests = paymentRequestRepository
                .findByFactoryIdAndReturnOrderIdOrderByCreatedAtDesc(factoryId, returnOrderId);
        return requests.stream().filter(row -> isLive(row.getStatus())).findFirst()
                .orElseGet(() -> requests.stream().findFirst().orElse(null));
    }

    private PaymentRequest loadRequest(String factoryId, String returnOrderId, boolean lock) {
        PaymentRequest latest = latestRequest(factoryId, returnOrderId);
        if (latest == null) throw new BusinessException(404, "退货退款申请不存在");
        PaymentRequest request = lock
                ? lockRequest(factoryId, returnOrderId, latest.getId())
                : entityManager.find(PaymentRequest.class, latest.getId(), LockModeType.NONE);
        if (request == null || !Objects.equals(factoryId, request.getFactoryId())
                || !Objects.equals(returnOrderId, request.getReturnOrderId())) {
            throw new BusinessException(404, "退货退款申请不存在");
        }
        assertRequestSource(loadOrder(factoryId, returnOrderId), request);
        return request;
    }

    private void assertRequestSource(ReturnOrder order, PaymentRequest request) {
        if (order.getReturnType() != ReturnType.SALES_RETURN || request.getSourceType() != PaymentSourceType.SALES
                || !Objects.equals(order.getFactoryId(), request.getFactoryId())
                || !Objects.equals(order.getId(), request.getReturnOrderId())
                || !Objects.equals(order.getSourceOrderId(), request.getSalesOrderId())
                || !Objects.equals(order.getCounterpartyId(), request.getCustomerId())
                || request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw conflict("退款申请与原退货、销售单或客户来源不一致，请由财务核对")
                    .withCode("RETURN_REFUND_REQUEST_SOURCE_MISMATCH");
        }
    }

    /**
     * Lock the database row before refreshing the managed instance.  A JPA
     * find(PESSIMISTIC_WRITE) can otherwise hit the first-level cache and lock
     * an already stale version after the voucher listener has updated it.
     */
    private PaymentRequest lockRequest(String factoryId, String returnOrderId, String requestId) {
        Query query = entityManager.createNativeQuery(
                "SELECT id FROM payment_requests WHERE id = :requestId "
                        + "AND factory_id = :factoryId AND return_order_id = :returnOrderId "
                        + "AND deleted_at IS NULL FOR UPDATE");
        query.setParameter("requestId", requestId);
        query.setParameter("factoryId", factoryId);
        query.setParameter("returnOrderId", returnOrderId);
        List<?> lockedIds = query.getResultList();
        if (lockedIds.isEmpty() || !Objects.equals(String.valueOf(lockedIds.get(0)), requestId)) {
            throw new BusinessException(404, "退货退款申请不存在");
        }
        PaymentRequest request = entityManager.find(PaymentRequest.class, requestId);
        if (request == null) throw new BusinessException(404, "退货退款申请不存在");
        entityManager.refresh(request);
        if (!Objects.equals(factoryId, request.getFactoryId())
                || !Objects.equals(returnOrderId, request.getReturnOrderId())) {
            throw new BusinessException(404, "退货退款申请不存在");
        }
        return request;
    }

    private ReturnOrder loadOrder(String factoryId, String id) {
        ReturnOrder order = returnOrderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "退货单不存在"));
        if (!Objects.equals(factoryId, order.getFactoryId())) throw new BusinessException(403, "退货单不属于当前工厂");
        return order;
    }

    private SalesOrder loadSalesOrder(String factoryId, String id, boolean lock) {
        if (id == null || id.isBlank()) throw conflict("退货缺少源销售单，不能猜测退款归属");
        if (lock) {
            return salesOrderRepository.findByIdAndFactoryIdForUpdate(id, factoryId)
                    .orElseThrow(() -> new BusinessException(409, "源销售单不存在或不属于当前工厂")
                            .withHint("请停止退款并联系管理员核对退货单与源销售单归属，确认来源完整后再重试"));
        }
        return salesOrderRepository.findById(id).filter(row -> Objects.equals(factoryId, row.getFactoryId()))
                .orElseThrow(() -> new BusinessException(409, "源销售单不存在或不属于当前工厂")
                        .withHint("请停止退款并联系管理员核对退货单与源销售单归属，确认来源完整后再重试"));
    }

    private void lockOrder(ReturnOrder order, String factoryId) {
        if (!ReturnOrderRowLock.lockAndRefresh(entityManager, order, factoryId)) {
            throw new BusinessException(409, "退货单已失效，请刷新后重试")
                    .withHint("请刷新退货详情后重新发起退款");
        }
    }

    private Customer lockCustomer(String factoryId, String id) {
        Customer customer = entityManager.find(Customer.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (customer == null || !Objects.equals(factoryId, customer.getFactoryId())) {
            throw new BusinessException(403, "客户不存在或不属于当前工厂");
        }
        return customer;
    }

    private void ensureCustomerMatches(ReturnOrder order, SalesOrder salesOrder, Customer customer) {
        if (!Objects.equals(order.getCounterpartyId(), customer.getId())
                || !Objects.equals(salesOrder.getCustomerId(), customer.getId())) {
            throw conflict("退货、销售单与客户归属不一致").withCode("RETURN_REFUND_SOURCE_MISMATCH");
        }
    }

    private static PaymentMethod parseRefundMethod(String value) {
        try {
            PaymentMethod method = PaymentMethod.valueOf(value.trim().toUpperCase());
            if (method != PaymentMethod.CASH && method != PaymentMethod.BANK_TRANSFER) {
                throw new IllegalArgumentException();
            }
            return method;
        } catch (Exception ex) {
            throw new BusinessException(400, "退款付款方式请选择现金或银行转账")
                    .withCode("RETURN_REFUND_PAYMENT_METHOD_INVALID");
        }
    }

    private static boolean isLive(PaymentRequestStatus status) {
        return RESERVED.contains(status);
    }

    private static void requireActor(Long actorId) {
        if (actorId == null || actorId <= 0) throw new BusinessException(401, "缺少工厂用户身份");
    }

    private static void requireStatus(PaymentRequest request, PaymentRequestStatus expected, String message) {
        if (request.getStatus() != expected) throw conflict(message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(409, message)
                .withHint("请打开退货详情核对源销售单与客户，再重新提交退款");
    }

    private record Eligibility(String status, String reason, BigDecimal refundableAmount) {
    }
}
