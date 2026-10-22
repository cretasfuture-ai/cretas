package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;

@Entity
@Table(name = "warehouse_transfer_movements", indexes = {
        @Index(name = "idx_wtm_transfer", columnList = "transfer_id"),
        @Index(name = "idx_wtm_item", columnList = "transfer_item_id"),
        @Index(name = "idx_wtm_request", columnList = "request_key")
}, uniqueConstraints = @UniqueConstraint(name = "uq_wtm_request_item", columnNames = {"request_key", "transfer_item_id"}))
@Where(clause = "deleted_at IS NULL")
@Data @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class WarehouseTransferMovement extends BaseEntity {
    public enum Kind { DISPATCH, RECEIPT }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name="factory_id", nullable=false, length=191) private String factoryId;
    @Column(name="transfer_id", nullable=false, length=191) private String transferId;
    @Column(name="transfer_item_id", nullable=false) private Long transferItemId;
    @Enumerated(EnumType.STRING) @Column(name="kind", nullable=false, length=16) private Kind kind;
    @Column(name="operation_id", nullable=false, length=64) private String operationId;
    @Column(name="request_key", nullable=false, length=64) private String requestKey;
    @Column(name="source_batch_id", length=191) private String sourceBatchId;
    @Column(name="target_batch_id", length=191) private String targetBatchId;
    @Column(name="quantity", nullable=false, precision=18, scale=6) private BigDecimal quantity;
    @Column(name="unit", nullable=false, length=20) private String unit;
    @Column(name="actor_id") private Long actorId;
}
