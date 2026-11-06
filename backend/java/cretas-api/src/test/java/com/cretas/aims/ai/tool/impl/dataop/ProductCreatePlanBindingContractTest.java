package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductWorkProcessAssignee;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.impl.ProductWorkProcessServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contract for server-owned source-chain snapshots used by product_create confirmation. */
class ProductCreatePlanBindingContractTest {

    private static final String FACTORY = "F006";
    private static final String SOURCE = "source-product";
    private static final String TARGET = "target-product";

    @Test
    void preparedDigestContainsCopiedFieldsAndSourceDriftIsRejected() {
        ProductWorkProcessRepository repository = mock(ProductWorkProcessRepository.class);
        WorkProcessRepository masters = mock(WorkProcessRepository.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        ProductWorkProcessAssigneeRepository assignees = mock(ProductWorkProcessAssigneeRepository.class);
        UserRepository users = mock(UserRepository.class);
        ProcessSecretRecipeGuard secretGuard = mock(ProcessSecretRecipeGuard.class);
        ProductWorkProcess row = process(1, "kg", "RAW_MATERIAL", "INPUT");
        WorkProcess master = WorkProcess.builder().id("wp-1").factoryId(FACTORY)
                .processName("切片").lockVersion(7L).isActive(true).build();
        ProductType source = new ProductType();
        source.setId(SOURCE);
        source.setFactoryId(FACTORY);
        source.setName("相似产品");
        source.setProductCategory("FINISHED_PRODUCT");
        when(products.findByIdAndFactoryId(SOURCE, FACTORY)).thenReturn(Optional.of(source));
        when(repository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY, SOURCE))
                .thenReturn(List.of(row));
        when(masters.findByFactoryIdAndIdIn(eq(FACTORY), any())).thenReturn(List.of(master));
        ProductWorkProcessServiceImpl service = new ProductWorkProcessServiceImpl(
                repository, masters, products, assignees, users, secretGuard);

        ProductWorkProcessService.PreparedChain prepared = service.prepareChain(FACTORY, SOURCE);
        assertThat(prepared.digest()).hasSize(64);
        assertThat(prepared.processes()).hasSize(1);
        assertThat(prepared.processes().get(0).defaultCostCategory()).isEqualTo("RAW_MATERIAL");
        assertThat(prepared.processes().get(0).auxBasis()).isEqualTo("INPUT");
        assertThat(prepared.masters().get(0).lockVersion()).isEqualTo(7L);

        ProductWorkProcessService.PreparedChain tampered =
                new ProductWorkProcessService.PreparedChain(
                        SOURCE, prepared.processes(), prepared.masters(), "0".repeat(64),
                        prepared.processNames());
        assertThatThrownBy(() -> service.validatePreparedChain(FACTORY, SOURCE, tampered))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("损坏");

        row.setProcessOrder(2);
        assertThatThrownBy(() -> service.validatePreparedChain(FACTORY, SOURCE, prepared))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已变化");
    }

    @Test
    void preparedSnapshotCopiesWithoutReReadingLatestValuesForWrite() {
        ProductWorkProcessRepository repository = mock(ProductWorkProcessRepository.class);
        WorkProcessRepository masters = mock(WorkProcessRepository.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        ProductWorkProcessAssigneeRepository assignees = mock(ProductWorkProcessAssigneeRepository.class);
        UserRepository users = mock(UserRepository.class);
        ProcessSecretRecipeGuard secretGuard = mock(ProcessSecretRecipeGuard.class);
        ProductWorkProcess sourceRow = process(1, "kg", "PACKAGING", "OUTPUT");
        WorkProcess master = WorkProcess.builder().id("wp-1").factoryId(FACTORY)
                .processName("包装").lockVersion(3L).isActive(true).build();
        ProductType source = new ProductType();
        source.setId(SOURCE);
        source.setFactoryId(FACTORY);
        source.setName("相似产品");
        source.setProductCategory("FINISHED_PRODUCT");
        when(products.findByIdAndFactoryId(SOURCE, FACTORY)).thenReturn(Optional.of(source));
        when(repository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY, SOURCE))
                .thenReturn(List.of(sourceRow));
        when(repository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY, TARGET))
                .thenAnswer(invocation -> targetRows);
        when(masters.findByFactoryIdAndIdIn(eq(FACTORY), any())).thenReturn(List.of(master));
        doNothing().when(secretGuard).requireCopySource(any(), any(), any());
        doNothing().when(secretGuard).requireProduct(any(), any(), any(), any());
        ProductWorkProcessServiceImpl service = new ProductWorkProcessServiceImpl(
                repository, masters, products, assignees, users, secretGuard);
        ProductWorkProcessService.PreparedChain prepared = service.prepareChain(FACTORY, SOURCE);
        when(repository.save(any(ProductWorkProcess.class))).thenAnswer(invocation -> {
            targetRows.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        assertThat(service.copyPreparedChain(FACTORY, SOURCE, TARGET, prepared)).isEqualTo(1);
        service.verifyPreparedChainReadback(FACTORY, TARGET, prepared);
        assertThat(targetRows).hasSize(1);
        assertThat(targetRows.get(0).getAuxBasis()).isEqualTo("OUTPUT");
    }

    @Test
    void preparedPackagingIsDeeplyImmutable() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("outer", new ArrayList<Object>(List.of(
                new LinkedHashMap<String, Object>(Map.of("amount", "1")))));
        List<Map<String, Object>> packaging = new ArrayList<>();
        packaging.add(nested);
        ProductWorkProcessService.PreparedProcess prepared =
                new ProductWorkProcessService.PreparedProcess(
                        "wp-1", 1, null, null, true, false, false, false, null,
                        packaging,
                        null, null, null, null);

        assertThatThrownBy(() -> prepared.packagingTemplate().get(0).put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) prepared.packagingTemplate().get(0).get("outer"))
                .clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private final List<ProductWorkProcess> targetRows = new ArrayList<>();

    private ProductWorkProcess process(int order, String unit, String category, String basis) {
        return ProductWorkProcess.builder()
                .factoryId(FACTORY)
                .productTypeId(SOURCE)
                .workProcessId("wp-1")
                .processOrder(order)
                .unitOverride(unit)
                .estimatedMinutesOverride(12)
                .reportingRequired(true)
                .allowSemiFinishedInjection(false)
                .allowMultipleUpstreamSources(true)
                .allowFinishedGoodsSource(false)
                .defaultCostCategory(category)
                .auxAllocMethod("BY_OUTPUT")
                .standardYieldRate(new java.math.BigDecimal("0.8750"))
                .auxUnitPrice(new java.math.BigDecimal("12.3400"))
                .auxBasis(basis)
                .build();
    }
}
