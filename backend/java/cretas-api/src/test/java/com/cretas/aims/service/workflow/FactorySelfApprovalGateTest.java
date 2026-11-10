package com.cretas.aims.service.workflow;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纪律排查 B1–B3 / B11 / B15: 「允许本人审批」开关的唯一判定处。
 * 各审批出口的「关 / 开+原因 / 开+无原因」三态在各自的服务测试里; 这里钉判定本身的边界。
 */
class FactorySelfApprovalGateTest {

    private final FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
    private final FactorySelfApprovalGate gate = new FactorySelfApprovalGate(settings);

    @Test
    @DisplayName("不是本人: 不读开关, 不要求原因")
    void notSelf_passesWithoutReadingSwitch() {
        FactorySelfApprovalGate.Decision d = gate.decide("F1", false, null);
        assertThat(d.forbidden()).isFalse();
        assertThat(d.selfApproved()).isFalse();
        verify(settings, never()).findAllowSelfApprovalByFactoryId("F1");
    }

    @Test
    @DisplayName("开关关 / 没有 settings 行 / 读取异常: 一律按关 (fail closed)")
    void switchOffMissingOrBroken_forbidden() {
        when(settings.findAllowSelfApprovalByFactoryId("OFF")).thenReturn(false);
        when(settings.findAllowSelfApprovalByFactoryId("MISSING")).thenReturn(null);
        when(settings.findAllowSelfApprovalByFactoryId("BROKEN")).thenThrow(new IllegalStateException("db down"));

        for (String factory : new String[]{"OFF", "MISSING", "BROKEN"}) {
            assertThat(gate.decide(factory, true, "只有我一个人").forbidden()).as(factory).isTrue();
        }
        assertThat(gate.decide("", true, "只有我一个人").forbidden()).isTrue();
        assertThat(new FactorySelfApprovalGate(null).decide("F1", true, "只有我一个人").forbidden()).isTrue();
        assertThat(FactorySelfApprovalGate.decide(null, "F1", true, "只有我一个人").forbidden()).isTrue();
        assertThat(FactorySelfApprovalGate.decide(null, "F1", false, null).forbidden()).isFalse();
    }

    @Test
    @DisplayName("开关开 + 原因: 放行, 原因去首尾空白")
    void switchOn_withReason_allowedAndTrimmed() {
        when(settings.findAllowSelfApprovalByFactoryId("F1")).thenReturn(true);
        FactorySelfApprovalGate.Decision d = gate.decide("F1", true, "  本厂只有我一个人  ");
        assertThat(d.selfApproved()).isTrue();
        assertThat(d.reason()).isEqualTo("本厂只有我一个人");
    }

    @Test
    @DisplayName("开关开 + 空原因 → 422; 超长原因 → 400")
    void switchOn_blankOrTooLongReason_rejected() {
        when(settings.findAllowSelfApprovalByFactoryId("F1")).thenReturn(true);
        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> gate.decide("F1", true, blank))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        assertThat(((BusinessException) e).getCode()).isEqualTo(422);
                        assertThat(((BusinessException) e).getErrorCode())
                                .isEqualTo(FactorySelfApprovalGate.REASON_REQUIRED);
                    });
        }
        String tooLong = "因".repeat(FactorySelfApprovalGate.MAX_REASON_LENGTH + 1);
        assertThatThrownBy(() -> gate.decide("F1", true, tooLong))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(FactorySelfApprovalGate.REASON_TOO_LONG));
    }
}
