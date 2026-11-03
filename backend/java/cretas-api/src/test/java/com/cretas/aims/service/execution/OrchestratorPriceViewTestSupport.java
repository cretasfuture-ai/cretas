package com.cretas.aims.service.execution;

import com.cretas.aims.security.PriceFieldResponseAdvice;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;

/**
 * 手工 new 出来的编排器没有价格可见性组件: 结果数据一律不交给摘要模型、存档报告不拼进提示词 (fail closed)。
 * 测的是别的行为 (脱敏 / 数字出处闸 …)、调用者本就是老板或超管的用例, 用这里装一个「原样、全部看得到」的判定。
 * 「看不到」那一侧由 FinanceReportLlmPromptPriceContractTest 用真实判定覆盖。
 */
final class OrchestratorPriceViewTestSupport {

    private OrchestratorPriceViewTestSupport() {
    }

    static void callerSeesAllAmounts(IntentExecutionOrchestrator orchestrator) {
        PriceFieldResponseAdvice view = Mockito.mock(PriceFieldResponseAdvice.class,
                Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        Mockito.when(view.maskedViewFor(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(view.seesFinanceFigures(any())).thenReturn(true);
        ReflectionTestUtils.setField(orchestrator, "priceView", view);
    }
}
