package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.ArPostingPending;
import com.cretas.aims.entity.finance.ArPostingPendingStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.finance.ArPostingPendingRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.finance.FinanceRowScopeQuery;
import com.cretas.aims.service.notification.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S3 发货应收挂账失败 → 发货成功 + 待补账记录 + 告警 (Steve 2026-11-15 裁决: 不回滚发货)。
 * 真实 Hibernate / H2 (PG 兼容) + 真实事务管理器, 不是 Mockito 仓库。
 *
 * <p>三层断言:
 * <ol>
 *   <li>事务层(真事务管理器 + 真实 {@code ArApServiceImpl} 代理): 挂账抛 BusinessException / 非 Business 的
 *       运行时异常(ArithmeticException)后, 发货事务照常提交, 发货侧写入保留, 待补账行落库, 挂账晚于发货提交;</li>
 *   <li>shipDelivery 层(发货放在真事务里调用, 挂账走 afterCommit): 挂账失败 → 发货 SHIPPED + 待补账 + 告警;
 *       挂账返回既有记录(幂等)→ 无待补账; 异常后复查已存在(并发已挂)→ 无待补账;</li>
 *   <li>租户 / 行范围: 列表只见本厂, 行范围受限账号只见本人订单的待补账; 待补账记录不可删除。</li>
 * </ol>
 * 说明: H2 无法复现 PG「语句失败后事务 aborted」, 该场景由「挂账在独立事务」的结构保证, 未在真 PG 上验证。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({ArPostingPendingService.class, com.cretas.aims.service.finance.impl.ArApServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("发货挂账失败: 待补账记录 + 告警, 发货不回滚")
class ArPostingPendingContractTest {

    private static final String F = "F-ARPP-1";
    private static final String F_OTHER = "F-ARPP-2";
    private static final String ORDER_ID = "SO-ARPP-1";
    private static final String DELIVERY_ID = "DLV-ARPP-1";

    @Autowired ArPostingPendingService service;
    @Autowired ArPostingPendingRepository pendingRepo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ArApTransactionRepository arRepo;
    @Autowired ArApService realArApService;
    @MockBean FinanceRowScopeQuery rowScope;
    @MockBean com.cretas.aims.service.finance.AccountingPeriodService accountingPeriodService;
    @MockBean com.cretas.aims.service.finance.FinancePaymentReferenceLock paymentReferenceLock;
    @Autowired PlatformTransactionManager tm;
    @Autowired com.cretas.aims.repository.CustomerRepository customerRepo;
    @MockBean NotificationService notificationService;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.execute("DELETE FROM ar_posting_pending"); // 实体禁止删除, 测试清理走 SQL
        arRepo.deleteAll();
        // Mockito 对 List 返回值默认给空表 = 「无可见行」; 默认应为不受限(null)
        org.mockito.Mockito.reset(rowScope);
        when(rowScope.creators(any())).thenReturn(null);
    }

    @Test
    @DisplayName("事务层: 真实 ArApServiceImpl 抛 BusinessException 或 ArithmeticException 后, 发货事务仍可提交, 待补账落库, 挂账晚于发货提交")
    void anyPostingFailureLeavesShipTransactionAlone() {
        RuntimeException[] failures = {
                new BusinessException(403, "无权访问该销售订单的应收"),
                new ArithmeticException("Rounding necessary")   // 审查点名的: 非 BusinessException 的运行时异常
        };
        for (RuntimeException failure : failures) {
            clean();
            List<String> order = new ArrayList<>();
            org.mockito.Mockito.doThrow(failure).when(rowScope).requireSalesOrderOwner(F, ORDER_ID);

            new TransactionTemplate(tm).executeWithoutResult(s -> {
                ArPostingPending marker = new ArPostingPending();   // 代表「发货已完成的写入」
                marker.setFactoryId(F);
                marker.setDeliveryId("SHIP-MARKER");
                marker.setFailureReason("marker");
                marker.setStatus(ArPostingPendingStatus.RESOLVED);
                pendingRepo.save(marker);

                service.postAfterShipCommit(F, DELIVERY_ID, "DN-1", ORDER_ID, "CUST-1", 5L,
                        new BigDecimal("113.00"), 7L, null,
                        () -> {
                            order.add("posted");
                            return realArApService.recordReceivableIfAbsent(F, "CUST-1", ORDER_ID,
                                    "SALES_DELIVERY", DELIVERY_ID, new BigDecimal("113.00"),
                                    LocalDate.now(), 7L, "t");
                        });
                order.add("ship-body-done");
            }); // 发货事务提交; 若挂账在发货事务内抛异常穿过代理, 这里会是 UnexpectedRollbackException

            assertThat(order).as(failure.getClass().getSimpleName() + ": 挂账必须晚于发货事务体").containsExactly("ship-body-done", "posted");
            assertThat(pendingRepo.findAll().stream().map(ArPostingPending::getDeliveryId))
                    .as("发货侧写入保留 + 待补账落库").contains("SHIP-MARKER", DELIVERY_ID);
            ArPostingPending row = pendingRepo
                    .findFirstByFactoryIdAndDeliveryIdAndStatus(F, DELIVERY_ID, ArPostingPendingStatus.PENDING)
                    .orElseThrow();
            assertThat(row.getFailureReason()).contains(failure.getClass().getSimpleName());
            assertThat(row.getOwnerId()).isEqualTo(5L);
            assertThat(row.getAmount()).isEqualByComparingTo("113.00");
            assertThat(row.getCreatedAt()).isNotNull();
            verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
            org.mockito.Mockito.reset(notificationService);
        }
    }

    @Test
    @DisplayName("成功路径: 发货提交后挂账的写入必须真正落库(挂账不在独立事务里就会写进已完成的发货事务而丢失), 且不产生待补账")
    void postingAfterShipCommitIsActuallyCommitted() {
        new TransactionTemplate(tm).executeWithoutResult(s ->
                service.postAfterShipCommit(F, DELIVERY_ID, "DN-1", ORDER_ID, "CUST-1", 5L,
                        new BigDecimal("113.00"), 7L, null, () -> {
                            ArApTransaction ar = new ArApTransaction();
                            ar.setFactoryId(F);
                            ar.setTransactionNumber("AR-OK-1");
                            ar.setTransactionType(ArApTransactionType.AR_INVOICE);
                            ar.setCounterpartyType(CounterpartyType.CUSTOMER);
                            ar.setCounterpartyId("CUST-1");
                            ar.setSourceType("SALES_DELIVERY");
                            ar.setSourceId(DELIVERY_ID);
                            ar.setAmount(new BigDecimal("113.00"));
                            ar.setBalanceAfter(new BigDecimal("113.00"));
                            ar.setTransactionDate(LocalDate.now());
                            return arRepo.save(ar);
                        }));

        assertThat(service.receivableAlreadyPosted(F, "SALES_DELIVERY", DELIVERY_ID))
                .as("发货事务提交后, 挂账行必须已提交可见").isTrue();
        assertThat(openPending()).isEmpty();
    }

    @Test
    @DisplayName("崩溃保护: 发货已提交但 afterCommit 回调没跑(进程被杀的模拟), 发货事务内写下的 PENDING 意图行仍在, 且无告警")
    void intentRowSurvivesWhenAfterCommitCallbackNeverRuns() {
        boolean[] posted = {false};
        try {
            new TransactionTemplate(tm).executeWithoutResult(s -> {
                // 先注册一个 afterCommit 就抛的同步: 后面注册的挂账回调因此不会执行(模拟 commit 之后进程消失)
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        throw new IllegalStateException("simulated crash after commit");
                    }
                });
                service.postAfterShipCommit(F, DELIVERY_ID, "DN-1", ORDER_ID, "CUST-1", 5L,
                        new BigDecimal("113.00"), 7L, null, () -> {
                            posted[0] = true;
                            return null;
                        });
            });
        } catch (IllegalStateException expected) {
            // 模拟崩溃的异常冲出提交点, 预期内
        }
        assertThat(posted[0]).as("挂账回调确实没有执行").isFalse();
        List<ArPostingPending> open = openPending();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getDeliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(open.get(0).getFailureReason()).contains("挂账进行中");
        assertThat(open.get(0).getOwnerId()).isEqualTo(5L);
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("发货事务回滚时意图行一起回滚, 不留幽灵待补账")
    void intentRowRollsBackWithShipTransaction() {
        try {
            new TransactionTemplate(tm).executeWithoutResult(s -> {
                service.postAfterShipCommit(F, DELIVERY_ID, "DN-1", ORDER_ID, "CUST-1", 5L,
                        new BigDecimal("113.00"), 7L, null, () -> null);
                throw new IllegalStateException("发货后续失败, 事务回滚");
            });
        } catch (IllegalStateException expected) {
            // 预期内
        }
        assertThat(pendingRepo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("挂账成功后意图行自动关闭为 RESOLVED(不是 PENDING, 也不留旧原因)")
    void successClosesTheIntentRow() {
        new TransactionTemplate(tm).executeWithoutResult(s ->
                service.postAfterShipCommit(F, DELIVERY_ID, "DN-1", ORDER_ID, "CUST-1", 5L,
                        new BigDecimal("113.00"), 7L, null, () -> "ok"));
        List<ArPostingPending> all = pendingRepo.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(ArPostingPendingStatus.RESOLVED);
        assertThat(all.get(0).getResolvedAt()).isNotNull();
        assertThat(all.get(0).getFailureReason()).doesNotContain("挂账进行中");
    }

    @Test
    @DisplayName("挂账被「诚实跳过」(返回 null: 金额缺失/客户不存在/订单状态不可开票)也当失败落待补账并告警")
    void skippedPostingReturningNullIsRecordedAsFailure() {
        com.cretas.aims.service.finance.ArApService arApService = mock(com.cretas.aims.service.finance.ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        SalesServiceImpl sales = salesService(arApService);

        SalesDeliveryRecord shipped = shipInTx(sales);

        assertThat(shipped.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
        List<ArPostingPending> open = openPending();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getFailureReason()).contains("自动挂账被跳过");
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
    }

    @Test
    @DisplayName("告警自己抛异常不影响待补账记录; 待补账记录不可删除")
    void alertFailureDoesNotLoseRecordAndRecordCannotBeDeleted() {
        org.mockito.Mockito.doThrow(new RuntimeException("通知通道挂了"))
                .when(notificationService).notifyRole(anyString(), anyString(), anyString(), anyString());
        ArPostingPending row = service.recordFailure(F, DELIVERY_ID, "DN-1", ORDER_ID, "C", 5L,
                new BigDecimal("1.00"), 1L, new RuntimeException("x"));
        assertThat(pendingRepo.findById(row.getId())).isPresent();

        assertThatThrownBy(() -> {
            pendingRepo.delete(pendingRepo.findById(row.getId()).orElseThrow());
            pendingRepo.flush();
        }).isInstanceOf(UnsupportedOperationException.class);
        assertThat(pendingRepo.findById(row.getId())).isPresent();
    }

    @Test
    @DisplayName("行范围: 受限账号只看本人订单的待补账; 范围为空什么都看不到; 不受限看全厂(仍按工厂隔离)")
    void listHonorsFinanceRowScope() {
        service.recordFailure(F, "D-1", "DN-1", "SO-1", "C", 5L, new BigDecimal("1.00"), 1L, new RuntimeException("a"));
        service.recordFailure(F, "D-2", "DN-2", "SO-2", "C", 6L, new BigDecimal("2.00"), 1L, new RuntimeException("b"));
        service.recordFailure(F, "D-3", "DN-3", "SO-3", "C", null, new BigDecimal("3.00"), 1L, new RuntimeException("c"));
        service.recordFailure(F_OTHER, "D-9", "DN-9", "SO-9", "C", 5L, new BigDecimal("9.00"), 1L, new RuntimeException("z"));

        assertThat(service.list(F, null, 1, 20).getTotalElements()).isEqualTo(3);

        when(rowScope.creators(F)).thenReturn(List.of(5L));
        var scoped = service.list(F, null, 1, 20);
        assertThat(scoped.getContent()).extracting(ArPostingPending::getDeliveryId).containsExactly("D-1");
        assertThat(service.list(F, ArPostingPendingStatus.PENDING, 1, 20).getTotalElements()).isEqualTo(1);

        when(rowScope.creators(F)).thenReturn(List.of());
        assertThat(service.list(F, null, 1, 20).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("shipDelivery: 挂账抛 BusinessException → 发货成功 + 待补账落库 + 告警触发")
    void shipSucceedsAndPendingRecordedWhenReceivablePostingThrows() {
        com.cretas.aims.service.finance.ArApService arApService = mock(com.cretas.aims.service.finance.ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(403, "无权访问该销售订单的应收"));
        SalesServiceImpl sales = salesService(arApService);

        SalesDeliveryRecord shipped = shipInTx(sales);

        assertThat(shipped.getStatus()).as("发货必须成功, 不回滚").isEqualTo(SalesDeliveryStatus.SHIPPED);
        List<ArPostingPending> rows = pendingRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getFactoryId()).isEqualTo(F);
        assertThat(rows.get(0).getDeliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(rows.get(0).getStatus()).isEqualTo(ArPostingPendingStatus.PENDING);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("200.00");
        assertThat(rows.get(0).getFailureReason()).contains("无权访问该销售订单的应收");
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), contains("DN-ARPP-1"));
    }

    @Test
    @DisplayName("阴性对照: 挂账返回既有记录(幂等命中, 不抛)不产生待补账、不告警 —— 不经过失败分支, 失败分支里的幂等复查由「并发已挂」用例守")
    void idempotentHitCreatesNoPending() {
        com.cretas.aims.service.finance.ArApService arApService = mock(com.cretas.aims.service.finance.ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArApTransaction());
        SalesServiceImpl sales = salesService(arApService);

        shipInTx(sales);

        assertThat(openPending()).isEmpty();
        assertThat(pendingRepo.findAll()).hasSize(1);
        assertThat(pendingRepo.findAll().get(0).getStatus()).isEqualTo(ArPostingPendingStatus.RESOLVED);
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("shipDelivery: 并发已挂(异常后复查该发运单应收已存在)不算失败, 不产生待补账")
    void raceWhereReceivableAlreadyExistsCreatesNoPending() {
        ArApTransaction existing = new ArApTransaction();
        existing.setFactoryId(F);
        existing.setTransactionNumber("AR-ARPP-1");
        existing.setTransactionType(ArApTransactionType.AR_INVOICE);
        existing.setCounterpartyType(CounterpartyType.CUSTOMER);
        existing.setCounterpartyId("CUST-1");
        existing.setSourceType("SALES_DELIVERY");
        existing.setSourceId(DELIVERY_ID);
        existing.setAmount(new BigDecimal("200.00"));
        existing.setBalanceAfter(new BigDecimal("200.00"));
        existing.setTransactionDate(LocalDate.now());
        arRepo.saveAndFlush(existing);

        com.cretas.aims.service.finance.ArApService arApService = mock(com.cretas.aims.service.finance.ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_aat_ar_invoice_per_shipment"));
        SalesServiceImpl sales = salesService(arApService);

        SalesDeliveryRecord shipped = shipInTx(sales);

        assertThat(shipped.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
        assertThat(openPending()).isEmpty();
        assertThat(pendingRepo.findAll()).hasSize(1);
        assertThat(pendingRepo.findAll().get(0).getStatus()).isEqualTo(ArPostingPendingStatus.RESOLVED);
        assertThat(pendingRepo.findAll().get(0).getFailureReason()).contains("幂等命中");
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("同一发运单重复失败不堆叠; 列表按工厂隔离并可按状态筛选")
    void dedupeAndTenantScopedListing() {
        service.recordFailure(F, DELIVERY_ID, "DN-1", ORDER_ID, "C", 5L, new BigDecimal("1.00"), 1L, new RuntimeException("a"));
        service.recordFailure(F, DELIVERY_ID, "DN-1", ORDER_ID, "C", 5L, new BigDecimal("2.00"), 1L, new RuntimeException("b"));
        service.recordFailure(F_OTHER, DELIVERY_ID, "DN-X", "SO-X", "C", 5L, new BigDecimal("3.00"), 1L, new RuntimeException("c"));

        assertThat(pendingRepo.count()).isEqualTo(2);
        var mine = service.list(F, null, 1, 20);
        assertThat(mine.getTotalElements()).isEqualTo(1);
        assertThat(mine.getContent().get(0).getAmount()).isEqualByComparingTo("2.00");
        assertThat(mine.getContent().get(0).getFailureReason()).contains("b");
        assertThat(service.list(F, ArPostingPendingStatus.RESOLVED, 1, 20).getTotalElements()).isZero();
        assertThat(service.list(F_OTHER, ArPostingPendingStatus.PENDING, 1, 20).getTotalElements()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private List<ArPostingPending> openPending() {
        return pendingRepo.findAll().stream()
                .filter(r -> r.getStatus() == ArPostingPendingStatus.PENDING).toList();
    }

    /** 发货放进真事务调用: 挂账走 afterCommit(与生产一致), 而不是单测里的直调分支。 */
    private SalesDeliveryRecord shipInTx(SalesServiceImpl sales) {
        return new TransactionTemplate(tm).execute(st -> sales.shipDelivery(F, DELIVERY_ID, 7L));
    }

    private SalesServiceImpl salesService(com.cretas.aims.service.finance.ArApService arApService) {
        SalesOrderRepository orderRepo = Mockito.mock(SalesOrderRepository.class);
        SalesDeliveryRecordRepository deliveryRepo = Mockito.mock(SalesDeliveryRecordRepository.class);
        SalesOrderItemRepository itemRepo = Mockito.mock(SalesOrderItemRepository.class);

        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", DELIVERY_ID);
        d.setDeliveryNumber("DN-ARPP-1");
        d.setFactoryId(F);
        d.setSalesOrderId(ORDER_ID);
        d.setStatus(SalesDeliveryStatus.DRAFT);
        d.setRecordRole("SHIPMENT");
        d.setDeliveryMethod("SELF_PICKUP");
        d.setDeliveryDate(LocalDate.now());
        d.setTotalAmount(new BigDecimal("200.00"));
        d.setItems(new ArrayList<>());

        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setFactoryId(F);
        o.setCustomerId("CUST-1");
        o.setTotalAmount(new BigDecimal("900.00"));
        o.setStatus(SalesOrderStatus.CONFIRMED);
        o.setItems(new ArrayList<>());

        when(deliveryRepo.findById(DELIVERY_ID)).thenReturn(Optional.of(d));
        when(deliveryRepo.findByIdAndFactoryIdForUpdate(DELIVERY_ID, F)).thenReturn(Optional.of(d));
        when(orderRepo.findById(ORDER_ID)).thenReturn(Optional.of(o));
        when(orderRepo.findByIdAndFactoryId(ORDER_ID, F)).thenReturn(Optional.of(o));
        when(orderRepo.findByIdAndFactoryIdForUpdate(ORDER_ID, F)).thenReturn(Optional.of(o));
        when(itemRepo.findBySalesOrderId(ORDER_ID)).thenReturn(Collections.emptyList());
        when(deliveryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SalesServiceImpl sales = new SalesServiceImpl(orderRepo, itemRepo, deliveryRepo,
                mock(FinishedGoodsBatchRepository.class), mock(CustomerRepository.class),
                mock(ProductTypeRepository.class), arApService, mock(ApplicationEventPublisher.class));
        // 真实的待补账服务 + 真实的应收仓库(幂等复查/切换缝查询走真实 JPQL)
        ReflectionTestUtils.setField(sales, "receivablePendingService", service);
        ReflectionTestUtils.setField(sales, "arApTransactionRepository", arRepo);
        return sales;
    }
}
