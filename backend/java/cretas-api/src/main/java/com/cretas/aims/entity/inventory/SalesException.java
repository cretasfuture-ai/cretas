package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.SalesExceptionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Where;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 销售异常单 —— <b>被拦下的超发尝试</b>的留痕。
 *
 * <h2>它记的是「没发生的事」</h2>
 * {@code SalesServiceImpl.assertWithinOverShipCap} 抛 409 之后, 那次发货确认整体回滚:
 * 库存没扣、单据没变、订单状态没动。<b>本表是那次尝试留下的唯一痕迹。</b>
 *
 * <p>⚠️ 所以 {@link #attemptedQuantity} 读作「仓管<b>想</b>填的实发量」，
 * ⛔ 不要读成「实际发出去的量」—— 那个数不存在, 这一单没发。
 *
 * <h2>⛔ 为什么不复用 {@code PurchaseException}</h2>
 * 见 {@code V20261101_20__sales_exceptions.sql} 文件头的完整论证。一句话:
 * 那张表的 {@code receive_record_id} / {@code purchase_order_id} 是 NOT NULL 且
 * 销售链上没有对应物, 而更硬的一条是<b>生命周期不同</b> ——
 * 采购异常是既成事实需要 ACCEPT/RETURN 决策, 销售超发是被拒绝的请求, 没有东西可决策。
 *
 * <h2>本表<b>刻意</b>没有决策流程</h2>
 * 没有 {@code decision} / {@code decisionBy} / {@code returnOrderId}。
 * 加一组恒为 null 的决策列会让读到它的人以为背后有一条流程 ——
 * 而实际上没有任何入口会去填它们。留白比留一组死列诚实。
 *
 * <h2>事务边界</h2>
 * 本实体由 {@code SalesExceptionServiceImpl} 在 {@code REQUIRES_NEW} 独立事务里写入,
 * 因为写它的那一刻外层事务<b>注定要回滚</b>(下一行就抛 409)。
 * 与外层同事务 = 什么都留不下来。详见该 impl 的类注释。
 *
 * @since 2026-08-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "sales_exceptions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uidx_sales_exceptions_number",
                        columnNames = {"factory_id", "exception_number"})
        },
        indexes = {
                @Index(name = "idx_sales_exceptions_factory", columnList = "factory_id"),
                @Index(name = "idx_sales_exceptions_delivery", columnList = "delivery_record_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class SalesException extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /**
     * 单号 {@code SE-<factoryId>-<yyyyMMdd>-<0001>}。
     *
     * <p>🔴 唯一约束是<b>全量</b>的 (⛔ 不带 {@code WHERE deleted_at IS NULL}):
     * 号一旦发出永不回收, 软删的行仍然占着它。理由见迁移文件头 ——
     * 本仓 57 个发号器里 20 个坏在「被占用了吗」被软删答成「有没有活着的行」。
     */
    @NotBlank
    @Column(name = "exception_number", nullable = false, length = 50)
    private String exceptionNumber;

    @NotBlank
    @Column(name = "delivery_record_id", nullable = false, length = 191)
    private String deliveryRecordId;

    /** 可读发货单号 (冗余存储)。⛔ 不要只存 UUID —— 采购异常屏就是这么把 UUID 显示给用户的。 */
    @Column(name = "delivery_number", length = 50)
    private String deliveryNumber;

    /** 出事的发货行 id。可空: 理论上行 id 为 null 的行不会走到这道闸, 但不用 NOT NULL 去赌。 */
    @Column(name = "delivery_item_id")
    private Long deliveryItemId;

    @Column(name = "sales_order_id", length = 191)
    private String salesOrderId;

    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    @Column(name = "product_name", length = 200)
    private String productName;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false, length = 32)
    private SalesExceptionType exceptionType;

    /** 计划发货量 (判据的基准)。 */
    @NotNull
    @Column(name = "planned_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal plannedQuantity;

    /** 仓管<b>想</b>填的实发量。⛔ 不是发出去的量 —— 这一单被拒绝了。 */
    @NotNull
    @Column(name = "attempted_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal attemptedQuantity;

    /** 当时的上限 = {@code plannedQuantity × (1 + limitRate)}。 */
    @Column(name = "max_allowed_quantity", precision = 15, scale = 4)
    private BigDecimal maxAllowedQuantity;

    @Column(name = "unit", length = 20)
    private String unit;

    /**
     * 当时生效的上限比率 ({@code OverReceiveLimit.rate()})。
     *
     * <p>可空是给将来留的: 今天走到本表的路径一定有非空 rate
     * (不限制时闸直接放行, 根本不会到这里)。
     */
    @Column(name = "limit_rate", precision = 9, scale = 6)
    private BigDecimal limitRate;

    /**
     * 上限的<b>来源</b> ({@code OverReceiveLimit.source()})。
     *
     * <p>🔴 这一列是本表最有用的一列: 没有它, 「上限是 10%」分不清是工厂自己配的、
     * ops 全局兜底的, 还是代码默认的 —— 而处理一条「为什么我发不了货」的投诉时,
     * 答案通常就在这一行。
     */
    @NotBlank
    @Column(name = "limit_source", nullable = false, length = 120)
    private String limitSource;

    /**
     * 今天只有 {@code "BLOCKED"}。
     *
     * <p>⚠️ 它不是「待处理」—— 这里<b>没有</b>待办, 发货已经被拒绝了。
     */
    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_BLOCKED;

    public static final String STATUS_BLOCKED = "BLOCKED";

    /** 操作人 (仓管)。可空: 部分内部调用路径没有 userId, ⛔ 不兜底成 0 顶一个假用户。 */
    @Column(name = "attempted_by")
    private Long attemptedBy;

    @NotNull
    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
