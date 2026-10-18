package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import com.cretas.aims.service.finance.FinanceOwnershipSql;

@Repository
public interface ArApTransactionRepository extends JpaRepository<ArApTransaction, String> {

    Optional<ArApTransaction> findByIdAndFactoryId(String id, String factoryId);

    /** DB-side owner filter; source rows with unknown/cross-factory provenance evaluate to NULL. */
    @Query(value = "SELECT t.* FROM ar_ap_transactions t "
            + "WHERE t.factory_id=:factoryId AND t.deleted_at IS NULL "
            + "AND (CAST(:counterpartyType AS text) IS NULL OR t.counterparty_type=CAST(:counterpartyType AS text)) "
            + "AND (CAST(:counterpartyId AS text) IS NULL OR t.counterparty_id=:counterpartyId) "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners) "
            + "ORDER BY t.transaction_date DESC, t.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM ar_ap_transactions t "
            + "WHERE t.factory_id=:factoryId AND t.deleted_at IS NULL "
            + "AND (CAST(:counterpartyType AS text) IS NULL OR t.counterparty_type=CAST(:counterpartyType AS text)) "
            + "AND (CAST(:counterpartyId AS text) IS NULL OR t.counterparty_id=:counterpartyId) "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners)",
            nativeQuery = true)
    Page<ArApTransaction> findScopedTransactions(@Param("factoryId") String factoryId,
            @Param("counterpartyType") String counterpartyType,
            @Param("counterpartyId") String counterpartyId,
            @Param("owners") List<Long> owners, Pageable pageable);

    @Query(value = "SELECT t.* FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.deleted_at IS NULL AND t.approval_status='PENDING' "
            + "AND t.transaction_type IN ('AR_ADJUSTMENT','AP_ADJUSTMENT') "
            + "AND (CAST(:counterpartyType AS text) IS NULL OR t.counterparty_type=CAST(:counterpartyType AS text)) "
            + "AND (CAST(:minAmount AS numeric) IS NULL OR ABS(t.amount)>=CAST(:minAmount AS numeric)) "
            + "AND (CAST(:maxAmount AS numeric) IS NULL OR ABS(t.amount)<=CAST(:maxAmount AS numeric)) "
            + "AND (CAST(:fromDate AS date) IS NULL OR t.created_at>=CAST(:fromDate AS date)) "
            + "AND (CAST(:toDate AS date) IS NULL OR t.created_at<CAST(:toDate AS date)+INTERVAL '1 day') "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners) "
            + "ORDER BY t.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.deleted_at IS NULL AND t.approval_status='PENDING' "
            + "AND t.transaction_type IN ('AR_ADJUSTMENT','AP_ADJUSTMENT') "
            + "AND (CAST(:counterpartyType AS text) IS NULL OR t.counterparty_type=CAST(:counterpartyType AS text)) "
            + "AND (CAST(:minAmount AS numeric) IS NULL OR ABS(t.amount)>=CAST(:minAmount AS numeric)) "
            + "AND (CAST(:maxAmount AS numeric) IS NULL OR ABS(t.amount)<=CAST(:maxAmount AS numeric)) "
            + "AND (CAST(:fromDate AS date) IS NULL OR t.created_at>=CAST(:fromDate AS date)) "
            + "AND (CAST(:toDate AS date) IS NULL OR t.created_at<CAST(:toDate AS date)+INTERVAL '1 day') "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners)",
            nativeQuery = true)
    Page<ArApTransaction> findScopedPendingAdjustments(@Param("factoryId") String factoryId,
            @Param("counterpartyType") String counterpartyType,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("owners") List<Long> owners, Pageable pageable);

    @Query(value = "SELECT t.* FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.counterparty_type=:counterpartyType AND t.counterparty_id=:counterpartyId "
            + "AND t.transaction_date BETWEEN :startDate AND :endDate AND t.deleted_at IS NULL "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners) "
            + "ORDER BY t.transaction_date ASC, t.created_at ASC", nativeQuery = true)
    List<ArApTransaction> findScopedByCounterpartyAndDateRange(
            @Param("factoryId") String factoryId, @Param("counterpartyType") String counterpartyType,
            @Param("counterpartyId") String counterpartyId, @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate, @Param("owners") List<Long> owners);

    @Query(value = "SELECT COALESCE(SUM(CASE "
            + "WHEN t.transaction_type IN ('AR_PAYMENT','AR_CREDIT_NOTE','AP_PAYMENT','AP_CREDIT_NOTE') THEN -ABS(t.amount) "
            + "WHEN t.transaction_type IN ('AR_ADJUSTMENT','AP_ADJUSTMENT','AR_REFUND') "
            + "AND t.approval_status='APPROVED' THEN t.amount "
            + "WHEN t.transaction_type IN ('AR_INVOICE','AP_INVOICE') THEN t.amount "
            + "ELSE 0 END), 0) FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.counterparty_type=:counterpartyType AND t.counterparty_id=:counterpartyId "
            + "AND t.transaction_date<:startDate AND t.deleted_at IS NULL "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners)", nativeQuery = true)
    List<BigDecimal> findScopedOpeningBalance(@Param("factoryId") String factoryId,
            @Param("counterpartyType") String counterpartyType, @Param("counterpartyId") String counterpartyId,
            @Param("startDate") LocalDate startDate, @Param("owners") List<Long> owners);

    @Query(value = "SELECT t.* FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.transaction_type='AR_INVOICE' AND t.outstanding_amount IS NOT NULL "
            + "AND t.outstanding_amount>0 AND t.due_date IS NOT NULL AND t.due_date<:today "
            + "AND t.deleted_at IS NULL AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners) "
            + "ORDER BY t.due_date ASC", nativeQuery = true)
    List<ArApTransaction> findScopedOverdueReceivables(@Param("factoryId") String factoryId,
            @Param("today") LocalDate today, @Param("owners") List<Long> owners);

    @Query(value = "SELECT t.* FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.transaction_type='AP_INVOICE' AND t.outstanding_amount IS NOT NULL "
            + "AND t.outstanding_amount>0 AND t.due_date IS NOT NULL AND t.due_date<:today "
            + "AND t.deleted_at IS NULL AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners) "
            + "ORDER BY t.due_date ASC", nativeQuery = true)
    List<ArApTransaction> findScopedOverduePayables(@Param("factoryId") String factoryId,
            @Param("today") LocalDate today, @Param("owners") List<Long> owners);

    @Query(value = "SELECT COALESCE(SUM(CASE WHEN t.transaction_type='AR_INVOICE' THEN t.amount "
            + "WHEN t.transaction_type IN ('AR_ADJUSTMENT','AR_REFUND') AND t.approval_status='APPROVED' THEN t.amount "
            + "WHEN t.transaction_type IN ('AR_PAYMENT','AR_CREDIT_NOTE') THEN -ABS(t.amount) ELSE 0 END),0) "
            + "FROM ar_ap_transactions t WHERE t.factory_id=:factoryId AND t.counterparty_type='CUSTOMER' "
            + "AND t.deleted_at IS NULL AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners)", nativeQuery = true)
    BigDecimal sumReceivablesScoped(@Param("factoryId") String factoryId, @Param("owners") List<Long> owners);

    @Query(value = "SELECT COALESCE(SUM(CASE WHEN t.transaction_type='AP_INVOICE' THEN t.amount "
            + "WHEN t.transaction_type='AP_ADJUSTMENT' AND t.approval_status='APPROVED' THEN t.amount "
            + "WHEN t.transaction_type IN ('AP_PAYMENT','AP_CREDIT_NOTE') THEN -ABS(t.amount) ELSE 0 END),0) "
            + "FROM ar_ap_transactions t WHERE t.factory_id=:factoryId AND t.counterparty_type='SUPPLIER' "
            + "AND t.deleted_at IS NULL AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners)", nativeQuery = true)
    BigDecimal sumPayablesScoped(@Param("factoryId") String factoryId, @Param("owners") List<Long> owners);

    @Query(value = "SELECT t.counterparty_id, t.counterparty_name, SUM(CASE "
            + "WHEN t.transaction_type IN ('AR_PAYMENT','AR_CREDIT_NOTE','AP_PAYMENT','AP_CREDIT_NOTE') THEN -ABS(t.amount) "
            + "ELSE t.amount END) FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.counterparty_type=:type AND t.deleted_at IS NULL "
            + "AND (t.transaction_type NOT IN ('AR_ADJUSTMENT','AP_ADJUSTMENT','AR_REFUND') OR t.approval_status='APPROVED') "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners) "
            + "GROUP BY t.counterparty_id,t.counterparty_name", nativeQuery = true)
    List<Object[]> sumByCounterpartyScoped(@Param("factoryId") String factoryId,
            @Param("type") String type, @Param("owners") List<Long> owners);

    @Query(value = "SELECT COUNT(*) FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.transaction_date BETWEEN :from AND :to AND t.deleted_at IS NULL "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners)", nativeQuery = true)
    long countScopedByFactoryIdAndTransactionDateBetweenNative(@Param("factoryId") String factoryId,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("owners") List<Long> owners);

    @Query(value = "SELECT COUNT(DISTINCT t.counterparty_id) FROM ar_ap_transactions t "
            + "WHERE t.factory_id=:factoryId AND t.counterparty_type=:type AND t.transaction_date BETWEEN :from AND :to "
            + "AND t.deleted_at IS NULL AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners)", nativeQuery = true)
    long countDistinctScopedCounterpartiesInPeriod(@Param("factoryId") String factoryId,
            @Param("type") String type, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("owners") List<Long> owners);

    @Query(value = "SELECT t.transaction_type, COUNT(*), COALESCE(SUM(CASE "
            + "WHEN t.transaction_type IN ('AR_PAYMENT','AR_CREDIT_NOTE','AP_PAYMENT','AP_CREDIT_NOTE') THEN -ABS(t.amount) "
            + "WHEN t.transaction_type IN ('AR_ADJUSTMENT','AP_ADJUSTMENT','AR_REFUND') AND t.approval_status='APPROVED' THEN t.amount "
            + "WHEN t.transaction_type IN ('AR_ADJUSTMENT','AP_ADJUSTMENT','AR_REFUND') THEN 0 ELSE t.amount END),0) "
            + "FROM ar_ap_transactions t WHERE t.factory_id=:factoryId AND t.deleted_at IS NULL AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners) "
            + "GROUP BY t.transaction_type", nativeQuery = true)
    List<Object[]> statisticsByTypeScoped(@Param("factoryId") String factoryId, @Param("owners") List<Long> owners);

    @Query(value = "SELECT t.* FROM ar_ap_transactions t WHERE t.factory_id=:factoryId "
            + "AND t.transaction_type='AP_PAYMENT' AND t.deleted_at IS NULL "
            + "AND NOT EXISTS (SELECT a.id FROM ar_ap_payment_allocations a WHERE a.factory_id=t.factory_id "
            + "AND a.payment_transaction_id=t.id AND a.deleted_at IS NULL) "
            + "AND " + FinanceOwnershipSql.AR_AP_OWNER_T + " IN (:owners) "
            + "ORDER BY t.transaction_date DESC,t.created_at DESC", nativeQuery = true)
    List<ArApTransaction> findScopedUnallocatedApPayments(@Param("factoryId") String factoryId,
            @Param("owners") List<Long> owners);

    /** Serialize all settlements of one AP invoice and keep factory isolation in the lock query. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ArApTransaction t WHERE t.id = :transactionId " +
            "AND t.factoryId = :factoryId " +
            "AND t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_INVOICE " +
            "AND t.deletedAt IS NULL")
    Optional<ArApTransaction> findPayableForSettlement(
            @Param("factoryId") String factoryId,
            @Param("transactionId") String transactionId);

    /**
     * 🔴🔒 AR 收款应用到发票 (follow-up to #577, 2026-09-04): 找出某销售订单下状态已知
     * (非 {@code NEEDS_RECONCILIATION}) 且仍有未清余额的 AR_INVOICE, 按挂账日期/创建时间
     * 从早到晚排序 (FIFO), 供 {@code recordArPayment} 做发票级核销分配。
     * {@code PESSIMISTIC_WRITE} 锁定这些行, 防止并发收款重复核销同一批发票 ——
     * 与 {@link #findPayableForSettlement} 对 AP_INVOICE 的做法同形。
     *
     * <p>⛔ 故意排除 {@code NEEDS_RECONCILIATION}: 这些是 V20261101_27 存量回填批次里
     * 无法唯一归因给单张发票的行 (同一 SO 下有 2+ 张发票、或发票没有 SO), 它们已结清多少
     * 是未知数。盲目当作「尚未结清任何金额」会把可能早已付清的存量发票重新打开 ——
     * 与 V20261028_94/V20261101_27 处理存量数据的原则一致: 未知就留给财务人工核对, 不猜。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.salesOrderId = :salesOrderId " +
            "AND t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_INVOICE " +
            "AND t.deletedAt IS NULL " +
            "AND t.paymentStatus IN (com.cretas.aims.entity.enums.PayablePaymentStatus.UNPAID, " +
            "                        com.cretas.aims.entity.enums.PayablePaymentStatus.PARTIALLY_PAID) " +
            "AND t.outstandingAmount IS NOT NULL AND t.outstandingAmount > 0 " +
            "ORDER BY t.transactionDate ASC, t.createdAt ASC")
    List<ArApTransaction> findOpenArInvoicesForSettlementBySalesOrderId(
            @Param("factoryId") String factoryId,
            @Param("salesOrderId") String salesOrderId);

    Page<ArApTransaction> findByFactoryIdOrderByTransactionDateDesc(String factoryId, Pageable pageable);

    Page<ArApTransaction> findByFactoryIdAndCounterpartyTypeOrderByTransactionDateDesc(
            String factoryId, CounterpartyType counterpartyType, Pageable pageable);

    Page<ArApTransaction> findByFactoryIdAndCounterpartyTypeAndCounterpartyIdOrderByTransactionDateDesc(
            String factoryId, CounterpartyType counterpartyType, String counterpartyId, Pageable pageable);

    /**
     * R28 P2 (R23 P5): list PENDING adjustments for the approval queue.
     * AR_ADJUSTMENT + AP_ADJUSTMENT only; approval_status='PENDING'; not soft-deleted.
     * Sorted newest first (created_at DESC) so urgent items surface to top.
     */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.PENDING " +
            "AND (t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT " +
            "OR t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_ADJUSTMENT) " +
            "AND t.deletedAt IS NULL " +
            "ORDER BY t.createdAt DESC")
    Page<ArApTransaction> findPendingAdjustments(@Param("factoryId") String factoryId, Pageable pageable);

    /**
     * R29 P1 filtered: PENDING adjustments by counterpartyType.
     * (Amount + date range filters applied in service layer to avoid PG
     *  parameter-type-inference issue with `:p IS NULL` on dynamically-null params.)
     */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.PENDING " +
            "AND (t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT " +
            "OR t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_ADJUSTMENT) " +
            "AND t.deletedAt IS NULL " +
            "AND t.counterpartyType = :counterpartyType " +
            "ORDER BY t.createdAt DESC")
    Page<ArApTransaction> findPendingAdjustmentsByType(
            @Param("factoryId") String factoryId,
            @Param("counterpartyType") com.cretas.aims.entity.enums.CounterpartyType counterpartyType,
            Pageable pageable);

    /** 查找某个交易对手的所有交易（用于对账单） */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.counterpartyType = :type AND t.counterpartyId = :counterpartyId " +
            "AND t.transactionDate BETWEEN :startDate AND :endDate " +
            "ORDER BY t.transactionDate ASC, t.createdAt ASC")
    List<ArApTransaction> findByCounterpartyAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("type") CounterpartyType type,
            @Param("counterpartyId") String counterpartyId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 逾期应收（AR_INVOICE，dueDate已过，未完全冲销） */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.transactionType = 'AR_INVOICE' " +
            "AND t.dueDate IS NOT NULL AND t.dueDate < :today " +
            "ORDER BY t.dueDate ASC")
    List<ArApTransaction> findOverdueReceivables(
            @Param("factoryId") String factoryId,
            @Param("today") LocalDate today);

    /** 逾期应付 */
    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.transactionType = 'AP_INVOICE' " +
            "AND t.outstandingAmount IS NOT NULL AND t.outstandingAmount > 0 " +
            "AND t.dueDate IS NOT NULL AND t.dueDate < :today " +
            "ORDER BY t.dueDate ASC")
    List<ArApTransaction> findOverduePayables(
            @Param("factoryId") String factoryId,
            @Param("today") LocalDate today);

    /**
     * 应收余额 = 挂账增加 - 收款减少 - 退货冲减.
     * R42 BUG-13 fix: 之前 SUM(amount) 把 AR_INVOICE + AR_PAYMENT 全加, 导致 ¥1,457K + ¥620K = ¥2,077K
     * (错误地翻倍). 正确应是 1,457K - 620K = 837K.
     *
     * <p>🔒 4-eyes fix (2026-07): AR_ADJUSTMENT 只在 approval_status='APPROVED' 时计入。此前无审批状态
     * 过滤 → PENDING (未审批) 调整立即污染看板总额, 且 REJECTED (被驳回但金额未清零) 调整永久污染,
     * 双双绕过 dual-control 闸 (recordAdjustment 故意不改 currentBalance, 直到 approveAdjustment 才改)。
     * 非调整类型 (AR_INVOICE / AR_PAYMENT / AR_CREDIT_NOTE) 自动过账, 默认即 APPROVED, 恒计入 (不受此闸阻挡)。
     * 修后 sumReceivables 与 gated ledger (Σ Customer.currentBalance) 口径一致。
     *
     * <p>🔒🔒 sign-agnostic fix (2026-07): AR_PAYMENT / AR_CREDIT_NOTE 用 {@code -ABS(t.amount)} (减去金额绝对值),
     * 而非 {@code -t.amount}。原因：写路径 SIGN 不一致 —— ArApServiceImpl.recordArPayment 存 NEGATIVE
     * (amount.negate()), 而 PaymentRequestServiceImpl.markPaidSales 的 AR_CREDIT_NOTE 存 POSITIVE。
     * 旧 {@code -t.amount} 对 POSITIVE 行正确 (减), 但对 NEGATIVE 行算成 -(-X)=+X → 把收款 ADD 进应收 →
     * 看板虚高 (F006 实测虚高 44%: 应收 ¥276,786 应为 ¥192,581)。{@code -ABS} 对两种存储 SIGN 都正确减去,
     * 无需数据迁移即自愈现有 mixed-sign 数据。invariant: PAYMENT/CREDIT_NOTE 语义上恒 REDUCE 应收 (客户付款/
     * 退款返利冲减), 唯一合法的 ADDITIVE 通道是 AR_ADJUSTMENT (保留 signed {@code t.amount})。写路径 SIGN
     * 归一化是更大的 🔒🔒 follow-up (23 条 prod 负号行 + 多消费者), 不在本 PR。
     */
    @Query("SELECT COALESCE(SUM(CASE " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_INVOICE THEN t.amount " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT " +
            "       AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED THEN t.amount " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_REFUND " +
            "       AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED THEN t.amount " +
            "  WHEN t.transactionType IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AR_CREDIT_NOTE) THEN -ABS(t.amount) " +
            "  ELSE 0 END), 0) FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.counterpartyType = 'CUSTOMER'")
    BigDecimal sumReceivables(@Param("factoryId") String factoryId);

    /**
     * 应付余额 = 挂账增加 - 付款减少 - 退货冲减.
     * R42 BUG-13 fix: 同 sumReceivables, 之前 SUM 全加 ¥247K + ¥197K = ¥444K (错). 正确是 50K.
     *
     * <p>🔒 4-eyes fix (2026-07): AP_ADJUSTMENT 同 sumReceivables —— 只在 APPROVED 时计入, PENDING/REJECTED
     * 不污染看板。非调整类型恒计入。
     *
     * <p>🔒🔒 sign-agnostic fix (2026-07): AP_PAYMENT / AP_CREDIT_NOTE 用 {@code -ABS(t.amount)} 同 sumReceivables。
     * 写路径 SIGN 不一致 —— ArApServiceImpl.recordApPayment / reverseOpeningPayable 存 NEGATIVE (negate()),
     * PaymentRequestServiceImpl.markPaidPurchase 的 AP_PAYMENT 存 POSITIVE。{@code -ABS} 对两种 SIGN 都正确减去,
     * 自愈现有 mixed-sign 数据, 无需迁移。写路径 SIGN 归一化留作 🔒🔒 follow-up。
     */
    @Query("SELECT COALESCE(SUM(CASE " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_INVOICE THEN t.amount " +
            "  WHEN t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_ADJUSTMENT " +
            "       AND t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED THEN t.amount " +
            "  WHEN t.transactionType IN (com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_CREDIT_NOTE) THEN -ABS(t.amount) " +
            "  ELSE 0 END), 0) FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.counterpartyType = 'SUPPLIER'")
    BigDecimal sumPayables(@Param("factoryId") String factoryId);

    /**
     * 按交易对手汇总余额。
     *
     * <p>🔒 4-eyes fix (2026-07): 与 sumReceivables/sumPayables 同类 —— 排除 PENDING/REJECTED 调整,
     * 避免 per-customer 应收/应付明细把未审批/被驳回的调整算进去。非调整类型全部计入 (approval_status
     * 默认 APPROVED, 此 predicate 对它们恒真)。
     *
     * <p>🔒🔒 sign-agnostic fix (2026-07): 原 {@code SUM(t.amount)} 直接裸加, 依赖写路径 SIGN 约定
     * (INVOICE 正 / PAYMENT 负)。写路径 SIGN 不一致时 (见 sumReceivables 注释), POSITIVE 存储的付款/冲减
     * 会被 ADD 进 per-counterparty 应收/应付明细 → 虚高。改用与 sumReceivables/sumPayables 一致的 sign-agnostic
     * CASE: PAYMENT/CREDIT_NOTE 恒 {@code -ABS(t.amount)} (减去金额), INVOICE 与已过审 ADJUSTMENT 保留
     * signed {@code t.amount} (ADJUSTMENT 是唯一合法 ADDITIVE 通道)。ORDER BY 同步用 CASE 保持排序正确。
     */
    @Query("SELECT t.counterpartyId, t.counterpartyName, SUM(CASE " +
            "  WHEN t.transactionType IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AR_CREDIT_NOTE, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_CREDIT_NOTE) THEN -ABS(t.amount) " +
            "  ELSE t.amount END) " +
            "FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.counterpartyType = :type " +
            "AND (t.transactionType NOT IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT, " +
            "                                com.cretas.aims.entity.enums.ArApTransactionType.AR_REFUND, " +
            "                                com.cretas.aims.entity.enums.ArApTransactionType.AP_ADJUSTMENT) " +
            "     OR t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED) " +
            "GROUP BY t.counterpartyId, t.counterpartyName " +
            "ORDER BY SUM(CASE " +
            "  WHEN t.transactionType IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AR_CREDIT_NOTE, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT, " +
            "                              com.cretas.aims.entity.enums.ArApTransactionType.AP_CREDIT_NOTE) THEN -ABS(t.amount) " +
            "  ELSE t.amount END) DESC")
    List<Object[]> sumByCounterparty(
            @Param("factoryId") String factoryId,
            @Param("type") CounterpartyType type);

    /**
     * 指定日期区间内的流水条数 —— 「本月交易笔数」。
     *
     * <p>🔴 2026-08-28 逐屏走查: 「财务概览」屏顶部写着「本月交易笔数 <b>0</b>」,
     * 而正下方的交易记录列表是「共 <b>32</b> 条」且全在本月。真因是前端读
     * {@code overview.monthlyTransactionCount} 而后端**从不产出**这个字段 ——
     * {@code undefined || 0} ⇒ 渲染成 0。</p>
     *
     * <p>⚠️ 同一个方法里已经有一段注释记着**完全同一形态的上一次**
     * ({@code payableCount}/{@code receivableCount} "silently showed 0 regardless of real data")。
     * 那次修了两个, 这三个漏了。</p>
     */
    @Query("SELECT COUNT(t) FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.transactionDate >= :from AND t.transactionDate <= :to")
    long countByFactoryIdAndTransactionDateBetween(
            @Param("factoryId") String factoryId,
            @Param("from") java.time.LocalDate from,
            @Param("to") java.time.LocalDate to);

    /** 指定日期区间内有交易往来的对手方数(去重) —— 「客户 N / 供应商 M」。 */
    @Query("SELECT COUNT(DISTINCT t.counterpartyId) FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.counterpartyType = :type " +
            "AND t.transactionDate >= :from AND t.transactionDate <= :to")
    long countDistinctCounterpartiesInPeriod(
            @Param("factoryId") String factoryId,
            @Param("type") CounterpartyType type,
            @Param("from") java.time.LocalDate from,
            @Param("to") java.time.LocalDate to);

    /** 查找期初余额快照（startDate之前最后一笔交易的balanceAfter） */
    @Query("SELECT t.balanceAfter FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId " +
            "AND t.counterpartyType = :type AND t.counterpartyId = :counterpartyId " +
            "AND t.transactionDate < :startDate " +
            "AND (t.transactionType NOT IN (com.cretas.aims.entity.enums.ArApTransactionType.AR_ADJUSTMENT, " +
            "                                com.cretas.aims.entity.enums.ArApTransactionType.AR_REFUND) " +
            "     OR t.approvalStatus = com.cretas.aims.entity.enums.ArApApprovalStatus.APPROVED) " +
            "ORDER BY t.transactionDate DESC, t.createdAt DESC")
    List<BigDecimal> findOpeningBalance(
            @Param("factoryId") String factoryId,
            @Param("type") CounterpartyType type,
            @Param("counterpartyId") String counterpartyId,
            @Param("startDate") LocalDate startDate);

    /** 检查销售订单是否已挂账 */
    /**
     * 存量「整单挂账」检测 —— 2026-08-28 应收改按实发分笔挂账的<b>切换缝</b>。
     *
     * <p>改动前每个 SO 只挂一笔 AR_INVOICE, 金额 = 订单全额, 且 {@code source_id} 为 NULL。
     * 改动后按子发运单分笔挂账 ({@code source_id} = 发运单 ID)。若一张<b>在飞</b>的订单
     * (已挂过整单应收、还有未发的量) 在升级后再发一批, 新逻辑会在整单应收之上<b>再挂一笔</b>
     * → 客户被双重计费。
     *
     * <p>prod 实测这不是假想: {@code SO-20260828-0013} 正是 PARTIAL_DELIVERED,
     * 已挂 88.50 整单应收, 还剩 2 盒未发 —— 升级后那 2 盒发货会再挂 44.25, 合计 132.75。
     *
     * <p>⇒ 本方法找出「有 source_id 为 NULL 的 AR_INVOICE」的订单, 调用方对它们
     * <b>保持旧行为</b>(不再挂账)。存量订单的账不被追溯改写, 新订单走正确口径。
     */
    @Query("SELECT COUNT(t) > 0 FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.salesOrderId = :salesOrderId " +
            "AND t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_INVOICE " +
            "AND t.deletedAt IS NULL AND t.sourceId IS NULL")
    boolean existsLegacyArInvoiceWithoutSource(@Param("factoryId") String factoryId,
                                               @Param("salesOrderId") String salesOrderId);

    boolean existsByFactoryIdAndSalesOrderIdAndTransactionType(
            String factoryId, String salesOrderId, ArApTransactionType transactionType);

    /** 检查采购订单是否已挂账 */
    boolean existsByFactoryIdAndPurchaseOrderIdAndTransactionType(
            String factoryId, String purchaseOrderId, ArApTransactionType transactionType);

    /**
     * Idempotent lookup for auto-posted PO payables (采购入库自动挂账 across partial receives).
     * Mirrors the predicate of {@link #existsByFactoryIdAndPurchaseOrderIdAndTransactionType}
     * but returns the existing row so callers can skip WITHOUT throwing inside a shared tx.
     */
    Optional<ArApTransaction> findFirstByFactoryIdAndPurchaseOrderIdAndTransactionType(
            String factoryId, String purchaseOrderId, ArApTransactionType transactionType);

    /** Source-based idempotency for non-SO/PO AP invoices. */
    Optional<ArApTransaction> findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
            String factoryId, String sourceType, String sourceId, ArApTransactionType transactionType);

    /** Source-based AP invoice queries for supplier delivery monthly reconciliation. */
    List<ArApTransaction> findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
            String factoryId, String sourceType, List<String> sourceIds, ArApTransactionType transactionType);

    /**
     * #3(a) 退货资金链: 按来源单据 (sourceType/sourceId) + 审批状态查调整。用于父单据 (退货单)
     * 被驳回时级联撤销其挂起的 AR/AP_ADJUSTMENT。返回该来源下所有匹配状态的调整行 (通常 0 或 1)。
     */
    List<ArApTransaction> findByFactoryIdAndSourceTypeAndSourceIdAndApprovalStatusAndDeletedAtIsNull(
            String factoryId, String sourceType, String sourceId,
            com.cretas.aims.entity.enums.ArApApprovalStatus approvalStatus);

    List<ArApTransaction> findByFactoryIdAndCounterpartyTypeAndCounterpartyIdAndTransactionTypeAndTransactionDateBetweenOrderByTransactionDateAscCreatedAtAsc(
            String factoryId, CounterpartyType counterpartyType, String counterpartyId,
            ArApTransactionType transactionType, LocalDate startDate, LocalDate endDate);

    @Query("SELECT t FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "AND t.counterpartyType = :counterpartyType AND t.counterpartyId = :counterpartyId " +
            "AND t.transactionType IN :transactionTypes " +
            "AND t.transactionDate BETWEEN :startDate AND :endDate " +
            "AND t.deletedAt IS NULL " +
            "ORDER BY t.transactionDate ASC, t.createdAt ASC")
    List<ArApTransaction> findByCounterpartyAndTypesAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("counterpartyType") CounterpartyType counterpartyType,
            @Param("counterpartyId") String counterpartyId,
            @Param("transactionTypes") List<ArApTransactionType> transactionTypes,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 收款幂等检查：同一 paymentReference 不能重复 */
    boolean existsByFactoryIdAndPaymentReference(String factoryId, String paymentReference);

    /** Legacy/unallocated payment detection for a specific PO and supplier. */
    List<ArApTransaction> findByFactoryIdAndPurchaseOrderIdAndCounterpartyIdAndTransactionTypeAndDeletedAtIsNull(
            String factoryId,
            String purchaseOrderId,
            String counterpartyId,
            ArApTransactionType transactionType);

    /** Historical AP_PAYMENT rows without an explicit payable allocation are read-only anomalies. */
    @Query("SELECT p FROM ArApTransaction p WHERE p.factoryId = :factoryId " +
            "AND p.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AP_PAYMENT " +
            "AND p.deletedAt IS NULL " +
            "AND NOT EXISTS (SELECT a.id FROM com.cretas.aims.entity.finance.ArApPaymentAllocation a " +
            "WHERE a.factoryId = p.factoryId AND a.paymentTransactionId = p.id AND a.deletedAt IS NULL) " +
            "ORDER BY p.transactionDate DESC, p.createdAt DESC")
    List<ArApTransaction> findUnallocatedApPayments(@Param("factoryId") String factoryId);

    /**
     * Issue #739: AR_PAYMENT idempotency for SO-bound 快速收款 (no paymentReference).
     * Find recent (last N minutes) AR_PAYMENT for same SO with same |amount|. Triggered when
     * user double-clicks "收款" — without this, second click silently creates duplicate 收款记录.
     *
     * NOTE: amount stored NEGATIVE on AR_PAYMENT (冲减), so caller passes negated amount.
     */
    @Query("SELECT t FROM ArApTransaction t "
            + "WHERE t.factoryId = :factoryId AND t.salesOrderId = :salesOrderId "
            + "AND t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT "
            + "AND t.amount = :amount "
            + "AND t.createdAt > :since "
            + "AND t.deletedAt IS NULL "
            + "ORDER BY t.createdAt DESC")
    List<ArApTransaction> findRecentArPaymentsForSO(
            @Param("factoryId") String factoryId,
            @Param("salesOrderId") String salesOrderId,
            @Param("amount") BigDecimal amount,
            @Param("since") java.time.LocalDateTime since);

    /**
     * Issue #317 fix: sum AR_PAYMENT amounts for a SO so SO.paidAmount/payment_status
     * can be derived. amounts on AR_PAYMENT rows are stored NEGATIVE (冲减应收) by
     * ArApServiceImpl.recordArPayment.
     *
     * <p>🔒🔒 sign-agnostic fix (2026-07): 改 {@code SUM(ABS(t.amount))} 而非 {@code SUM(-t.amount)}。
     * 若任一 AR_PAYMENT 行以 POSITIVE 存储 (mixed-sign prod 数据), 旧 {@code -t.amount} 会算成负数 →
     * SO.paidAmount 变负 → payment_status 误判。AR_PAYMENT 语义上恒是收款 (磁盘上 SIGN 不论),
     * {@code ABS} 取金额绝对值累计得正确的 累计收款, 对两种存储 SIGN 都正确。
     */
    @Query("SELECT COALESCE(SUM(ABS(t.amount)), 0) FROM ArApTransaction t " +
            "WHERE t.factoryId = :factoryId AND t.salesOrderId = :salesOrderId " +
            "AND t.transactionType = com.cretas.aims.entity.enums.ArApTransactionType.AR_PAYMENT " +
            "AND t.deletedAt IS NULL")
    BigDecimal sumArPaymentsBySalesOrderId(
            @Param("factoryId") String factoryId,
            @Param("salesOrderId") String salesOrderId);

    /** 按交易类型统计 */
    @Query("SELECT t.transactionType, COUNT(t), COALESCE(SUM(t.amount), 0) " +
            "FROM ArApTransaction t WHERE t.factoryId = :factoryId " +
            "GROUP BY t.transactionType")
    List<Object[]> statisticsByType(@Param("factoryId") String factoryId);
}
