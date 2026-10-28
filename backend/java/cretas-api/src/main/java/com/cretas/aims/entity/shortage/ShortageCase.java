package com.cretas.aims.entity.shortage;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 缺料异常处理实例 (S4 L1): 一个缺料事件一条, 同一来源再来只刷新它 (见 V20261103_96)。
 *
 * <p>「缺口已解决」只看物料事实 ({@link #gapResolvedAt}); 订单交付由销售单自己的状态体现, 两者不合并。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "shortage_cases")
@SQLDelete(sql = "UPDATE shortage_cases SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ShortageCase extends BaseEntity {

    public enum SourceType { SALES_ORDER }

    /** 阶段; 终态是 CANCELLED / MANUAL / CLOSED。GAP_RESOLVED 仍未关闭 (等后续确认)。 */
    public enum Stage {
        DETECTED, PLANNED, EXECUTING, WAITING_SUPPLY, PARTIALLY_RESOLVED, GAP_RESOLVED, CANCELLED, MANUAL, CLOSED;

        public boolean isTerminal() {
            return this == CANCELLED || this == MANUAL || this == CLOSED;
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
    @Column(name = "stage", length = 32, nullable = false)
    private Stage stage;

    /** 当前该谁处理 (岗位); 处理人可空。 */
    @Column(name = "owner_role", length = 64, nullable = false)
    private String ownerRole;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "gap_line_count", nullable = false)
    private int gapLineCount;

    /** 上次刷新时的输入摘要 (需求 / 放行 / 质检中 / 在途); 下一步写入前据此判断状态是否已变。 */
    @Column(name = "input_fingerprint", length = 64, nullable = false)
    private String inputFingerprint;

    @Column(name = "last_refreshed_at", nullable = false)
    private LocalDateTime lastRefreshedAt;

    @Column(name = "last_refresh_reason", length = 64, nullable = false)
    private String lastRefreshReason;

    @Column(name = "gap_resolved_at")
    private LocalDateTime gapResolvedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
