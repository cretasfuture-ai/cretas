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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * B 档: 客户侧工艺包的云端登记。只有不可展开的引用、版本、产出单位和执行端公钥;
 * ⛔ 不含配方、比例、步骤、参数或附件。
 */
@Entity
@Table(name = "process_secret_external_packages", uniqueConstraints = {
        @UniqueConstraint(name = "uq_process_secret_package_version", columnNames = {"factory_id", "package_ref", "package_version"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessSecretExternalPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 100)
    private String productTypeId;

    @Column(name = "package_ref", nullable = false, length = 64)
    private String packageRef;

    @Column(name = "package_version", nullable = false, length = 64)
    private String packageVersion;

    @Column(name = "output_unit", nullable = false, length = 20)
    private String outputUnit;

    /** 客户侧执行端的 Ed25519 公钥 (X.509 SubjectPublicKeyInfo, Base64)。私钥只在客户侧。 */
    @Column(name = "executor_public_key", nullable = false, length = 200)
    private String executorPublicKey;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProcessSecretPackageStatus status = ProcessSecretPackageStatus.ACTIVE;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = ProcessSecretPackageStatus.ACTIVE;
    }
}
