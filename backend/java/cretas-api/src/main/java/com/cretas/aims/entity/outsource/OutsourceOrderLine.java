package com.cretas.aims.entity.outsource;

import com.cretas.aims.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 委外任务行。PROCESS = 工序委外 (必须挂工单 + 工序任务); WHOLE = 整单委外 (不挂工单)。
 *
 * <p>累计量 (已收合格 / 不良 / 退货 / 返工待收 / 加工费) 只由服务端在收发动作里维护。
 * 加工费 = 实收合格数 × {@link #unitFee}; 不良不计费。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "outsource_order_lines")
@Where(clause = "deleted_at IS NULL")
public class OutsourceOrderLine extends BaseEntity {

    public enum OutsourceType { PROCESS, WHOLE }

    /** FINISHED: 回成品批次; SEMI: 回半成品物料批次 (供下道工序领料/投料)。 */
    public enum OutputKind { FINISHED, SEMI }

    public enum Status { OPEN, COMPLETED, CANCELLED }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "outsource_type", nullable = false, length = 16)
    private OutsourceType outsourceType;

    @Column(name = "production_plan_id", length = 191)
    private String productionPlanId;

    @Column(name = "production_batch_id")
    private Long productionBatchId;

    @Column(name = "work_process_task_id")
    private Long workProcessTaskId;

    @Column(name = "process_name", length = 100)
    private String processName;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_kind", nullable = false, length = 16)
    private OutputKind outputKind;

    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    @Column(name = "output_name", length = 200)
    private String outputName;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Column(name = "planned_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal plannedQuantity;

    /** 加工费单价 (含税)。 */
    @Column(name = "unit_fee", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitFee;

    /** 税率, 仅展示 (单价已含税)。 */
    @Column(name = "tax_rate", precision = 6, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "received_good_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal receivedGoodQuantity = BigDecimal.ZERO;

    @Column(name = "received_defect_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal receivedDefectQuantity = BigDecimal.ZERO;

    @Column(name = "returned_goods_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal returnedGoodsQuantity = BigDecimal.ZERO;

    /** 已退回委外厂返工、尚未重新收回的合格品数量 (重新收回时不再耗料)。 */
    @Column(name = "rework_pending_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal reworkPendingQuantity = BigDecimal.ZERO;

    /** 累计净加工费 (收货 + 退货冲减)。 */
    @Column(name = "fee_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /**
     * 剩余应收 (新料加工部分) = 计划 − 已收合格 − 已收不良 − 返工待收 (不小于 0)。
     * 退货时合格数已减、返工待收已加, 所以返工待收要扣掉, 否则同一批货被算成「还要新加工」。
     */
    public BigDecimal remainingToReceive() {
        BigDecimal r = plannedQuantity.subtract(receivedGoodQuantity).subtract(receivedDefectQuantity)
                .subtract(reworkPendingQuantity);
        return r.signum() < 0 ? BigDecimal.ZERO : r;
    }
}
