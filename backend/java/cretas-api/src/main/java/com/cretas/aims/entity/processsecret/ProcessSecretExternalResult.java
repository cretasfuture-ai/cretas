package com.cretas.aims.entity.processsecret;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * B 档: 客户侧执行端签名回传的批次结果。只登记、待核验; ⛔ 不直接改库存或成本。
 * 同一 (factoryId, eventId) 只收一次: 相同内容幂等返回, 不同内容拒绝。
 */
@Entity
@Table(name = "process_secret_external_results", uniqueConstraints = {
        @UniqueConstraint(name = "uq_process_secret_result_event", columnNames = {"factory_id", "event_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSecretExternalResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "package_id", nullable = false)
    private Long packageId;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "production_batch_id", nullable = false)
    private Long productionBatchId;

    @Column(name = "output_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal outputQuantity;

    @Column(name = "output_unit", nullable = false, length = 20)
    private String outputUnit;

    @Column(name = "signed_at", nullable = false)
    private LocalDateTime signedAt;

    @Column(name = "payload_digest", nullable = false, length = 64)
    private String payloadDigest;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private ProcessSecretResultReviewStatus reviewStatus = ProcessSecretResultReviewStatus.PENDING_REVIEW;

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @PrePersist
    void onCreate() {
        if (receivedAt == null) receivedAt = LocalDateTime.now();
        if (reviewStatus == null) reviewStatus = ProcessSecretResultReviewStatus.PENDING_REVIEW;
    }
}
