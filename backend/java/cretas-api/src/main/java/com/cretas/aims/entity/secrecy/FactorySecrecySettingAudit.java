package com.cretas.aims.entity.secrecy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 保密配置改动留痕 (V20261104_21), 只追加 (库里触发器拒绝 UPDATE; 实体没有 setter)。
 * 值: {@code UNSET} (改之前没有行) / {@code ON} / {@code OFF} / {@code FOLLOW} (跟随工艺保密)。
 */
@Entity
@Table(name = "factory_secrecy_setting_audit")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorySecrecySettingAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50, updatable = false)
    private String factoryId;

    @Column(name = "item_code", nullable = false, length = 40, updatable = false)
    private String itemCode;

    @Column(name = "old_value", nullable = false, length = 10, updatable = false)
    private String oldValue;

    @Column(name = "new_value", nullable = false, length = 10, updatable = false)
    private String newValue;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "actor_role", nullable = false, length = 50, updatable = false)
    private String actorRole;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "confirmed", nullable = false, updatable = false)
    private boolean confirmed;

    /** 来自「全开 / 全关」(逐项写入, 每项一行)。 */
    @Column(name = "bulk", nullable = false, updatable = false)
    private boolean bulk;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;
}
