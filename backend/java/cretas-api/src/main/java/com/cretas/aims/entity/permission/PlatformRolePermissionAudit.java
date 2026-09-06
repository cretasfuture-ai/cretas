package com.cretas.aims.entity.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * L1 {@code platform_role_permissions} 每次单格写入的追加审计 (step-2, 2026-09-06)。
 *
 * <p>为什么不用行上的 {@code updated_by}: 那是 last-write-wins, 改十次只剩最后一次;
 * 权限是「谁在什么时候把谁的什么权限从 A 改成 B」这种必须能回溯的东西。
 *
 * <p>⛔ 不继承 {@link com.cretas.aims.entity.BaseEntity}: 审计是 append-only, 没有软删,
 * 表上也没有 created_at / updated_at / deleted_at 三列 —— prod 跑 Hibernate validate,
 * 实体多一列就起不来 (#643 的教训)。列定义与 {@code V20261101_37} 逐列一致。
 */
@Entity
@Table(name = "platform_role_permission_audit", indexes = {
        @Index(name = "idx_prp_audit_role_module", columnList = "role_code, module_code"),
        @Index(name = "idx_prp_audit_changed_at", columnList = "changed_at")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PlatformRolePermissionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "module_code", nullable = false, length = 32)
    private String moduleCode;

    /** NULL = 原来没有这一行 ('-' 是「明确不给」, 两者下游后果相反, ⛔ 不要兜成 '-'). */
    @Column(name = "old_level", length = 8)
    private String oldLevel;

    @Column(name = "new_level", nullable = false, length = 8)
    private String newLevel;

    /** NOT NULL: token 取不到用户就拒绝写, 不写 NULL. */
    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "changed_by_role", length = 64)
    private String changedByRole;

    /** 从哪个工厂的页面改的 (L1 本身是全平台的). */
    @Column(name = "factory_id", length = 32)
    private String factoryId;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}
