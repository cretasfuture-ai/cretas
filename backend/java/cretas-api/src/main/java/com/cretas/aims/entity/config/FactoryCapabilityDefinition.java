package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable, versioned definition of one cross-module factory capability.
 *
 * This catalog describes dependency and configuration boundaries. It may point
 * at module_schemas, but it never replaces that table's field/workflow/permission
 * authority.
 */
@Entity
@Table(name = "factory_capability_definitions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryCapabilityDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "catalog_version", nullable = false, length = 64)
    private String catalogVersion;

    @Column(name = "capability_id", nullable = false, length = 64)
    private String capabilityId;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "derivation_strategy", nullable = false, length = 32)
    private String derivationStrategy;

    @Type(JsonType.class)
    @Column(name = "depends_on", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> dependsOn = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "match_keywords", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> matchKeywords = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "configuration_types", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> configurationTypes = new ArrayList<>();

    @Type(JsonType.class)
    @Column(name = "module_schema_codes", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> moduleSchemaCodes = new ArrayList<>();

    /**
     * Validated factory archetypes for which this definition is applicable.
     * v1 rows intentionally keep this empty so their immutable digest and
     * historical meaning remain unchanged.
     */
    @Type(JsonType.class)
    @Column(name = "sector_profiles", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> sectorProfiles = new ArrayList<>();

    /** Version-pinned authority identifiers from the Stage A research ledger. */
    @Type(JsonType.class)
    @Column(name = "authority_refs", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> authorityRefs = new ArrayList<>();

    /** Stable keys mapping this definition back to the food-safety matrix. */
    @Type(JsonType.class)
    @Column(name = "requirement_codes", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> requirementCodes = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
