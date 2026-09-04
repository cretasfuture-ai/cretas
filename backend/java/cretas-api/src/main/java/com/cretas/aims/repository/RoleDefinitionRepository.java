package com.cretas.aims.repository;

import com.cretas.aims.entity.config.RoleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 角色定义表 Repository — 见 {@link RoleDefinition} 的迁移背景说明.
 *
 * @author Cretas Team
 * @since 2026-09-04
 */
@Repository
public interface RoleDefinitionRepository extends JpaRepository<RoleDefinition, Integer> {

    /** role_code 全局唯一 (uk_role_definitions_role_code). */
    Optional<RoleDefinition> findByRoleCode(String roleCode);
}
