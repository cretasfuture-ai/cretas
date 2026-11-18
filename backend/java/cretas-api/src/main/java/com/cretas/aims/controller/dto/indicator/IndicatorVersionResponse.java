package com.cretas.aims.controller.dto.indicator;

import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 指标历史快照 (GET /indicators/{code}/versions)。以前直接返回 {@link IndicatorVersion} 实体, 金额类指标的
 * value / alertLevel 照出; 现在先转这个 DTO, 看不到时值与告警级别置空并标 valueHidden (见 IndicatorValueVisibility)。
 * 字段名与前端 IndicatorVersionResponse 及原实体序列化一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "指标历史快照")
public class IndicatorVersionResponse {

    private String id;
    private String factoryId;
    private String indicatorId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal value;
    private LocalDateTime computedAt;
    private String alertLevel;
    private String computeSource;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "数值与告警级别按权限配置不显示 (仅为 true 时出现)")
    private Boolean valueHidden;

    public static IndicatorVersionResponse fromEntity(IndicatorVersion v, boolean showValue) {
        IndicatorVersionResponse r = IndicatorVersionResponse.builder()
                .id(v.getId())
                .factoryId(v.getFactoryId())
                .indicatorId(v.getIndicatorId())
                .periodStart(v.getPeriodStart())
                .periodEnd(v.getPeriodEnd())
                .computedAt(v.getComputedAt())
                .computeSource(v.getComputeSource())
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
        if (showValue) {
            r.setValue(v.getValue());
            r.setAlertLevel(v.getAlertLevel());
        } else {
            r.setValueHidden(Boolean.TRUE);
        }
        return r;
    }
}
