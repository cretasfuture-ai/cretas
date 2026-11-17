package com.cretas.aims.controller.dto.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 指标取值响应 DTO — wraps {@link IndicatorValueResult} for HTTP.
 *
 * <p>携带计算值 + 计算时刻 + 来源 + 数据状态 + 业务时间窗 + 指标基本信息.
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "指标取值响应")
public class IndicatorValueResponse {

    @Schema(description = "指标编码")
    private String code;

    @Schema(description = "指标名称")
    private String name;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "指标取值")
    private BigDecimal value;

    @Schema(description = "物理计算时刻")
    private LocalDateTime computedAt;

    @Schema(description = "缓存命中标识")
    private Boolean cacheHit;

    @Schema(description = "来源标签: cache / precomputed / PYTHON_ENDPOINT:* / REAL_BUSINESS:*")
    private String source;

    @Schema(description = "数据状态: AVAILABLE / NO_DATA / NOT_COMPUTED / FAILED")
    private String status;

    @Schema(description = "业务时间窗起 (查询入参回显)")
    private LocalDate periodStart;

    @Schema(description = "业务时间窗止 (查询入参回显)")
    private LocalDate periodEnd;

    /**
     * Sprint 12 Phase B step 4: KPI 卡片 next-action 提示 (Issue #265).
     * null 表示该 indicator 暂无 actionHint (老 PRECOMPUTED/PYTHON_ENDPOINT indicators
     * 大部分无, REAL_BUSINESS strategies 通过 V_04 seed 配置).
     */
    @Schema(description = "next-action 提示 (fool-proof-design Rule 5, 可空)")
    private ActionHint actionHint;

    /**
     * 金额类指标且调用者没有价格查看口径时为 true (值 / 告警级别 / 阈值已置空, 见 IndicatorValueVisibility);
     * 前端据此显示「无权限查看」, 不是「未计算」。可见时不出现。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "数值按权限配置不显示 (仅为 true 时出现)")
    private Boolean valueHidden;

    /**
     * 从 service 计算结果 + 指标元信息构造 DTO.
     *
     * @deprecated Sprint 12 Phase B step 4 — 用 {@link #fromWithIndicator} 取代,
     *             这样能从 indicator.config jsonb 读 actionHint. 老 controller 调用如果继续
     *             用本方法 actionHint 会缺失.
     */
    @Deprecated
    public static IndicatorValueResponse from(String code, String name, String unit,
                                              IndicatorValueResult result,
                                              LocalDate periodStart, LocalDate periodEnd) {
        return IndicatorValueResponse.builder()
                .code(code)
                .name(name)
                .unit(unit)
                .value(result.value())
                .computedAt(result.computedAt())
                .cacheHit(result.cacheHit())
                .source(result.source())
                .status(result.status())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .build();
    }

    /**
     * Sprint 12 Phase B step 4: 推荐入口 — 从 indicator entity 直接构造, 自动 extract
     * actionHint from {@code config} jsonb.
     */
    public static IndicatorValueResponse fromWithIndicator(Indicator ind,
                                                            IndicatorValueResult result,
                                                            LocalDate periodStart,
                                                            LocalDate periodEnd) {
        return IndicatorValueResponse.builder()
                .code(ind.getCode())
                .name(ind.getName())
                .unit(ind.getUnit())
                .value(result.value())
                .computedAt(result.computedAt())
                .cacheHit(result.cacheHit())
                .source(result.source())
                .status(result.status())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .actionHint(extractActionHint(ind))
                .build();
    }

    /**
     * 从 indicator.config jsonb 取 actionHint — 委托 {@link ActionHint#fromConfig} (Sprint 12 #265
     * 列表+详情共用). 异常吞掉返 null (老板不应因 config 错配看不到 KPI).
     */
    static ActionHint extractActionHint(Indicator ind) {
        if (ind == null) return null;
        try {
            return ActionHint.fromConfig(ind.getConfig());
        } catch (Exception ex) {
            log.warn("extractActionHint failed for code={}: {}", ind.getCode(), ex.getMessage());
            return null;
        }
    }
    /** 数值按权限配置不显示: 值置空并标 valueHidden (不是 0)。 */
    public IndicatorValueResponse hideValue() {
        this.value = null;
        this.valueHidden = Boolean.TRUE;
        return this;
    }
}
