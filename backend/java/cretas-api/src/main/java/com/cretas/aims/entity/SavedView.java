package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;

/**
 * S6-01 保存的动态业务视图。只存查询定义, 不存结果行 (见 V20261103_106)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "saved_views")
@Where(clause = "deleted_at IS NULL")
public class SavedView extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "entity_key", nullable = false, length = 50)
    private String entityKey;

    @Column(name = "definition_json", nullable = false, columnDefinition = "TEXT")
    private String definitionJson;

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "shared", nullable = false)
    private Boolean shared = false;

    @PrePersist
    void stampOnCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (getCreatedAt() == null) setCreatedAt(now);
        if (getUpdatedAt() == null) setUpdatedAt(now);
    }

    @PreUpdate
    void stampOnUpdate() {
        setUpdatedAt(LocalDateTime.now());
    }
}
