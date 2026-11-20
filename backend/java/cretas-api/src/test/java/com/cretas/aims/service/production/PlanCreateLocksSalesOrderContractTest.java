package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 手工建计划 / 合并计划读销售单状态前必须先锁住销售单行, 并以锁内读到的状态为准
 * (持久化上下文里早先读过的旧对象不算)。真并发的行锁语义见
 * {@code SalesOrderStatusLockPostgresContractTest}。
 */
class PlanCreateLocksSalesOrderContractTest {

    private static final String F = "F1";
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final SalesOrderItemRepository items = mock(SalesOrderItemRepository.class);
    private ProductionPlanServiceImpl service;
    private SalesOrder so;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(mock(ProductionPlanRepository.class), mock(ProductionBatchRepository.class),
                mock(MaterialBatchRepository.class), mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class), mock(ProductTypeRepository.class),
                mock(ProductionPlanMapper.class), mock(ConversionRepository.class), mock(SchedulingService.class),
                mock(com.cretas.aims.repository.ProductionLineRepository.class), mock(UserRepository.class),
                mock(ExcelUtil.class), orders, items);
        so = new SalesOrder();
        so.setId("SO-1");
        so.setFactoryId(F);
        so.setStatus(SalesOrderStatus.FINANCE_APPROVED);   // 持久化上下文里的旧副本: 看起来还是已批准
        when(orders.findById("SO-1")).thenReturn(Optional.of(so));
    }

    private CreateProductionPlanRequest request() {
        CreateProductionPlanRequest r = new CreateProductionPlanRequest();
        r.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        r.setSourceOrderId("SO-1");
        return r;
    }

    @Test
    void theOrderIsLockedFirstAndTheLockedStatusDecides() {
        // 锁内读到的是别的事务刚提交的 CANCELLED
        when(orders.lockStatusForUpdate("SO-1", F)).thenReturn(Optional.of("CANCELLED"));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateAndEnrichSalesOrderSource", F, request()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("无法排产");
                });
        InOrder o = inOrder(orders);
        o.verify(orders).findById("SO-1");
        o.verify(orders).lockStatusForUpdate("SO-1", F);
    }

    @Test
    void anApprovedOrderPassesWhenTheLockedStatusIsStillApproved() {
        when(orders.lockStatusForUpdate("SO-1", F)).thenReturn(Optional.of("FINANCE_APPROVED"));
        // 到「快照归属合同」之前的状态校验应通过; 之后的步骤与本测试无关, 只关心没有 409 无法排产
        try {
            ReflectionTestUtils.invokeMethod(service, "validateAndEnrichSalesOrderSource", F, request());
        } catch (BusinessException e) {
            assertThat(e.getMessage()).doesNotContain("无法排产");
        }
        // 预先按 id 排序锁一次 + 读状态时再锁一次 (同事务重入), 所以至少一次
        org.mockito.Mockito.verify(orders, org.mockito.Mockito.atLeastOnce()).lockStatusForUpdate("SO-1", F);
    }

    @Test
    void theItemLevelPathLocksTheOrderToo() {
        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrderId("SO-1");
        when(items.findById(7L)).thenReturn(Optional.of(item));
        when(orders.lockStatusForUpdate("SO-1", F)).thenReturn(Optional.of("CANCELLED"));
        CreateProductionPlanRequest r = request();
        r.setSourceOrderId(null);
        r.setSourceOrderItemId("7");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateAndEnrichSalesOrderSource", F, r))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
    }

    @Test
    void aRowThatVanishedUnderTheLockIsRefusedNotTreatedAsApproved() {
        when(orders.lockStatusForUpdate("SO-1", F)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateAndEnrichSalesOrderSource", F, request()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void mergeLockOrderDoesNotDependOnWhichOrderIsThePrimary() {
        // A: 主单 SO-B + 额外 SO-A;  B: 主单 SO-A + 额外 SO-B —— 两边第一次加锁都必须先 SO-A 后 SO-B, 否则 PG 死锁
        for (String[] pair : new String[][]{{"SO-B", "SO-A"}, {"SO-A", "SO-B"}}) {
            SalesOrderRepository repo = mock(SalesOrderRepository.class);
            ProductionPlanServiceImpl svc = new ProductionPlanServiceImpl(mock(ProductionPlanRepository.class),
                    mock(ProductionBatchRepository.class), mock(MaterialBatchRepository.class),
                    mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                    mock(ProductTypeRepository.class), mock(ProductionPlanMapper.class), mock(ConversionRepository.class),
                    mock(SchedulingService.class), mock(com.cretas.aims.repository.ProductionLineRepository.class),
                    mock(UserRepository.class), mock(ExcelUtil.class), repo, items);
            CreateProductionPlanRequest r = request();
            r.setSourceOrderId(pair[0]);
            r.setSourceOrderIds(new java.util.ArrayList<>(java.util.List.of(pair[0], pair[1])));

            try {
                ReflectionTestUtils.invokeMethod(svc, "validateAndEnrichSalesOrderSource", F, r);
            } catch (BusinessException expected) {
                // 订单没有桩出来, 后面的校验报 404 与本测试无关; 只看加锁顺序
            }
            InOrder o = inOrder(repo);
            o.verify(repo).lockStatusForUpdate("SO-A", F);
            o.verify(repo).lockStatusForUpdate("SO-B", F);
        }
    }

    @Test
    void batchCreateFromSalesOrderLocksPrimaryAndExtrasInIdOrderWhicheverIsThePrimary() {
        for (String[] pair : new String[][]{{"SO-B", "SO-A"}, {"SO-A", "SO-B"}}) {
            SalesOrderRepository repo = mock(SalesOrderRepository.class);
            ProductionPlanServiceImpl svc = new ProductionPlanServiceImpl(mock(ProductionPlanRepository.class),
                    mock(ProductionBatchRepository.class), mock(MaterialBatchRepository.class),
                    mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                    mock(ProductTypeRepository.class), mock(ProductionPlanMapper.class), mock(ConversionRepository.class),
                    mock(SchedulingService.class), mock(com.cretas.aims.repository.ProductionLineRepository.class),
                    mock(UserRepository.class), mock(ExcelUtil.class), repo, items);
            com.cretas.aims.dto.production.BatchPlanFromSalesOrderRequest r =
                    new com.cretas.aims.dto.production.BatchPlanFromSalesOrderRequest();
            r.setSourceOrderId(pair[0]);
            r.setExtraSourceOrderIds(new java.util.ArrayList<>(java.util.List.of(pair[1])));
            r.setItemIds(new java.util.ArrayList<>());

            try {
                svc.createPlansFromSalesOrder(F, r, 1L);
            } catch (RuntimeException expected) {
                // 主单没有桩出来, 后面报找不到; 只看第一批加锁顺序
            }
            InOrder o = inOrder(repo);
            o.verify(repo).lockStatusForUpdate("SO-A", F);
            o.verify(repo).lockStatusForUpdate("SO-B", F);
            // 且这两次预锁都发生在主单行锁读取之前
            o.verify(repo).findByIdAndFactoryIdForUpdate(pair[0], F);
        }
    }
}
