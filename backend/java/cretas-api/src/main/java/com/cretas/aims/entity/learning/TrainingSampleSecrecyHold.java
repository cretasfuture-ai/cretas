package com.cretas.aims.entity.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 挂起待重判的训练样本 (Flyway V20261104_11): 本厂名单查不出来、按上次成功的名单判下来没问题的样本,
 * 先放这里, 定时按当前名单重判后才写进样本表。除了重判任务没有人读它。
 */
@Entity
@Table(name = "training_sample_secrecy_holds", indexes = {
        @Index(name = "idx_tssh_created", columnList = "created_at, id"),
        @Index(name = "idx_tssh_factory", columnList = "factory_id")
})
@Getter
@Setter
@NoArgsConstructor
public class TrainingSampleSecrecyHold {

    /** 样本类别: 意图样本 (ai_training_samples) / 低置信样本 (active_learning_samples)。 */
    public enum Kind { INTENT, ACTIVE_LEARNING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "sample_kind", nullable = false, length = 30)
    private Kind sampleKind;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 将要写进样本表的内容 (JSON; 已过内容规则, 未授权工厂已脱敏)。 */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "authorized_at_hold", nullable = false)
    private boolean authorizedAtHold;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    public static TrainingSampleSecrecyHold of(Kind kind, String factoryId, String payload,
                                               boolean authorized, LocalDateTime now) {
        TrainingSampleSecrecyHold h = new TrainingSampleSecrecyHold();
        h.setSampleKind(kind);
        h.setFactoryId(factoryId);
        h.setPayload(payload);
        h.setAuthorizedAtHold(authorized);
        h.setAttempts(0);
        h.setCreatedAt(now);
        return h;
    }
}
