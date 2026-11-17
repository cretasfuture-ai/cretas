package com.cretas.aims.service.intent.flywheel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-17 F006 飞轮把「帮我建个生产计划：明天做 2 吨演示·酱卤牛肉250g…」记成「…酱卤牛肉&lt;用量&gt;…」:
 * 配方专用单位 g 不看语境一律当用量, 把产品规格当成了配方用量。产品名里的数是品名的一部分, 原样保留。
 * (送给决策模型的文本不经这层脱敏 —— 只有飞轮落库用它, 见 IntentFlywheelRecorder.persist。)
 */
@DisplayName("飞轮脱敏: 产品名里的规格不当用量")
class FlywheelProductSpecNotDosageRedactionTest {

    private static final String INPUT = "帮我建个生产计划：明天做 2 吨演示·酱卤牛肉250g，周五交演示·上海连锁餐饮";
    private static final String PRODUCT = "演示·酱卤牛肉250g";

    @Test
    @DisplayName("本厂产品名里的 250g 原样保留 (原文基线与导出两条路)")
    void productSpecIsKept() {
        String raw = FlywheelTextProtector.rawBaseline(INPUT, List.of("牛肉前腱子"), List.of(PRODUCT));
        assertThat(raw).contains(PRODUCT).doesNotContain("<用量>");

        String export = FlywheelValueMasker.mask(INPUT, new FlywheelValueMasker.Scope(true, true, false),
                List.of(), List.of(PRODUCT), new LinkedHashMap<>());
        assertThat(export).contains(PRODUCT).doesNotContain("<用量>");
    }

    @Test
    @DisplayName("对照: 不给产品名单时 (改前) 250g 被抹成用量 —— 证明上面那条守的就是这处")
    void withoutProductNamesTheSpecWasMaskedAsDosage() {
        assertThat(FlywheelTextProtector.rawBaseline(INPUT, List.of())).contains("酱卤牛肉<用量>");
    }

    @Test
    @DisplayName("阴性对照: 真正的配方用量照抹 (「加盐5克」「放盐3g拉匀」)")
    void recipeDosageIsStillMasked() {
        assertThat(FlywheelTextProtector.rawBaseline("卤水里加盐5克", List.of(), List.of(PRODUCT)))
                .contains("<用量>").doesNotContain("5克");
        assertThat(FlywheelTextProtector.rawBaseline("做演示·酱卤牛肉250g时放盐3g拉匀", List.of(), List.of(PRODUCT)))
                .contains(PRODUCT).contains("<用量>").doesNotContain("3g");
    }
}
