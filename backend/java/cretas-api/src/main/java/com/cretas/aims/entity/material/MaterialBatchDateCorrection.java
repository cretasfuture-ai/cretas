package com.cretas.aims.entity.material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 批次日期更正台账 —— 字段级留痕，<b>带新旧值</b>。
 *
 * <p>2026-08-20 owner 裁定「入库日期也要管」。把 {@code receiptDate} 纳入账实红线之后，
 * 全仓没有任何能更正它的正路（{@code /adjust} 只动数量，盘点 apply 写的也是数量差异），
 * 所以锁与正路必须同一次落地。写入方只有一个：
 * {@code POST /material-batches/{batchId}/receipt-date-correction}。
 *
 * <p><b>⛔ 为什么不复用 {@code MaterialBatchAdjustment}</b>：那张表的
 * quantityBefore / adjustmentQuantity / quantityAfter 三列都是 NOT NULL，拿它记日期更正
 * 只能写三个 0 —— 数量台账里从此躺着一批「0 → 0」的调整行，而真正变了的东西只能塞进
 * reason 自由文本。「新旧值」就退化成了人读的散文，机器再也对不了账。
 *
 * <p><b>⛔ 为什么不继承 {@code BaseEntity}</b>：BaseEntity 带 {@code @SQLDelete} 软删 +
 * 类级 {@code @Where(deleted_at IS NULL)}，Hibernate 会把它拼进每一条 JPQL。审计行不该能
 * 被软删，更不该在「这条批次的日期被改过几次」这种查询上悄悄少几行。
 * 同类先例：{@code FinishedGoodsAdjustmentLog} 也不继承 BaseEntity。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "material_batch_date_corrections",
        indexes = {
                @Index(name = "idx_mbdc_batch", columnList = "material_batch_id, corrected_at"),
                @Index(name = "idx_mbdc_factory", columnList = "factory_id, corrected_at")
        })
public class MaterialBatchDateCorrection {

    /** 当前唯一允许更正的字段。与迁移里的 {@code chk_mbdc_field} CHECK 白名单逐字对应。 */
    public static final String FIELD_RECEIPT_DATE = "receiptDate";

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    /**
     * 冗余存一份工厂号：台账要能按工厂独立审计，且批次真被删了这条痕迹仍然要在，
     * 不能只靠回 join material_batches 才知道它属于谁。
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    /** 被更正的字段 (DTO 属性名)。DB 侧有 CHECK 白名单，这张表永远只装日期。 */
    @Column(name = "field_name", nullable = false, length = 32)
    private String fieldName;

    /**
     * ⚠️ 可空，且 NULL 的含义是「原来这一格就是空的」，不是「没记下来」。
     * inbound_date 在实体上标了 NOT NULL，所以 receiptDate 这一支实际不会出现 NULL。
     */
    @Column(name = "old_value")
    private LocalDate oldValue;

    @Column(name = "new_value", nullable = false)
    private LocalDate newValue;

    /** 必填。长度与 {@code material_batch_adjustments.reason} 对齐 (varchar 255)。 */
    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "corrected_by", nullable = false)
    private Long correctedBy;

    /**
     * 更正当时的 JWT 角色码。角色事后会变，只存 userId 答不了「他当时凭什么能改」。
     * 可空：拿不到 role 时如实留空，⛔ 不兜底成 'unknown' 之类能被下游当真的值。
     */
    @Column(name = "corrected_by_role", length = 50)
    private String correctedByRole;

    @Column(name = "corrected_at", nullable = false)
    private LocalDateTime correctedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (correctedAt == null) {
            correctedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
