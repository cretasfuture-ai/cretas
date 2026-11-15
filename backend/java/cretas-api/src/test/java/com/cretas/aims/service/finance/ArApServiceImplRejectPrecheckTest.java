package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.event.ArApAdjustmentRejectingEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1755 复审 L2: 驳回应付调整时, 「即将驳回」事件 (委外侧据此补冲减) 在加锁前发出;
 * 发之前必须先做不加锁的预检 (只能驳回待审 / 四眼), 否则委外侧抛的错 (如 OUTSOURCE_REWORK_MISMATCH)
 * 会盖掉真正的拒绝原因, 还白拿一次委外单锁。
 * 构造: 委外侧 (事件发布) 一旦被调用就抛 OUTSOURCE_REWORK_MISMATCH —— 预检失效时返回的错就会变成它。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("#1755 L2: 驳回调整先预检再发委外事件")
class ArApServiceImplRejectPrecheckTest {

    @Mock private ArApTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private EntityManager entityManager;
    @Mock private ApplicationEventPublisher publisher;

    private static final String FACTORY = "F006";
    private static final Long MAKER = 88L;
    private static final Long OTHER = 99L;

    private ArApServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ArApServiceImpl(transactionRepository, customerRepository, supplierRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", publisher);
        // 委外侧: 一被调用就报返工对不上 (真实场景: 账本与批次结存不一致)
        doThrow(new BusinessException(409, "返工件与退货记录对不上").withCode("OUTSOURCE_REWORK_MISMATCH"))
                .when(publisher).publishEvent(any(Object.class));
    }

    private ArApTransaction adj(ArApApprovalStatus status) {
        ArApTransaction t = new ArApTransaction();
        t.setId("ADJ-OS-1");
        t.setFactoryId(FACTORY);
        t.setTransactionType(ArApTransactionType.AP_ADJUSTMENT);
        t.setCounterpartyType(CounterpartyType.SUPPLIER);
        t.setCounterpartyId("SUP-OS");
        t.setAmount(new BigDecimal("-4.00"));
        t.setApprovalStatus(status);
        t.setOperatedBy(MAKER);
        t.setSourceType("OUTSOURCE_GOODS_RETURN");
        t.setSourceId("MV-1");
        when(transactionRepository.findById("ADJ-OS-1")).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    @DisplayName("提交人驳回自己的调整 → 四眼 403 (不是委外侧的 409), 委外事件不发")
    void selfRejectFailsFourEyesBeforeOutsourceSide() {
        ArApTransaction t = adj(ArApApprovalStatus.PENDING);
        Throwable thrown = catchThrowable(() -> service.rejectAdjustment(FACTORY, "ADJ-OS-1", MAKER, "自己驳回"));
        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(ArApServiceImpl.ADJUSTMENT_FOUR_EYES);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(403);
        assertThat(t.getApprovalStatus()).isEqualTo(ArApApprovalStatus.PENDING);
    }

    @Test
    @DisplayName("调整已不是待审 → 409「只能驳回 PENDING」, 委外事件不发")
    void nonPendingRejectFailsBeforeOutsourceSide() {
        adj(ArApApprovalStatus.APPROVED);
        Throwable thrown = catchThrowable(() -> service.rejectAdjustment(FACTORY, "ADJ-OS-1", OTHER, "再驳回"));
        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(thrown).isInstanceOf(BusinessException.class).hasMessageContaining("只能驳回 PENDING");
    }

    @Test
    @DisplayName("阳性对照: 别人驳回待审调整 → 预检通过, 委外事件照发 (这里委外侧抛错, 原样冒出)")
    void otherUserRejectReachesOutsourceSide() {
        adj(ArApApprovalStatus.PENDING);
        Throwable thrown = catchThrowable(() -> service.rejectAdjustment(FACTORY, "ADJ-OS-1", OTHER, "委外厂不认"));
        verify(publisher).publishEvent(any(ArApAdjustmentRejectingEvent.class));
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo("OUTSOURCE_REWORK_MISMATCH");
    }
}
