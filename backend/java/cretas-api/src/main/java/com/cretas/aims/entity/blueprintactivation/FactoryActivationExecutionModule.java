package com.cretas.aims.entity.blueprintactivation;

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

/** One module's outcome inside an activation execution. Per-module, never aggregated away. */
@Entity
@Table(name = "factory_activation_execution_modules",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_factory_activation_module_id_factory",
                        columnNames = {"id", "factory_id"}),
                @UniqueConstraint(name = "uk_factory_activation_module_parent_capability",
                        columnNames = {"execution_id", "factory_id", "capability_id"}),
                @UniqueConstraint(name = "uk_factory_activation_module_parent_ordinal",
                        columnNames = {"execution_id", "factory_id", "ordinal"})
        }, indexes = @Index(name = "idx_factory_activation_module_parent",
                columnList = "factory_id,execution_id,ordinal"))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryActivationExecutionModule extends BaseEntity {
    @Id @Column(length = 36, nullable = false, updatable = false) private String id;
    @Column(name = "execution_id", length = 36, nullable = false, updatable = false) private String executionId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false) private String factoryId;
    @Column(nullable = false) private Integer ordinal;
    @Column(name = "capability_id", length = 60, nullable = false) private String capabilityId;
    @Column(name = "status", length = 30, nullable = false) private String status;
    @Column(name = "command_receipt_id", length = 120) private String commandReceiptId;
    @Column(name = "target_runtime_id", length = 120) private String targetRuntimeId;
    @Column(name = "active_revision") private Long activeRevision;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(name = "active_digest", length = 64) private String activeDigest;
    @Column(name = "business_write_count", nullable = false) private Integer businessWriteCount;
    @Column(name = "failure_code", length = 60) private String failureCode;
    @Column(name = "failure_message", length = 500) private String failureMessage;
    @Column(name = "recovery", length = 500) private String recovery;
}
