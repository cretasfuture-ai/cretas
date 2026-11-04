package com.cretas.aims.entity.shortage;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 缺料实例的执行后核对 (V20261103_190): 重排 / 转采购之后目标达成没有。只有 ACHIEVED 算达成,
 * 其它三种都要在处理实例上标出来, 不默认成功。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "shortage_case_outcome_checks")
@SQLDelete(sql = "UPDATE shortage_case_outcome_checks SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ShortageCaseOutcomeCheck extends BaseEntity {

    public enum Trigger { RESCHEDULE_APPLIED, PURCHASE_DRAFT, MANUAL_RECHECK, RISK_RECHECK }

    public enum Verdict {
        ACHIEVED("目标已达成"), PENDING_SUPPLY("等到货, 交期可保"), NOT_ACHIEVED("目标未达成"), UNKNOWN("无法判定");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "action_id")
    private Long actionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", length = 32, nullable = false)
    private Trigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", length = 16, nullable = false)
    private Verdict verdict;

    @Column(name = "gap_closed", nullable = false)
    private boolean gapClosed;

    @Column(name = "open_gap_lines", nullable = false)
    private int openGapLines;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "latest_planned_end")
    private LocalDateTime latestPlannedEnd;

    @Column(name = "late_schedule_count", nullable = false)
    private int lateScheduleCount;

    @Column(name = "supply_ready_date")
    private LocalDate supplyReadyDate;

    @Column(name = "detail", columnDefinition = "text", nullable = false)
    private String detail;

    @Column(name = "actor_type", length = 32, nullable = false)
    private String actorType;

    @Column(name = "actor_id")
    private Long actorId;
}
