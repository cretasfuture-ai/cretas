package com.cretas.aims.entity.quality.firstarticle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工序首检的工厂级开关 (V20261115_51)。<b>没有行 = {@link Mode#OFF}</b>: 现有工厂开工/完工完全照旧。
 *
 * <p>机制归系统、策略按厂配 (Steve 2026-11-10); 工厂纪律类场景默认只提示不硬拦, 所以「拦截」要工厂显式选。
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "first_article_policies")
public class FirstArticlePolicy {

    public enum Mode {
        /** 关: 不查首检, 开工/完工与没有这项功能时完全一样 (默认)。 */
        OFF,
        /** 提示: 要首检的工序还没首检合格时, 开工/完工照常, 返回里带一句提示。 */
        WARN,
        /** 拦截: 要首检的工序没首检合格时, 不许报完工 (409 FIRST_ARTICLE_NOT_PASSED); 开工不拦 (首件要开工后才做得出来)。 */
        BLOCK
    }

    @Id
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private Mode mode = Mode.OFF;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
