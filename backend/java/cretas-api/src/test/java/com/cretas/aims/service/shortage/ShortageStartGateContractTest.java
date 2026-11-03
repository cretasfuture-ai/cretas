package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * S4-08 开工前的缺料闸: 工厂开关默认关 ⇒ 什么都不查、照常开工 (六膳门 N1); 开了且计划所属订单的缺料未解决
 * ⇒ 409 + 错误码 + 去哪处理; 带原因 ⇒ 放行并把原因记进缺料实例的处理记录; 缺口已解决 / 没有缺口行 /
 * 不是订单驱动的计划 ⇒ 照常开工。
 */
class ShortageStartGateContractTest {

    private final FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseLineRepository lines = mock(ShortageCaseLineRepository.class);
    private final ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
    private final ShortageStartGate gate = new ShortageStartGate(settings, cases, lines, events);

    private ProductionPlan plan;
    private ShortageCase theCase;
    private ShortageCaseLine gap;

    @BeforeEach
    void setUp() {
        plan = new ProductionPlan();
        plan.setId("PP-1");
        plan.setPlanNumber("PP-NO-1");
        plan.setSourceOrderId("SO-1");

        theCase = new ShortageCase();
        theCase.setId("C1");
        theCase.setFactoryId("F1");
        theCase.setStage(Stage.EXECUTING);
        when(cases.findOpenForUpdate("F1", ShortageCase.SourceType.SALES_ORDER, "SO-1")).thenReturn(Optional.of(theCase));

        gap = new ShortageCaseLine();
        gap.setMaterialTypeId("M1");
        gap.setMaterialName("猪大骨");
        gap.setUnit("kg");
        gap.setGapQty(new BigDecimal("8"));
        when(lines.findByCaseIdOrderByIdAsc("C1")).thenAnswer(i -> List.of(gap));
    }

    @Test
    void withTheSwitchOffNothingIsCheckedAndStartGoesAhead() {
        when(settings.findBlockStartOnOpenShortageByFactoryId("F1")).thenReturn(false);
        assertThatCode(() -> gate.check("F1", plan, null, 9L)).doesNotThrowAnyException();

        when(settings.findBlockStartOnOpenShortageByFactoryId("F1")).thenReturn(null);   // 没有设置行 = 关
        assertThatCode(() -> gate.check("F1", plan, null, 9L)).doesNotThrowAnyException();

        verify(cases, never()).findOpenForUpdate(anyString(), any(), anyString());
        verify(events, never()).save(any());
    }

    @Test
    void withTheSwitchOnAnOpenShortageBlocksTheStartAndSaysWhereToGo() {
        when(settings.findBlockStartOnOpenShortageByFactoryId("F1")).thenReturn(true);

        assertThatThrownBy(() -> gate.check("F1", plan, "  ", 9L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessage()).contains("PP-NO-1").contains("猪大骨 缺 8");
                    assertThat(e.getErrorCode()).isEqualTo(ShortageStartGate.CODE);
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getActionHint()).contains("缺料异常").contains("强制开工原因");
                });
        verify(events, never()).save(any());
    }

    @Test
    void aReasonLetsTheStartThroughAndIsRecordedOnTheCase() {
        when(settings.findBlockStartOnOpenShortageByFactoryId("F1")).thenReturn(true);

        gate.check("F1", plan, " 客户加急, 先用库存余料开 ", 9L);

        ArgumentCaptor<ShortageCaseEvent> ev = ArgumentCaptor.forClass(ShortageCaseEvent.class);
        verify(events).save(ev.capture());
        assertThat(ev.getValue().getCaseId()).isEqualTo("C1");
        assertThat(ev.getValue().getTriggerReason()).isEqualTo("FORCED_START");
        assertThat(ev.getValue().getDetail()).contains("PP-NO-1").contains("客户加急, 先用库存余料开");
        assertThat(ev.getValue().getActorId()).isEqualTo(9L);
        assertThat(ev.getValue().getActorType()).isEqualTo("FACTORY_USER");
    }

    @Test
    void aResolvedGapNoGapLinesNoCaseOrNoOrderDoNotBlock() {
        when(settings.findBlockStartOnOpenShortageByFactoryId("F1")).thenReturn(true);

        theCase.setStage(Stage.GAP_RESOLVED);
        assertThatCode(() -> gate.check("F1", plan, null, 9L)).doesNotThrowAnyException();

        theCase.setStage(Stage.EXECUTING);
        gap.setGapQty(BigDecimal.ZERO);
        assertThatCode(() -> gate.check("F1", plan, null, 9L)).doesNotThrowAnyException();

        gap.setGapQty(new BigDecimal("8"));
        when(cases.findOpenForUpdate("F1", ShortageCase.SourceType.SALES_ORDER, "SO-1")).thenReturn(Optional.empty());
        assertThatCode(() -> gate.check("F1", plan, null, 9L)).doesNotThrowAnyException();

        plan.setSourceOrderId(null);
        assertThatCode(() -> gate.check("F1", plan, null, 9L)).doesNotThrowAnyException();
        verify(events, never()).save(any());
    }
}
