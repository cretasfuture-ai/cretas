package com.cretas.aims.entity.crew;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一线岗位 → 数字员工 (F-crew-gaps D4, 原型是代码常量, 正式端按厂配置; V20261115_61)。
 * 没有行 = 用默认对应 ({@code DigitalCrewCatalog#DEFAULT_ROLE_MAPPING}); member_key 为空 = 该岗位不给入口。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "digital_crew_role_mappings",
        uniqueConstraints = @UniqueConstraint(name = "uk_digital_crew_role_mappings",
                columnNames = {"factory_id", "frontline_role"}))
public class DigitalCrewRoleMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "frontline_role", length = 50, nullable = false)
    private String frontlineRole;

    @Column(name = "member_key", length = 20)
    private String memberKey;

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
