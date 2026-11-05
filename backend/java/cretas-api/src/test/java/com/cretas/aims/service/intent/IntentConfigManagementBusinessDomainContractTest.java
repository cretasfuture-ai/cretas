package com.cretas.aims.service.intent;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class IntentConfigManagementBusinessDomainContractTest {
    private FactoryRepository factories;
    private IntentConfigManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        factories = mock(FactoryRepository.class);
        service = new IntentConfigManagementServiceImpl(mock(AIIntentConfigRepository.class));
        service.setFactoryRepository(factories);
    }

    @Test
    void missingNullTypeAndRepositoryFailureNeverDefaultToFactory() {
        when(factories.findById("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveBusinessDomain("MISSING"))
                .isInstanceOf(IllegalStateException.class);

        Factory unknown = new Factory();
        unknown.setType(null);
        when(factories.findById("NULL_TYPE")).thenReturn(Optional.of(unknown));
        assertThatThrownBy(() -> service.resolveBusinessDomain("NULL_TYPE"))
                .isInstanceOf(IllegalStateException.class);

        when(factories.findById("BROKEN")).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.resolveBusinessDomain("BROKEN"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void knownFactoryTypesResolveToExplicitManufacturingDomain() {
        for (FactoryType type : new FactoryType[]{FactoryType.FACTORY,
                FactoryType.HEADQUARTERS, FactoryType.CENTRAL_KITCHEN}) {
            Factory factory = new Factory();
            factory.setType(type);
            when(factories.findById(type.name())).thenReturn(Optional.of(factory));
            assertThat(service.resolveBusinessDomain(type.name())).isEqualTo("FACTORY");
        }
    }

    @Test
    void restaurantAndBranchResolveToRetiredRestaurantDomain() {
        for (FactoryType type : new FactoryType[]{FactoryType.RESTAURANT, FactoryType.BRANCH}) {
            Factory factory = new Factory();
            factory.setType(type);
            when(factories.findById(type.name())).thenReturn(Optional.of(factory));
            assertThat(service.resolveBusinessDomain(type.name())).isEqualTo("RESTAURANT");
        }
    }

    @Test
    void manufacturingAuthorityIsNotCachedAcrossTypeChangesOrDeletion() {
        Factory factory = new Factory();
        factory.setType(FactoryType.FACTORY);
        Factory retired = new Factory();
        retired.setType(FactoryType.RESTAURANT);
        when(factories.findById("TENANT_42"))
                .thenReturn(Optional.of(factory), Optional.of(retired), Optional.empty());
        assertThat(service.resolveBusinessDomain("TENANT_42")).isEqualTo("FACTORY");
        assertThat(service.resolveBusinessDomain("TENANT_42")).isEqualTo("RESTAURANT");
        assertThatThrownBy(() -> service.resolveBusinessDomain("TENANT_42"))
                .isInstanceOf(IllegalStateException.class);
        verify(factories, times(3)).findById("TENANT_42");
    }
}
