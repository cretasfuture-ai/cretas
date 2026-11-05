package com.cretas.aims.service.config.impl;

import com.cretas.aims.entity.config.FactoryTemplate;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.util.RetiredTemplatePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetiredTemplateBoundaryContractTest {
    @Mock FactoryTemplateRepository templates;
    @Mock FactoryConfigurationRepository configurations;
    @Mock FactoryModuleConfigRepository modules;
    @Mock ConfigChangeLogRepository changes;
    @InjectMocks FactoryConfigServiceImpl service;

    @BeforeEach
    void injectOptionalTemplateRepository() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "factoryTemplateRepository", templates);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESTAURANT", "BRANCH"})
    void historicalIndustryTemplateCannotCreateAnyDraft(String industry) {
        FactoryTemplate template = FactoryTemplate.builder().templateCode("CUSTOM-LEGACY")
                .industryType(industry).baseConfig(Map.of()).build();
        when(templates.findByTemplateCodeIgnoreCase("CUSTOM-LEGACY")).thenReturn(Optional.of(template));
        assertThatThrownBy(() -> service.applyTemplate("F006", "CUSTOM-LEGACY", 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("RETIRED_CONFIGURATION_DOMAIN"));
        verifyNoInteractions(configurations, modules, changes);
    }

    @Test
    void renamedTemplateCannotEnableARetiredModule() {
        FactoryTemplate template = FactoryTemplate.builder().templateCode("CUSTOM")
                .industryType("FOOD_PROCESSING")
                .baseConfig(Map.of("moduleConfigs", Map.of("enabledModules", List.of("restaurantFinance"))))
                .build();
        when(templates.findByTemplateCodeIgnoreCase("CUSTOM")).thenReturn(Optional.of(template));
        assertThatThrownBy(() -> service.applyTemplate("F006", "CUSTOM", 7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(configurations, modules, changes);
    }

    @Test
    void factoryTemplateThatDisablesAnObsoleteModuleRemainsAvailable() {
        FactoryTemplate template = FactoryTemplate.builder().templateCode("FOOD_PROCESSING")
                .industryType("FOOD_PROCESSING").baseConfig(Map.of("moduleConfigs",
                        Map.of("enabledModules", List.of("production", "warehouse"),
                                "disabledModules", List.of("restaurant")))).build();
        assertThat(RetiredTemplatePolicy.isRetired(template)).isFalse();
    }

    @Test
    void cleanupTemplateActuallyDisablesAnExistingRetiredConfiguration() {
        FactoryTemplate template = FactoryTemplate.builder().templateCode("FOOD_PROCESSING")
                .industryType("FOOD_PROCESSING").baseConfig(Map.of("moduleConfigs",
                        Map.of("disabledModules", List.of("restaurant")))).build();
        var draft = new com.cretas.aims.entity.config.FactoryConfiguration();
        draft.setFactoryId("F006");
        draft.setConfigVersion(1);
        draft.setStatus("DRAFT");
        var old = new com.cretas.aims.entity.config.FactoryModuleConfig();
        old.setFactoryId("F006");
        old.setModuleCode("restaurant");
        old.setEnabled(true);
        when(templates.findByTemplateCodeIgnoreCase("FOOD_PROCESSING")).thenReturn(Optional.of(template));
        when(configurations.findDraft("F006")).thenReturn(Optional.of(draft));
        when(modules.findByFactoryIdAndModuleCodeAndConfigVersion("F006", "restaurant", 1)).thenReturn(Optional.of(old));
        service.applyTemplate("F006", "FOOD_PROCESSING", 7L);
        assertThat(old.getEnabled()).isFalse();
        verify(modules).save(old);
        verify(modules, times(1)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void renamedTemplateCannotSeedRetiredDynamicFieldsBeforeDraftCreation() {
        FactoryTemplate template = FactoryTemplate.builder().templateCode("CUSTOM")
                .industryType("FOOD_PROCESSING")
                .baseConfig(Map.of("seedDynamicFields", List.of(Map.of("moduleCode", "restaurant", "fieldCode", "dish"))))
                .build();
        when(templates.findByTemplateCodeIgnoreCase("CUSTOM")).thenReturn(Optional.of(template));
        assertThatThrownBy(() -> service.applyTemplate("F006", "CUSTOM", 7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(configurations, modules, changes);
    }
}
