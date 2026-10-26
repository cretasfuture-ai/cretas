package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 缺口 06「汇合配比校验」: 报工请求里的偏差原因 + 服务端算出的配比快照 (落库留痕)。
 *
 * <p>客户端只填 {@link #reason} / {@link #note}; 其余字段由
 * {@link com.cretas.aims.service.workflow.BlendRatioCheck} 在正式提交时<b>计算并覆盖</b>,
 * 客户端传什么都不作数。web 随 {@code row_payload.blendRatio} 落库,
 * App 随 {@code production_reports.custom_fields.blendRatio} 落库。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlendRatioDeviation {

    /** 偏差原因 (标准原因之一, 见 BlendRatioCheck.STANDARD_REASONS)。仅超差时要求。 */
    private String reason;
    /** 原因 = 「其他」时必填的说明。 */
    private String note;

    // ── 以下由服务端计算 ──────────────────────────────────────────
    /** WITHIN = 都在容差内; OVER_WITH_REASON = 超差且已填原因; SKIPPED_UNITS = 单位无法统一, 本次未校验。 */
    private String status;
    /** 本次使用的容差 (百分点)。 */
    private BigDecimal tolerancePp;
    /** 比较口径: 同一单位时为该单位 (如 kg / 个); 混合质量单位时为 g (折算)。 */
    private String basis;
    /** 最大偏差 (百分点, 绝对值)。 */
    private BigDecimal maxDeviationPp;
    /** status = SKIPPED_UNITS 时的原因。 */
    private String skipReason;
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String workflowPortId;
        private String materialName;
        /** 实际投入量 (按 basis 口径)。 */
        private BigDecimal quantity;
        /** 配方份额 (%) —— 已按本次参与的端口归一化。 */
        private BigDecimal recipeSharePercent;
        /** 实际份额 (%)。 */
        private BigDecimal actualSharePercent;
        /** 实际 − 配方 (百分点, 带符号)。 */
        private BigDecimal deviationPp;
        private boolean overTolerance;
    }
}
