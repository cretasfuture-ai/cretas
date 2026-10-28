package com.cretas.aims.service.impl;

import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「摄像头抓拍交给 AI 识别」开关只由本厂显式改:
 * 不认识这一项的界面 (App 的 AI 设置页) 整份提交时不把它冲掉; 导入别厂导出的设置不把别厂的同意带过来。
 * 走 {@code PUT /settings/ai} / {@code PUT /settings} / {@code POST /settings/import} 背后的服务方法。
 */
class FactorySettingsCameraSwitchPersistenceTest {

    private static final String FACTORY = "F006";

    private final ObjectMapper mapper = new ObjectMapper();
    private FactorySettings row;
    private FactorySettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        row = FactorySettings.builder().factoryId(FACTORY).build();
        FactorySettingsRepository repo = mock(FactorySettingsRepository.class);
        when(repo.findByFactoryId(FACTORY)).thenAnswer(inv -> Optional.of(row));
        when(repo.save(any(FactorySettings.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new FactorySettingsServiceImpl(repo, mapper, mock(FactoryRepository.class));
    }

    private Boolean stored() throws Exception {
        return row.getAiSettings() == null ? null
                : mapper.readValue(row.getAiSettings(), FactorySettingsDTO.AISettings.class).getCameraSnapshotAnalysis();
    }

    private static FactorySettingsDTO.AISettings appScreenSave() {
        // App 的 AI 设置页只认识这几项, 提交时不带摄像头开关
        return FactorySettingsDTO.AISettings.builder().enabled(true).tone("friendly").detailLevel("brief").build();
    }

    @Test
    void savingFromAScreenThatDoesNotKnowTheSwitchKeepsIt() throws Exception {
        service.updateAiSettings(FACTORY, FactorySettingsDTO.AISettings.builder().cameraSnapshotAnalysis(true).build());
        assertThat(stored()).isTrue();

        service.updateAiSettings(FACTORY, appScreenSave());
        assertThat(stored()).isTrue();
        assertThat(mapper.readTree(row.getAiSettings()).path("tone").asText()).isEqualTo("friendly");

        service.updateAiSettings(FACTORY, FactorySettingsDTO.AISettings.builder().cameraSnapshotAnalysis(false).build());
        assertThat(stored()).isFalse();
    }

    @Test
    void wholeSettingsSaveKeepsTheSwitchToo() throws Exception {
        service.updateAiSettings(FACTORY, FactorySettingsDTO.AISettings.builder().cameraSnapshotAnalysis(true).build());

        service.saveSettings(FACTORY, FactorySettingsDTO.builder().aiSettings(appScreenSave()).build());

        assertThat(stored()).isTrue();
    }

    @Test
    void importingAnotherFactorysExportDoesNotCarryItsConsent() throws Exception {
        String otherFactoryExport = mapper.writeValueAsString(FactorySettingsDTO.builder().factoryId("F007")
                .aiSettings(FactorySettingsDTO.AISettings.builder().enabled(true).cameraSnapshotAnalysis(true).build())
                .build());

        service.importSettings(FACTORY, otherFactoryExport);
        assertThat(stored()).isNull();

        // 本厂自己打开过的, 导入也不会把它关掉
        service.updateAiSettings(FACTORY, FactorySettingsDTO.AISettings.builder().cameraSnapshotAnalysis(true).build());
        service.importSettings(FACTORY, mapper.writeValueAsString(FactorySettingsDTO.builder()
                .aiSettings(FactorySettingsDTO.AISettings.builder().cameraSnapshotAnalysis(false).build()).build()));
        assertThat(stored()).isTrue();
    }
}
