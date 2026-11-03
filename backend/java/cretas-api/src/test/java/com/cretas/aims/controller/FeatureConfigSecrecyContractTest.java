package com.cretas.aims.controller;

import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
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
 * feature-config 只读地下发 {@code secrecy: {CODE: bool}} (平台专属表的生效值); 工厂端任何 PUT 都改不了它。
 */
class FeatureConfigSecrecyContractTest {

    static final String F = "F-FC-SEC";

    final FactoryFeatureConfigRepository repository = mock(FactoryFeatureConfigRepository.class);
    final FactorySecrecySettingRepository settings = mock(FactorySecrecySettingRepository.class);
    final FactorySecrecyConfig secrecy = new FactorySecrecyConfig(settings,
            (org.springframework.transaction.PlatformTransactionManager) null);
    final FeatureConfigController controller = new FeatureConfigController(repository,
            mock(FactoryFeatureMenuWriteLock.class), secrecy);

    @Test
    @SuppressWarnings("unchecked")
    void theListCarriesAReadOnlySecrecyRowFromThePlatformTable() {
        FactoryFeatureConfig module = config("production", new HashMap<>(Map.of("x", 1)));
        // 库里即使有人写了 moduleId=secrecy 的行 (例如 AI 功能开关工具) 也不下发
        FactoryFeatureConfig forged = config("secrecy", new HashMap<>(Map.of("secrecy", Map.of("PROCESS_SECRET", false))));
        when(repository.findByFactoryIdAndDeletedAtIsNull(F)).thenReturn(List.of(module, forged));
        when(settings.findByFactoryId(F)).thenReturn(List.of(setting(SecrecyItem.PROCESS_SECRET, true)));

        List<Object> data = controller.getAll(F).getData();
        assertThat(data).contains(module).doesNotContain(forged);
        Map<String, Object> row = (Map<String, Object>) data.get(data.size() - 1);
        assertThat(row).containsEntry("moduleId", "secrecy").containsEntry("readOnly", true);
        Map<String, Boolean> secrecyMap = (Map<String, Boolean>) row.get("secrecy");
        assertThat(secrecyMap).hasSize(SecrecyItem.values().length)
                .containsEntry("PROCESS_SECRET", true)
                .containsEntry("PLATFORM_RECIPE_BLOCK", false)
                .containsEntry("AI_SECRECY_AUDIT", true);   // 跟随 a
        assertThat((Map<String, Object>) row.get("config")).containsEntry("secrecy", secrecyMap);
        assertThat(controller.getOne(F, "secrecy").getData()).isEqualTo(row);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedReadShowsEveryItemAsOn() {
        when(repository.findByFactoryIdAndDeletedAtIsNull(F)).thenReturn(List.of());
        when(settings.findByFactoryId(F)).thenThrow(new org.springframework.dao.QueryTimeoutException("slow"));
        Map<String, Object> row = (Map<String, Object>) controller.getAll(F).getData().get(0);
        assertThat(((Map<String, Boolean>) row.get("secrecy")).values()).containsOnly(true);
    }

    @Test
    void aFactorySidePutCannotChangeSecrecy() {
        FactoryFeatureConfig existing = config("production", new HashMap<>());
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(F, "production")).thenReturn(Optional.of(existing));
        when(repository.findByFactoryIdAndModuleIdAndDeletedAtIsNull(F, "secrecy"))
                .thenReturn(Optional.of(config("secrecy", new HashMap<>())));

        assertThatThrownBy(() -> controller.update(F, "secrecy", new HashMap<>(Map.of("secrecy",
                Map.of("PROCESS_SECRET", false)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        assertThatThrownBy(() -> controller.update(F, "production", new HashMap<>(Map.of("secrecy",
                Map.of("PROCESS_SECRET", false)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(repository, never()).save(any());
        verify(settings, never()).save(any());

        // 阳性对照: 普通字段照常能改
        controller.update(F, "production", new HashMap<>(Map.of("analysisDimensions", List.of("cost"))));
        verify(repository).save(existing);
    }

    static FactoryFeatureConfig config(String moduleId, Map<String, Object> cfg) {
        FactoryFeatureConfig c = new FactoryFeatureConfig();
        c.setFactoryId(F);
        c.setModuleId(moduleId);
        c.setEnabled(true);
        c.setConfig(cfg);
        return c;
    }

    static FactorySecrecySetting setting(SecrecyItem item, Boolean enabled) {
        return FactorySecrecySetting.builder().factoryId(F).itemCode(item.name()).enabled(enabled).updatedBy(1L).build();
    }
}
