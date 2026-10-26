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

import java.math.BigDecimal;

/**
 * 半成品待检分笔账 (缺口 11 第二轮审查 H2/H3/M1)。
 *
 * <p>一行半成品库存 ({@code semi_finished_inventory}) 按产品码 / 计划锚跨批次累加多笔入账, 行上只有一个
 * 总的待检数量。撤销、驳回、数量更正某一笔时, 只能解除<b>这一笔还没放行</b>的那部分待检 —— 否则
 * 「A 已放行、B 待检, 删掉 A」会把 B 的待检抵消掉, B 未经放行就能领。
 *
 * <p>每笔扣成待检的入账记一行: {@link #reportId} (App 报工) 或 {@link #holdRef} (逐道录入凭据) 认出是哪一笔;
 * 都认不出的入账撤销时一律不解除待检 (保守)。{@link #quantityOpen} 是仍未放行的量, 质检放行 / 判不合格时整行清零。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "semi_qc_hold_lots",
        indexes = @Index(name = "idx_semi_qc_hold_lots_semi", columnList = "factory_id,semi_finished_id"))
public class SemiQcHoldLot extends BaseEntity {

    /**
     * 分笔账不删除: 放行 / 判不合格 / 撤销都是把 {@link #quantityOpen} 改小或清零 ({@code SemiQcHoldLots}),
     * 记录本身保留 (撤销时据它认笔)。不挂类级 {@code @Where}, 意外删除直接拦下 (写法同 BatchLineageEdge)。
     */
    @jakarta.persistence.PreRemove
    protected void preventRemove() {
        throw new UnsupportedOperationException("SemiQcHoldLot is kept for attribution; removal not allowed");
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "semi_finished_id", nullable = false)
    private Long semiFinishedId;

    /** App 报工入账: 报工 id。 */
    @Column(name = "report_id")
    private Long reportId;

    /**
     * 这一笔入账所属的生产批次 (App 报工的批次; 逐道录入为空, 它的行是单计划锚)。
     * 放行前按「当前未放行的笔」反查计划判质检 FAILED (第三轮审查 H-1)。
     */
    @Column(name = "production_batch_id")
    private Long productionBatchId;

    /** 逐道录入入账: 凭据 (PS:计划:工序:行)。 */
    @Column(name = "hold_ref", length = 160)
    private String holdRef;

    /** 这一笔入账时扣成待检的量。 */
    @Column(name = "quantity_held", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantityHeld;

    /** 仍未放行的量 (撤销按它解除; 放行/判不合格清零)。 */
    @Column(name = "quantity_open", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantityOpen;
}
