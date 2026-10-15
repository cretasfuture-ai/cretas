package com.cretas.aims.service.impl;

import com.cretas.aims.blueprint.activation.AssemblyActivationActorResolver;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.*;
import com.cretas.aims.security.*;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductTypePlatformActorContractTest {
    private final ProductTypeRepository rows = mock(ProductTypeRepository.class);
    private ProductTypeServiceImpl service() {
        return new ProductTypeServiceImpl(rows, new ObjectMapper(), mock(CustomerRepository.class),
                mock(WorkflowUnitReviewService.class), mock(ProductSpecificationConversionSyncService.class), mock(ProductPackagingSpecService.class));
    }
    private ProductTypeDTO request() {
        return ProductTypeDTO.builder().code("PR-62").name("原味面包").unit("个")
                .productCategory("FINISHED_PRODUCT").createdBy(999L).build();
    }

    @Test void platformAuditExistsBeforeFirstSaveAndNeverProjectsPlatformIdIntoUsersFk() {
        var service = service();
        var authority = mock(AssemblyActivationActorResolver.class);
        ReflectionTestUtils.setField(service, "assemblyActorResolver", authority);
        var actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,62L);
        when(rows.save(any())).thenAnswer(call -> {
            ProductType row = call.getArgument(0);
            assertThat(row.getActorType()).isEqualTo(AuthenticatedActorType.PLATFORM_ADMIN);
            assertThat(row.getActorId()).isEqualTo(62L); assertThat(row.getCreatedBy()).isNull();
            return row;
        });
        assertThat(service.createProductTypeAsActor("F1",request(),actor).getUnit()).isEqualTo("个");
        verify(authority).authorize(eq(actor),eq("F1"),anySet());
    }

    @Test void ordinaryFactoryCreateRetainsLegacyAuditWithoutDependingOnAssembly() {
        var dto=request(); dto.setCreatedBy(7L);
        when(rows.save(any())).thenAnswer(call -> {
            ProductType row=call.getArgument(0); assertThat(row.getCreatedBy()).isEqualTo(7L);
            assertThat(row.getActorType()).isNull(); assertThat(row.getActorId()).isNull(); return row;
        });
        assertThat(service().createProductType("F1",dto).getUnit()).isEqualTo("个");
    }

    @Test void absentAssemblyAuthorityRejectsBeforeAnyRepositoryAccess() {
        assertThatThrownBy(() -> service().createProductTypeAsActor("F1", request(),new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,62L)))
                .hasMessageContaining("未启用");
        verifyNoInteractions(rows);
    }
}
