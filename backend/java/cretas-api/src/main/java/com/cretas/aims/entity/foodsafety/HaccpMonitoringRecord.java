package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * HACCP 监控记录 — Sprint 8 P3 Phase A.
 *
 * <p>每个 batch 在每个 CCP 必须有监控记录. 超出 {@link HaccpCheckpoint#criticalLimitMin}~
 * {@link HaccpCheckpoint#criticalLimitMax} 即 deviation, 自动触发纠偏措施.
 *
 * <p>食品安全召回 (Sprint 8 P3 Skill `food-safety-recall`) 关键 step:
 * `haccp_checkpoint_review` Tool 查 batch 的 CCP 全记录 → 有 deviation 即列警告 +
 * correctiveAction lookup → LLM 总结.
 *
 * <p>查询 hot path: (factory_id, batch_number) 找该批次全部监控记录 + (factory_id,
 * is_deviation=true) 找偏离记录. 两个 index 已建.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "haccp_monitoring_records",
        indexes = {
                @Index(name = "idx_haccp_monitoring_factory_batch",
                        columnList = "factory_id,batch_number"),
                @Index(name = "idx_haccp_monitoring_deviation",
                        columnList = "factory_id,is_deviation"),
                @Index(name = "idx_haccp_monitoring_checkpoint", columnList = "checkpoint_id")
        })
@Where(clause = "deleted_at IS NULL")
public class HaccpMonitoringRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** {@link HaccpCheckpoint#id} 引用. */
    @Column(name = "checkpoint_id", nullable = false)
    private Long checkpointId;

    /** 批次号 (跟生产 batch 关联). */
    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    /** 监控时间. */
    @Column(name = "monitoring_time", nullable = false)
    private LocalDateTime monitoringTime;

    /** 实测值 (跟 CCP critical_limit_min/max 比较). */
    @Column(name = "measured_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal measuredValue;

    /** 操作员 user_id. */
    @Column(name = "operator_user_id", nullable = false)
    private Long operatorUserId;

    /** 是否偏离限值 (measured_value 超出 critical_limit_min/max). */
    @Column(name = "is_deviation", nullable = false)
    @Builder.Default
    private boolean isDeviation = false;

    /** 偏离时的处置说明 (is_deviation=true 时必填). */
    @Column(name = "deviation_action", columnDefinition = "TEXT")
    private String deviationAction;

    /** 备注. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ==================== 缺口 13: 工序报工时录入 (V20261103_89) ====================

    /** 报工所在生产批次 (App 报工 = 任务的批次; web 逐道录入 = 本行物化的批次; 纯库存道为 null)。 */
    @Column(name = "production_batch_id")
    private Long productionBatchId;

    /** 所属生产计划 —— 「批次或其计划有未处理偏离」挡放行, 判定口径见 CcpOpenDeviationLookup。 */
    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;

    /** 在哪个工序录的 (work_processes.id)。 */
    @Column(name = "work_process_id", length = 50)
    private String workProcessId;

    /** 工序序。 */
    @Column(name = "process_order")
    private Integer processOrder;

    /** 来源: APP_STEP_REPORT / WEB_PROCESS_SHEET / MANUAL。 */
    @Column(name = "source", length = 32)
    private String source;

    /** 录入时的临界限值快照 —— 限值以后改了, 这条记录当时按什么判的仍可追溯。 */
    @Column(name = "critical_limit_min_snapshot", precision = 19, scale = 4)
    private BigDecimal criticalLimitMinSnapshot;

    @Column(name = "critical_limit_max_snapshot", precision = 19, scale = 4)
    private BigDecimal criticalLimitMaxSnapshot;

    @Column(name = "unit_snapshot", length = 20)
    private String unitSnapshot;

    @Column(name = "operator_name", length = 100)
    private String operatorName;

    /** 报工凭据 (YR:报工id / PS:计划:行): CCP 与产出同一事务落库。 */
    @Column(name = "report_ref", length = 160)
    private String reportRef;

    /** 偏离已处理时间; null 且 isDeviation=true = 未处理 (挡放行、挡发货)。 */
    @Column(name = "deviation_handled_at")
    private LocalDateTime deviationHandledAt;

    @Column(name = "deviation_handled_by")
    private Long deviationHandledBy;

    @Column(name = "deviation_handled_by_name", length = 100)
    private String deviationHandledByName;

    /** 未处理的偏离: 偏离了且还没人填纠偏措施并标记已处理。 */
    public boolean isOpenDeviation() {
        return isDeviation && deviationHandledAt == null;
    }
}
