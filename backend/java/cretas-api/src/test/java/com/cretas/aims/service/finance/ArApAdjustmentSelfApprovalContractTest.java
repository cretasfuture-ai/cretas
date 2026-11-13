package com.cretas.aims.service.finance;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import com.cretas.aims.service.workflow.FactorySelfApprovalGate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 红线 4 (2026-11-13): 应收/应付手工调整的审批/驳回接入工厂级「允许本人审批」开关
 * ({@link FactorySelfApprovalGate}), 与改价、退款、盘点同口径。
 *
 * <ul>
 *   <li>开关关 (默认) / 未注入 gate: 提交人审批或驳回自己的调整 ⇒ 403 四眼, 余额与记录都不动;</li>
 *   <li>开关开: 本人审批必须写原因 (422), 写了 ⇒ 放行, 调整记录上 self_approved + 原因落库;</li>
 *   <li>不是本人: 不打标记, 传了原因也忽略。</li>
 * </ul>
 */
class ArApAdjustmentSelfApprovalContractTest {

    private static final String F = "F006";
    private static final long MAKER = 11L;
    private static final long OTHER = 22L;

    private final ArApTransactionRepository transactions = mock(ArApTransactionRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
    private ArApServiceImpl service;
    private Customer customer;

    @BeforeEach
    void setUp() {
        service = new ArApServiceImpl(transactions, customers, suppliers);
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("locked"));
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "accountingPeriodService", mock(AccountingPeriodService.class));
        ReflectionTestUtils.setField(service, "selfApprovalGate", new FactorySelfApprovalGate(settings));
        ReflectionTestUtils.setField(service, "transactionManager", new NoopTransactionManager());

