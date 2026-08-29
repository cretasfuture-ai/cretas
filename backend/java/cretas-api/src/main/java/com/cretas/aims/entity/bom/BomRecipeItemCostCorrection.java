package com.cretas.aims.entity.bom;

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

/** Price-only correction ledger for an immutable, current ACTIVE packaging BOM row. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bom_recipe_item_cost_corrections", indexes = {
        @Index(name = "idx_bricc_item", columnList = "bom_recipe_item_id, corrected_at"),
        @Index(name = "idx_bricc_factory", columnList = "factory_id, corrected_at")
})
public class BomRecipeItemCostCorrection {
    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "bom_recipe_id", nullable = false, length = 191)
    private String bomRecipeId;

    @Column(name = "bom_recipe_item_id", nullable = false)
    private Long bomRecipeItemId;

    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    @Column(name = "old_unit_price", precision = 15, scale = 4)
    private BigDecimal oldUnitPrice;

    @Column(name = "new_unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal newUnitPrice;

    @Column(name = "old_item_cost", precision = 15, scale = 4)
    private BigDecimal oldItemCost;

    @Column(name = "new_item_cost", nullable = false, precision = 15, scale = 4)
    private BigDecimal newItemCost;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "corrected_by", nullable = false)
    private Long correctedBy;

    @Column(name = "corrected_by_role", nullable = false, length = 50)
    private String correctedByRole;

    @Column(name = "corrected_at", nullable = false)
    private LocalDateTime correctedAt;

    @PrePersist
    void prePersist() {
        if (correctedAt == null) correctedAt = LocalDateTime.now();
    }
}
