package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
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
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 取消人的数据范围是「本人」时, 取消自己订单会连带取消订单名下别人 / 系统创建的计划:
 * 计划行范围不能再按「谁创建的计划」二次拦。⛔ 只对确实属于该订单的计划放行, 别的计划照旧按范围拦。
 */
class PlanCancelViaSalesOrderScopeContractTest {

    private static final String F = "F1";
    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
    private final BusinessRowScope scope = mock(BusinessRowScope.class);
    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(plans, batches, mock(MaterialBatchRepository.class),
                mock(MaterialConsumptionRepository.class), mock(ProductionPlanBatchUsageRepository.class),
                mock(ProductTypeRepository.class), mock(ProductionPlanMapper.class), mock(ConversionRepository.class),
                mock(SchedulingService.class), mock(com.cretas.aims.repository.ProductionLineRepository.class),
                mock(UserRepository.class), mock(ExcelUtil.class), mock(SalesOrderRepository.class),
                mock(SalesOrderItemRepository.class));
        ReflectionTestUtils.setField(service, "businessRowScope", scope);
        // 取消人只看得到自己 (id=5) 创建的行
        when(scope.creatorIds(F)).thenReturn(List.of(5L));
        lenient().when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(batches.findByFactoryIdAndProductionPlanId(any(), any())).thenReturn(Collections.emptyList());
    }

    private ProductionPlan plan(String id, String sourceOrderId, Long createdBy) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setPlanNumber(id);
        p.setFactoryId(F);
        p.setSourceOrderId(sourceOrderId);
        p.setCreatedBy(createdBy);
        p.setPlannedQuantity(new BigDecimal("10"));
        p.setStatus(ProductionPlanStatus.PENDING);
        when(plans.findByIdForUpdate(id)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void anOrdinaryCancelOfAPlanCreatedByAnotherUserIsStillBlockedByRowScope() {
        ProductionPlan p = plan("PP-1", "SO-1", 99L);

        assertThatThrownBy(() -> service.cancelProductionPlan(F, "PP-1", "客户撤单", 5L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThat(p.getStatus()).isEqualTo(ProductionPlanStatus.PENDING);
    }

    @Test
    void cancellingWithTheOrderAllowsThePlansOfThatOrderEvenWhenSomeoneElseCreatedThem() {
        ProductionPlan p = plan("PP-1", "SO-1", 99L);

        service.cancelProductionPlan(F, "PP-1", "客户撤单", 5L, "SO-1");

        assertThat(p.getStatus()).isEqualTo(ProductionPlanStatus.CANCELLED);
    }

    @Test
    void aPlanThatDoesNotBelongToThatOrderStaysUnderRowScope() {
        ProductionPlan p = plan("PP-2", "SO-OTHER", 99L);

        assertThatThrownBy(() -> service.cancelProductionPlan(F, "PP-2", "客户撤单", 5L, "SO-1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThat(p.getStatus()).isEqualTo(ProductionPlanStatus.PENDING);
    }

    @Test
    void anotherFactorysPlanIsNeverReleasedByTheOrderId() {
        ProductionPlan p = plan("PP-3", "SO-1", 5L);
        p.setFactoryId("OTHER-FACTORY");

        assertThatThrownBy(() -> service.cancelProductionPlan(F, "PP-3", "客户撤单", 5L, "SO-1"))
                .isInstanceOf(BusinessException.class);
        assertThat(p.getStatus()).isEqualTo(ProductionPlanStatus.PENDING);
    }
}
