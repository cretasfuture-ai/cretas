package com.cretas.aims.entity.config;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 一线角色 AI 写操作的按工厂开放记录 (表 ai_frontline_intent_grants, V20261101_57)。
 *
 * <p>一行 = 某工厂把某个 AI 写意图开放给某个一线角色。关闭不删行, 写 revokedAt/revokedBy。
 * 默认开放的日常工作不在表里 (见 FrontlineAiWriteGrantService.DEFAULT_OPEN)。
 */
@Entity
@Table(name = "ai_frontline_intent_grants")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiFrontlineIntentGrant {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "role_code", length = 50, nullable = false)
    private String roleCode;

    @Column(name = "intent_code", length = 50, nullable = false)
    private String intentCode;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    protected void onCreate() {
        if (grantedAt == null) {
            grantedAt = LocalDateTime.now();
        }
    }
}
