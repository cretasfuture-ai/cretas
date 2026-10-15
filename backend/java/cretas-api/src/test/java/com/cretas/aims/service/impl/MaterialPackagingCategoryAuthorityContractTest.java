package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.unit.*;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaterialPackagingCategoryAuthorityContractTest {
    private final RawMaterialTypeRepository rows = mock(RawMaterialTypeRepository.class);
    private RawMaterialTypeServiceImpl service() {
        var service = new RawMaterialTypeServiceImpl(rows, mock(MaterialBatchRepository.class), mock(ConversionRepository.class),
                mock(MaterialPackagingHierarchyRepository.class), mock(MaterialCodeSegmentRepository.class),
                mock(ExcelUtil.class), mock(WorkflowUnitReviewService.class));
        var units = mock(UnitContractService.class);
        when(units.normalize(any(),any())).thenReturn(new UnitNormalizationResult("kg", "kg",
                new CanonicalUnit("kg", UnitDimension.MASS, "kg", BigDecimal.ONE, "kg", 3)));
        when(units.supportsUsage(any(),any(),any())).thenReturn(true);
        when(units.storageUnit(any(),any())).thenReturn("kg");
        ReflectionTestUtils.setField(service,"unitContractService",units);
        when(rows.save(any())).thenAnswer(call->call.getArgument(0));
        return service;
    }
    private RawMaterialTypeDTO request(String category) {
        return RawMaterialTypeDTO.builder().code("M-TEST").name("验收物料").category(category).unit("kg")
                .storageType("dry").createdBy(7L).build();
    }

    @ParameterizedTest @ValueSource(strings={"包材", "包辅材", "PACKAGING", "PACKAGE"})
    void packagingAliasesCannotSkipRequiredPricing(String category) {
        var service=service();
        assertThatThrownBy(()->service.createMaterialType("F1", request(category))).hasMessageContaining("含税单价");
        verify(rows,never()).save(any());
    }

    @ParameterizedTest @ValueSource(strings={"包材", "包辅材", "PACKAGING", "PACKAGE"})
    void packagingAliasesClearStorageAndRetainConfirmedPrice(String category) {
        var service=service(); var request=request(category);request.setTaxIncludedUnitPrice(new BigDecimal("113.00"));
        var result=service.createMaterialType("F1", request);
        assertThat(result.getMainCategory()).isEqualTo("包材");
        assertThat(result.getStorageType()).isNull();
        assertThat(result.getTaxIncludedUnitPrice()).isEqualByComparingTo("113.00");
        assertThat(result.getMaterialReferencePrice()).isEqualByComparingTo("100.0000");
    }

    @Test void rawMaterialsRetainStorageAndOptionalPricing() {
        var result=service().createMaterialType("F1",request("原料"));
        assertThat(result.getMainCategory()).isEqualTo("原料");
        assertThat(result.getStorageType()).isEqualTo("dry");
        assertThat(result.getTaxIncludedUnitPrice()).isNull();
        assertThat(result.getCreatedBy()).isEqualTo(7L);
    }
}
