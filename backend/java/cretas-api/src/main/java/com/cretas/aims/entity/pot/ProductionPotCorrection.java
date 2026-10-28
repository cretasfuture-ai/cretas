package com.cretas.aims.entity.pot;

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
 * S3-16 锅次更正留痕: 每次更正 / 确认 / 作废一行, 只追加 (V20261103_121)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "production_pot_corrections")
@SQLDelete(sql = "UPDATE production_pot_corrections SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class ProductionPotCorrection extends BaseEntity {

    public static final String ACTION_CORRECT = "CORRECT";
    public static final String ACTION_CONFIRM = "CONFIRM";
    public static final String ACTION_VOID = "VOID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "pot_id", nullable = false)
    private Long potId;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "field_name", nullable = false, length = 64)
    private String fieldName;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "corrected_by")
    private Long correctedBy;

    @Column(name = "corrected_by_name", length = 100)
    private String correctedByName;

    @Column(name = "corrected_at", nullable = false)
    private LocalDateTime correctedAt;
}
