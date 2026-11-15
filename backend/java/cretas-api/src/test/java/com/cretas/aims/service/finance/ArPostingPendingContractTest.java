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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
 *   <li>事务层: 挂账抛异常<b>穿过 {@code @Transactional} 代理</b>后, 外层(发货)事务仍能提交, 待补账行落库;
 *       recordReceivableIfAbsent 去掉 noRollbackFor 时这里提交即 UnexpectedRollbackException;</li>
 *   <li>shipDelivery 层: 挂账抛 BusinessException → 发货仍成功 + 待补账落库 + 告警触发;
 *       幂等命中(返回既有 / 异常后复查已存在)不产生待补账;</li>
 *   <li>租户: 列表只见本厂。</li>
 * </ol>
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
        pendingRepo.deleteAll();
        arRepo.deleteAll();
    }

    @Test
    @DisplayName("事务层: 真实 ArApServiceImpl 的 BusinessException 穿过 @Transactional 代理后, 发货事务仍可提交, 发货侧写入保留, 待补账落库")
    void failureThroughTransactionalProxyDoesNotDoomShipTransaction() {
        TransactionTemplate ship = new TransactionTemplate(tm);
        ship.executeWithoutResult(s -> {
            // 代表「发货已完成的写入」
            ArPostingPending shipped = new ArPostingPending();
            shipped.setFactoryId(F);
            shipped.setDeliveryId("SHIP-MARKER");
            shipped.setFailureReason("marker");
            shipped.setStatus(ArPostingPendingStatus.RESOLVED);
            pendingRepo.save(shipped);

            // 真实 ArApServiceImpl(真 @Transactional 代理): requireSalesOrderOwner 抛 BusinessException 穿过代理
            org.mockito.Mockito.doThrow(new BusinessException(403, "无权访问该销售订单的应收"))
                    .when(rowScope).requireSalesOrderOwner(F, ORDER_ID);
            try {
                realArApService.recordReceivableIfAbsent(F, "CUST-1", ORDER_ID, "SALES_DELIVERY", DELIVERY_ID,
                        new BigDecimal("113.00"), LocalDate.now(), 7L, "t");
                throw new AssertionError("应抛 BusinessException");
            } catch (BusinessException e) {
                service.recordFailure(F, DELIVERY_ID, "DN-1", ORDER_ID, "CUST-1",
                        new BigDecimal("113.00"), 7L, e);
            }
        }); // 提交; 被 doom 则此处抛 UnexpectedRollbackException

        assertThat(pendingRepo.findAll().stream().map(ArPostingPending::getDeliveryId))
                .contains("SHIP-MARKER", DELIVERY_ID);
        ArPostingPending row = pendingRepo
                .findFirstByFactoryIdAndDeliveryIdAndStatus(F, DELIVERY_ID, ArPostingPendingStatus.PENDING)
                .orElseThrow();
        assertThat(row.getFactoryId()).isEqualTo(F);
        assertThat(row.getSalesOrderId()).isEqualTo(ORDER_ID);
        assertThat(row.getAmount()).isEqualByComparingTo("113.00");
        assertThat(row.getFailureReason()).contains("无权访问该销售订单的应收");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("shipDelivery: 挂账抛 BusinessException → 发货成功 + 待补账落库 + 告警触发")
    void shipSucceedsAndPendingRecordedWhenReceivablePostingThrows() {
        com.cretas.aims.service.finance.ArApService arApService = mock(com.cretas.aims.service.finance.ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(403, "无权访问该销售订单的应收"));
        SalesServiceImpl sales = salesService(arApService);

        SalesDeliveryRecord shipped = sales.shipDelivery(F, DELIVERY_ID, 7L);

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
    @DisplayName("shipDelivery: 幂等命中(挂账返回既有记录)不产生待补账、不告警")
    void idempotentHitCreatesNoPending() {
        com.cretas.aims.service.finance.ArApService arApService = mock(com.cretas.aims.service.finance.ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArApTransaction());
        SalesServiceImpl sales = salesService(arApService);

        sales.shipDelivery(F, DELIVERY_ID, 7L);

        assertThat(pendingRepo.findAll()).isEmpty();
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

        SalesDeliveryRecord shipped = sales.shipDelivery(F, DELIVERY_ID, 7L);

        assertThat(shipped.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
        assertThat(pendingRepo.findAll()).isEmpty();
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("同一发运单重复失败不堆叠; 列表按工厂隔离并可按状态筛选")
    void dedupeAndTenantScopedListing() {
        service.recordFailure(F, DELIVERY_ID, "DN-1", ORDER_ID, "C", new BigDecimal("1.00"), 1L, new RuntimeException("a"));
        service.recordFailure(F, DELIVERY_ID, "DN-1", ORDER_ID, "C", new BigDecimal("2.00"), 1L, new RuntimeException("b"));
        service.recordFailure(F_OTHER, DELIVERY_ID, "DN-X", "SO-X", "C", new BigDecimal("3.00"), 1L, new RuntimeException("c"));

        assertThat(pendingRepo.count()).isEqualTo(2);
        var mine = service.list(F, null, 1, 20);
        assertThat(mine.getTotalElements()).isEqualTo(1);
        assertThat(mine.getContent().get(0).getAmount()).isEqualByComparingTo("2.00");
        assertThat(mine.getContent().get(0).getFailureReason()).contains("b");
        assertThat(service.list(F, ArPostingPendingStatus.RESOLVED, 1, 20).getTotalElements()).isZero();
        assertThat(service.list(F_OTHER, ArPostingPendingStatus.PENDING, 1, 20).getTotalElements()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

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
