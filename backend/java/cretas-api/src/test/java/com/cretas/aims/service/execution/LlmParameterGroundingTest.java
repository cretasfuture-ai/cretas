package com.cretas.aims.service.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 出处规则的边界 (真实入口上的行为见 ToolDispatchServiceParameterGroundingTest)。 */
class LlmParameterGroundingTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "taskId", Map.of("type", "integer"),
                    "actualQuantity", Map.of("type", "number"),
                    "notes", Map.of("type", "string"),
                    "reason", Map.of("type", "string", "enum", List.of("A", "B")),
                    "status", Map.of("type", "string")),
            "required", List.of("taskId", "actualQuantity"));

    private static List<BigDecimal> mentions(String text) {
        return LlmParameterGrounding.numberMentions(text).stream()
                .map(LlmParameterGrounding.Evidence::value).toList();
    }

    @Test
    void numberMentionsReadArabicChineseDecimalsAndZero() {
        assertThat(mentions("产量 3.5 公斤")).containsExactly(new BigDecimal("3.5"));
        assertThat(mentions("做了十公斤")).containsExactly(BigDecimal.valueOf(10));
        assertThat(mentions("一百五")).containsExactly(BigDecimal.valueOf(150));
        assertThat(mentions("三点五斤")).containsExactly(new BigDecimal("3.5"));
        assertThat(mentions("两万")).containsExactly(BigDecimal.valueOf(20000));
        assertThat(mentions("产量零")).containsExactly(BigDecimal.ZERO);
        assertThat(mentions("零个")).containsExactly(BigDecimal.ZERO);
        assertThat(mentions("产量０")).containsExactly(BigDecimal.ZERO);           // 全角
        assertThat(mentions("10kg")).containsExactly(BigDecimal.TEN);
    }

    @Test
    void identifiersAndWordsAreNotNumbers() {
        // 编号里的数字不算 (否则 UUID 里的 0 会给「产量 0」作证)
        assertThat(mentions("产品 eb0aa47b 批次 B001")).isEmpty();
        // 「零件 / 零售 / 零下」里的零不是 0
        assertThat(mentions("零件做完了")).isEmpty();
        assertThat(mentions("零下")).isEmpty();
        // 对照: 同一句换成真正说 0 就有
        assertThat(mentions("零件做了0个")).containsExactly(BigDecimal.ZERO);
    }

    @Test
    void contextStringsThatAreNotWholeNumbersDoNotGround() {
        // UUID 形状的上下文值不能给 0 作证; 对照: 同一个上下文里整值是数字的可以
        Map<String, Object> existing = Map.of("productId", "eb0aa47b-0000-4000-8000-000000000000");
        var r = LlmParameterGrounding.ground(Map.of("actualQuantity", 0), existing, "做完了", SCHEMA);
        assertThat(r.accepted()).doesNotContainKey("actualQuantity");
        var ok = LlmParameterGrounding.ground(Map.of("actualQuantity", 0), Map.of("count", "0"), "做完了", SCHEMA);
        assertThat(ok.accepted()).containsEntry("actualQuantity", 0);
    }

    @Test
    void principalIdsAreNotEvidence() {
        var r = LlmParameterGrounding.ground(Map.of("actualQuantity", 42),
                Map.of("userId", 42L, "factoryId", "F006"), "做完了", SCHEMA);
        assertThat(r.accepted()).isEmpty();
        assertThat(r.dropped()).containsKey("actualQuantity");
    }

    @Test
    void enumAndNonFreeTextStringsPassThrough() {
        var r = LlmParameterGrounding.ground(Map.of("reason", "A", "status", "IN_PROGRESS"),
                Map.of(), "开始", SCHEMA);
        assertThat(r.accepted()).containsEntry("reason", "A").containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void freeTextMustBeTheUsersWordsAndNotJustANumber() {
        assertThat(LlmParameterGrounding.ground(Map.of("notes", "1827"), Map.of(), "1827", SCHEMA).dropped())
                .containsKey("notes");
        assertThat(LlmParameterGrounding.ground(Map.of("notes", "刀钝了"), Map.of(), "备注:刀钝了。", SCHEMA)
                .accepted()).containsEntry("notes", "刀钝了");
        assertThat(LlmParameterGrounding.isFreeTextParam("rejectionReason")).isTrue();
        assertThat(LlmParameterGrounding.isFreeTextParam("ackRemark")).isTrue();
        assertThat(LlmParameterGrounding.isFreeTextParam("taskId")).isFalse();
    }

    @Test
    void nonNumberInNumericSlotIsDropped() {
        var r = LlmParameterGrounding.ground(Map.of("actualQuantity", "未知"), Map.of(), "做完了", SCHEMA);
        assertThat(r.dropped()).containsEntry("actualQuantity", "不是数字");
    }
}
