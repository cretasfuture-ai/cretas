package com.cretas.aims.entity.quality.firstarticle;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工序首检单, 挂在一个工序任务 ({@code work_process_tasks.id}) 上。
 *
 * <p>状态机 (只由 {@code FirstArticleService} 推进):
 * <pre>
 *   (送检) → PENDING ──判定──→ PASSED / FAILED
 *              │                    │
 *              └──────作废──────────┴──→ VOID (写原因; 作废后可重新送检)
 *   FAILED 之后返工, 再送检开一张新单 (旧单保留)。
 * </pre>
 * 任务的首检状态 = 最近一张非 VOID 的单。同一任务同时只有一张 PENDING (部分唯一索引兜底并发)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "first_article_inspections")
@Where(clause = "deleted_at IS NULL")
public class FirstArticleInspection extends BaseEntity {

    public enum Status {
        PENDING, PASSED, FAILED, VOID;

        public String getDisplayName() {
            return switch (this) {
                case PENDING -> "待判定";
                case PASSED -> "首检合格";
                case FAILED -> "首检不合格";
                case VOID -> "已作废";
            };
        }
    }

    public enum Verdict { PASSED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "work_process_task_id", nullable = false)
    private Long workProcessTaskId;

    @Column(name = "production_batch_id", nullable = false)
    private Long productionBatchId;

    @Column(name = "work_process_id", nullable = false, length = 50)
    private String workProcessId;

    @Column(name = "standard_id", nullable = false)
    private Long standardId;

    @Column(name = "standard_revision", nullable = false)
    private Integer standardRevision;

    @Column(name = "standard_name", nullable = false, length = 100)
    private String standardName;

    /** 送检时的标准检验项快照 (判定按它算, 后改标准不影响这张单)。 */
    @Column(name = "standard_items_json", nullable = false, columnDefinition = "TEXT")
    private String standardItemsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "sample_quantity", precision = 15, scale = 4)
    private BigDecimal sampleQuantity;

    @Column(name = "sample_unit", length = 20)
    private String sampleUnit;

    @Column(name = "request_notes", length = 500)
    private String requestNotes;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /** 判定时逐项实测值与单项结论 (服务端按快照算出, ⛔ 不信客户端给的单项结论)。 */
    @Column(name = "results_json", columnDefinition = "TEXT")
    private String resultsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_verdict", length = 16)
    private Verdict autoVerdict;

    @Column(name = "judged_by")
    private Long judgedBy;

    @Column(name = "judged_at")
    private LocalDateTime judgedAt;

    @Column(name = "judge_notes", length = 500)
    private String judgeNotes;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;
}
