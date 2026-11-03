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
 * 先放这里, 定时按当前名单重判: 过了才写进样本表 (挂起行删除); 提到名单里的词 → REJECTED, 保留给人工复核 (Q5)。
 * {@code payload} 是原话原文 (复审 N3)。除了重判任务没有人读它; 标 REJECTED 时当场换成 {@link #DROPPED_PAYLOAD}
 * (复审第二轮第 1 条: 原文不随复核行永久留着; 迁移里的 ck_tssh_rejected_no_raw 钉住)。
 */
@Entity
@Table(name = "training_sample_secrecy_holds", indexes = {
        @Index(name = "idx_tssh_pending", columnList = "review_status, last_attempt_at, created_at, id"),
        @Index(name = "idx_tssh_factory", columnList = "factory_id")
})
@Getter
@Setter
@NoArgsConstructor
public class TrainingSampleSecrecyHold {

    /** 样本类别: 意图样本 (ai_training_samples) / 低置信样本 (active_learning_samples)。 */
    public enum Kind { INTENT, ACTIVE_LEARNING }

    /** PENDING = 等重判; REJECTED = 重判仍提到名单里的词, 留给人工复核 (不删, 不放行)。 */
    public enum ReviewStatus { PENDING, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "sample_kind", nullable = false, length = 30)
    private Kind sampleKind;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 重判用的原话原文 (JSON)。 */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "reject_reason", length = 40)
    private String rejectReason;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /** REJECTED 行的 payload: 原话原文已丢, 只留原因与时间 (reject_reason / rejected_at)。 */
    public static final String DROPPED_PAYLOAD = "{\"raw_text_dropped\":true}";

    public static TrainingSampleSecrecyHold of(Kind kind, String factoryId, String payload, LocalDateTime now) {
        TrainingSampleSecrecyHold h = new TrainingSampleSecrecyHold();
        h.setSampleKind(kind);
        h.setFactoryId(factoryId);
        h.setPayload(payload);
        h.setReviewStatus(ReviewStatus.PENDING);
        h.setAttempts(0);
        h.setCreatedAt(now);
        return h;
    }
}
