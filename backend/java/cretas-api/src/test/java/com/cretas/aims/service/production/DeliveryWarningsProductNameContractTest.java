package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.DeliveryWarnDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 交货预警页「产品类型」列: 联产计划的 productType 是内部原料锚点
 * ({@code __RAW_WORKFLOW_OWNER__<原料id>}), 接口输出不许把它原样给人看 (F006 上线后发现)。
 * 跑在真实入口 {@code getDeliveryWarnings}; 锚点的存储与匹配逻辑不动。
 */
class DeliveryWarningsProductNameContractTest {

    private static final String F = "F006";
    private static final String RAW_ID = "RMT_F006_LSM_2bce8bda718ed689";
    private static final String PLACEHOLDER = "__RAW_WORKFLOW_OWNER__";

    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(plans, mock(ProductionBatchRepository.class),
                mock(MaterialBatchRepository.class), mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class), mock(ProductTypeRepository.class),
                mock(ProductionPlanMapper.class), mock(ConversionRepository.class),
                mock(SchedulingService.class), mock(com.cretas.aims.repository.ProductionLineRepository.class),
                mock(UserRepository.class), mock(ExcelUtil.class), mock(SalesOrderRepository.class),
                mock(SalesOrderItemRepository.class));
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", materials);
    }

    private static ProductType product(String id, String name) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private void planWith(ProductType pt) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PP-1");
        plan.setPlanNumber("PLAN-1");
        plan.setFactoryId(F);
        plan.setProductTypeId(pt.getId());
        plan.setProductType(pt);
        plan.setPlannedQuantity(new BigDecimal("10"));
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setExpectedCompletionDate(LocalDate.now().plusDays(1));
        when(plans.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(plan));
    }

    private String shownName() {
        List<DeliveryWarnDTO> out = service.getDeliveryWarnings(F, 7);
        assertThat(out).hasSize(1);
        return out.get(0).getProductTypeName();
    }

    @Test
    void anchorShowsTheRawMaterialNameNotThePlaceholder() {
        planWith(product(RAW_ID, PLACEHOLDER + RAW_ID));
        RawMaterialType r = new RawMaterialType();
        r.setId(RAW_ID);
        r.setName("LY-FX-安格斯65碎肉");
        when(materials.findByIdAndFactoryId(RAW_ID, F)).thenReturn(Optional.of(r));

        assertThat(shownName()).isEqualTo("LY-FX-安格斯65碎肉");
    }

    @Test
    void anchorWithoutRawMaterialRowNeverLeaksThePrefix() {
        planWith(product(RAW_ID, PLACEHOLDER + RAW_ID));
        when(materials.findByIdAndFactoryId(RAW_ID, F)).thenReturn(Optional.empty());

        assertThat(shownName()).doesNotContain(PLACEHOLDER);
    }

    @Test
    void ordinaryProductNameIsUnchanged() {
        planWith(product("SKU-1", "卤牛肉"));

        assertThat(shownName()).as("阳性对照: 普通产品照旧").isEqualTo("卤牛肉");
    }
}
