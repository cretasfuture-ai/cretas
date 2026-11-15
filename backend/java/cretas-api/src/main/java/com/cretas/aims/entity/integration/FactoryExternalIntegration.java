package com.cretas.aims.entity.integration;

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
 * 一家工厂对一个外部软件的接口授权 (V20261115_51, 统一原型迁移 P10)。
 * 平台专属: 只有 {@code ExternalIntegrationService} 的平台方法写; 工厂端只读。
 * 没有行或 {@code status != ACTIVE} = 没授权, 外部对照列 / 外部数据页一律不出现。
 * 时间列是服务器本地时间 (与其它配置表一致); 推送令牌只存 SHA-256。
 */
@Entity
@Table(name = "factory_external_integrations")
@IdClass(FactoryExternalIntegration.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryExternalIntegration {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";

    @Id
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** {@link com.cretas.aims.service.integration.ExternalSystem#code()}。 */
    @Id
    @Column(name = "external_system", nullable = false, length = 30)
    private String system;

    /** ACTIVE / SUSPENDED。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 外部系统里的来源组织 id (黑湖工厂 id); 推送时必须一致。 */
    @Column(name = "source_org_id", length = 64)
    private String sourceOrgId;

    @Column(name = "authorized_at", nullable = false)
    private LocalDateTime authorizedAt;

    /** 平台管理员 id。 */
    @Column(name = "authorized_by", nullable = false)
    private Long authorizedBy;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "push_token_sha256", length = 64)
    private String pushTokenSha256;

    @Column(name = "push_token_issued_at")
    private LocalDateTime pushTokenIssuedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @Getter
    public static class Key implements Serializable {
        private String factoryId;
        private String system;
    }
}
