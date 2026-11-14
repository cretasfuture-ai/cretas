package com.cretas.aims.entity.issue;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 「发现的问题」(/issues) 的处置记录 (V20261114_41): 关闭结论 / 转成待办。
 *
 * <p>⛔ 不改来源单据的阶段: 来源仍在各自页面按原流程处理。一个来源对象在本表最多一行 (factory + sourceType + sourceId)。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "factory_issue_dispositions",
        uniqueConstraints = @UniqueConstraint(name = "uk_factory_issue_dispositions_source",
                columnNames = {"factory_id", "source_type", "source_id"}))
public class IssueDisposition {

    /** 来源对象类型。新增来源要同时改迁移里的 CHECK 和 {@code IssueDispositionService} 的来源校验。 */
    public enum SourceType {
        SHORTAGE_CASE, DELIVERY_RISK, PROCESS_PARAM_DEVIATION, COST_DEVIATION, COLD_CHAIN_ALERT, DAILY_RECONCILE_RUN
    }

    public enum Status { OPEN, CLOSED, CONVERTED }

    /** 关闭结论: 沿用原型清单 (DECISIONS M8), ⛔ 不自创。 */
    public enum Conclusion {
        HANDLED("已处理"), FALSE_ALARM("误报"), ACCEPTED_DEVIATION("接受偏差");

        private final String label;

        Conclusion(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 32, nullable = false)
    private SourceType sourceType;

    @Column(name = "source_id", length = 191, nullable = false)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "conclusion", length = 32)
    private Conclusion conclusion;

    @Column(name = "reason", length = 200)
    private String reason;

    /** 处置时的问题标题快照 (待办列表不再回读来源)。 */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** 站内路径 (以 / 开头), 待办里点进去用。 */
    @Column(name = "link_path", length = 255)
    private String linkPath;

    @Column(name = "assignee_user_id")
    private Long assigneeUserId;

    @Column(name = "actor_type", length = 32, nullable = false)
    private String actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "acted_at", nullable = false)
    private LocalDateTime actedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
