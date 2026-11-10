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
 * 网页审批页靠 {@code GET /settings/features} 的 allowSelfApproval 决定给不给本人审批原因框
 * (纪律排查 B1–B3/B11/B15)。默认 false; 由 {@code PUT /settings} (DTO) 改后读得到。
 */
class FactorySettingsSelfApprovalFeatureToggleContractTest {

    private static final String FACTORY = "F006";

    private FactorySettings row;
    private FactorySettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        row = FactorySettings.builder().factoryId(FACTORY).build();
        FactorySettingsRepository repo = mock(FactorySettingsRepository.class);
        when(repo.findByFactoryId(FACTORY)).thenAnswer(inv -> Optional.of(row));
        when(repo.save(any(FactorySettings.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new FactorySettingsServiceImpl(repo, new ObjectMapper(), mock(FactoryRepository.class));
    }

    @Test
    void defaultIsOffAndTurningItOnIsVisibleToTheApprovalPages() {
        assertThat(service.getFeatureToggles(FACTORY)).containsEntry("allowSelfApproval", false);

        FactorySettingsDTO on = new FactorySettingsDTO();
        on.setAllowSelfApproval(true);
        service.saveSettings(FACTORY, on);

        assertThat(row.getAllowSelfApproval()).isTrue();
        assertThat(service.getFeatureToggles(FACTORY)).containsEntry("allowSelfApproval", true);
    }

    @Test
    void aSaveThatDoesNotMentionTheSwitchLeavesItAlone() {
        row.setAllowSelfApproval(true);
        service.saveSettings(FACTORY, new FactorySettingsDTO());
        assertThat(service.getFeatureToggles(FACTORY)).containsEntry("allowSelfApproval", true);
    }
}
