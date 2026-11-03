package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 缺口 03「回料比例上限」: 报工请求里的超限原因 + 服务端算出的快照 (随 row_payload.reworkRatio 落库留痕)。
 * 客户端只填 reason/note, 其余由 {@link com.cretas.aims.service.workflow.ReworkRatioCheck} 计算并覆盖。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReworkRatioDeviation {
    private String reason;
    private String note;

    /** WITHIN = 没超; OVER_WITH_REASON = 超限且已填原因。 */
    private String status;
    /** 产品设的上限 (%)。 */
    private BigDecimal limitPercent;
    /** 本道回料投入 / 主料投入 (%)。 */
    private BigDecimal actualPercent;
    private BigDecimal reworkKg;
    private BigDecimal mainKg;
}
