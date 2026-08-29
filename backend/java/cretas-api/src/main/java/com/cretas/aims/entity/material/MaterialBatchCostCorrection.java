package com.cretas.aims.entity.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 原料批次估值更正台账。批次单价属于库存成本事实，任何更正必须同时保存新旧值和原因。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "material_batch_cost_corrections",
        indexes = {
                @Index(name = "idx_mbcc_batch", columnList = "material_batch_id, corrected_at"),
                @Index(name = "idx_mbcc_factory", columnList = "factory_id, corrected_at")
        })
public class MaterialBatchCostCorrection {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    /** null 表示更正前尚未估值。 */
    @Column(name = "old_unit_price", precision = 18, scale = 6)
    private BigDecimal oldUnitPrice;

    @Column(name = "new_unit_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal newUnitPrice;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "corrected_by", nullable = false)
    private Long correctedBy;

    @Column(name = "corrected_by_role", length = 50)
    private String correctedByRole;

    @Column(name = "corrected_at", nullable = false)
    private LocalDateTime correctedAt;

    @PrePersist
    void prePersist() {
        if (correctedAt == null) correctedAt = LocalDateTime.now();
    }
}
