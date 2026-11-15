package com.cretas.aims.entity.outsource;

import com.cretas.aims.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 委外收发明细 (单据与审计)。库存数量的真值在 material_batches / finished_goods_batches,
 * 本表记录「哪个批次、多少、为什么」。
 *
 * <ul>
 *   <li>ISSUE: 我方仓批次 (source) → 委外仓批次 (target)</li>
 *   <li>MATERIAL_RETURN: 委外仓批次 (source) → 原批次 (target)</li>
 *   <li>RECEIVE: 产出批次 (target); 其下若干 CONSUME (parent = 本条) 记耗用的委外仓批次</li>
 *   <li>GOODS_RETURN: 收货批次 (source) 退回委外厂返工; amount 为负 (加工费冲减)</li>
 *   <li>LOSS: 委外仓批次 (source) 登记损耗, reason 必填</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "outsource_movements")
@SQLDelete(sql = "UPDATE outsource_movements SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class OutsourceMovement extends BaseEntity {

    public enum Type { ISSUE, MATERIAL_RETURN, RECEIVE, CONSUME, GOODS_RETURN, LOSS }

    public enum BatchKind { MATERIAL, FINISHED }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "line_id", nullable = false, length = 64)
    private String lineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20)
    private Type movementType;

    @Column(name = "parent_movement_id", length = 64)
    private String parentMovementId;

    @Column(name = "biz_date", nullable = false)
    private LocalDate bizDate;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    @Column(name = "source_batch_id", length = 191)
    private String sourceBatchId;

    @Column(name = "target_batch_id", length = 191)
    private String targetBatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_batch_kind", length = 16)
    private BatchKind targetBatchKind;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(name = "defect_quantity", precision = 18, scale = 6)
    private BigDecimal defectQuantity;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "ap_transaction_id", length = 191)
    private String apTransactionId;

    @Column(name = "quality_return_order_id", length = 191)
    private String qualityReturnOrderId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "client_request_id", length = 64)
    private String clientRequestId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
