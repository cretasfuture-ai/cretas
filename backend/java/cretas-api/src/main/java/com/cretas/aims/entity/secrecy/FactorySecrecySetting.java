package com.cretas.aims.entity.secrecy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一家工厂的一项保密配置 (V20261104_21)。平台专属: 只有 {@code FactorySecrecySettingsService} 写。
 * 没有行 = 默认值; {@code enabled} 为 null 只出现在 AI_SECRECY_AUDIT 上, 表示「跟随工艺保密」。
 * 不继承 BaseEntity: 配置行不软删, 改动历史在 {@link FactorySecrecySettingAudit}。
 */
@Entity
@Table(name = "factory_secrecy_settings")
@IdClass(FactorySecrecySetting.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorySecrecySetting {

    @Id
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Id
    @Column(name = "item_code", nullable = false, length = 40)
    private String itemCode;

    /** true = 开, false = 关, null = 跟随工艺保密 (只有 AI_SECRECY_AUDIT)。 */
    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @Getter
    public static class Key implements Serializable {
        private String factoryId;
        private String itemCode;
    }
}
