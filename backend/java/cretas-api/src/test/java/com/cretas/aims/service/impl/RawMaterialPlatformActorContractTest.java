package com.cretas.aims.service.impl;

import com.cretas.aims.blueprint.activation.AssemblyActivationActorResolver;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.security.*;
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

class RawMaterialPlatformActorContractTest {
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
        return service;
    }
    private RawMaterialTypeDTO request() {
        return RawMaterialTypeDTO.builder().code("M-TEST").name("面粉").category("原料").unit("kg")
                .storageType("dry").createdBy(999L).build();
    }

    @Test void platformAuditExistsBeforeFirstPersistWithoutBorrowingFactoryUserOne() {
        var service=service(); var authority=mock(AssemblyActivationActorResolver.class);
        ReflectionTestUtils.setField(service,"assemblyActorResolver",authority);
        var actor=new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,62L);
        when(rows.save(any())).thenAnswer(call->{RawMaterialType row=call.getArgument(0);
            assertThat(row.getActorType()).isEqualTo(AuthenticatedActorType.PLATFORM_ADMIN);
            assertThat(row.getActorId()).isEqualTo(62L); assertThat(row.getCreatedBy()).isNull(); return row;});
        assertThat(service.createMaterialTypeAsActor("F1",request(),actor).getStorageType()).isEqualTo("dry");
        verify(authority).authorize(eq(actor),eq("F1"),anySet());
    }

    @Test void ordinaryCreateRetainsExistingAuditAndStorageBehaviour() {
        var service=service(); var dto=request(); dto.setCreatedBy(7L);
        when(rows.save(any())).thenAnswer(call->{RawMaterialType row=call.getArgument(0);
            assertThat(row.getCreatedBy()).isEqualTo(7L); assertThat(row.getActorType()).isNull(); return row;});
        assertThat(service.createMaterialType("F1",dto).getStorageType()).isEqualTo("dry");
    }

    @Test void disabledAssemblyAuthorityRejectsBeforeAnyMaterialAccess() {
        assertThatThrownBy(()->service().createMaterialTypeAsActor("F1",request(),new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,62L)))
                .hasMessageContaining("未启用"); verifyNoInteractions(rows);
    }

    @ParameterizedTest @ValueSource(strings={"包材","包辅材","PACKAGING","PACKAGE"})
    void platformPackagingAliasesRequirePricingBeforeFirstSave(String category) {
        var service=service();
        ReflectionTestUtils.setField(service,"assemblyActorResolver",mock(AssemblyActivationActorResolver.class));
        var dto=request(); dto.setCategory(category);
        assertThatThrownBy(()->service.createMaterialTypeAsActor("F1",dto,new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,62L)))
                .hasMessageContaining("含税单价");
        verify(rows,never()).save(any());
    }

    @ParameterizedTest @ValueSource(strings={"包材","包辅材","PACKAGING","PACKAGE"})
    void platformPackagingAliasesRetainPriceAndPlatformAuditWithoutStorage(String category) {
        var service=service();
        ReflectionTestUtils.setField(service,"assemblyActorResolver",mock(AssemblyActivationActorResolver.class));
        var dto=request(); dto.setCategory(category); dto.setTaxIncludedUnitPrice(new BigDecimal("113.00"));
        when(rows.save(any())).thenAnswer(call->{RawMaterialType row=call.getArgument(0);
            assertThat(row.getCreatedBy()).isNull(); assertThat(row.getActorId()).isEqualTo(62L); return row;});
        var result=service.createMaterialTypeAsActor("F1",dto,new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,62L));
        assertThat(result.getMainCategory()).isEqualTo("包材");
        assertThat(result.getStorageType()).isNull();
        assertThat(result.getTaxIncludedUnitPrice()).isEqualByComparingTo("113.00");
        assertThat(result.getMaterialReferencePrice()).isEqualByComparingTo("100.0000");
    }
}
