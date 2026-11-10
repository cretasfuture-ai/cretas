package com.cretas.aims.entity.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 批次放行台账 (纪律排查 A3, Steve 2026-11-10): 批次每一次从「不可投产」(质检中/过期/报废/不良)
 * 变成可投产, 都记一行 —— 谁、从什么状态、经哪条路、因为什么。不论工厂怎么配放行规则, 这一行都要写。
 *
 * <p>⛔ 不继承 BaseEntity: 审计行不能被软删, 也不能在「这批放行过几次」的查询里悄悄少几行
 * (同 {@link MaterialBatchDateCorrection})。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "material_batch_releases",
        indexes = {
                @Index(name = "idx_mbr_batch", columnList = "material_batch_id, released_at"),
                @Index(name = "idx_mbr_factory", columnList = "factory_id, released_at")
        })
public class MaterialBatchRelease {

    /** 放行走的哪条路。与迁移里的 CHECK 白名单逐字对应。 */
    public enum Channel {
        /** 仓库页手动改状态 (PUT /material-batches/{id}/status)。 */
        WAREHOUSE_STATUS_CHANGE,
        /** AI batch_update 工具改状态。 */
        AI_BATCH_UPDATE,
        /** AI 质量主管放行决策 (release_decision)。 */
        QC_RELEASE_DECISION,
        /** 质检单处置判放行。 */
        QC_DISPOSITION,
        /** 质检放行页放行原料收货批次 (D-S411-04)。 */
        QC_RELEASE
    }

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    @Column(name = "from_status", nullable = false, length = 32)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32)
    private String toStatus;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "inspection_id", length = 191)
    private String inspectionId;

    @Column(name = "released_by")
    private Long releasedBy;

    @Column(name = "released_by_role", length = 64)
    private String releasedByRole;

    @Column(name = "released_at", nullable = false)
    private LocalDateTime releasedAt;
}
