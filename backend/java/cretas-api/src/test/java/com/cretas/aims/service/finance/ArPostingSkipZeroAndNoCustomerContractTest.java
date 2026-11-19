package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.ArPostingPending;
import com.cretas.aims.entity.finance.ArPostingPendingStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
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
 * #1768 (D-S3) 口径补丁, Steve 2026-11-16 复核口径(#1795):
 * <ul>
 *   <li>零金额发货(赠品/样品, 发货行与订单行单价都确定为 0) = <b>无需挂账</b>: 不调挂账、不告警、没有 PENDING 待补账,
 *       但在发货事务内留一行 RESOLVED、原因以 {@code ZERO_AMOUNT:} 开头的追溯行; 追溯行写失败则发货整体回滚、不告警。</li>
 *   <li>没有客户的订单、查不到本厂订单(含别厂订单 id)、真正的挂账失败: 仍按失败处理(待补账 PENDING + 告警, 发货不回滚)。</li>
 *   <li>缺价回填(Steve 2026-11-17): 发货行单价为 null 时按来源订单行单价回填计入应收; 订单行也没价则按失败处理。</li>
 * </ul>
 *
 * <p>全部走产品真实入口 {@code SalesServiceImpl#shipDelivery}, 放在真事务里调用(挂账走 afterCommit, 与生产一致),
 * 待补账服务与仓库是真实 Hibernate / H2。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({ArPostingPendingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("发货挂账: 零金额=无需挂账(留 RESOLVED/ZERO_AMOUNT 追溯行, 不告警); 无客户订单与真实失败仍待补账 + 告警")
class ArPostingSkipZeroAndNoCustomerContractTest {

    private static final String F = "F-ARSKIP-1";
    private static final String ORDER_ID = "SO-ARSKIP-1";
    private static final String DELIVERY_ID = "DLV-ARSKIP-1";

    @Autowired ArPostingPendingService service;
    @Autowired ArPostingPendingRepository pendingRepo;
    @Autowired ArApTransactionRepository arRepo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    @MockBean FinanceRowScopeQuery rowScope;
    @MockBean NotificationService notificationService;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.execute("DELETE FROM ar_posting_pending"); // 实体禁止删除, 测试清理走 SQL
        arRepo.deleteAll();
        Mockito.reset(rowScope, notificationService);
        when(rowScope.creators(any())).thenReturn(null);
    }

    @Test
    @DisplayName("赠品/样品(有明细, 发货行与订单行单价都为 0; 0 与 0.00 都算零): 无需挂账 —— 只留一行 RESOLVED/ZERO_AMOUNT 追溯行, 无 PENDING 待补账、无告警、不调挂账")
    void giftShipmentIsSkippedWithoutPendingOrAlert() {
        for (String zero : new String[]{"0", "0.00"}) {
            clean();
            ArApService arApService = mock(ArApService.class);
            SalesServiceImpl sales = salesServiceWithLine(arApService, zero, zero);

            SalesDeliveryRecord shipped = shipInTx(sales);

            assertThat(shipped.getStatus()).as(zero + ": 发货成功").isEqualTo(SalesDeliveryStatus.SHIPPED);
            assertThat(shipped.getItems()).as("确实是有明细的发货").hasSize(1);
            assertThat(openPending()).as(zero + ": 没有待处理(PENDING)的待补账").isEmpty();
            var trace = pendingRepo.findAll();
            assertThat(trace).as(zero + ": 留一行追溯记录").hasSize(1);
            assertThat(trace.get(0).getStatus()).isEqualTo(ArPostingPendingStatus.RESOLVED);
            assertThat(trace.get(0).getFailureReason()).startsWith("ZERO_AMOUNT:");
            assertThat(trace.get(0).getDeliveryId()).isEqualTo(DELIVERY_ID);
            assertThat(trace.get(0).getResolvedAt()).isNotNull();
            assertThat(trace.get(0).getAmount()).isEqualByComparingTo("0");
            verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
            verify(arApService, never()).recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("不放开: 订单行有价(100)而发货行录成 0 → 误录不是赠品, 仍待补账 + 告警")
    void orderLinePricedButShipLineZeroIsStillFailure() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        SalesServiceImpl sales = salesServiceWithLine(arApService, "0", "100.00");

        SalesDeliveryRecord shipped = shipInTx(sales);

        assertThat(shipped.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
        assertThat(openPending()).hasSize(1);
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
    }

    @Test
    @DisplayName("不放开: 订单行单价没维护(null)、发货行 0 → 缺价不是赠品, 仍待补账 + 告警")
    void orderLinePriceMissingIsStillFailure() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        SalesServiceImpl sales = salesServiceWithLine(arApService, "0", null);

        shipInTx(sales);

        assertThat(openPending()).hasSize(1);
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
    }

    @Test
    @DisplayName("不放开: 空明细(头额默认 0 / 0.00) 不算零金额, 仍待补账 + 告警")
    void emptyItemsWithZeroHeaderIsStillFailure() {
        for (String zero : new String[]{"0", "0.00"}) {
            clean();
            ArApService arApService = mock(ArApService.class);
            when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(null);
            SalesServiceImpl sales = salesService(arApService, "CUST-1", new BigDecimal(zero));

            shipInTx(sales);

            assertThat(openPending()).as(zero).hasSize(1);
            verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
        }
    }

    @Test
    @DisplayName("边界: 0.01 不算零金额, 照常调挂账(成功则意图行关闭为 RESOLVED, 无告警)")
    void oneCentIsNotZero() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArApTransaction());
        SalesServiceImpl sales = salesService(arApService, "CUST-1", new BigDecimal("0.01"));

        shipInTx(sales);

        verify(arApService).recordReceivableIfAbsent(eq(F), eq("CUST-1"), eq(ORDER_ID), eq("SALES_DELIVERY"),
                eq(DELIVERY_ID), eq(new BigDecimal("0.01")), any(), any(), any());
        List<ArPostingPending> all = pendingRepo.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(ArPostingPendingStatus.RESOLVED);
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("边界: 0.01 且挂账被跳过(返回 null) 仍按失败 —— 待补账 + 告警, 说明 0.01 没被当成零")
    void oneCentSkippedByPostingIsStillFailure() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        SalesServiceImpl sales = salesService(arApService, "CUST-1", new BigDecimal("0.01"));

        shipInTx(sales);

        assertThat(openPending()).hasSize(1);
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
    }

    @Test
    @DisplayName("没有客户的订单(null 或空串): 仍按失败 —— 待补账(PENDING) + 告警, 发货成功, 不调挂账")
    void orderWithoutCustomerIsStillFailure() {
        for (String customer : new String[]{null, "", "  "}) {
            clean();
            ArApService arApService = mock(ArApService.class);
            SalesServiceImpl sales = salesService(arApService, customer, new BigDecimal("200.00"));

            SalesDeliveryRecord shipped = shipInTx(sales);

            assertThat(shipped.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
            assertThat(openPending()).as("customer=[" + customer + "]: 落待补账").hasSize(1);
            assertThat(openPending().get(0).getFailureReason()).contains("没有客户");
            verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
            verify(arApService, never()).recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("追溯行幂等 + 不影响默认列表: 同一发运单同一原因只留一行, 默认(PENDING)列表看不到, status=RESOLVED 能查到")
    void zeroAmountTraceRowIsIdempotentAndHiddenFromDefaultList() {
        for (int i = 0; i < 2; i++) {
            service.recordNoPostingNeeded(F, DELIVERY_ID, "DN-1", ORDER_ID, "CUST-1", 5L,
                    BigDecimal.ZERO, 7L, "ZERO_AMOUNT", "零金额发货");
        }
        assertThat(pendingRepo.findAll()).hasSize(1);
        assertThat(service.list(F, ArPostingPendingStatus.PENDING, 1, 20).getTotalElements()).isZero();
        assertThat(service.list(F, ArPostingPendingStatus.RESOLVED, 1, 20).getTotalElements()).isEqualTo(1);
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("追溯行幂等按原因查库: 同一发运单已有一行别的 RESOLVED(挂账成功关闭的意图行)时, 重复调用仍只写一行 ZERO_AMOUNT")
    void zeroAmountTraceIdempotentWhenOtherResolvedRowExists() {
        // 先插一行同发运单、原因不是 ZERO_AMOUNT 的 RESOLVED: 「取任意一行 RESOLVED 再比原因」会取到它而漏判
        ArPostingPending other = new ArPostingPending();
        other.setFactoryId(F);
        other.setDeliveryId(DELIVERY_ID);
        other.setStatus(ArPostingPendingStatus.RESOLVED);
        other.setFailureReason("自动挂账成功, 意图行自动关闭");
        other.setResolvedAt(java.time.LocalDateTime.now());
        pendingRepo.saveAndFlush(other);

        for (int i = 0; i < 3; i++) {
            service.recordNoPostingNeeded(F, DELIVERY_ID, "DN-1", ORDER_ID, "CUST-1", 5L,
                    BigDecimal.ZERO, 7L, "ZERO_AMOUNT", "零金额发货");
        }

        List<ArPostingPending> zero = pendingRepo.findAll().stream()
                .filter(r -> r.getFailureReason().startsWith("ZERO_AMOUNT:")).toList();
        assertThat(zero).as("ZERO_AMOUNT 追溯行只一行").hasSize(1);
        assertThat(pendingRepo.findAll()).as("别的 RESOLVED 行原样保留").hasSize(2);
        // 真 JPA 上跑派生查询: 前缀里的 _ 按字面匹配(不当 LIKE 通配), 别的工厂 / 别的状态查不到
        assertThat(pendingRepo.existsByFactoryIdAndDeliveryIdAndStatusAndFailureReasonStartingWith(
                F, DELIVERY_ID, ArPostingPendingStatus.RESOLVED, "ZERO_AMOUNT:")).isTrue();
        assertThat(pendingRepo.existsByFactoryIdAndDeliveryIdAndStatusAndFailureReasonStartingWith(
                F, DELIVERY_ID, ArPostingPendingStatus.RESOLVED, "ZEROXAMOUNT:")).as("_ 不当通配符").isFalse();
        assertThat(pendingRepo.existsByFactoryIdAndDeliveryIdAndStatusAndFailureReasonStartingWith(
                "F-OTHER", DELIVERY_ID, ArPostingPendingStatus.RESOLVED, "ZERO_AMOUNT:")).isFalse();
        assertThat(pendingRepo.existsByFactoryIdAndDeliveryIdAndStatusAndFailureReasonStartingWith(
                F, DELIVERY_ID, ArPostingPendingStatus.PENDING, "ZERO_AMOUNT:")).isFalse();
    }

    @Test
    @DisplayName("零金额追溯行写入失败: 异常抛出、整个发货事务回滚(同事务的标记行也没了), 不写待补账、不告警、不调挂账")
    void zeroAmountTraceWriteFailureRollsBackShipmentWithoutAlert() {
        ArApService arApService = mock(ArApService.class);
        SalesServiceImpl sales = salesServiceWithLine(arApService, "0", "0");
        ArPostingPendingService failing = Mockito.spy(service);
        Mockito.doThrow(new IllegalStateException("追溯行写入失败(模拟)")).when(failing).recordNoPostingNeeded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        ReflectionTestUtils.setField(sales, "receivablePendingService", failing);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransactionTemplate(tm).execute(st -> {
                    // 同一发货事务里先写一行标记: 事务真回滚的话它不会留下
                    service.recordNoPostingNeeded(F, "DLV-MARKER", "DN-M", ORDER_ID, "CUST-1", 5L,
                            BigDecimal.ZERO, 7L, "MARKER", "同事务标记行");
                    return sales.shipDelivery(F, DELIVERY_ID, 7L);
                }))
                .hasMessageContaining("追溯行写入失败");

        verify(failing).recordNoPostingNeeded(eq(F), eq(DELIVERY_ID), any(), any(), any(), any(), any(), any(),
                eq("ZERO_AMOUNT"), any());
        assertThat(pendingRepo.findAll()).as("发货事务回滚: 标记行与待补账都没有").isEmpty();
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
        verify(arApService, never()).recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("租户: 挂账段按 id + factoryId 查订单; 本厂查不到(别厂订单 id)时按失败处理(待补账 + 告警), 不用裸 findById 读到的订单挂账")
    void orderOfOtherFactoryIsNotFoundAndTreatedAsFailure() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArApTransaction());
        SalesServiceImpl sales = salesService(arApService, "CUST-OTHER", new BigDecimal("200.00"));
        SalesOrderRepository orderRepo = (SalesOrderRepository) ReflectionTestUtils.getField(sales, "salesOrderRepository");
        // 裸 findById 仍能读到这张订单(别厂订单就是这个形状); 带 factoryId 的查询在本厂查不到。
        // 注: 生产上发货入口 lockSalesOrderForDelivery 已按 factoryId 加锁, 别厂订单在更前面就被拦;
        // 这里钉的是挂账段自己的租户边界(纵深防御), 所以加锁桩保持命中, 只让挂账段的查询查不到。
        when(orderRepo.findByIdAndFactoryId(ORDER_ID, F)).thenReturn(Optional.empty());

        SalesDeliveryRecord shipped = shipInTx(sales);

        assertThat(shipped.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
        List<ArPostingPending> open = openPending();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getFailureReason()).contains("不属于本工厂");
        assertThat(open.get(0).getCustomerId()).as("不把别厂订单的客户写进待补账").isNull();
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
        verify(arApService, never()).recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("缺价回填: 混合发货里发货行单价为 null 的那行按订单行价(100)回填, 应收 = 5×20 + 5×100 = 600, 发货行价与头额同步")
    void missingShipLinePriceIsBackfilledFromOrderLine() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArApTransaction());
        SalesServiceImpl sales = salesServiceWithLines(arApService,
                new String[]{"20", "20"}, new String[]{null, "100"});

        SalesDeliveryRecord shipped = shipInTx(sales);

        verify(arApService).recordReceivableIfAbsent(eq(F), eq("CUST-1"), eq(ORDER_ID), eq("SALES_DELIVERY"),
                eq(DELIVERY_ID), eq(new BigDecimal("600.00")), any(), any(), any());
        assertThat(shipped.getItems().get(1).getUnitPrice()).as("缺价行已回填").isEqualByComparingTo("100");
        assertThat(shipped.getTotalAmount()).as("头额按回填后重算").isEqualByComparingTo("600");
        assertThat(openPending()).isEmpty();
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("缺价回填: 订单行也没价 → 不按 0 计入(不挂少了的 100), 按失败: 待补账 + 告警, 发货成功")
    void missingShipLinePriceWithoutOrderPriceIsFailure() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArApTransaction());
        SalesServiceImpl sales = salesServiceWithLines(arApService,
                new String[]{"20", "20"}, new String[]{null, null});

        SalesDeliveryRecord shipped = shipInTx(sales);

        assertThat(shipped.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
        verify(arApService, never()).recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any());
        List<ArPostingPending> open = openPending();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getFailureReason()).contains("缺单价");
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
    }

    @Test
    @DisplayName("缺价回填不影响正常价: 发货行有价(30)而订单行价不同(100) → 按发货行价挂 150, 不被订单行覆盖")
    void pricedShipLineIsNotOverwritten() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArApTransaction());
        SalesServiceImpl sales = salesServiceWithLine(arApService, "30", "100");

        SalesDeliveryRecord shipped = shipInTx(sales);

        verify(arApService).recordReceivableIfAbsent(eq(F), eq("CUST-1"), eq(ORDER_ID), eq("SALES_DELIVERY"),
                eq(DELIVERY_ID), eq(new BigDecimal("150.00")), any(), any(), any());
        assertThat(shipped.getItems().get(0).getUnitPrice()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("阳性对照: 真实挂账失败(抛 BusinessException) 仍写待补账 + 发严重告警, 发货不回滚")
    void realPostingFailureStillRecordsPendingAndAlerts() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(500, "数据库写入失败"));
        SalesServiceImpl sales = salesService(arApService, "CUST-1", new BigDecimal("200.00"));

        SalesDeliveryRecord shipped = shipInTx(sales);

        assertThat(shipped.getStatus()).isEqualTo(SalesDeliveryStatus.SHIPPED);
        List<ArPostingPending> open = openPending();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getFailureReason()).contains("数据库写入失败");
        assertThat(open.get(0).getAmount()).isEqualByComparingTo("200.00");
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
    }

    @Test
    @DisplayName("不放开的情形: 金额「不知道」(空明细且无头额 → 算出 0) 仍按失败, 待补账 + 告警")
    void unknownAmountComputedAsZeroIsStillFailure() {
        ArApService arApService = mock(ArApService.class);
        when(arApService.recordReceivableIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        SalesServiceImpl sales = salesService(arApService, "CUST-1", null);

        shipInTx(sales);

        assertThat(openPending()).hasSize(1);
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
    }

    @Test
    @DisplayName("不放开的情形: 销售订单不存在仍按失败, 待补账 + 告警")
    void missingOrderIsStillFailure() {
        ArApService arApService = mock(ArApService.class);
        SalesServiceImpl sales = salesService(arApService, "CUST-1", new BigDecimal("200.00"));
        SalesOrderRepository orderRepo = (SalesOrderRepository) ReflectionTestUtils.getField(sales, "salesOrderRepository");
        when(orderRepo.findById(ORDER_ID)).thenReturn(Optional.empty());
        when(orderRepo.findByIdAndFactoryId(ORDER_ID, F)).thenReturn(Optional.empty());

        shipInTx(sales);

        List<ArPostingPending> open = openPending();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getFailureReason()).contains("销售订单不存在");
        verify(notificationService).notifyRole(eq(F), eq("finance_manager"), contains("挂账失败"), anyString());
    }

    @Test
    @DisplayName("判零细则(补充, 真实入口见上): 缺单价/缺数量/部分行缺价、极小单价舍入成 0、订单行有价或报价含税单价非 0、挂不上订单行、空明细、0.01、负数 都不算零")
    void itemLevelZeroJudgement() {
        SalesOrder giftOrder = orderWith(orderLine(1L, "0", null), orderLine(2L, "0.00", "0"));
        assertThat(knownZero(giftOrder, items(line(1L, "0", "5")), "0.00")).isTrue();
        assertThat(knownZero(giftOrder, items(line(1L, "0.00", "1"), line(2L, "0", "2")), "0")).isTrue();
        assertThat(knownZero(giftOrder, items(line(1L, null, "5")), "0.00")).as("发货行缺单价").isFalse();
        assertThat(knownZero(giftOrder, items(line(1L, "0", null)), "0.00")).as("缺数量").isFalse();
        assertThat(knownZero(giftOrder, items(line(1L, "0", "5"), line(2L, null, "1")), "0.00")).as("部分行缺单价").isFalse();
        assertThat(knownZero(giftOrder, items(line(1L, "0.0040", "1")), "0.00")).as("极小单价舍入成 0").isFalse();
        assertThat(knownZero(giftOrder, items(line(99L, "0", "1")), "0.00")).as("挂不上订单行").isFalse();
        assertThat(knownZero(giftOrder, items(line(null, "0", "1")), "0.00")).as("没有订单行 ID").isFalse();
        assertThat(knownZero(orderWith(orderLine(1L, "100", null)), items(line(1L, "0", "1")), "0.00"))
                .as("订单行有价, 发货行 0(含全额折扣算出 0 的情形)").isFalse();
        assertThat(knownZero(orderWith(orderLine(1L, null, null)), items(line(1L, "0", "1")), "0.00"))
                .as("订单行缺价").isFalse();
        assertThat(knownZero(orderWith(orderLine(1L, "0", "1.00")), items(line(1L, "0", "1")), "0.00"))
                .as("订单行报价含税单价非 0").isFalse();
        assertThat(knownZero(giftOrder, items(), "0.00")).as("空明细").isFalse();
        assertThat(knownZero(giftOrder, items(line(1L, "0", "1")), "0.01")).as("0.01").isFalse();
        assertThat(knownZero(giftOrder, items(line(1L, "0", "1")), "-1.00")).as("负数").isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private static SalesDeliveryItem line(Long orderItemId, String unitPrice, String qty) {
        SalesDeliveryItem i = new SalesDeliveryItem();
        i.setSalesOrderItemId(orderItemId);
        i.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        i.setDeliveredQuantity(qty == null ? null : new BigDecimal(qty));
        return i;
    }

    private static SalesOrderItem orderLine(Long id, String unitPrice, String quotedGross) {
        SalesOrderItem oi = new SalesOrderItem();
        ReflectionTestUtils.setField(oi, "id", id);
        oi.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        oi.setQuotedGrossUnitPrice(quotedGross == null ? null : new BigDecimal(quotedGross));
        return oi;
    }

    private static SalesOrder orderWith(SalesOrderItem... lines) {
        SalesOrder o = new SalesOrder();
        o.setItems(new ArrayList<>(List.of(lines)));
        return o;
    }

    private static SalesDeliveryRecord items(SalesDeliveryItem... lines) {
        SalesDeliveryRecord r = new SalesDeliveryRecord();
        r.setItems(new ArrayList<>(List.of(lines)));
        return r;
    }

    private static boolean knownZero(SalesOrder o, SalesDeliveryRecord r, String value) {
        Boolean b = ReflectionTestUtils.invokeMethod(SalesServiceImpl.class, "isKnownZeroShipmentValue",
                o, r, new BigDecimal(value));
        return Boolean.TRUE.equals(b);
    }

    private List<ArPostingPending> openPending() {
        return pendingRepo.findAll().stream()
                .filter(r -> r.getStatus() == ArPostingPendingStatus.PENDING).toList();
    }

    private SalesDeliveryRecord shipInTx(SalesServiceImpl sales) {
        return new TransactionTemplate(tm).execute(st -> sales.shipDelivery(F, DELIVERY_ID, 7L));
    }

    private SalesServiceImpl salesService(ArApService arApService, String customerId, BigDecimal deliveryTotal) {
        return build(arApService, customerId, deliveryTotal, new ArrayList<>(), new ArrayList<>(), mock(FinishedGoodsBatchRepository.class));
    }

    /**
     * 有一条明细的发货: 发货行单价 {@code shipLinePrice}、订单行单价 {@code orderLinePrice}(null = 没维护), 数量 5 盒;
     * 成品库存给一个足量批次, 让 shipDelivery 真走完扣库存再到挂账段。
     */
    private SalesServiceImpl salesServiceWithLine(ArApService arApService, String shipLinePrice, String orderLinePrice) {
        return salesServiceWithLines(arApService, new String[]{shipLinePrice, orderLinePrice});
    }

    /** 多行发货: 每行 {发货行单价, 订单行单价}(null = 缺价/没维护), 每行 5 盒, 订单行 id 从 501 起。 */
    private SalesServiceImpl salesServiceWithLines(ArApService arApService, String[]... lines) {
        List<SalesOrderItem> orderItems = new ArrayList<>();
        List<SalesDeliveryItem> deliveryItems = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            long lineId = 501L + i;
            SalesOrderItem oi = new SalesOrderItem();
            ReflectionTestUtils.setField(oi, "id", lineId);
            oi.setProductTypeId("PT-ARSKIP");
            oi.setQuantity(new BigDecimal("5"));
            oi.setUnit("盒");
            oi.setUnitPrice(lines[i][1] == null ? null : new BigDecimal(lines[i][1]));
            orderItems.add(oi);

            SalesDeliveryItem di = new SalesDeliveryItem();
            di.setSalesOrderItemId(lineId);
            di.setProductTypeId("PT-ARSKIP");
            di.setProductName("样品" + (i + 1));
            di.setUnit("盒");
            di.setDeliveredQuantity(new BigDecimal("5"));
            di.setUnitPrice(lines[i][0] == null ? null : new BigDecimal(lines[i][0]));
            deliveryItems.add(di);
        }

        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        ReflectionTestUtils.setField(batch, "id", "FGB-ARSKIP-1");
        batch.setFactoryId(F);
        batch.setProductTypeId("PT-ARSKIP");
        batch.setBatchNumber("FGB-ARSKIP-1");
        batch.setUnit("盒");
        batch.setProducedQuantity(new BigDecimal("100"));
        FinishedGoodsBatchRepository fgRepo = mock(FinishedGoodsBatchRepository.class);
        when(fgRepo.findShippableBatchesAllWarehousesExcluding(any(), any(), any())).thenReturn(new ArrayList<>(List.of(batch)));
        when(fgRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        return build(arApService, "CUST-1", BigDecimal.ZERO, deliveryItems, orderItems, fgRepo);
    }

    private SalesServiceImpl build(ArApService arApService, String customerId, BigDecimal deliveryTotal,
                                   List<SalesDeliveryItem> deliveryItems, List<SalesOrderItem> orderItems,
                                   FinishedGoodsBatchRepository fgRepo) {
        SalesOrderRepository orderRepo = Mockito.mock(SalesOrderRepository.class);
        SalesDeliveryRecordRepository deliveryRepo = Mockito.mock(SalesDeliveryRecordRepository.class);
        SalesOrderItemRepository itemRepo = Mockito.mock(SalesOrderItemRepository.class);

        SalesDeliveryRecord d = new SalesDeliveryRecord();
        ReflectionTestUtils.setField(d, "id", DELIVERY_ID);
        d.setDeliveryNumber("DN-ARSKIP-1");
        d.setFactoryId(F);
        d.setSalesOrderId(ORDER_ID);
        d.setStatus(SalesDeliveryStatus.DRAFT);
        d.setRecordRole("SHIPMENT");
        d.setDeliveryMethod("SELF_PICKUP");
        d.setDeliveryDate(java.time.LocalDate.now());
        d.setTotalAmount(deliveryTotal);
        d.setItems(deliveryItems);

        SalesOrder o = new SalesOrder();
        ReflectionTestUtils.setField(o, "id", ORDER_ID);
        o.setFactoryId(F);
        o.setCustomerId(customerId);
        o.setTotalAmount(new BigDecimal("900.00"));
        o.setStatus(SalesOrderStatus.CONFIRMED);
        o.setItems(orderItems);

        when(deliveryRepo.findById(DELIVERY_ID)).thenReturn(Optional.of(d));
        when(deliveryRepo.findByIdAndFactoryIdForUpdate(DELIVERY_ID, F)).thenReturn(Optional.of(d));
        when(orderRepo.findById(ORDER_ID)).thenReturn(Optional.of(o));
        when(orderRepo.findByIdAndFactoryId(ORDER_ID, F)).thenReturn(Optional.of(o));
        when(orderRepo.findByIdAndFactoryIdForUpdate(ORDER_ID, F)).thenReturn(Optional.of(o));
        when(itemRepo.findBySalesOrderId(ORDER_ID)).thenReturn(orderItems.isEmpty() ? Collections.emptyList() : orderItems);
        // 缺价回填只按 salesOrderId + factoryId 查订单行(别的工厂查不到)
        when(itemRepo.findBySalesOrderIdAndSalesOrderFactoryId(ORDER_ID, F))
                .thenReturn(orderItems.isEmpty() ? Collections.emptyList() : orderItems);
        when(deliveryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SalesServiceImpl sales = new SalesServiceImpl(orderRepo, itemRepo, deliveryRepo,
                fgRepo, mock(CustomerRepository.class),
                mock(ProductTypeRepository.class), arApService, mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(sales, "receivablePendingService", service);
        ReflectionTestUtils.setField(sales, "arApTransactionRepository", arRepo);
        return sales;
    }
}
