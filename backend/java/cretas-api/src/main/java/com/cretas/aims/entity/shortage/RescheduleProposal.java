package com.cretas.aims.entity.shortage;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 缺料实例上的一次重排预览 (S4 L1 切片 3): 生成时每个受影响排程任务的基线, 以及最多 3 个候选。
 *
 * <p>确认时逐个重读任务与 {@link #baseline} 比对 (id + updatedAt + 产线 + 起止), 有一个对不上就整体 409
 * 「方案已过期」, 一行都不写。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "reschedule_proposals")
@SQLDelete(sql = "UPDATE reschedule_proposals SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class RescheduleProposal extends BaseEntity {

    public enum Status { OPEN, APPLIED, STALE }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    @Column(name = "case_id", length = 36, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.OPEN;

    /** 每个受影响任务: scheduleId / updatedAt / lineId / start / end (ISO 本地时间)。 */
    @org.hibernate.annotations.Type(io.hypersistence.utils.hibernate.type.json.JsonBinaryType.class)
    @Column(name = "baseline", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> baseline;

    /** 候选: key / label / feasible / notes / moves[{scheduleId, fromLine, toLine, fromStart, toStart, fromEnd, toEnd}] ...。 */
    @org.hibernate.annotations.Type(io.hypersistence.utils.hibernate.type.json.JsonBinaryType.class)
    @Column(name = "candidates", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> candidates;

    @Column(name = "applied_candidate", length = 32)
    private String appliedCandidate;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_by_type", length = 32, nullable = false)
    private String createdByType;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
}
