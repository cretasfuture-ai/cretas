package com.cretas.aims.mapper;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductionPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionPlanSourceDisplayMapperTest {

    private final ProductionPlanMapper mapper = new ProductionPlanMapper();

    @Test
    void preservesSalesOrderDisplayQuantitySeparatelyFromNormalizedPlanQuantity() {
        CreateProductionPlanRequest request = new CreateProductionPlanRequest();
        request.setProductTypeId("PROD-1");
        request.setPlannedQuantity(new BigDecimal("500"));
        request.setPlannedUnit("piece");
        request.setSourceDisplayQuantity(new BigDecimal("10"));
        request.setSourceDisplayUnit("box");
        request.setWorkflowOutputUnit("g");

        ProductionPlan entity = mapper.toEntity(request, "F006", 1L);
        ProductionPlanDTO dto = mapper.toDTO(entity);

        // Storage side keeps the canonical code the request carried.
        assertThat(entity.getPlannedUnit()).isEqualTo("piece");
        assertThat(entity.getSourceDisplayUnit()).isEqualTo("box");

        // DTO side is the user-facing exit: since 2026-08-18 every *Unit field goes
        // through UnitDisplayNames.display, so a code never reaches the user.
        assertThat(dto.getPlannedQuantity()).isEqualByComparingTo("500");
        assertThat(dto.getPlannedUnit()).isEqualTo("件");
        assertThat(dto.getSourceDisplayQuantity()).isEqualByComparingTo("10");
        assertThat(dto.getSourceDisplayUnit()).isEqualTo("盒");
        // Scientific measurement symbols are deliberately NOT translated.
        assertThat(dto.getWorkflowOutputUnit()).isEqualTo("g");
    }
}
