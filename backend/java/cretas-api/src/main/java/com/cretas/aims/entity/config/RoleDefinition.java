package com.cretas.aims.entity.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * 角色定义表 (RBAC 角色注册表), 供 {@link com.cretas.aims.security.DataScopeResolver}
 * 解析 {@link com.cretas.aims.entity.enums.DataScope} 用.
 *
 * <h3>2026-09-04 缺陷修复背景</h3>
 * 此前本表<b>只以裸 SQL 字符串形式存在</b>（{@code DataScopeResolver} 用 {@code JdbcTemplate}
 * 直接拼 {@code SELECT data_scope FROM role_definitions WHERE role_code = ?}），无 JPA
 * entity。表本身从未在 {@code cretas_prod_db} 上被真正建出来 —— 唯一试图建它的
 * Flyway migration（{@code V20260416_00__bootstrap_completeness.sql}）在这台 prod
 * 库上被 baseline（{@code 20260416.99}）挡在了门外, 而后续加 {@code data_scope}
 * 列的 {@code V20260519_08} 是个防御性 {@code IF EXISTS ... ELSE NOTICE} 空转, 于是
 * 生产上<b>每一次</b> role scope 解析都走 {@code relation "role_definitions" does not
 * exist} 报错 → 硬编码 fallback。详见 {@code V20261101_28__create_role_definitions_table.sql}
 * 的迁移注释 + {@link com.cretas.aims.security.DataScopeResolver} 的类注释。
 *
 * <p>升级成真正的 JPA entity 是这次修复的一部分: 让本仓已有的
 * {@code *RepositoryQueryValidationTest} (H2 + Hibernate {@code ddl-auto}) 也能覆盖到
 * 这张表 —— 裸 SQL 字符串够不到那道闸, entity 能。
 *
 * <p>无 {@code BaseEntity} (无 factory_id / deleted_at) —— 这是全局角色注册表, 不分租户,
 * 也不软删.
 */
@Entity
@Table(name = "role_definitions",
       uniqueConstraints = @UniqueConstraint(name = "uk_role_definitions_role_code", columnNames = {"role_code"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** 角色代码, 与 {@link com.cretas.aims.entity.enums.FactoryUserRole} 枚举名一一对应. */
    @Column(name = "role_code", nullable = false, unique = true, length = 50)
    private String roleCode;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "level", nullable = false)
    private Integer level;

    @Column(name = "department", length = 50)
    private String department;

    @Column(name = "is_deprecated")
    private Boolean isDeprecated;

    /**
     * 数据权限范围, 存 {@link com.cretas.aims.entity.enums.DataScope} 的 name()
     * (ALL / DEPT_AND_BELOW / SELF_AND_BELOW / SELF / CUSTOM).
     */
    @Column(name = "data_scope", nullable = false, length = 32)
    private String dataScope;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
