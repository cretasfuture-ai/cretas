package com.cretas.aims.service.indicator;

import com.cretas.aims.dto.platform.CreateFactoryRequest;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.repository.DroolsRuleRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.impl.FactoryServiceImpl;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import com.cretas.aims.service.restaurant.RestaurantAgentActionWorkflowProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactoryIndicatorProvisionerContractTest {
    private final FactoryRepository factories = mock(FactoryRepository.class);
    private final IndicatorRepository indicators = mock(IndicatorRepository.class);
    private final IndicatorComputationRepository computations = mock(IndicatorComputationRepository.class);
    private final IndicatorComputationStrategyRegistry registry = mock(IndicatorComputationStrategyRegistry.class);
    private final FactoryIndicatorProvisioner service = new FactoryIndicatorProvisioner(
            factories, indicators, computations, registry);
    private final Map<String, Indicator> definitions = new HashMap<>();
    private final List<IndicatorComputation> sources = new ArrayList<>();

    @BeforeEach void setup() {
        Factory factory = new Factory(); factory.setId("NEW");
        when(factories.findByIdForUpdate("NEW")).thenReturn(Optional.of(factory));
        when(indicators.existsIncludingDeleted(eq("NEW"), anyString()))
                .thenAnswer(call -> definitions.containsKey(call.getArgument(1)));
        when(indicators.save(any())).thenAnswer(call -> {
            Indicator item = call.getArgument(0); definitions.put(item.getCode(), item); return item;
        });
        when(computations.save(any())).thenAnswer(call -> {
            IndicatorComputation item = call.getArgument(0); sources.add(item); return item;
        });
        when(registry.find(anyString())).thenReturn(Optional.of(mock(IndicatorComputationStrategy.class)));
    }

    @Test void createsFourTenantDefinitionsWiredToExistingStrategiesWithoutBusinessValues() {
        assertThat(service.provision("NEW")).isEqualTo(4);
        assertThat(definitions).containsOnlyKeys("FACTORY_LU_YIELD_RATE", "FACTORY_GOOD_OUTPUT_RATE",
                "FACTORY_LU_UNIT_COST", "FACTORY_ACTUAL_TOTAL_COST");
        assertThat(definitions.values()).allSatisfy(item -> {
            assertThat(item.getFactoryId()).isEqualTo("NEW");
            assertThat(item.getCategory()).isEqualTo("FACTORY");
            assertThat(item.getIsActive()).isTrue();
            assertThat(item.getLastValue()).isNull();
            assertThat(item.getLastComputedAt()).isNull();
            assertThat(item.getCacheTtlSeconds()).isEqualTo(300);
        });
        assertThat(sources).hasSize(4).allSatisfy(item -> {
            assertThat(item.getComputeType()).isEqualTo("JPA_AGGREGATE");
            assertThat(item.getIndicatorId()).isEqualTo(definitions.get(item.getComputeSource()).getId());
            assertThat(item.getPriority()).isEqualTo(1);
            assertThat(item.getIsActive()).isTrue();
        });
        var order = inOrder(factories, indicators);
        order.verify(factories).findByIdForUpdate("NEW");
        order.verify(indicators).existsIncludingDeleted("NEW", "FACTORY_LU_YIELD_RATE");
    }

    @Test void retryPreservesIdsAndInactiveCustomizedDefinitionAndSource() {
        service.provision("NEW");
        Indicator cost = definitions.get("FACTORY_LU_UNIT_COST");
        cost.setIsActive(false); cost.setName("本厂成本"); cost.setCacheTtlSeconds(777);
        List<String> ids = definitions.values().stream().map(Indicator::getId).sorted().toList();
        sources.get(0).setIsActive(false);
        assertThat(service.provision("NEW")).isZero();
        assertThat(definitions.values().stream().map(Indicator::getId).sorted().toList()).isEqualTo(ids);
        assertThat(cost.getIsActive()).isFalse(); assertThat(cost.getName()).isEqualTo("本厂成本");
        assertThat(cost.getCacheTtlSeconds()).isEqualTo(777);
        assertThat(sources).hasSize(4); assertThat(sources.get(0).getIsActive()).isFalse();
    }

    @Test void doesNotCopyDefinitionsFromAnotherTenant() {
        Factory other = new Factory(); other.setId("OTHER");
        when(factories.findByIdForUpdate("OTHER")).thenReturn(Optional.of(other));
        when(indicators.existsIncludingDeleted(eq("OTHER"), anyString())).thenReturn(false);
        service.provision("OTHER");
        assertThat(definitions.values()).allMatch(item -> item.getFactoryId().equals("OTHER"));
        verify(indicators, never()).existsIncludingDeleted(eq("NEW"), anyString());
    }

    @Test void restaurantIsNotGivenManufacturingMetrics() {
        Factory restaurant = new Factory(); restaurant.setType(FactoryType.RESTAURANT);
        when(factories.findByIdForUpdate("NEW")).thenReturn(Optional.of(restaurant));
        assertThat(service.provision("NEW")).isZero();
        verifyNoInteractions(indicators, computations, registry);
    }

    @Test void missingFactoryOrMissingStrategyFailsRatherThanClaimingInitialized() {
        assertThatThrownBy(() -> service.provision("MISSING")).isInstanceOf(IllegalStateException.class);
        when(registry.find("FACTORY_LU_YIELD_RATE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.provision("NEW")).isInstanceOf(IllegalStateException.class);
        verify(indicators, never()).save(any()); verifyNoInteractions(computations);
    }

    @Test void formalFactoryCreationCallsProvisionerAndDoesNotSwallowItsFailure() {
        DroolsRuleRepository rules = mock(DroolsRuleRepository.class);
        FactoryIndicatorProvisioner provisioner = mock(FactoryIndicatorProvisioner.class);
        FactoryServiceImpl factoryService = new FactoryServiceImpl(factories, rules,
                mock(RestaurantAgentActionWorkflowProvisioner.class), provisioner);
        when(factories.save(any())).thenAnswer(call -> call.getArgument(0));
        CreateFactoryRequest request = CreateFactoryRequest.builder()
                .name("新厂验收").industryCode("FOOD").regionCode("2026").build();
        var result = factoryService.createFactory(request);
        verify(provisioner).provision(result.getId());
        doThrow(new IllegalStateException("写入失败")).when(provisioner).provision(anyString());
        assertThatThrownBy(() -> factoryService.createFactory(request))
                .isInstanceOf(IllegalStateException.class).hasMessage("写入失败");
    }
}
