package com.cretas.aims.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * D-S2-09: the plan DTO carries the output names resolved server-side from THIS factory's catalog, so the
 * list no longer depends on the browser separately fetching the product catalog (when that fetch came back
 * empty, every row read 「未知产品」 although the plan's own ids resolve).
 */
class ProductionPlanTargetProductNamesTest {

    private static ProductType product(String id, String factory, String name) {
        ProductType type = new ProductType();
        type.setId(id);
        type.setFactoryId(factory);
        type.setName(name);
        return type;
    }

    private static RawMaterialType raw(String id, String factory, String name) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(factory);
        material.setName(name);
        return material;
    }

    private ProductionPlanServiceImpl service(ProductTypeRepository products, RawMaterialTypeRepository raws)
            throws Exception {
        Constructor<?> ctor = null;
        for (Constructor<?> candidate : ProductionPlanServiceImpl.class.getDeclaredConstructors()) {
            if (ctor == null || candidate.getParameterCount() > ctor.getParameterCount()) ctor = candidate;
        }
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == ProductTypeRepository.class ? products : null;
        }
        ProductionPlanServiceImpl service = (ProductionPlanServiceImpl) ctor.newInstance(args);
        Field field = ProductionPlanServiceImpl.class.getDeclaredField("rawMaterialTypeRepository");
        field.setAccessible(true);
        field.set(service, raws);
        return service;
    }

    @Test
    void thePlanDtoCarriesNamesOfItsOwnFactorysOutputsAndNeverAnotherFactorys() throws Exception {
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        RawMaterialTypeRepository raws = mock(RawMaterialTypeRepository.class);
        when(products.findByIdIn(anyCollection())).thenReturn(List.of(
                product("P-1", "F006", "酱牛肉 250g"),
                product("P-OTHER", "F999", "别家的产品")));
        when(raws.findByIdIn(anyCollection())).thenReturn(List.of(raw("R-1", "F006", "牛骨(副产)")));
        ProductionPlanServiceImpl service = service(products, raws);

        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setProductTypeId("P-1");
        plan.setTargetFinishedGoodIds(List.of("P-1", "R-1", "P-OTHER", "P-GONE"));
        ProductionPlanDTO dto = new ProductionPlanDTO();

        ReflectionTestUtils.invokeMethod(service, "enrichWithTargetProductNames", dto, plan);

        assertThat(dto.getTargetFinishedGoodNames())
                .containsEntry("P-1", "酱牛肉 250g")
                .containsEntry("R-1", "牛骨(副产)")
                .doesNotContainKeys("P-OTHER", "P-GONE");
    }

    @Test
    void aPlanWithoutAnyOutputIdsGetsNoNamesMap() throws Exception {
        ProductionPlanServiceImpl service = service(mock(ProductTypeRepository.class), mock(RawMaterialTypeRepository.class));
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        ProductionPlanDTO dto = new ProductionPlanDTO();

        ReflectionTestUtils.invokeMethod(service, "enrichWithTargetProductNames", dto, plan);

        assertThat(dto.getTargetFinishedGoodNames()).isNull();
    }
}
