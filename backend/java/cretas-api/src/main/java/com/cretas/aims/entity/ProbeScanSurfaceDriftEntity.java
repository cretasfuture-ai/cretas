package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * ⛔ 临时探针，用完即撤（票 OPS-SOFT-DELETE-SCAN-SURFACE-DRIFTS-ON-NEW-ENTITY-20260907）。
 *
 * <p>它存在的唯一目的：在**真 CI 上**证明新增一个带 {@code deleted_at} 的实体会让
 * `soft-delete-scan-surface` 那道闸变红（= 扫描面漂移在引入它的那个 PR 上就被说出来），
 * 而不是等它合进 main 之后由下一个无关 PR 连坐。
 *
 * <p>与 #672 引入 {@code platform_role_permission_audits} 那次同形：带 {@code @Table}、
 * 带软删列，因此会进入生成器的扫描面并改变 {@code scripts/cron/soft-delete-orphans.sql}。
 *
 * <p>下一个提交会把本文件撤掉，闸随即回绿。
 */
@Entity
@Table(name = "probe_scan_surface_drift")
public class ProbeScanSurfaceDriftEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Long getId() {
        return id;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
