package com.cretas.aims.entity.crew;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 数字班组配置的调整记录: 谁、什么时候、改了什么、为什么 (V20261115_61)。只追加。 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "digital_crew_setting_logs")
public class DigitalCrewSettingLog {

    public enum ChangeKind { MEMBERS, ROLE_MAPPING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_kind", length = 20, nullable = false)
    private ChangeKind changeKind;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_name", length = 100, nullable = false)
    private String actorName;

    @Column(name = "reason", length = 200, nullable = false)
    private String reason;

    @Column(name = "before_json", columnDefinition = "TEXT", nullable = false)
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT", nullable = false)
    private String afterJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
