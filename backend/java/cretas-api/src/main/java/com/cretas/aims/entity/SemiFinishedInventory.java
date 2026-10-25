package com.cretas.aims.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import lombok.*;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 半成品库存 (WIP) — G6 地基 (Phase B 方案1)。
 *
 * <p>每道工序产出 = 一笔有余额的半成品存量行。下道领用它, 未领的留作结余 WIP (张权 G7)。
 * 现状 (Phase A) 只有 {@code carryover_quantity} 单批记录值 + {@code intermediate_batch_no}
 * 工序批次号, 都不是"有余额的库存账"。本实体补这个缺口。
 *
 * <p><b>设计依据</b>: docs/superpowers/specs/2026-06-02-f006-wip-yield-G6G7G8-design.md 第二章方案1。
 *
 * <p><b>权威源职责</b>: WIP 余额 / 领用 / 退回 / 防呆 {@code :max} 全靠本表 (库存权威账)。
 * lineage 边只做溯源副产物, 不做库存计算 (Wave 4)。
 *
 * <p><b>幂等键</b>: {@code intermediate_batch_no} (复用 Phase A 已生成的工序批次号, 工厂内唯一)。
 * 跨天多次报工同一 task 累加 {@code produced_quantity} 到同一行 (Wave 2 接入 submitReport)。
 *
 * <p><b>jsonb 字段</b>: {@code materialBatchRefs} 镜像 {@link ProductionReport#getMaterialBatchRefs()}
 * (同 {@code @Type(JsonType.class)} + {@code columnDefinition="jsonb"}), 从产出报工继承溯源。
 *
 * <p><b>本 Wave 范围</b>: 仅地基 (实体 + 表 + repository)。不改报工/出成率行为 (Wave2/3)。
 */
@Entity
@Table(name = "semi_finished_inventory", indexes = {
        @Index(name = "idx_sfi_factory_batch", columnList = "factory_id, batch_id"),
        @Index(name = "idx_sfi_intermediate_batch_no", columnList = "intermediate_batch_no"),
        @Index(name = "idx_sfi_factory_status", columnList = "factory_id, status")
}, uniqueConstraints = {
        // W8 BUG-SP1-NEW-ROW 修复: 工序批次号工厂内唯一 (幂等键防并发重复 insert)。
        //
        // 生产 PG 的权威约束是 Flyway 的 partial unique index
        //   uq_sfi_intermediate_batch_no ON (factory_id, intermediate_batch_no) WHERE deleted_at IS NULL
        // (允许软删除行 + 一条 live 行同 code)。JPA @UniqueConstraint 无法表达 partial WHERE,
        // 这里声明的是 non-partial composite —— 仅在 H2 测试 (ddl-auto=create-drop) 下建表生效,
        // 让 SP1 new-row 并发竞争触发 ConstraintViolation → 走 catch-retry-into-existing 路径。
        //
        // prod 不受此注解影响: pg-prod=ddl-auto:none (不建), prod=validate (Hibernate validate
        // 不校验/不新增 unique 约束, 仅校验表+列)。prod 防护靠上述 Flyway partial index。
        @UniqueConstraint(name = "uq_sfi_factory_intermediate_batch_no",
                columnNames = {"factory_id", "intermediate_batch_no"})
})
@Data
// toBuilder: 只服务一个用途 —— 投料下拉要给「预扣后的可见余量」造一个【脱管】副本
// (WipInventoryServiceImpl#listAvailableWip)。⛔ 绝不能改被 Hibernate 管着的那一行的
// availableQuantity: 那是只读查询里的脏检查写库, 属于库存事故。逐字段手抄同样能做到,
// 但字段一多就会漏 —— 让 Lombok 跟着字段走。
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SemiFinishedInventory {

    /** WIP 库存状态 */
    public static class Status {
        /** 有余额可领用 */
        public static final String AVAILABLE = "AVAILABLE";
        /** 余额清零 (全部被下道领走) */
        public static final String DEPLETED = "DEPLETED";
        /** 结余余料退回总仓 (D3, Wave 4) */
        public static final String RETURNED = "RETURNED";
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 乐观锁: 并发领用扣减 available_quantity 时防超领 (设计章四风险表) */
    @jakarta.persistence.Version
    @Column(name = "version")
    private Long version;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 生产批次 (关联 ProductionBatch.id) */
    @Column(name = "batch_id")
    private Long batchId;

    /** 工序批次号 — 复用 Phase A 已生成的 (天然幂等键, 工厂内唯一) */
    @Column(name = "intermediate_batch_no", nullable = false, length = 64)
    private String intermediateBatchNo;

    /** 哪道工序任务产出的 (关联 WorkProcessTask.id) */
    @Column(name = "source_work_process_task_id")
    private Long sourceWorkProcessTaskId;

    /** 工序序 (冗余, 便于按序聚合 / 排序) */
    @Column(name = "process_order")
    private Integer processOrder;

    /** 产品类型 id (冗余溯源) */
    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    /**
     * 这一行装的是不是<b>回料</b> (金检/X光剔出的不良品, 要回到下一批靠前的工序重新投入)。
     *
     * <p><b>为什么必须落成一列</b>: 回料对下一批而言是「期初就在库里的料」, 它必须能穿过
     * {@code listWipByFactory} 的<b>阶段过滤</b> ({@code processOrder < maxProcessOrder}, 防同批内回锅)。
     * 而阶段过滤跑在工厂级行集上, 拿不到画布 —— 判据必须在<b>行上</b>可读。
     *
     * <p><b>⛔ 不用把 {@code processOrder} 改成 0 来表达这件事</b>: 那一列还被
     * {@code ProcessSheetServiceImpl.getInventoryYieldCard} 用来<b>排序并串起出成率链</b>
     * (上一行的 produced = 下一行的投入)。回料行排到最前面, 良品那道的出成率会被算成
     * 950/50 = 1900%; 而且 {@code resolveSourceWipNo} 按 {@code processOrder == 本道-1} 找源,
     * 0 会让它对不上任何一道。回料的 processOrder 仍诚实记<b>产出它的那道</b>。
     *
     * <p><b>判据来源唯一</b>: 建行/入账时由 {@code WorkflowReworkNodes} (画布 {@code data.isRework})
     * + {@code workflow_task_ports} 反查得出, ⛔ 不信客户端在 {@code semi_outputs} 里带上来的
     * {@code isRework} (那是给 RN 算 {@code defectQuantity} 用的展示值, 未经服务端校验)。
     * 一旦为 true 不再翻回 false (同一个回料产品的行跨批次复用)。
     */
    @Column(name = "is_rework", nullable = false)
    @Builder.Default
    private Boolean isRework = Boolean.FALSE;

    /** 该道总产出 (= Σ该 task 的 output, 跨天多次报工累加) */
    @Column(name = "produced_quantity", precision = 12, scale = 2)
    private BigDecimal producedQuantity;

    /** 已被下道领走 (Σ下道引用本 WIP 的 input) */
    @Column(name = "consumed_quantity", precision = 12, scale = 2)
    private BigDecimal consumedQuantity;

    /** 余额 = produced − consumed + adjustment (G7 结余; 防呆下道领用 :max) */
    @Column(name = "available_quantity", precision = 12, scale = 2)
    private BigDecimal availableQuantity;

    /**
     * 盘点净修正量 (可正可负, default 0) — 半成品盘点专用。
     *
     * <p>本系统 {@code availableQuantity} 是"派生-on-mutation"字段 (每次 IN/OUT 由
     * {@code producedQuantity − consumedQuantity} 重算)。盘点差异若直接写 availableQuantity
     * 会被后续报工/领用重算覆盖; 且 producedQuantity/consumedQuantity/accumulatedCost/unitCost
     * 喂 {@code VoucherExportServiceImpl} 半成品入库/发出凭证 + unitCost 分母, 盘点不得改动。
     *
     * <p>故盘点差异只累加进本列, 所有 availableQuantity 重算站点纳入本列:
     * <pre>availableQuantity = producedQuantity − consumedQuantity + adjustmentQuantity</pre>
     * → produced/consumed/成本 保持不变 (凭证+成本不变式保住), 修正持久 (重算不丢)。
     */
    @Column(name = "adjustment_quantity", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal adjustmentQuantity = BigDecimal.ZERO;

    /** 滚动累计成本(人工+材料); null=无成本数据 (单元 A.2 地基; 算法在后续任务) */
    @Column(name = "accumulated_cost", precision = 14, scale = 2)
    private BigDecimal accumulatedCost;

    /** 单位成本 = accumulatedCost / producedQuantity; null=无成本数据 (单元 A.2 地基) */
    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "unit", length = 16)
    private String unit;

    /** 状态: AVAILABLE / DEPLETED / RETURNED */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = Status.AVAILABLE;

    /**
     * 溯源 (从产出报工继承)。
     * 格式镜像 {@link ProductionReport#getMaterialBatchRefs()}:
     * [{"materialBatchId": Long, "quantity": Number, "unit": String|null}]。
     * 同用 {@code @Type(JsonType.class)} + {@code columnDefinition="jsonb"}。
     */
    @Type(JsonType.class)
    @Column(name = "material_batch_refs", columnDefinition = "jsonb")
    private List<Map<String, Object>> materialBatchRefs;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ===================== 余额口径 (唯一实现) =====================
    //
    // G02 (2026-10-24): 扣减校验曾用 produced − consumed (漏了 adjustment), 写回却用
    // produced − consumed + adjustment —— 盘亏 −30 时领 60 被放行、写回 −10; 盘盈 +30 时领 100 被错拒。
    // ⇒ 校验 / 扣减 / 撤回 / 流水 balanceAfter 一律走下面这几个方法, ⛔ 不在调用方再手写一遍减法。
    //
    // 方法名刻意不用 get/is 前缀: 本实体在部分接口里被直接序列化, 带 get 前缀会凭空多出 JSON 字段。
    // null 视为 0: 与历史全部重算站点一致 (占位行建行时三列都落 0; adjustment 列 default 0)。

    /** 余额公式本体: {@code produced − consumed + adjustment}。 */
    public static BigDecimal balanceOf(BigDecimal produced, BigDecimal consumed, BigDecimal adjustment) {
        return zeroIfNull(produced).subtract(zeroIfNull(consumed)).add(zeroIfNull(adjustment));
    }

    /** 按当前 produced / consumed / adjustment 现算的余额 (不读 availableQuantity 存量列)。 */
    public BigDecimal computeBalance() {
        return balanceOf(producedQuantity, consumedQuantity, adjustmentQuantity);
    }

    /** 若再消耗 {@code extraConsumed} (原生单位) 之后的余额; 不改任何字段。 */
    public BigDecimal balanceAfterConsuming(BigDecimal extraConsumed) {
        return balanceOf(producedQuantity, zeroIfNull(consumedQuantity).add(zeroIfNull(extraConsumed)),
                adjustmentQuantity);
    }

    /** 把 availableQuantity 刷成公式值并返回 —— produced / consumed 改完之后调用。 */
    public BigDecimal refreshAvailableQuantity() {
        this.availableQuantity = computeBalance();
        return this.availableQuantity;
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
