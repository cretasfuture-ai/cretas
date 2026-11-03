package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 物料平衡表 (缺口 #07): 每个生产计划按工序「投入 = 主产出 + 副产 + 已确认损耗 + 未解释差额」。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialBalanceTableDTO {
    /** 黄 / 红阈值, 百分数 (5 / 15) */
    private BigDecimal warnPct;
    private BigDecimal redPct;
    private List<PlanBlock> plans;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanBlock {
        private String planId;
        private String planNumber;
        private String productName;
        private List<String> batchNumbers;
        private List<Row> rows;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private String processName;
        private Integer processOrder;
        /** BALANCED / WARN / RED / UNIT_INCOMPARABLE / NO_INPUT */
        private String level;
        /** 数量所用单位 (质量统一 kg); 单位不可比 / 没投入时为 null */
        private String unit;
        private BigDecimal input;
        private BigDecimal output;
        private BigDecimal byproducts;
        private BigDecimal confirmedLoss;
        /** 带符号: 正 = 投入多于去向 */
        private BigDecimal unexplained;
        /** 未解释差额占投入, 百分数 (取绝对值, 1 位小数); 不可比时 null */
        private BigDecimal unexplainedPct;
        private Integer taskCount;
        /** 给人看的一句话 (单位不可比 / 没登记投入时说明原因) */
        private String note;
    }
}
