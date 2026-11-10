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

import java.time.LocalDateTime;

/**
 * 时限计时的人工登记事实 —— 食品 DAG 缺口 14。
 *
 * <p>系统里原来没有「解冻开始」「放进冷藏」这两个时刻, 只能由人登记。暂停一律取服务端当前时间,
 * ⛔ 不收客户端传的时间: 补一条过去的暂停就能把已超时的料救回来。
 * 「开始解冻」允许补登实际时间 (纪律排查 A13), 必须带补登原因; 登记发生的时刻是 createdAt。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "process_time_marks")
@Where(clause = "deleted_at IS NULL")
@SQLDelete(sql = "UPDATE process_time_marks SET deleted_at = NOW() WHERE id = ?")
public class ProcessTimeMark extends BaseEntity {

    public static final class Kind {
        /** 起算事实, 如解冻开始。 */
        public static final String START = "START";
        /** 暂停计时, 如放进冷藏; endedAt 为空 = 还在暂停。 */
        public static final String PAUSE = "PAUSE";

        private Kind() {
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

    @Column(name = "mark_kind", nullable = false, length = 16)
    private String markKind;

    @Column(name = "mark_code", nullable = false, length = 32)
    private String markCode;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "started_by", nullable = false)
    private Long startedBy;

    @Column(name = "started_by_name", length = 100)
    private String startedByName;

    @Column(name = "ended_by")
    private Long endedBy;

    @Column(name = "note", length = 500)
    private String note;

    /** 补登原因: startedAt 早于登记时刻 (createdAt) 时必填 (纪律排查 A13)。 */
    @Column(name = "backfill_reason", length = 500)
    private String backfillReason;
}
