package com.cretas.aims.repository.permission;

import com.cretas.aims.entity.permission.PlatformRolePermissionAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** L1 单格写入审计 (append-only), 见 {@link PlatformRolePermissionAudit}. */
@Repository
public interface PlatformRolePermissionAuditRepository extends JpaRepository<PlatformRolePermissionAudit, Long> {

    List<PlatformRolePermissionAudit> findTop50ByOrderByChangedAtDesc();

    List<PlatformRolePermissionAudit> findByRoleCodeAndModuleCodeOrderByChangedAtDesc(String roleCode, String moduleCode);
}
