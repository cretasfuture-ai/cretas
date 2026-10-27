package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 半成品库存 (WIP) 行 — 只读视图 (GET /production/batches/{id}/wip 输出)。
 *
 * <p>RN / web-admin 展示该批次每道工序的中间品存量: 工序 / 产出 / 已领 / 余额 / 状态。
 * 镜像 {@link com.cretas.aims.entity.SemiFinishedInventory} 字段, 不暴露 version / 软删等内部列。</p>
 *
 * <p>设计依据: docs/superpowers/specs/2026-06-02-f006-wip-yield-G6G7G8-design.md 第二/三章 (G6/G7)。
 * Wave 4 (前端 UI) 新增此只读端点供看板/报工领用展示。</p>
 *
 * <p><b>⚠️ 成本字段 (unitCost / productionDate) 的暴露口径 (by-design, 勿误改)</b>:
 * 工厂级快照 {@code GET /{factoryId}/semi-finished/inventory} 有两种调用:
 * <ul>
 *   <li><b>无过滤</b> (看板 / 半成品重量库存页 {@code warehouse/semi-finished/list.vue}) —
 *       客户明确要求"只做重量库存", 仓管员只看可用量, <b>{@code unitCost} 一律 null</b> (脱敏)。</li>
 *   <li><b>带 {@code productTypeId} 过滤</b> (逐道录入 SFI 投料下拉 {@code processSheet.getSemiFinishedInventory}) —
 *       picker context, <b>返回真实 {@code unitCost}</b> (诚实 null = 成本未知)。</li>
 * </ul>
 * 即"要成本走过滤变体"。此差异是刻意的 C3 口径, 见
 * {@link com.cretas.aims.service.wip.impl.WipInventoryServiceImpl#listWipByFactory(String, String, Integer)}
 * 的 {@code pickerContext} 分支。<b>不要给无过滤变体加成本</b> —— 会把成本泄漏到重量库存看板。
 * 如需给无过滤看板加成本, 应显式加 opt-in 参数 (如 {@code ?withCost=true}), 默认保持脱敏。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WipRowDTO {

    /** 工序批次号 (天然唯一键; RN 报工领用时作 sourceWipNo 传回)。 */
    private String intermediateBatchNo;

    /** 哪道工序任务产出 (关联 WorkProcessTask.id)。 */
    private Long sourceWorkProcessTaskId;

    /** 工序序 (第几道, 升序)。 */
    private Integer processOrder;

    /** 工序名 (焯水/卤制...; join 回填, 查不到留 null)。 */
    private String processName;

    /** 产品类型 id (冗余溯源)。 */
    private String productTypeId;

    /** 该道总产出 (Σ 该 task output, 跨天累加)。 */
    private BigDecimal producedQuantity;

    /** 已被下道领走 (Σ 下道引用本 WIP 的 input)。 */
    private BigDecimal consumedQuantity;

    /**
     * 余额 = produced − consumed (G7 结余; 下道领用 :max)，<b>已减去 {@link #pendingFeedQuantity}</b>。
     *
     * <p>🔴 2026-09-06 起这一栏是「现在还能领多少」，不是「账面还剩多少」。两者的差就是
     * {@code pendingFeedQuantity} —— 别的计划已经录进逐道投料、但要到小结/结单才真的扣的那部分。
     * 要拿账面原值请用 {@code producedQuantity − consumedQuantity}（再加 adjustment），
     * 或把这一栏加回 {@code pendingFeedQuantity}。
     */
    private BigDecimal availableQuantity;

    /**
     * 已被别的计划录入、但尚未结算扣减的投料量（本行原生单位；无待扣 = 0，不是 null）。
     *
     * <p>它是 {@code availableQuantity} 被减掉的那个数，单独端出来是为了让界面能解释
     * 「为什么这个批次余量比昨天少 / 为什么是 0」。⛔ 它<b>不是</b>一笔占用：没有人持有它、
     * 也没有释放动作，它只是「已经承诺出去的量」的即时投影。
     */
    private BigDecimal pendingFeedQuantity;

    /**
     * 这一行至少有一条待扣投料<b>折算不出来</b>（计数单位来源缺每盒克重 / 引用批次已消失）。
     *
     * <p>🔴 为真时 {@code availableQuantity} 是<b>偏高</b>的：那部分待扣量既没算进
     * {@code pendingFeedQuantity}，也没从余量里减掉 —— 因为诚实地说，我们不知道它是多少。
     * ⚠️ {@code false} 说的是「没有算不出来的引用」，不是「余量一定准」。
     */
    private boolean pendingFeedUnresolved;

    /**
     * 待扣量<b>超过</b>了账面可用量 —— 账实漂移，不是舍入误差。
     *
     * <p>成因只有三种：超投、有一笔扣减丢了、待扣被算重了。三种都要人去查账，⛔ 不是「换个批次继续干」。
     *
     * <p>🔴 为真时 {@code availableQuantity} 被 floor 到 0，而 <b>0 和「这批货正好领完了」长得一模一样</b>。
     * 这一栏就是把两者分开的那个位：真正被承诺掉的量在 {@link #pendingFeedQuantity} 里，
     * 它比账面可用量还大。后端同时打了 WARN，但界面不能指望有人去读日志。
     */
    private boolean pendingFeedExceedsAvailable;

    /** 单位。 */
    private String unit;

    /** 状态: AVAILABLE / DEPLETED / RETURNED。 */
    private String status;

    /**
     * 质检放行状态 (缺口 11, 镜像 {@code SemiFinishedInventory.inspectionStatus}):
     * null = 不需检 / PENDING = 待检 (下拉里显示「待检」且不可选) / RELEASED / REJECTED。
     */
    private String inspectionStatus;

    /**
     * 缺口 11 (审查高 2): 这一行里待检的数量 (inspectionStatus=PENDING 时才有值)。
     * 可领 = availableQuantity − inspectionPendingQuantity; 已放行的存量照常可领。
     */
    private BigDecimal inspectionPendingQuantity;
    // ==================== C3: 工厂级半成品重量库存视图扩展字段 ====================

    /**
     * C3: 产品类型名称 (join 回填; 工厂级视图用, 批次级视图留 null)。
     */
    private String productTypeName;

    /**
     * C3: 生产批次 ID (工厂级视图回填; 批次级视图留 null 因已在路径参数中)。
     */
    private Long batchId;

    // ── 双出成率 (段1: F006 需求) ──────────────────────────────────────────────

    /**
     * 对上工序出成率 = 本道产出 / 本道投入 (小数形式, 如 0.8889 = 88.89%)。
     * 镜像 StepYieldDTO.yieldRate。单位不可比 → null (不臆造)。
     */
    private BigDecimal stepYieldRate;

    /**
     * 对原料累计出成率 = 本道产出(折首道单位) / 首道投入量 (小数形式)。
     * 镜像 StepYieldDTO.cumulativeYieldRate。跨单位无折算 → null。
     */
    private BigDecimal cumulativeYieldRate;

    // ── ② 批次下拉补 生产日期 / 成本 (逐道 SFI 投料下拉用; 仅过滤查询[picker]填充) ──

    /**
     * ② 生产日期 (取自 SemiFinishedInventory.createdAt.toLocalDate)。仅逐道 SFI 投料下拉 (带 productTypeId 过滤)
     * 填充; 无参 (看板/盘点) 快照留 null (C3 视图口径不变)。
     */
    private LocalDate productionDate;

    /**
     * ② 单位成本 (SemiFinishedInventory.unitCost) — 逐道投料下拉展示成本。
     * 🔴 诚实 null: 未接通成本的半成品 → null (前端显示"成本未知")。
     * <b>仅</b>逐道投料下拉 (带 productTypeId 过滤) 填充; 无参 (看板/盘点) 快照<b>不</b>暴露成本 (C3 口径不变)。
     */
    private BigDecimal unitCost;

    /**
     * ② 每盒/份标准克重 (取自 {@link com.cretas.aims.entity.ProductType#getGramsPerUnit()}, "1 份/盒 = X 克")。
     * 计数单位 (盒/个/件/只) 半成品作 kg 道投料来源时, 前端据此把 kg⇄盒 折算 (余 N 盒 ≈ M kg)。
     * 🔴 诚实 null: 未配置每盒克重 → null (前端据此拦截盒装投料, 禁止臆造)。
     * <b>仅</b>逐道投料下拉 (带 productTypeId 过滤 = pickerContext) 填充; 无参快照留 null (C3 口径不变)。
     */
    private BigDecimal gramsPerUnit;

    // ── 回料 (不良品回投) ──────────────────────────────────────────────────

    /**
     * 这一行装的是不是回料 (镜像 {@code SemiFinishedInventory.isRework})。
     * 回料行在下拉里与良品半成品长得一模一样, 而它们的可选性规则不同 ——
     * 前端靠这一栏区分, 不要再拿工序号去猜。
     */
    private Boolean isRework;

    /**
     * 回料行的纯度判定标记。<b>非回料行 / 无目标产品的快照 → null</b>。
     *
     * <ul>
     *   <li>{@code ENFORCED} —— 两侧都配了纯度等级, 这道闸真的判过它且放行了</li>
     *   <li>{@code UNCONFIGURED} —— 至少一侧没配纯度等级 ⇒ <b>未经纯度判定</b>就放行了</li>
     * </ul>
     * 被拦下的行不会出现在结果里, 所以这里没有 BLOCKED 档。
     *
     * <p>🔴 {@code UNCONFIGURED} 是诚实标记而不是占位符: 它说的是「我没判」,
     * 不是「我判过了没问题」。两者对操作员完全不同 —— 前者该提醒去配纯度等级。
     */
    private String reworkPurityStatus;
}