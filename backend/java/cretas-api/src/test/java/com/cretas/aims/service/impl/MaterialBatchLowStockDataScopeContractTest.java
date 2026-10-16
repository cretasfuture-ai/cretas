package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialBatchLowStockDataScopeContractTest {

    private MaterialBatchRepository batchRepository;
    private RawMaterialTypeRepository materialTypeRepository;
    private DataScopeResolver resolver;
    private MaterialBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(MaterialBatchRepository.class);
        materialTypeRepository = mock(RawMaterialTypeRepository.class);
        resolver = mock(DataScopeResolver.class);
        service = new MaterialBatchServiceImpl(
                batchRepository,
                mock(MaterialBatchAdjustmentRepository.class),
                materialTypeRepository,
                mock(MaterialBatchMapper.class),
                mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class),
                mock(FuturePlanMatchingService.class));
        ReflectionTestUtils.setField(service, "dataScopeResolver", resolver);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher",
                mock(InventoryLowStockEventPublisher.class));
        when(materialTypeRepository.findByFactoryIdAndIsActive("F006", true))
                .thenReturn(List.of(materialType("RM-visible", "Visible", "5"),
                        materialType("RM-hidden", "Hidden", "5")));
    }

    @AfterEach
    void clearScope() {
        while (DataScopeContext.current() != null) {
            DataScopeContext.pop();
        }
    }

    @Test
    void selfScopeUsesCreatedByRowsBeforeComputingWarnings() {
        DataScopeContext context = context(DataScope.SELF, 101L);
        DataScopeContext.push(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(101L));
        when(batchRepository.findByFactoryIdAndCreatedByIn(anyString(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(batch("RM-visible", 101L, "2"))));

        List<Map<String, Object>> warnings = service.getLowStockWarnings("F006");

        verify(batchRepository).findByFactoryIdAndCreatedByIn("F006", List.of(101L), Pageable.unpaged());
        assertThat(warnings).extracting(w -> w.get("materialTypeId"))
                .containsExactly("RM-visible");
    }

    @Test
    void allScopeKeepsExistingFactoryWideWarningPath() {
        DataScopeContext context = context(DataScope.ALL, 101L);
        DataScopeContext.push(context);
        when(batchRepository.sumQuantityByMaterialType("F006"))
                .thenReturn(List.<Object[]>of(new Object[]{"RM-visible", new BigDecimal("2")}));

        List<Map<String, Object>> warnings = service.getLowStockWarnings("F006");

        verify(batchRepository).sumQuantityByMaterialType("F006");
        assertThat(warnings).isNotEmpty();
    }

    @Test
    void authenticatedDirectInvocationResolvesAllScopeForStatisticsSelfCall() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "101");
        request.setAttribute(com.cretas.aims.security.AuthenticatedActor.REQUEST_ATTRIBUTE,
                new com.cretas.aims.security.AuthenticatedActor(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER,101L,"F006","warehouse_worker"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(resolver.resolve(101L)).thenReturn(context(DataScope.ALL, 101L));
        when(batchRepository.sumQuantityByMaterialType("F006"))
                .thenReturn(List.<Object[]>of(new Object[]{"RM-visible", new BigDecimal("2")}));

        assertThat(service.getLowStockWarnings("F006")).isNotEmpty();
        verify(batchRepository).sumQuantityByMaterialType("F006");
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void authenticatedResolutionFailureRejectsBeforeReadingTypesOrBatches() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "101");
        request.setAttribute(com.cretas.aims.security.AuthenticatedActor.REQUEST_ATTRIBUTE,
                new com.cretas.aims.security.AuthenticatedActor(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER,101L,"F006","warehouse_worker"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(resolver.resolve(101L)).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.getLowStockWarnings("F006"));
        org.mockito.Mockito.verifyNoInteractions(materialTypeRepository, batchRepository);
        RequestContextHolder.resetRequestAttributes();
    }

    private static DataScopeContext context(DataScope scope, Long userId) {
        return DataScopeContext.builder().userId(userId).factoryId("F006")
                .roleCode("warehouse_worker").scope(scope).department("warehouse")
                .column("created_by").build();
    }

    private static RawMaterialType materialType(String id, String name, String minStock) {
        RawMaterialType type = new RawMaterialType();
        type.setId(id);
        type.setFactoryId("F006");
        type.setCode(id);
        type.setName(name);
        type.setMinStock(new BigDecimal(minStock));
        type.setIsActive(true);
        return type;
    }

    private static MaterialBatch batch(String materialTypeId, Long createdBy, String receipt) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("batch-" + materialTypeId);
        batch.setFactoryId("F006");
        batch.setMaterialTypeId(materialTypeId);
        batch.setCreatedBy(createdBy);
        batch.setReceiptQuantity(new BigDecimal(receipt));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        return batch;
    }
}
