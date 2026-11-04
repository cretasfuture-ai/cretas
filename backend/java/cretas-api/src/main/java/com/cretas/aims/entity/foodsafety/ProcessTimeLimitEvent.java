package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 超时被拦的留痕与处置 —— 食品 DAG 缺口 14。
 *
 * <p>领用被 {@code ProcessTimeLimitService} 拦下时写一条 OPEN (独立事务, 主事务回滚后还在);
 * 质检复检放行或判不合格后转 RELEASED / REJECTED, 记录保留。
 *
 * <p>{@link #coveredUntil}: 处置只管到这个起算时刻为止的笔, 之后新入同一行的料不沾光。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "process_time_limit_events")
@Where(clause = "deleted_at IS NULL")
@SQLDelete(sql = "UPDATE process_time_limit_events SET deleted_at = NOW() WHERE id = ?")
public class ProcessTimeLimitEvent extends BaseEntity {

    public static final class Status {
        public static final String OPEN = "OPEN";
        public static final String RELEASED = "RELEASED";
        public static final String REJECTED = "REJECTED";

        private Status() {
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 191)
    private String targetId;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    /** 想领这批料的生产批次。 */
    @Column(name = "production_batch_id")
    private Long productionBatchId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "workflow_port_id", length = 128)
    private String workflowPortId;

    @Column(name = "process_name", length = 255)
    private String processName;

    @Column(name = "limit_minutes", nullable = false)
    private Integer limitMinutes;

    @Column(name = "start_event", nullable = false, length = 32)
    private String startEvent;

    @Column(name = "mix_rule", nullable = false, length = 16)
    private String mixRule;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "deadline_at", nullable = false)
    private LocalDateTime deadlineAt;

    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;

    @Column(name = "blocked_by")
    private Long blockedBy;

    /** 超时的数量: 判不合格时转出这么多 (半成品)。 */
    @Column(name = "expired_quantity", precision = 15, scale = 4)
    private BigDecimal expiredQuantity;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "covered_until", nullable = false)
    private LocalDateTime coveredUntil;

    /** 配置快照: 复检放行后多久内有效; null = 不再限时。 */
    @Column(name = "recheck_valid_minutes")
    private Integer recheckValidMinutes;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "conclusion", columnDefinition = "TEXT")
    private String conclusion;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_by_name", length = 100)
    private String decidedByName;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;
}
