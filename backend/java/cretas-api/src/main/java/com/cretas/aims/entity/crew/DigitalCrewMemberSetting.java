package com.cretas.aims.entity.crew;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 本厂是否启用某位数字员工 (V20261115_61)。没有行 = 启用。 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "digital_crew_member_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_digital_crew_member_settings",
                columnNames = {"factory_id", "member_key"}))
public class DigitalCrewMemberSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "member_key", length = 20, nullable = false)
    private String memberKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_by_name", length = 100)
    private String updatedByName;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
