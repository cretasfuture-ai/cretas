package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 成本差异页的「产品」列: 多产出计划的 productTypeId 是内部原料锚点
 * ({@code __RAW_WORKFLOW_OWNER__<原料id>}), 页面和 Excel 导出都不许把占位符原样给人看
 * (F006 演示 L4/L5, 统一验收 2026-11-10)。
 */
class PlanCostVarianceProductNameContractTest {

    private static final String FACTORY = "F006";
    private static final String RAW_ID = "RMT_F006_LSM_2bce8bda718ed689";

    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final ProductTypeRepository products = mock(ProductTypeRepository.class);
    private final PlanCostVarianceService service = new PlanCostVarianceService(
            mock(PlanStandardCostLineRepository.class), mock(ProductionPlanRepository.class),
            mock(ProductionBatchRepository.class), mock(MaterialConsumptionRepository.class),
            mock(MaterialBatchRepository.class), materials,
            mock(OrderCostBreakdownService.class), mock(UnitConversionService.class));

    private static ProductType product(String id, String name) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private static RawMaterialType raw(String id, String name) {
        RawMaterialType r = new RawMaterialType();
        r.setId(id);
        r.setName(name);
        return r;
    }

    private void wire() {
        ReflectionTestUtils.setField(service, "productTypeRepository", products);
    }

    @Test
    void anchorProductShowsTheRawMaterialNameNotThePlaceholder() {
        wire();
        when(products.findByIdAndFactoryId(RAW_ID, FACTORY))
                .thenReturn(Optional.of(product(RAW_ID, "__RAW_WORKFLOW_OWNER__" + RAW_ID)));
        when(materials.findByIdAndFactoryId(RAW_ID, FACTORY)).thenReturn(Optional.of(raw(RAW_ID, "LY-FX-安格斯65碎肉")));

        assertThat(service.productName(FACTORY, RAW_ID)).isEqualTo("LY-FX-安格斯65碎肉");
    }

    @Test
    void anchorWithoutARawMaterialRowNeverLeaksThePrefix() {
        wire();
        when(products.findByIdAndFactoryId(RAW_ID, FACTORY))
                .thenReturn(Optional.of(product(RAW_ID, "__RAW_WORKFLOW_OWNER__" + RAW_ID)));
        when(materials.findByIdAndFactoryId(RAW_ID, FACTORY)).thenReturn(Optional.empty());

        assertThat(service.productName(FACTORY, RAW_ID)).doesNotContain("__RAW_WORKFLOW_OWNER__");
    }

    @Test
    void ordinaryProductNameIsUnchanged() {
        wire();
        when(products.findByIdAndFactoryId("SKU-1", FACTORY)).thenReturn(Optional.of(product("SKU-1", "卤牛肉")));

        assertThat(service.productName(FACTORY, "SKU-1")).as("阳性对照: 普通产品照旧").isEqualTo("卤牛肉");
    }

    @Test
    void unknownProductStaysNullSoThePageShowsADash() {
        wire();
        when(products.findByIdAndFactoryId("GONE", FACTORY)).thenReturn(Optional.empty());

        assertThat(service.productName(FACTORY, "GONE")).isNull();
        assertThat(service.productName(FACTORY, null)).isNull();
    }
}
