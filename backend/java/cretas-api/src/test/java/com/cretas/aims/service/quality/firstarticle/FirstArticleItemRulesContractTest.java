package com.cretas.aims.service.quality.firstarticle;

import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection.Verdict;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.Item;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.ItemType;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules.ResultInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 检验项校验与逐项判定: 每条拒绝配一条放行; 上下限端点算合格。 */
class FirstArticleItemRulesContractTest {

    static Item num(String name, String lo, String hi, String target) {
        return new Item(null, name, ItemType.NUMERIC, null, "g", lo == null ? null : new BigDecimal(lo),
                hi == null ? null : new BigDecimal(hi), target == null ? null : new BigDecimal(target), null, null);
    }

    static Item opt(String name, List<String> opts, List<String> pass, Boolean required) {
        return new Item(null, name, ItemType.OPTION, required, null, null, null, null, opts, pass);
    }

    @Test
    void validateRejectsMeaninglessStandards() {
        assertThatThrownBy(() -> FirstArticleItemRules.validate(List.of())).hasMessageContaining("至少要有一个");
        assertThatThrownBy(() -> FirstArticleItemRules.validate(List.of(num("块重", null, null, null))))
                .hasMessageContaining("至少要填上限或下限");
        assertThatThrownBy(() -> FirstArticleItemRules.validate(List.of(num("块重", "220", "180", null))))
                .hasMessageContaining("下限大于上限");
        assertThatThrownBy(() -> FirstArticleItemRules.validate(List.of(num("块重", "180", "220", "230"))))
                .hasMessageContaining("标准值必须在上下限之间");
        assertThatThrownBy(() -> FirstArticleItemRules.validate(List.of(opt("封口", List.of("平整", "漏气"),
                List.of("平整", "漏气"), null)))).hasMessageContaining("判定没有意义");
        assertThatThrownBy(() -> FirstArticleItemRules.validate(List.of(opt("封口", List.of("平整", "漏气"),
                List.of("褶皱"), null)))).hasMessageContaining("不在选项里");
        assertThatThrownBy(() -> FirstArticleItemRules.validate(List.of(num("块重", "1", "2", null),
                num("块重", "1", "2", null)))).hasMessageContaining("重复");
        // 放行对照
        List<Item> ok = FirstArticleItemRules.validate(List.of(num(" 块重 ", "180", "220", "200"),
                opt("封口", List.of("平整", "漏气"), List.of("平整"), false)));
        assertThat(ok).extracting(Item::key).containsExactly("i1", "i2");
        assertThat(ok.get(0).name()).isEqualTo("块重");
        assertThat(ok.get(1).required()).isFalse();
    }

    @Test
    void evaluateBoundariesMissingAndUnknownItems() {
        List<Item> items = FirstArticleItemRules.validate(List.of(num("块重", "180", "220", null),
                opt("封口", List.of("平整", "漏气"), List.of("平整"), false)));
        // 端点合格; 选检项没填 → pass null, 不影响总判定
        var ev = FirstArticleItemRules.evaluate(items, List.of(new ResultInput("i1", new BigDecimal("220"), null)));
        assertThat(ev.autoVerdict()).isEqualTo(Verdict.PASSED);
        assertThat(ev.items().get(1).pass()).isNull();
        // 选检项填了不合格值 → 总判定不合格
        ev = FirstArticleItemRules.evaluate(items, List.of(new ResultInput("i1", new BigDecimal("180"), null),
                new ResultInput("i2", null, "漏气")));
        assertThat(ev.autoVerdict()).isEqualTo(Verdict.FAILED);
        assertThat(ev.failedItemNames()).containsExactly("封口");
        // 超出 0.01 → 不合格
        ev = FirstArticleItemRules.evaluate(items, List.of(new ResultInput("i1", new BigDecimal("220.01"), null)));
        assertThat(ev.autoVerdict()).isEqualTo(Verdict.FAILED);
        // 必检项缺值 → 400; 不认识的项 / 不在选项里的值 → 400
        assertThatThrownBy(() -> FirstArticleItemRules.evaluate(items, List.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("块重");
        assertThatThrownBy(() -> FirstArticleItemRules.evaluate(items,
                List.of(new ResultInput("i9", BigDecimal.ONE, null)))).hasMessageContaining("没有的检验项");
        assertThatThrownBy(() -> FirstArticleItemRules.evaluate(items, List.of(
                new ResultInput("i1", new BigDecimal("200"), null), new ResultInput("i2", null, "褶皱"))))
                .hasMessageContaining("不是可选项");
    }

    @Test
    void jsonRoundTripKeepsEveryField() {
        ObjectMapper m = new ObjectMapper();
        List<Item> items = FirstArticleItemRules.validate(List.of(num("块重", "180", "220", "200"),
                opt("封口", List.of("平整", "漏气"), List.of("平整"), true)));
        List<Item> back = FirstArticleItemRules.readItems(m, FirstArticleItemRules.write(m, items));
        assertThat(back).isEqualTo(items);
        assertThatThrownBy(() -> FirstArticleItemRules.readItems(m, "{bad")).isInstanceOf(IllegalStateException.class);
    }
}
