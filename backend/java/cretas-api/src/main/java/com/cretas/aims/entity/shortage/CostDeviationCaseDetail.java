package com.cretas.aims.entity.shortage;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成本 / 损耗异常实例的读数与处理结果 (V20261104_31), 与 {@link ShortageCase} 一对一。
 *
 * <p>{@link #amountVariance} 为 null = 金额未知 (物料平衡没有单价口径), ⛔ 不当 0;
 * 对外给出前按采购价权限脱敏。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cost_deviation_case_details")
@SQLDelete(sql = "UPDATE cost_deviation_case_details SET deleted_at = NOW() WHERE case_id = ?")
@Where(clause = "deleted_at IS NULL")
public class CostDeviationCaseDetail extends BaseEntity {

    /** 处理结果。 */
    public enum Resolution {
        REASONABLE("差异合理"), CORRECTED("已纠正"), STANDARD_UPDATE("标准需调整"), DATA_ERROR("录入有误");

        private final String label;

        Resolution(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "plan_id", length = 191, nullable = false)
    private String planId;

    @Column(name = "plan_number", length = 100)
    private String planNumber;

    @Column(name = "product_name", length = 200)
    private String productName;

    /** 触发读数, 百分数 (绝对值): 成本差异率 / 最大工序未解释差额占比。 */
    @Column(name = "metric_pct", nullable = false, precision = 12, scale = 2)
    private BigDecimal metricPct;

    @Column(name = "threshold_pct", nullable = false, precision = 6, scale = 2)
    private BigDecimal thresholdPct;

    @Column(name = "amount_variance", precision = 20, scale = 2)
    private BigDecimal amountVariance;

    /** 算不出的项数 (无法比较的成本项 / 单位不可比的工序)。 */
    @Column(name = "unknown_item_count", nullable = false)
    private int unknownItemCount;

    /** 给人看的一句话, 不含金额。 */
    @Column(name = "summary", columnDefinition = "text", nullable = false)
    private String summary;

    /** 读数回到阈值内的时间; 实例不自动关闭, 等负责人给结论。 */
    @Column(name = "below_threshold_at")
    private LocalDateTime belowThresholdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_code", length = 32)
    private Resolution resolutionCode;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
