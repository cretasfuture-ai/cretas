package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.service.datacenter.OperationLogService;
import com.cretas.aims.service.impl.FactorySettingsServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1580 独立审查低危 5: 「允许本人审批」开关 / 改价阈值改动留痕 (谁、什么时候、改前 → 改后),
 * 并且导入别厂导出的设置不会把本厂的开关打开。走 FactorySettingsServiceImpl 的真实保存 / 导入 / 重置路径。
 */
class ApprovalPolicyAuditTest {

    private static final String FACTORY = "F006";

    private final OperationLogService operationLog = mock(OperationLogService.class);
    private FactorySettings row;
    private FactorySettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        row = FactorySettings.builder().factoryId(FACTORY).build();
        FactorySettingsRepository repo = mock(FactorySettingsRepository.class);
        when(repo.findByFactoryId(FACTORY)).thenAnswer(inv -> Optional.of(row));
        when(repo.save(any(FactorySettings.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new FactorySettingsServiceImpl(repo, new ObjectMapper(), mock(FactoryRepository.class));
        ReflectionTestUtils.setField(service, "approvalPolicyAudit", new ApprovalPolicyAudit(operationLog));
    }

    @Test
    @DisplayName("打开开关 → 记一条改前 false / 改后 true 的操作日志")
    void turningTheSwitchOnIsRecordedWithBeforeAndAfter() {
        FactorySettingsDTO on = new FactorySettingsDTO();
        on.setAllowSelfApproval(true);
        service.saveSettings(FACTORY, on);

        verify(operationLog).recordDiff(eq(FACTORY), eq(ApprovalPolicyAudit.MODULE), eq("FactorySettings"), eq(FACTORY),
                eq(Map.of("allowSelfApproval", false, "priceDecreaseApprovalPct", "10", "priceIncreaseApprovalPct", "20")),
                eq(Map.of("allowSelfApproval", true, "priceDecreaseApprovalPct", "10", "priceIncreaseApprovalPct", "20")),
                eq("打开「允许本人审批」"));
    }

    @Test
    @DisplayName("阴性对照: 与审批规则无关的保存不记")
    void unrelatedSaveIsNotRecorded() {
        FactorySettingsDTO unrelated = new FactorySettingsDTO();
        unrelated.setBlockStartOnOpenShortage(true);
        service.saveSettings(FACTORY, unrelated);
        verify(operationLog, never()).recordDiff(anyString(), anyString(), anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    @DisplayName("导入别厂导出的设置 (里面开关是开的) 不会打开本厂开关")
    void importDoesNotTurnTheSwitchOn() {
        service.importSettings(FACTORY, "{\"allowSelfApproval\": true, \"language\": \"zh-CN\"}");
        assertThat(row.getAllowSelfApproval()).isFalse();
        verify(operationLog, never()).recordDiff(anyString(), anyString(), anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    @DisplayName("重置为默认值: 开关关、阈值回 10/20, 并留痕")
    void resetTurnsItOffAndIsRecorded() {
        row.setAllowSelfApproval(true);
        row.setPriceDecreaseApprovalPct(new BigDecimal("5.00"));
        service.resetToDefaults(FACTORY);
        assertThat(row.getAllowSelfApproval()).isFalse();
        assertThat(row.getPriceDecreaseApprovalPct()).isEqualByComparingTo("10");
        verify(operationLog).recordDiff(eq(FACTORY), eq(ApprovalPolicyAudit.MODULE), anyString(), anyString(),
                anyMap(), anyMap(), eq("关闭「允许本人审批」"));
    }
}
