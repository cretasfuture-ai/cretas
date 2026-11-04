package com.cretas.aims.dto.quality;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工艺参数偏离汇总的返回体 (只读)。数据来自报工时落库的判定快照 (production_reports.custom_fields.paramChecks),
 * 按「当时的标准」判定, 不拿今天的标准重判。
 */
public final class ProcessParamDeviationDTO {

    private ProcessParamDeviationDTO() {
    }

    /** 实际生效的查询窗口 (报工日期, 含两端)。没传时是最近 30 天, 页面如实显示。 */
    @Data
    @Builder
    public static class Window {
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Data
    @Builder
    public static class SummaryView {
        private Window window;
        /** 窗口内 (满足筛选) 填了参数、有判定快照的总条数 = 全部汇总行「报工次数」之和; 0 表示这段时间没有任何可判定的参数数据。 */
        private long totalChecked;
        /** 其中判为偏高 / 偏低的总条数。 */
        private long totalDeviations;
        private List<SummaryRow> rows;
    }

    /** 一个「工序 + 参数」一行。分母 = 填了该参数的次数, 不是该工序的全部报工次数。 */
    @Data
    @Builder
    public static class SummaryRow {
        /** 工序名; 取不到时为 null (页面显示「—」, 不编)。 */
        private String processName;
        private String paramKey;
        private String paramLabel;
        private String unit;
        /** 填了该参数的报工次数 (分母)。 */
        private long reportCount;
        /** 偏离次数 = 偏高 + 偏低。 */
        private long deviationCount;
        private long highCount;
        private long lowCount;
        /** 偏离率 (%), 保留 1 位小数: deviationCount / reportCount。reportCount 恒 &gt; 0, 所以不会出现除零。 */
        private BigDecimal deviationRate;
    }

    /** 一条偏离明细 = 一次报工里一个参数的判定。 */
    @Data
    @Builder
    public static class DetailRow {
        private Long reportId;
        /** 报工所属业务日期。 */
        private LocalDate reportDate;
        /** 报工录入时间。 */
        private LocalDateTime reportedAt;
        private Long batchId;
        private String batchNumber;
        private String productTypeId;
        private String productName;
        private String processName;
        private String paramKey;
        private String paramLabel;
        private BigDecimal measuredValue;
        private String unit;
        private BigDecimal standardValue;
        private BigDecimal rangeMin;
        private BigDecimal rangeMax;
        /** HIGH 偏高 / LOW 偏低。 */
        private String status;
        private Long reporterId;
        private String reporterName;
    }
}
