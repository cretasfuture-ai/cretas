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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 一个产品的工艺保密策略。存在即生效: 没有这一行的产品, 系统行为与改造前完全一致。
 *
 * <p>{@code policyVersion} 每次变更 +1, 用于乐观并发与审计对账; 它与业务配方版本无关。</p>
 */
@Entity
@Table(name = "process_secret_policies", uniqueConstraints = {
        @UniqueConstraint(name = "uq_process_secret_policy_product", columnNames = {"factory_id", "product_type_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSecretPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 100)
    private String productTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_mode", nullable = false, length = 30)
    private ProcessSecretStorageMode storageMode;

    /** 客户指定的保密负责人 (工厂用户 id)。 */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Builder.Default
    @Column(name = "policy_version", nullable = false)
    private Long policyVersion = 1L;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (policyVersion == null) policyVersion = 1L;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
