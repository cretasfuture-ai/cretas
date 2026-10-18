package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.impl.SalesFinanceApproveVoucherListener;
import com.cretas.aims.service.voucher.VoucherService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉住四个凭证监听器「接住 save() 返回值」—— 否则第二次 save 拿旧 version 去 UPDATE。
 *
 * <h2>它守的是什么</h2>
 * 这四个监听器都是 {@code @Async + @TransactionalEventListener(AFTER_COMMIT)}, 形状一致:
 * <pre>
 *   findById → save(标 PENDING) → 干活 → save(标 CREATED/FAILED)
 * </pre>
 * {@code @Async} 线程上<b>没有绑定的 EntityManager</b>(OSIV 只绑请求线程) ⇒ 实体是 detached ⇒
 * {@code save()} 走 {@code merge()}, 而 <b>merge 返回的是一个新的托管实例</b>,
 * 原对象的 {@code @Version} 不会被回写。
 *
 * <p>所以丢掉第一次 save 的返回值, 第二次 save 就必然拿旧 version 去
 * {@code UPDATE ... WHERE version = V}, 而库里已经是 V+1 ——
 * <b>单线程、确定性、每次首发必炸</b>。栈帧原文:
 * {@code DefaultMergeEventListener.entityIsDetached → targetEntity → StaleObjectStateException}。
 *
 * <h2>为什么不是并发(自然实验)</h2>
 * {@code TransferVoucherListener} 是逐字相同的双 save 形状、同样 @Async + AFTER_COMMIT,
 * 但 {@code InternalTransfer} <b>没有 @Version</b>(实测: 另外三个实体各 1 个, 它 0 个)
 * ⇒ {@code internal_transfers} 零次乐观锁冲突, 而它内层 catch 触发过 10 次
 * (阳性对照, 证明那条路真的在跑)。<b>判别量是 @Version, 不是并发。</b>
 *
 * <h2>prod 后果(2026-08-19 普查)</h2>
 * 全部冲突都在 {@code SimpleAsyncTaskExecutor} 线程上, {@code http-nio} 上 0 次,
 * {@code GlobalExceptionHandler} 的 409 {@code OPTIMISTIC_LOCK_CONFLICT} 一次都没触发过
 * —— <b>用户侧完全无感</b>, 财审返回 200。丢掉的正是 {@code PENDING → CREATED/FAILED} 那次写:
 * {@code sales_orders} 19/38、{@code purchase_orders} 6/7、{@code return_orders} 10/29
 * 永久停在 PENDING(界面显示「凭证生成中」), 而三张表里 <b>FAILED 都是 0</b> ——
 * 因为写 FAILED 的正是抛异常的那次 save, fail-soft 设计 100% 失效。
 *
 * <p>⚠️ 单测为什么以前抓不到: mock 掉的 repository 的 {@code save()} 通常
 * {@code thenReturn(同一个对象)} 或干脆返回 null, 于是「有没有接住返回值」这件事
 * <b>在测试里没有可观测的差别</b>。本测试特意让 {@code save()} 返回<b>另一个</b>实例
 * —— 这才是真实 merge 的形状。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleDetachedMergeContractTest {

    private static final String FACTORY = "F006";
    private static final String ID = "ORDER-1";

    @Mock private VoucherService voucherService;

    // ==================== ① 销售财审 → 凭证 (prod 冲突 308 次, 最高) ====================

    @Test
    @DisplayName("SalesFinanceApproveVoucherListener: 第二次 save 必须用 merge 返回的实例")
    void salesFinanceApproveListenerMustReuseMergedInstance() {
        SalesOrderRepository repo = mock(SalesOrderRepository.class);

        SalesOrder detached = new SalesOrder();
        detached.setId(ID);
        detached.setVflag(VoucherFlag.UNCREATED);

        // 真实 merge 的形状: 返回一个**不同的**托管实例, 原对象的 version 不回写
        SalesOrder merged = new SalesOrder();
        merged.setId(ID);
        merged.setVflag(VoucherFlag.PENDING);

        when(repo.findById(ID)).thenReturn(Optional.of(detached));
        when(repo.save(any(SalesOrder.class))).thenReturn(merged);
        when(voucherService.createFromBusiness(anyString(), anyString(), anyString()))
                .thenReturn(new Voucher());

        new SalesFinanceApproveVoucherListener(repo, voucherService)
                .onFinanceApproved(new SalesOrderFinanceApprovedEvent(this, FACTORY, ID, 1309L));

        ArgumentCaptor<SalesOrder> cap = ArgumentCaptor.forClass(SalesOrder.class);
        verify(repo, times(2)).save(cap.capture());
        List<SalesOrder> saved = cap.getAllValues();

        assertSame(detached, saved.get(0), "第一次 save 应该存那个 detached 实例");
        assertSame(merged, saved.get(1),
                "第二次 save 必须用第一次 merge 返回的实例。用原对象 = 拿旧 @Version 去 UPDATE "
                        + "⇒ StaleObjectStateException ⇒ vflag 永久停在 PENDING, 且 FAILED 永远写不进去。");
        assertNotSame(saved.get(0), saved.get(1),
                "⚠️ 阳性对照: 如果这两个是同一个对象, 说明桩没模拟出 merge 的形状, 上面那条断言是恒真式");
    }

    // ==================== ② 采购收货 → 凭证 (prod 冲突 144 次) ====================

    @Test
    @DisplayName("PurchaseOrderVoucherListener: 第二次 save 必须用 merge 返回的实例")
    void purchaseOrderListenerMustReuseMergedInstance() {
        PurchaseOrderRepository repo = mock(PurchaseOrderRepository.class);

        PurchaseOrder detached = new PurchaseOrder();
        detached.setId(ID);
        detached.setVflag(VoucherFlag.UNCREATED);
        PurchaseOrder merged = new PurchaseOrder();
        merged.setId(ID);
        merged.setVflag(VoucherFlag.PENDING);

        when(repo.findById(ID)).thenReturn(Optional.of(detached));
        when(repo.save(any(PurchaseOrder.class))).thenReturn(merged);
        when(voucherService.createFromBusiness(anyString(), anyString(), anyString()))
                .thenReturn(new Voucher());

        PurchaseOrderVoucherListener listener = new PurchaseOrderVoucherListener(repo, voucherService);
        ReflectionTestUtils.invokeMethod(listener, "handleVoucherGeneration", FACTORY, ID);

        ArgumentCaptor<PurchaseOrder> cap = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(repo, times(2)).save(cap.capture());
        assertSame(detached, cap.getAllValues().get(0));
        assertSame(merged, cap.getAllValues().get(1),
                "第二次 save 必须用 merge 返回的实例 —— purchase_orders 6/7 因此卡在 PENDING");
        assertNotSame(cap.getAllValues().get(0), cap.getAllValues().get(1),
                "⚠️ 阳性对照: 两个是同一对象则上条断言恒真");
    }

    // ==================== ③ 退货单 → 凭证 (prod 冲突 24 次, 纯丢更新) ====================

    @Test
    @DisplayName("ReturnOrderVoucherListener: 锁住托管行后在同一事务内完整写出 vflag")
    void returnOrderListenerLocksManagedRowAndWritesTerminalFlag() {
        ReturnOrderRepository repo = mock(ReturnOrderRepository.class);
        EntityManager entityManager = mock(EntityManager.class);

        ReturnOrder detached = new ReturnOrder();
        detached.setId(ID);
        detached.setFactoryId(FACTORY);
        detached.setVflag(VoucherFlag.UNCREATED);

        when(repo.findById(ID)).thenReturn(Optional.of(detached));
        when(voucherService.createFromBusiness(anyString(), anyString(), anyString()))
                .thenReturn(new Voucher());

        ReturnOrderVoucherListener listener = returnOrderListener(repo, entityManager);
        ReflectionTestUtils.invokeMethod(listener, "handleVoucherGeneration", FACTORY, ID);

        verify(entityManager).refresh(detached);
        verify(repo, never()).save(any(ReturnOrder.class));
        verify(voucherService).createFromBusiness(FACTORY, "RETURN_ORDER", ID);
        assertEquals(VoucherFlag.CREATED, detached.getVflag());
    }

    @Test
    @DisplayName("ReturnOrderVoucherListener: 错误工厂在锁和凭证写入前拒绝")
    void returnOrderListenerRejectsWrongFactoryBeforeSideEffects() {
        ReturnOrderRepository repo = mock(ReturnOrderRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ReturnOrder foreign = new ReturnOrder();
        foreign.setId(ID);
        foreign.setFactoryId("F007");
        foreign.setVflag(VoucherFlag.UNCREATED);
        when(repo.findById(ID)).thenReturn(Optional.of(foreign));

        ReturnOrderVoucherListener listener = returnOrderListener(repo, entityManager);
        ReflectionTestUtils.invokeMethod(listener, "handleVoucherGeneration", FACTORY, ID);

        verify(entityManager, never()).refresh(any());
        verify(repo, never()).save(any(ReturnOrder.class));
        verify(voucherService, never()).createFromBusiness(anyString(), anyString(), anyString());
        assertEquals(VoucherFlag.UNCREATED, foreign.getVflag());
    }

    @Test
    @DisplayName("ReturnOrderVoucherListener: 失败回滚后独立事务重新锁行并写出 FAILED")
    void returnOrderListenerWritesFailedFlagWithoutDetachedSave() {
        ReturnOrderRepository repo = mock(ReturnOrderRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ReturnOrder order = new ReturnOrder();
        order.setId(ID);
        order.setFactoryId(FACTORY);
        order.setVflag(VoucherFlag.UNCREATED);
        when(repo.findById(ID)).thenReturn(Optional.of(order));
        when(voucherService.createFromBusiness(FACTORY, "RETURN_ORDER", ID))
                .thenThrow(new IllegalStateException("voucher failed"));

        ReturnOrderVoucherListener listener = returnOrderListener(repo, entityManager);
        ReflectionTestUtils.invokeMethod(listener, "handleVoucherGeneration", FACTORY, ID);

        verify(entityManager, times(2)).refresh(order);
        verify(repo, never()).save(any(ReturnOrder.class));
        assertEquals(VoucherFlag.FAILED, order.getVflag());
    }

    @Test
    @DisplayName("ReturnOrderVoucherListener: reject 先完成时锁后跳过幽灵凭证")
    void returnOrderListenerSkipsRejectedOrderAfterLock() {
        ReturnOrderRepository repo = mock(ReturnOrderRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        ReturnOrder rejected = new ReturnOrder();
        rejected.setId(ID);
        rejected.setFactoryId(FACTORY);
        rejected.setStatus(ReturnOrderStatus.REJECTED);
        rejected.setVflag(VoucherFlag.UNCREATED);
        when(repo.findById(ID)).thenReturn(Optional.of(rejected));

        ReturnOrderVoucherListener listener = returnOrderListener(repo, entityManager);
        ReflectionTestUtils.invokeMethod(listener, "handleVoucherGeneration", FACTORY, ID);

        verify(entityManager).refresh(rejected);
        verify(voucherService, never()).createFromBusiness(anyString(), anyString(), anyString());
        verify(repo, never()).save(any(ReturnOrder.class));
        assertEquals(VoucherFlag.UNCREATED, rejected.getVflag());
    }

    private ReturnOrderVoucherListener returnOrderListener(ReturnOrderRepository repo,
                                                            EntityManager entityManager) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        Query rowLockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(rowLockQuery);
        when(rowLockQuery.setParameter(anyString(), any())).thenReturn(rowLockQuery);
        when(rowLockQuery.getResultList()).thenReturn(List.of(ID));
        ReturnOrderVoucherListener listener = new ReturnOrderVoucherListener(repo, voucherService);
        ReflectionTestUtils.setField(listener, "entityManager", entityManager);
        ReflectionTestUtils.setField(listener, "transactionManager", transactionManager);
        return listener;
    }

    // ==================== ④ 调拨 → 凭证 (当前不炸, 因为实体没有 @Version) ====================

    @Test
    @DisplayName("TransferVoucherListener: 同样接住返回值 —— 它今天不炸只是因为实体没有 @Version")
    void transferListenerMustReuseMergedInstanceEvenThoughItCannotFailToday() {
        InternalTransferRepository repo = mock(InternalTransferRepository.class);

        InternalTransfer detached = new InternalTransfer();
        detached.setId(ID);
        detached.setVflag(VoucherFlag.UNCREATED);
        detached.setTotalAmount(BigDecimal.TEN);   // 0 值调拨会提前 return, 拿不到 save
        InternalTransfer merged = new InternalTransfer();
        merged.setId(ID);
        merged.setVflag(VoucherFlag.PENDING);
        merged.setTotalAmount(BigDecimal.TEN);

        when(repo.findById(ID)).thenReturn(Optional.of(detached));
        when(repo.save(any(InternalTransfer.class))).thenReturn(merged);
        when(voucherService.createFromBusiness(anyString(), anyString(), anyString()))
                .thenReturn(new Voucher());

        TransferVoucherListener listener = new TransferVoucherListener(repo, voucherService);
        ReflectionTestUtils.invokeMethod(listener, "handleVoucherGeneration", FACTORY, ID);

        ArgumentCaptor<InternalTransfer> cap = ArgumentCaptor.forClass(InternalTransfer.class);
        verify(repo, times(2)).save(cap.capture());
        assertSame(merged, cap.getAllValues().get(1),
                "InternalTransfer 今天没有 @Version 所以 stale merge 不会被检出 —— "
                        + "但形状与另外三处完全相同, 谁给它加上 @Version 就会**静默**变成同样的 bug");
    }

    // ==================== 阴性对照: 状态机的终态确实被写出去了 ====================

    @Test
    @DisplayName("阴性对照: 修复之后 vflag 的终态(CREATED)确实落在第二次 save 上")
    void terminalFlagIsActuallyWritten() {
        SalesOrderRepository repo = mock(SalesOrderRepository.class);

        SalesOrder detached = new SalesOrder();
        detached.setId(ID);
        detached.setVflag(VoucherFlag.UNCREATED);
        SalesOrder merged = new SalesOrder();
        merged.setId(ID);
        merged.setVflag(VoucherFlag.PENDING);

        when(repo.findById(ID)).thenReturn(Optional.of(detached));
        when(repo.save(any(SalesOrder.class))).thenReturn(merged);
        when(voucherService.createFromBusiness(anyString(), anyString(), anyString()))
                .thenReturn(new Voucher());

        new SalesFinanceApproveVoucherListener(repo, voucherService)
                .onFinanceApproved(new SalesOrderFinanceApprovedEvent(this, FACTORY, ID, 1309L));

        ArgumentCaptor<SalesOrder> cap = ArgumentCaptor.forClass(SalesOrder.class);
        verify(repo, times(2)).save(cap.capture());
        assertEquals(VoucherFlag.CREATED, cap.getAllValues().get(1).getVflag(),
                "第二次 save 的对象上必须带着终态 —— prod 上 CREATED/FAILED 都写不进去, "
                        + "三张表 FAILED 全是 0, 就是因为这次写被乐观锁挡掉了");
    }
}
