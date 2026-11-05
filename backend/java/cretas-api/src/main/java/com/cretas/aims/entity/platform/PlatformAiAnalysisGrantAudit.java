package com.cretas.aims.entity.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 平台 AI 分析入口授权的留痕 (V20261105_91), 只追加: 库里触发器拒绝 UPDATE / DELETE / TRUNCATE,
 * 实体不可变、没有 setter, 仓库只有写入和读取 ({@code PlatformAiAnalysisGrantAuditRepository})。
 */
@Entity
@Immutable
@Table(name = "platform_ai_analysis_grant_audit")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformAiAnalysisGrantAudit {

    public static final String GRANT = "GRANT";
    public static final String REVOKE = "REVOKE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** {@link #GRANT} / {@link #REVOKE}。 */
    @Column(name = "action", nullable = false, length = 10, updatable = false)
    private String action;

    @Column(name = "grantee_type", nullable = false, length = 10, updatable = false)
    private String granteeType;

    @Column(name = "grantee_ref", nullable = false, length = 50, updatable = false)
    private String granteeRef;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "actor_role", nullable = false, length = 50, updatable = false)
    private String actorRole;

    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;
}
