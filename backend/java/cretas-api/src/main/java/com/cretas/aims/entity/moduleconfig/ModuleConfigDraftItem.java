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

/** One typed customer-facing module decision within a module configuration DRAFT. */
@Entity
@Table(name = "module_config_draft_items",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_module_config_draft_item_module",
                        columnNames = {"draft_id", "module_id"}),
                @UniqueConstraint(name = "uk_module_config_draft_item_ordinal",
                        columnNames = {"draft_id", "ordinal"})
        })
@org.hibernate.annotations.SQLDelete(sql = "UPDATE module_config_draft_items SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ModuleConfigDraftItem extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;

    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private Integer ordinal;

    @Column(name = "module_id", length = 20, nullable = false, updatable = false)
    private String moduleId;

    @Column(name = "module_label", length = 100, nullable = false, updatable = false)
    private String moduleLabel;

    @Column(name = "module_detail", length = 500, nullable = false, updatable = false)
    private String moduleDetail;

    @Column(name = "carrier", length = 32, nullable = false, updatable = false)
    private String carrier;

    @Column(name = "desired_enabled", nullable = false)
    private Boolean desiredEnabled;

    @Column(name = "rationale", length = 500, nullable = false)
    private String rationale;

    @Column(name = "current_frontend_visible", nullable = false)
    private Boolean currentFrontendVisible;

    @Column(name = "current_backend_enabled", nullable = false)
    private Boolean currentBackendEnabled;

    @Column(name = "current_state", length = 24, nullable = false)
    private String currentState;

    @Column(name = "backend_gated", nullable = false)
    private Boolean backendGated;

    @Column(name = "gate_code_count", nullable = false)
    private Integer gateCodeCount;

    @Column(name = "historical_read_policy", length = 48, nullable = false)
    private String historicalReadPolicy;
}
