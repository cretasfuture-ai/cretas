package com.cretas.aims.service.vision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 肉品初评 prompt 的部位纪律。
 *
 * <p>实测长相 (2026-09-16, 演示样张过线上接口): 一盘切成方块的雪花肉被说成「牛小排」且 confidence 0.95;
 * 另一张写成英文 "Sirloin"; 第三张写成「眼肉 (Ribeye)」。部位是自由填空 ⇒ 模型敢猜、口径不统一。
 * 修法是把 cut 收窄到与演示租户「分割」工序 20 个产出端口同名的中文词表, 方块/薄片一律 null。
 *
 * <p>这里钉的是 prompt <b>结构</b>(词表进了 prompt、方块规则在、confidence 与 cut 挂钩), 不是模型行为 ——
 * 模型行为在 PR 描述里用四张样张的接口读数记录。
 */
class VisionTemplateRegistryMeatCutPromptTest {

    private static String meatCutPrompt() {
        return VisionTemplateRegistry.find(VisionTemplateRegistry.MEAT_CUT)
                .orElseThrow(() -> new AssertionError("MEAT_CUT 模板不存在"))
                .prompt();
    }

    @Test
    @DisplayName("20 个部位中文词全部进了 prompt, 且清单外要求填 null")
    void vocabularyIsInThePrompt() {
        String prompt = meatCutPrompt();
        assertThat(VisionTemplateRegistry.MEAT_CUT_VOCABULARY).hasSize(20);
        for (String cut : VisionTemplateRegistry.MEAT_CUT_VOCABULARY) {
            assertThat(prompt).as("部位「%s」不在 prompt 里", cut).contains(cut);
        }
        assertThat(prompt).contains("清单外的一律填 null");
    }

    @Test
    @DisplayName("方块/薄片不判部位、禁英文、confidence 与 cut 挂钩 —— 三条纪律都在")
    void disciplineLinesArePresent() {
        String prompt = meatCutPrompt();
        assertThat(prompt).contains("已切成方块、薄片、肉粒、肉末的, cut 填 null");
        assertThat(prompt).contains("不要用英文");
        assertThat(prompt).contains("cut 为 null 或拿不准时 confidence 不高于 0.6");
        // 评分是外观描述不是等级 —— deck 与讲法都承诺「不做 AI 分级」
        assertThat(prompt).contains("不是等级");
    }

    /** 对照: 耳标模板不许被这套词表污染。 */
    @Test
    @DisplayName("对照: EAR_TAG 模板 prompt 不含部位词表")
    void earTagPromptUntouched() {
        String earTag = VisionTemplateRegistry.find(VisionTemplateRegistry.EAR_TAG).orElseThrow().prompt();
        assertThat(earTag).doesNotContain("牛腩").doesNotContain("cut 为 null");
    }
}
