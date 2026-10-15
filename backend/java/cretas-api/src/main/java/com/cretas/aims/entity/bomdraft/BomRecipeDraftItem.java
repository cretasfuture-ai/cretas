package com.cretas.aims.entity.bomdraft;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/** One exact material/output line in a Blueprint-owned BOM draft. */
@Entity
@Table(name = "bom_recipe_draft_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bom_draft_item_identity",
                columnNames = {"id", "draft_id", "factory_id"}),
        @UniqueConstraint(name = "uk_bom_draft_item_ordinal",
                columnNames = {"draft_id", "factory_id", "ordinal"}),
        @UniqueConstraint(name = "uk_bom_draft_item_port",
                columnNames = {"draft_id", "factory_id", "workflow_process_node_id", "workflow_port_id"})
}, indexes = @Index(name = "idx_bom_draft_item_parent",
        columnList = "factory_id,draft_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BomRecipeDraftItem extends BaseEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(name = "material_ref_type", length = 30, nullable = false, updatable = false)
    private String materialRefType;

    @Column(name = "material_ref_id", length = 191, nullable = false, updatable = false)
    private String materialRefId;

    @Column(name = "material_snapshot_digest", length = 64, nullable = false, updatable = false)
    private String materialSnapshotDigest;

    @Column(name = "material_category", length = 20, nullable = false)
    private String materialCategory;

    @Column(name = "standard_quantity", precision = 20, scale = 8, nullable = false)
    private BigDecimal standardQuantity;

    @Column(length = 20, nullable = false)
    private String unit;

    @Column(name = "yield_percent", precision = 7, scale = 4, nullable = false)
    private BigDecimal yieldPercent;

    @Column(name = "price_unit", length = 20)
    private String priceUnit;

    @Column(name = "quantity_to_price_factor", precision = 24, scale = 12)
    private BigDecimal quantityToPriceFactor;

    @Column(name = "is_optional", nullable = false)
    private Boolean optional;

    @Column(name = "workflow_material_node_id", length = 128, nullable = false)
    private String workflowMaterialNodeId;

    @Column(name = "workflow_process_node_id", length = 128, nullable = false)
    private String workflowProcessNodeId;

    @Column(name = "workflow_port_id", length = 128, nullable = false)
    private String workflowPortId;

    @Column(name = "workflow_edge_id", length = 128, nullable = false)
    private String workflowEdgeId;

    @Column(name = "workflow_port_direction", length = 10, nullable = false)
    private String workflowPortDirection;

    @Column(name = "change_reason", length = 500, nullable = false)
    private String changeReason;
}
