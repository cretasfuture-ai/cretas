package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactorySchedulingConfig;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.FactoryTempWorker;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.config.EncodingRule;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.entity.hr.HrInsuranceConfig;
import com.cretas.aims.repository.EncodingRuleRepository;
import com.cretas.aims.repository.FactorySchedulingConfigRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.FactoryTempWorkerRepository;
import com.cretas.aims.repository.WagePolicyRepository;
import com.cretas.aims.repository.hr.HrInsuranceConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Smoke tests for {@link CanvasFactoryConfigController}.
 *
 * <p>Coverage focus (Canvas Phase B):
 * <ul>
 *   <li>6 sub-modules basic CRUD reach (scheduling / temp-workers / insurance / wage / encoding / settings)</li>
 *   <li>AUD-4 version conflict path</li>
 *   <li>Cross-factory 404 (security)</li>
 *   <li>4位一体 error response (code + actionHint)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S4-08 「缺料时拦开工」开关的持久化与默认值 (PR 检查必跑: *ContractTest)")
class FactorySettingsShortageSwitchContractTest {

    @Mock FactorySchedulingConfigRepository schedulingRepo;
    @Mock FactoryTempWorkerRepository tempWorkerRepo;
    @Mock HrInsuranceConfigRepository insuranceRepo;
    @Mock WagePolicyRepository wagePolicyRepo;
    @Mock EncodingRuleRepository encodingRuleRepo;
    @Mock FactorySettingsRepository factorySettingsRepo;

    private CanvasFactoryConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasFactoryConfigController(
                schedulingRepo, tempWorkerRepo, insuranceRepo,
                wagePolicyRepo, encodingRuleRepo, factorySettingsRepo);
    }

    // ==================== Overview ====================

    // ==================== blockStartOnOpenShortage (S4-08 缺料时拦开工, 默认关) ====================

    @Test
    @DisplayName("updateSettings blockStartOnOpenShortage=true 持久化且可读回")
    void updateSettings_blockStartOnOpenShortage_persistsAndIsReadable() {
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.empty());
        when(factorySettingsRepo.saveAndFlush(any(FactorySettings.class))).thenAnswer(inv -> {
            FactorySettings s = inv.getArgument(0);
            s.setId(1);
            s.setVersion(0L);
            return s;
        });

        Map<String, Object> body = new HashMap<>();
        body.put("factoryName", "测试工厂");
        body.put("blockStartOnOpenShortage", true);

        ApiResponse<Map<String, Object>> resp = controller.updateSettings("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals(true, resp.getData().get("blockStartOnOpenShortage"));
    }

    @Test
    @DisplayName("updateSettings 部分PUT不携带blockStartOnOpenShortage时不清空已有值")
    void updateSettings_partialPut_doesNotWipeExistingBlockStartOnOpenShortage() {
        FactorySettings s = new FactorySettings();
        s.setId(1);
        s.setFactoryId("F006");
        s.setVersion(2L);
        s.setBlockStartOnOpenShortage(true);
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.of(s));
        when(factorySettingsRepo.saveAndFlush(any(FactorySettings.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 2L);
        body.put("factoryName", "改个名字");

        ApiResponse<Map<String, Object>> resp = controller.updateSettings("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals(true, resp.getData().get("blockStartOnOpenShortage"));
    }

    @Test
    @DisplayName("新建的工厂设置默认不拦开工 (六膳门 N1)")
    void newFactorySettingsDoNotBlockStartByDefault() {
        assertEquals(false, new FactorySettings().getBlockStartOnOpenShortage());
    }
}
