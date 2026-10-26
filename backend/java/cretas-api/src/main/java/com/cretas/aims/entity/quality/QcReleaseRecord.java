package com.cretas.aims.entity.quality;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;

/**
 * 质检放行记录 (缺口 11, owner 2026-10-25) —— 每次「放行 / 判不合格」一行, 只增不改。
 *
 * <p>记录三件事: <b>结论</b> ({@link #decision} + {@link #conclusion})、<b>放行人</b>、<b>放行时间</b>。
 *
 * <h2>为什么不复用 {@code quality_inspections}</h2>
 * 那张表 {@code production_batch_id} / {@code sample_size} / {@code pass_count} / {@code fail_count}
 * 全部 NOT NULL, 而且被看板合格率、质检统计、AI 质检工具直接聚合。放行是「对一批库存做处置决定」,
 * 没有抽样数; 硬塞进去要么编造抽样数 (把假数据灌进合格率), 要么改它的约束 (影响面没验过)。
 * ⇒ 单独一张小表, 由 {@code QcReleaseService} 唯一写入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "qc_release_records",
        indexes = {
                @Index(name = "idx_qc_release_factory_target", columnList = "factory_id,target_type,target_id"),
                @Index(name = "idx_qc_release_factory_time", columnList = "factory_id,released_at")
        })
@Where(clause = "deleted_at IS NULL")
public class QcReleaseRecord extends BaseEntity {

    /** 放行对象的存货形态。 */
    public static final class TargetType {
        /** 常驻半成品库存行 (semi_finished_inventory.id) */
        public static final String SEMI_FINISHED = "SEMI_FINISHED";
        /** 逐道录入的在制半成品批次 (material_batches.id, sourceDocType=PRODUCTION_BATCH) */
        public static final String WIP_MATERIAL_BATCH = "WIP_MATERIAL_BATCH";
        /** 成品批次 (finished_goods_batches.id) */
        public static final String FINISHED_GOODS = "FINISHED_GOODS";

        private TargetType() {
        }
    }

    /** 放行决定。 */
    public static final class Decision {
        public static final String RELEASED = "RELEASED";
        public static final String REJECTED = "REJECTED";

        private Decision() {
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 191)
    private String targetId;

    /** 批次号快照 (成品批号 / 工序批次号 / 在制批号), 给人看和导出用。 */
    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    /** 放行时这批的数量快照 (原生单位) + 单位。 */
    @Column(name = "quantity", precision = 15, scale = 4)
    private java.math.BigDecimal quantity;

    @Column(name = "unit", length = 20)
    private String unit;

    /** RELEASED / REJECTED。 */
    @Column(name = "decision", nullable = false, length = 16)
    private String decision;

    /** 检验结论 (必填, 质检员写的原话)。 */
    @Column(name = "conclusion", nullable = false, columnDefinition = "TEXT")
    private String conclusion;

    @Column(name = "released_by", nullable = false)
    private Long releasedBy;

    @Column(name = "released_by_name", length = 100)
    private String releasedByName;

    @Column(name = "released_at", nullable = false)
    private LocalDateTime releasedAt;
}
