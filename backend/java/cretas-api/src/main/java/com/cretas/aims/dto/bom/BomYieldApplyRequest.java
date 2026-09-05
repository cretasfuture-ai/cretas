package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 单行 BOM 出成率应用请求.
 *
 * <p>POST /api/mobile/{factoryId}/bom/yield-estimate/recalculate-apply 的请求体元素.
 * 调用方只提交用户明确选中的行.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomYieldApplyRequest {

    /** 当前 ACTIVE/current bom_recipes.id。 */
    @NotNull(message = "recipeId 不能为空")
    private String recipeId;

    /**
     * 要写入的成品<b>标准出成率</b>(百分比)。写进 {@code bom_recipes.overall_yield_rate}。
     * 前端应直接传 suggestedYieldRate (已 ×100).
     *
     * <h3>合法区间: {@code (0, 300]}</h3>
     * <ul>
     *   <li>🔴 {@code 100.00} <b>合法</b>(零损耗)。2026-09-06 之前它被当成「未配置」哨兵,
     *       填 100 等于把备料折算静默关掉 —— 那条路已经拆掉, 见
     *       {@code ProductionPlanServiceImpl#resolveEffectiveProductYieldRate}。</li>
     *   <li>&gt; 100 <b>合法</b>: 保水/腌制等工序出成率天然超过 100
     *       (六膳门猪舌保水实测 105–126%), 所以没有 ≤100 上限。</li>
     *   <li>{@code 300} 是<b>防手滑上限</b>, 不是工艺上限 —— 3 倍已经远超任何保水工艺,
     *       到这个数几乎一定是把小数点点错了 (e.g. 把 0.9375 当成 93.75 又 ×100)。</li>
     * </ul>
     *
     * <p>⚠️ 三层同一条规则, 改一处必须改另外两处(形态 D):
     * 本 DTO 的 {@code @DecimalMin/@DecimalMax} / 服务层
     * {@code BomYieldEstimateServiceImpl#recalculateApply} 的显式守卫 /
     * 数据库 {@code bom_recipes.chk_br_yield}(由 {@code V20261101_32} 放宽到 300)。
     * ⛔ 只改 DTO 不改 CHECK, 会在 PG 上 500 而 H2 单测全绿(H2 profile 关了 Flyway)。
     * 前端 {@code web-admin/src/views/production/YieldStandardView.vue} 的输入校验同此上限。
     */
    @NotNull(message = "yieldRate 不能为空")
    @DecimalMin(value = "0.01", message = "yieldRate 必须 > 0")
    @DecimalMax(value = "300.00", message = "yieldRate 不能超过 300%")
    private BigDecimal yieldRate;

    /**
     * M10 乐观并发保护: 调用方期望的当前 DB 值 (来自上次预览结果的 {@code currentYieldRate}).
     *
     * <p>若提供, 应用前会对比数据库实际值; 不一致 → 整批返回 HTTP 409 + staleRows 列表.
     * null 表示调用方不做并发检查 (向后兼容旧前端).
     */
    private BigDecimal expectedCurrentYieldRate;
}
