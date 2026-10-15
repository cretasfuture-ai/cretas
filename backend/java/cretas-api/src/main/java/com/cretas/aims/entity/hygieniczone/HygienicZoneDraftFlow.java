package com.cretas.aims.entity.hygieniczone;

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

/** Explicit people, material or air flow row owned only by a hygienic zoning draft. */
@Entity
@Table(name = "hygienic_zone_draft_flows", uniqueConstraints = {
        @UniqueConstraint(name = "uk_hygienic_zone_draft_flow_edge",
                columnNames = {"draft_id", "flow_type", "from_zone_code", "to_zone_code"}),
        @UniqueConstraint(name = "uk_hygienic_zone_draft_flow_ordinal", columnNames = {"draft_id", "ordinal"})
})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class HygienicZoneDraftFlow extends BaseEntity {
    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;
    @Column(name = "flow_type", length = 20, nullable = false)
    private String flowType;
    @Column(name = "from_zone_code", length = 40, nullable = false)
    private String fromZoneCode;
    @Column(name = "to_zone_code", length = 40, nullable = false)
    private String toZoneCode;
    @Column(name = "direction", length = 20, nullable = false)
    private String direction;
    @Column(name = "crossing_policy", length = 20, nullable = false)
    private String crossingPolicy;
    @Column(name = "control_point", length = 30, nullable = false)
    private String controlPoint;
    @Column(name = "rationale", length = 500, nullable = false)
    private String rationale;
}
