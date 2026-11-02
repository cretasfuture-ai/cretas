package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 句子里的批次状态说法 → 枚举 (ERP-S6-10)。整链行为见 {@link MaterialBatchChatStatusEntryTest}。 */
class MaterialBatchStatusPhraseTest {

    @ParameterizedTest(name = "「{0}」→ {1}")
    @CsvSource({
            "查询可用状态的原料批次, AVAILABLE",
            "可用的批次, AVAILABLE",
            "已用完的批次, USED_UP",
            "用完了的原料, USED_UP",
            "已耗尽的批次, DEPLETED",
            "已过期状态的原料批次, EXPIRED",
            "过期的原料, EXPIRED",
            "检验中的批次, INSPECTING",
            "待质检的原料, INSPECTING",
            "质检中, INSPECTING",
            "生产预留的批次, PRODUCING_RESERVED",
            "已预留的批次, RESERVED",
            "预留的原料, RESERVED",
            "已报废的批次, SCRAPPED",
            "不良品批次, DEFECTIVE",
            "冻品批次, FROZEN",
            "状态为USED_UP, USED_UP",
            "status=in_stock 的批次, IN_STOCK",
            "库存中有哪些已过期的, EXPIRED",
            "按过期时间排序的可用批次, AVAILABLE",
    })
    void recognisesOneStatus(String sentence, MaterialBatchStatus expected) {
        assertThat(MaterialBatchStatusPhrase.fromSentence(sentence)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"查询原料批次", "查询原料批次状态", "批次的状态分布", "所有批次", "按过期时间排序", ""})
    void noStatusMentionedMeansNoFilter(String sentence) {
        assertThat(MaterialBatchStatusPhrase.fromSentence(sentence)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"查询冻结状态的原料批次", "状态为锁定", "即将过期的原料批次", "临期批次", "不可用的批次"})
    void aNamedStatusThatCannotBeMappedIsRejectedNotDropped(String sentence) {
        assertThatThrownBy(() -> MaterialBatchStatusPhrase.fromSentence(sentence))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没认出你说的批次状态")
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(400);
    }

    @Test
    void twoDifferentStatusesInOneSentenceAreRejected() {
        assertThatThrownBy(() -> MaterialBatchStatusPhrase.fromSentence("可用和已过期的批次"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("一次只能按一个批次状态查询");
    }

    @ParameterizedTest
    @EnumSource(MaterialBatchStatus.class)
    void everyDisplayNameAndCodeNormalisesAsAnExplicitParam(MaterialBatchStatus status) {
        assertThat(MaterialBatchStatusPhrase.normalizeParam(status.name().toLowerCase())).isEqualTo(status.name());
        if (status != MaterialBatchStatus.IN_STOCK) {
            assertThat(MaterialBatchStatusPhrase.normalizeParam(status.getDisplayName())).isEqualTo(status.name());
        }
    }

    @Test
    void anUnknownExplicitParamIsPassedThroughForTheServiceToReject() {
        assertThat(MaterialBatchStatusPhrase.normalizeParam("IN_USE")).isEqualTo("IN_USE");
        assertThat(MaterialBatchStatusPhrase.normalizeParam("  ")).isNull();
    }
}
