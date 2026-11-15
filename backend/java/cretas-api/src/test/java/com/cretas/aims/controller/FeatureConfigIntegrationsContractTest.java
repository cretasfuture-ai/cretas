package com.cretas.aims.controller;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一原型迁移 P10: feature-config 只读下发 {@code moduleId = "integrations"} (平台专属表的 ACTIVE 授权);
 * 工厂端任何 PUT 都改不了它 (阴性)。
 */
class FeatureConfigIntegrationsContractTest {

    static final String F = "F-FC-EXT";

    final FactoryFeatureConfigRepository repository = mock(FactoryFeatureConfigRepository.class);
    final ExternalIntegrationService integrations = mock(ExternalIntegrationService.class);
    final FeatureConfigController controller = new FeatureConfigController(repository,
            mock(FactoryFeatureMenuWriteLock.class),
            new FactorySecrecyConfig(mock(FactorySecrecySettingRepository.class),
                    (org.springframework.transaction.PlatformTransactionManager) null),
            integrations);

    @Test
    @SuppressWarnings("unchecked")
    void theListCarriesAReadOnlyIntegrationsRowFromThePlatformTableOnly() {
        FactoryFeatureConfig module = config("production", new HashMap<>(Map.of("x", 1)));
        // 库里即使有人写了 moduleId=integrations 的行 (自称有授权) 也不下发
        FactoryFeatureConfig forged = config("integrations",
                new HashMap<>(Map.of("integrations", Map.of("blacklake", Map.of("authorized", true)))));
        when(repository.findByFactoryIdAndDeletedAtIsNull(F)).thenReturn(List.of(module, forged));
        Map<String, Object> view = Map.of("blacklake", Map.of("authorized", true, "stale", false));
        when(integrations.featureConfigView(F)).thenReturn(view);

        List<Object> data = controller.getAll(F).getData();
        assertThat(data).contains(module).doesNotContain(forged);
        Map<String, Object> row = (Map<String, Object>) data.get(data.size() - 1);
        assertThat(row).containsEntry("moduleId", "integrations").containsEntry("readOnly", true)
                .containsEntry("integrations", view);
        assertThat((Map<String, Object>) row.get("config")).containsEntry("integrations", view);
        assertThat(controller.getOne(F, "integrations").getData()).isEqualTo(row);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFactoryWithoutAuthorizationGetsAnEmptyIntegrationsRow() {
        when(repository.findByFactoryIdAndDeletedAtIsNull(F)).thenReturn(List.of());
        when(integrations.featureConfigView(F)).thenReturn(Map.of());
        List<Object> data = controller.getAll(F).getData();
        Map<String, Object> row = (Map<String, Object>) data.get(data.size() - 1);
        assertThat((Map<String, Object>) row.get("integrations")).isEmpty();
    }

    @Test
    void aFactorySidePutCannotChangeIntegrations() {
        FactoryFeatureConfig existing = config("production", new HashMap<>());
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(F, "production")).thenReturn(Optional.of(existing));
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(F, "integrations"))
                .thenReturn(Optional.of(config("integrations", new HashMap<>())));

        assertThatThrownBy(() -> controller.update(F, "integrations", new HashMap<>(Map.of("integrations",
                Map.of("blacklake", Map.of("authorized", true))))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(() -> controller.update(F, "production", new HashMap<>(Map.of("integrations",
                Map.of("blacklake", Map.of("authorized", true))))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        verify(repository, never()).save(any());
    }

    private static FactoryFeatureConfig config(String moduleId, Map<String, Object> cfg) {
        FactoryFeatureConfig c = new FactoryFeatureConfig();
        c.setFactoryId(F);
        c.setModuleId(moduleId);
        c.setConfig(cfg);
        return c;
    }
}
