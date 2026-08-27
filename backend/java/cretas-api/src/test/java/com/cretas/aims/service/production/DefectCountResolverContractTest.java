package com.cretas.aims.service.production;

import com.cretas.aims.entity.enums.DefectCountOutcome;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 结单三态：「已清点有 N 个」/「已清点确认零」/「没清点」必须落成三个不同的值。
 *
 * <h2>它守的那个缺陷</h2>
 *
 * <p>2026-08-26 生产实测：72 张报工单<b>零填写</b>不良品数，而旧代码兜底填 0，
 * 于是 8 个已完工批次全部声称 100% 良品 —— 其中 <b>4 个</b>的质检明确记了不合格
 * （批次 10766：抽样 80 / 不合格 5）。{@code V20260826_05/06} 已把那批假数清成 NULL。</p>
 *
 * <p>本闸保证它<b>不会从结单这条路再长回来</b>：核心是最后一条 ——
 * <b>「没回答」落库必须是 null，不是 0。</b></p>
 */
@DisplayName("结单不良品三态")
class DefectCountResolverContractTest {

    @Test
    @DisplayName("已清点且有不良品 → 原样落库")
    void countedKeepsQuantity() {
        var r = DefectCountResolver.resolve(DefectCountOutcome.COUNTED, new BigDecimal("3"));
        assertThat(r.outcome()).isEqualTo(DefectCountOutcome.COUNTED);
        assertThat(r.quantity()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("已清点确认零不良 → 落 0, 而且这个 0 是真实读数")
    void noneConfirmedStoresRealZero() {
        var r = DefectCountResolver.resolve(DefectCountOutcome.NONE_CONFIRMED, null);
        assertThat(r.outcome()).isEqualTo(DefectCountOutcome.NONE_CONFIRMED);
        assertThat(r.quantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("没清点 → 落 null")
    void notCountedStoresNull() {
        var r = DefectCountResolver.resolve(DefectCountOutcome.NOT_COUNTED, null);
        assertThat(r.outcome()).isEqualTo(DefectCountOutcome.NOT_COUNTED);
        assertThat(r.quantity()).isNull();
    }

    @Test
    @DisplayName("🔴 核心: 「没回答」落 null 而不是 0 —— 这正是 8 个批次谎报 100% 良品的成因")
    void unansweredMustNotBecomeZero() {
        var r = DefectCountResolver.resolve(null, null);
        assertThat(r.outcome()).isNull();
        assertThat(r.quantity())
                .as("旧客户端没回答时若落 0, 就等于假装有人清点过并确认了零不良 —— "
                        + "那是 V20260826_05/06 刚清掉的东西")
                .isNull();
    }

    @Test
    @DisplayName("「确认零不良」与「没清点」必须落成不同的值 (三态的全部意义)")
    void confirmedZeroAndNotCountedAreDistinguishable() {
        var confirmed = DefectCountResolver.resolve(DefectCountOutcome.NONE_CONFIRMED, null);
        var notCounted = DefectCountResolver.resolve(DefectCountOutcome.NOT_COUNTED, null);
        assertThat(confirmed.quantity()).isNotNull();
        assertThat(notCounted.quantity()).isNull();
        assertThat(confirmed.outcome()).isNotEqualTo(notCounted.outcome());
    }

    @Test
    @DisplayName("矛盾输入一律拒绝, ⛔ 不做静默纠正")
    void contradictoryInputIsRejected() {
        // 说有不良品却不填数量
        assertThatThrownBy(() -> DefectCountResolver.resolve(DefectCountOutcome.COUNTED, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> DefectCountResolver.resolve(DefectCountOutcome.COUNTED, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
        // 说确认零不良却填了个非零数
        assertThatThrownBy(() ->
                DefectCountResolver.resolve(DefectCountOutcome.NONE_CONFIRMED, new BigDecimal("2")))
                .isInstanceOf(BusinessException.class);
        // 说没清点却填了数量
        assertThatThrownBy(() ->
                DefectCountResolver.resolve(DefectCountOutcome.NOT_COUNTED, new BigDecimal("1")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("阴性对照: 合法组合不许被误拒")
    void legalCombinationsAreAccepted() {
        assertThatCode(() -> {
            DefectCountResolver.resolve(DefectCountOutcome.NONE_CONFIRMED, BigDecimal.ZERO);
            DefectCountResolver.resolve(DefectCountOutcome.COUNTED, new BigDecimal("0.5"));
            DefectCountResolver.resolve(DefectCountOutcome.NOT_COUNTED, null);
        }).doesNotThrowAnyException();
    }

    // ==================== 回填到批次的决策 ====================
    //
    // 一个计划可能对多个批次。生产实测(2026-08-27): 30 个计划里 24 个只有 1 个正式批次;
    // 已结单的 15 个里 13 个只有 1 个(87%)。⇒ 唯一时回填, 多于一个时不猜。

    @Test
    @DisplayName("唯一批次 + 已清点 → 回填")
    void backfillsWhenExactlyOneBatch() {
        var r = DefectCountResolver.resolve(DefectCountOutcome.COUNTED, new BigDecimal("2"));
        var d = DefectCountResolver.decideBatchBackfill(r, 1);
        assertThat(d.write()).isTrue();
        assertThat(d.quantity()).isEqualByComparingTo("2");
        assertThat(d.skipReason()).isNull();
    }

    @Test
    @DisplayName("唯一批次 + 确认零不良 → 回填 0 (这个 0 是真实读数)")
    void backfillsRealZero() {
        var r = DefectCountResolver.resolve(DefectCountOutcome.NONE_CONFIRMED, null);
        var d = DefectCountResolver.decideBatchBackfill(r, 1);
        assertThat(d.write()).isTrue();
        assertThat(d.quantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("🔴 多个批次 → 不猜, 且必须说明原因")
    void refusesToGuessAcrossMultipleBatches() {
        var r = DefectCountResolver.resolve(DefectCountOutcome.COUNTED, new BigDecimal("5"));
        var d = DefectCountResolver.decideBatchBackfill(r, 3);
        assertThat(d.write()).as("摊到哪个批次是业务判断, 猜一个就是在编数据").isFalse();
        assertThat(d.skipReason())
                .as("⛔ 不许静默跳过 —— 否则「没回填」和「回填了但值为空」不可分")
                .isNotNull()
                .startsWith("AMBIGUOUS");
    }

    @Test
    @DisplayName("没清点 / 没回答 → 批次上保持 NULL, ⛔ 不写 0")
    void neverWritesZeroForUnknown() {
        for (var r : new DefectCountResolver.Resolved[]{
                DefectCountResolver.resolve(DefectCountOutcome.NOT_COUNTED, null),
                DefectCountResolver.resolve(null, null)}) {
            var d = DefectCountResolver.decideBatchBackfill(r, 1);
            assertThat(d.write()).isFalse();
            assertThat(d.quantity()).isNull();
        }
    }

    @Test
    @DisplayName("零个批次也不回填 (阴性对照: 不许 index out of bounds)")
    void handlesZeroBatches() {
        var r = DefectCountResolver.resolve(DefectCountOutcome.COUNTED, new BigDecimal("1"));
        assertThat(DefectCountResolver.decideBatchBackfill(r, 0).write()).isFalse();
    }
}
