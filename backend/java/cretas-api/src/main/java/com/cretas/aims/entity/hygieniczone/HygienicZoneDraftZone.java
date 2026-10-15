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

/** Typed zone row owned only by a hygienic zoning draft. */
@Entity
@Table(name = "hygienic_zone_draft_zones", uniqueConstraints = {
        @UniqueConstraint(name = "uk_hygienic_zone_draft_zone_code", columnNames = {"draft_id", "zone_code"}),
        @UniqueConstraint(name = "uk_hygienic_zone_draft_zone_ordinal", columnNames = {"draft_id", "ordinal"})
})
@org.hibernate.annotations.SQLDelete(sql = "UPDATE hygienic_zone_draft_zones SET deleted_at = NOW() WHERE id = ?", check = org.hibernate.annotations.ResultCheckStyle.COUNT)
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class HygienicZoneDraftZone extends BaseEntity {
    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "draft_id", length = 36, nullable = false, updatable = false)
    private String draftId;
    @Column(name = "factory_id", length = 50, nullable = false, updatable = false)
    private String factoryId;
    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;
    @Column(name = "zone_code", length = 40, nullable = false)
    private String zoneCode;
    @Column(name = "zone_name", length = 120, nullable = false)
    private String zoneName;
    @Column(name = "hygiene_level", length = 30, nullable = false)
    private String hygieneLevel;
    @Column(name = "purpose", length = 500, nullable = false)
    private String purpose;
    @Column(name = "temperature_class", length = 30, nullable = false)
    private String temperatureClass;
    @Column(name = "entry_policy", length = 30, nullable = false)
    private String entryPolicy;
    @Column(name = "change_reason", length = 500, nullable = false)
    private String changeReason;
}
