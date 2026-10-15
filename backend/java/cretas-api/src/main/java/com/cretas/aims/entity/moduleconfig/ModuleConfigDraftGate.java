package com.cretas.aims.entity.moduleconfig;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

/** Typed expansion from one customer module to one existing backend gate code. */
@Entity
@Table(name = "module_config_draft_gates",
        uniqueConstraints = @UniqueConstraint(name = "uk_module_config_draft_gate_code",
                columnNames = {"draft_id", "gate_code"}))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ModuleConfigDraftGate extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "draft_item_id", length = 36, nullable = false, updatable = false)
    private String draftItemId;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(name = "module_id", length = 20, nullable = false, updatable = false)
    private String moduleId;

    @Column(name = "gate_ordinal", nullable = false, updatable = false)
    private Integer gateOrdinal;

    @Column(name = "gate_code", length = 64, nullable = false, updatable = false)
    private String gateCode;

    @Column(name = "desired_enabled", nullable = false)
    private Boolean desiredEnabled;
}
