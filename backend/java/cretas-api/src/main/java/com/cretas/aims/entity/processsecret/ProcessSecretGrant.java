package com.cretas.aims.entity.processsecret;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDateTime;

/** 保密负责人签发给某个人、某个产品、限时、可撤销的工艺授权。 */
@Entity
@Table(name = "process_secret_grants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSecretGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 64)
    private String productTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grantee_type", nullable = false, length = 30)
    private ProcessSecretGranteeType granteeType;

    @Column(name = "grantee_id", nullable = false)
    private Long granteeId;

    @Builder.Default
    @Column(name = "can_view_full", nullable = false)
    private Boolean canViewFull = false;

    @Builder.Default
    @Column(name = "can_export", nullable = false)
    private Boolean canExport = false;

    @Builder.Default
    @Column(name = "can_edit", nullable = false)
    private Boolean canEdit = false;

    @Column(name = "purpose", nullable = false, length = 200)
    private String purpose;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isActiveAt(LocalDateTime now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    public boolean allows(ProcessSecretAction action) {
        return switch (action) {
            case VIEW_FULL -> Boolean.TRUE.equals(canViewFull);
            case EXPORT -> Boolean.TRUE.equals(canExport);
            case EDIT -> Boolean.TRUE.equals(canEdit);
            default -> false;
        };
    }
}