        customer = new Customer();
        customer.setId("C-1");
        customer.setFactoryId(F);
        customer.setName("QA-客户");
        customer.setCurrentBalance(new BigDecimal("100.00"));
        when(customers.findByIdAndFactoryId("C-1", F)).thenReturn(Optional.of(customer));
        when(customers.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
        when(transactions.save(any(ArApTransaction.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ArApTransaction pending(String id) {
        ArApTransaction txn = new ArApTransaction();
        txn.setId(id);
        txn.setFactoryId(F);
        txn.setCounterpartyType(CounterpartyType.CUSTOMER);
        txn.setCounterpartyId("C-1");
        txn.setTransactionType(ArApTransactionType.AR_ADJUSTMENT);
        txn.setApprovalStatus(ArApApprovalStatus.PENDING);
        txn.setAmount(new BigDecimal("-30.00"));
        txn.setOperatedBy(MAKER);
        txn.setTransactionDate(LocalDate.now());
        txn.setRemark("QA-红线4");
        when(transactions.findById(id)).thenReturn(Optional.of(txn));
        return txn;
    }

    private void switchOn(boolean on) {
        when(settings.findAllowSelfApprovalByFactoryId(F)).thenReturn(on);
    }

    private static Object code(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    private void assertNothingWritten(ArApTransaction txn) {
        verify(customers, never()).save(any());
        verify(transactions, never()).save(any());
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("100.00");
        assertThat(txn.getApprovalStatus()).isEqualTo(ArApApprovalStatus.PENDING);
        assertThat(txn.isSelfApproved()).isFalse();
    }

    // ------------------------------------------------------------------ 审批

    @Test
    @DisplayName("开关关: 提交人自批被拒 (403 四眼), 余额和记录都不动 —— 传了原因也不行")
    void makerCannotApproveOwnAdjustmentWhileSwitchOff() {
        switchOn(false);
        ArApTransaction txn = pending("TX-OFF");

        assertThatThrownBy(() -> service.approveAdjustment(F, "TX-OFF", MAKER, "本厂只有我一个人"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(403);
                    assertThat(e.getMessage()).contains("4 眼原则");
                })
                .satisfies(e -> assertThat(code(e)).isEqualTo(ArApServiceImpl.ADJUSTMENT_FOUR_EYES));
        assertNothingWritten(txn);
    }

    @Test
    @DisplayName("没注入 gate (旧装配) = 开关关: 自批仍被拒")
    void missingGateCountsAsSwitchOff() {
        ReflectionTestUtils.setField(service, "selfApprovalGate", null);
        ArApTransaction txn = pending("TX-NOGATE");

        assertThatThrownBy(() -> service.approveAdjustment(F, "TX-NOGATE", MAKER))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ArApServiceImpl.ADJUSTMENT_FOUR_EYES));
        assertNothingWritten(txn);
    }

    @Test
    @DisplayName("开关开但没写原因: 422 SELF_APPROVAL_REASON_REQUIRED, 在任何写入之前")
    void switchOnButNoReasonIsRefusedBeforeAnyWrite() {
        switchOn(true);
        ArApTransaction txn = pending("TX-NOREASON");

        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.approveAdjustment(F, "TX-NOREASON", MAKER, blank))
                    .as(String.valueOf(blank))
                    .satisfies(e -> assertThat(code(e)).isEqualTo(FactorySelfApprovalGate.REASON_REQUIRED));
        }
        assertNothingWritten(txn);
    }

    @Test
    @DisplayName("开关开 + 原因: 本人审批放行, 余额变动, self_approved + 原因 (trim) 落在调整记录上")
    void switchOnWithReasonApprovesAndRecordsTrace() {
        switchOn(true);
        ArApTransaction txn = pending("TX-SELF");

        ArApTransaction saved = service.approveAdjustment(F, "TX-SELF", MAKER, "  本厂只有我一个人负责这项审批 ");

        assertThat(saved.getApprovalStatus()).isEqualTo(ArApApprovalStatus.APPROVED);
        assertThat(saved.getApprovedBy()).isEqualTo(MAKER);
        assertThat(saved.isSelfApproved()).isTrue();
        assertThat(saved.getSelfApprovalReason()).isEqualTo("本厂只有我一个人负责这项审批");
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("70.00");
        verify(transactions).save(txn);
    }

    @Test
    @DisplayName("别人审批: 不是本人审批, 传了原因也不留痕 (阴性对照)")
    void anotherApproverIsNotMarkedSelfApproved() {
        switchOn(true);
        pending("TX-OTHER");

        ArApTransaction saved = service.approveAdjustment(F, "TX-OTHER", OTHER, "不该被记下");

        assertThat(saved.getApprovalStatus()).isEqualTo(ArApApprovalStatus.APPROVED);
        assertThat(saved.isSelfApproved()).isFalse();
        assertThat(saved.getSelfApprovalReason()).isNull();
        verify(settings, never()).findAllowSelfApprovalByFactoryId(F);
    }

    // ------------------------------------------------------------------ 驳回

    @Test
    @DisplayName("开关关: 提交人驳回自己的调整同样被拒")
    void makerCannotRejectOwnAdjustmentWhileSwitchOff() {
        switchOn(false);
        ArApTransaction txn = pending("TX-REJ-OFF");

        assertThatThrownBy(() -> service.rejectAdjustment(F, "TX-REJ-OFF", MAKER, "录错了"))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ArApServiceImpl.ADJUSTMENT_FOUR_EYES));
        assertNothingWritten(txn);
    }

    @Test
    @DisplayName("开关开: 本人驳回必须有原因, 驳回原因即本人审批原因并留痕, 余额不动")
    void switchOnSelfRejectNeedsReasonAndIsRecorded() {
        switchOn(true);
        ArApTransaction txn = pending("TX-REJ-ON");

        assertThatThrownBy(() -> service.rejectAdjustment(F, "TX-REJ-ON", MAKER, " "))
                .satisfies(e -> assertThat(code(e)).isEqualTo(FactorySelfApprovalGate.REASON_REQUIRED));
        assertNothingWritten(txn);

        ArApTransaction saved = service.rejectAdjustment(F, "TX-REJ-ON", MAKER, "录错了金额");

        assertThat(saved.getApprovalStatus()).isEqualTo(ArApApprovalStatus.REJECTED);
        assertThat(saved.isSelfApproved()).isTrue();
        assertThat(saved.getSelfApprovalReason()).isEqualTo("录错了金额");
        assertThat(customer.getCurrentBalance()).isEqualByComparingTo("100.00");
    }

    // ------------------------------------------------------------------ 批量

    @Test
    @DisplayName("批量审批: 原因只作用于本人提交的条目; 开关关时本人条目逐条失败, 不影响别人的")
    void batchApprovePassesReasonThroughPerItem() {
        switchOn(true);
        ArApTransaction own = pending("TX-B-OWN");
        ArApTransaction others = pending("TX-B-OTHER");
        others.setOperatedBy(OTHER + 1);

        Map<String, Object> result = service.batchApproveAdjustments(
                F, List.of("TX-B-OWN", "TX-B-OTHER"), MAKER, "本厂只有我一个人");

        assertThat(result).containsEntry("approvedCount", 2).containsEntry("failedCount", 0);
        assertThat(own.isSelfApproved()).isTrue();
        assertThat(own.getSelfApprovalReason()).isEqualTo("本厂只有我一个人");
        assertThat(others.isSelfApproved()).isFalse();
        assertThat(others.getSelfApprovalReason()).isNull();
    }

    @Test
    @DisplayName("批量审批不带原因 (旧调用): 开关开时本人条目 422 失败, 别人的照常通过")
    void batchWithoutReasonFailsOnlyOwnItems() {
        switchOn(true);
        ArApTransaction own = pending("TX-B2-OWN");
        ArApTransaction others = pending("TX-B2-OTHER");
        others.setOperatedBy(OTHER + 1);

        Map<String, Object> result = service.batchApproveAdjustments(F, List.of("TX-B2-OWN", "TX-B2-OTHER"), MAKER);

        assertThat(result).containsEntry("approvedCount", 1).containsEntry("failedCount", 1);
        assertThat(own.getApprovalStatus()).isEqualTo(ArApApprovalStatus.PENDING);
        assertThat(own.isSelfApproved()).isFalse();
        assertThat(others.getApprovalStatus()).isEqualTo(ArApApprovalStatus.APPROVED);
    }

    /** 批量逐条 REQUIRES_NEW 的桩: 只让 TransactionTemplate 能跑, 不模拟回滚 (回滚边界另有测试)。 */
    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
