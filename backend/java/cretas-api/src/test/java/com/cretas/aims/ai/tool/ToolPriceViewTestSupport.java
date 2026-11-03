package com.cretas.aims.ai.tool;

import com.cretas.aims.security.PriceFieldResponseAdvice;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 手工 new 出来的工具没有价格可见性组件, 文字里的金额一律按看不到 (fail closed)。
 * 测的是别的行为 (工艺保密 / 缺成本口径 …)、调用者本就是财务或老板的用例, 用这里装一个「全部看得到」的判定。
 * 「看不到」那一侧由 ChatReplyPriceTextContractTest 用真实判定覆盖。
 */
public final class ToolPriceViewTestSupport {

    private ToolPriceViewTestSupport() {
    }

    public static <T extends AbstractTool> T callerSeesAllAmounts(T tool) {
        PriceFieldResponseAdvice view = Mockito.mock(PriceFieldResponseAdvice.class,
                Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        Mockito.when(view.showsKey(any(), anyString())).thenReturn(true);
        Mockito.when(view.showsSpec(any(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(tool, "priceView", view);
        return tool;
    }
}
