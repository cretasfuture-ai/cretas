package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.ThreePriceSkuDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.bom.CostVarianceService;
import com.cretas.aims.service.bom.StandardCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 六扇门 D1 延伸: per-SKU「三价对比」看板 — 标准BOM成本 vs 销售价 vs 实际成本。
 *
 * <p><b>不新造成本口径</b>: 完全复用已在生产中运行的超支报警引擎两个服务:
 * <ul>
 *   <li>{@link StandardCostService#resolveStandardUnitCost} — 同口径标准成本
 *       (料 + 研发预估标准人工, 与 {@code OrderCostAlarmListener} 完全一致的口径)</li>
 *   <li>{@link CostVarianceService} — 超支阈值解析 + 方差百分比计算</li>
 * </ul>
 * 本服务只是把这套已跑在生产环境里的推送引擎口径, 以「看板」形式按 SKU 逐一展示,
 * 让财审/销售主管不必等超支才被动收到推送, 也能主动查看全量 SKU 的当前状态。
 *
 * <p><b>实际成本来源</b>: 最近一条已算出 {@code unitCost} 的完工批次
 * ({@link ProductionBatch#getUnitCost()}, 由实体 {@code calculateMetrics()} 诚实计算 = 金额 ÷ 产出数量,
 * 即「元/产出单位」, 跨单位批次也照算 (D-W-01); 缺成本输入时为 null)。取「最近一条」而非加权平均, 与超支报警引擎按单批次触发的
 * 语义保持一致 (报警引擎也是每次单批产出触发, 不做历史加权)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreePriceComparisonService {

    private static final int PCT_SCALE = 2;

    private final ProductTypeRepository productTypeRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final StandardCostService standardCostService;
    private final CostVarianceService costVarianceService;

    /**
     * @param factoryId      工厂ID
     * @param maskPrice      true=价格脱敏 (procurement:price:view 权限缺失, 金额字段置 null)
     * @param overBudgetOnly true=只返回当前超支 (variancePct > threshold) 的 SKU
     * @param category       可选: 按 {@code ProductType.category} 过滤
     * @return per-SKU 三价对比行, 超支优先排序 (overBudget desc, variancePct desc, name asc)
     */
    @Transactional(readOnly = true)
    public List<ThreePriceSkuDTO> compareBySku(String factoryId, boolean maskPrice,
                                                boolean overBudgetOnly, String category) {
        List<ProductType> products = (category != null && !category.isBlank())
                ? productTypeRepository.findByFactoryIdAndCategory(factoryId, category)
                : productTypeRepository.findByFactoryIdAndIsActiveTrue(factoryId);

        List<ThreePriceSkuDTO> rows = new ArrayList<>();
        for (ProductType pt : products) {
            if (pt.getId() == null || Boolean.FALSE.equals(pt.getIsActive())) {
                continue;
            }

            StandardCostService.StandardUnitCost std =
                    standardCostService.resolveStandardUnitCost(factoryId, pt.getId());
            BigDecimal standardCost = std.getTotalUnitCost();

            ProductionBatch latestPriced = resolveLatestPricedBatch(factoryId, pt.getId());
            BigDecimal actualCost = latestPriced != null ? latestPriced.getUnitCost() : null;

            BigDecimal variancePct = costVarianceService.computeVariancePct(actualCost, standardCost);
            BigDecimal threshold = costVarianceService.resolveThreshold(factoryId, pt.getId());
            // 诚实: 标准成本口径不全 (variancePct=null) → 不判超支, 与推送引擎口径一致 (不误报)
            boolean overBudget = variancePct != null && variancePct.compareTo(threshold) > 0;

            if (overBudgetOnly && !overBudget) {
                continue;
            }

            BigDecimal salesPrice = pt.getUnitPrice();
            BigDecimal grossMargin = computeGrossMarginPct(salesPrice, actualCost);

            rows.add(ThreePriceSkuDTO.builder()
                    .productTypeId(pt.getId())
                    .productName(pt.getName())
                    .productCode(pt.getCode())
                    .productCategory(pt.getCategory())
                    .unit(pt.getUnit())
                    .standardCost(maskPrice ? null : standardCost)
                    .salesPrice(maskPrice ? null : salesPrice)
                    .taxIncludedSalesPrice(maskPrice ? null : pt.getTaxIncludedUnitPrice())
                    .actualCost(maskPrice ? null : actualCost)
                    .variancePct(maskPrice ? null : variancePct)
                    .threshold(maskPrice ? null : threshold)
                    .grossMargin(maskPrice ? null : grossMargin)
                    .overBudget(overBudget)
                    .caliberHint(std.getCaliberHint())
                    .actualCostAsOfBatchNumber(latestPriced != null ? latestPriced.getBatchNumber() : null)
                    .actualCostAsOf(latestPriced != null ? latestPriced.getCreatedAt() : null)
                    .build());
        }

        rows.sort(Comparator
                .comparing((ThreePriceSkuDTO r) -> Boolean.TRUE.equals(r.getOverBudget()) ? 0 : 1)
                .thenComparing(r -> r.getVariancePct() == null ? BigDecimal.valueOf(Long.MIN_VALUE) : r.getVariancePct(),
                        Comparator.reverseOrder())
                .thenComparing(r -> r.getProductName() == null ? "" : r.getProductName()));

        return rows;
    }

    /**
     * 单个 SKU 的「目录价理论毛利」: 与 {@link #compareBySku} 的 salesPrice / actualCost / grossMargin
     * 目录价 = {@code ProductType.unitPrice}, 毛利率 = (价 - 成本) / 价 × 100 与 compareBySku 同一公式;
     * <b>实际成本口径不同</b>: 本方法 (AI 问答的理论毛利) 取近 {@link #CATALOG_COST_BATCHES} 批加权, 避免单批异常;
     * compareBySku (三价对比看板) 仍取最近一条已算出 unitCost 的批次, 与超支报警引擎单批语义一致。
     *
     * <p>不做任何兜底: 缺目录价 / 无已定价批次 → 对应字段 null, 毛利率 null, 由调用方说明缺哪一边。
     * 不查标准成本 (只有 compareBySku 的超支判定才需要)。
     */
    @Transactional(readOnly = true)
    public CatalogMargin catalogMarginFor(String factoryId, ProductType pt) {
        BigDecimal salesPrice = pt.getUnitPrice();
        // 理论毛利的成本不取「最近一批」(单批产量异常会把毛利带到 -101%, 2026-11-15 财务复跑 Q16),
        // 取最近 CATALOG_COST_BATCHES 批已算单位成本的批次, 加权口径 Σ总成本 ÷ Σ实际产量, 与成本查询工具同分母。
        List<ProductionBatch> recent = productionBatchRepository
                .findRecentPricedBatches(factoryId, pt.getId(), PageRequest.of(0, CATALOG_COST_BATCHES));
        ProductionBatch latestPriced = recent == null || recent.isEmpty() ? null : recent.get(0);
        BigDecimal actualCost = weightedUnitCost(recent);
        return new CatalogMargin(
                salesPrice,
                actualCost,
                computeGrossMarginPct(salesPrice, actualCost),
                latestPriced != null ? latestPriced.getBatchNumber() : null,
                latestPriced != null ? latestPriced.getCreatedAt() : null,
                weightedCostBatchCount(recent),
                latestPriced != null ? latestPriced.getUnitCost() : null);
    }

    /**
     * 理论毛利取最近几批的加权单位成本。取 5 批: 一批小批量或试产会把单批口径带偏, 5 批足以摊平, 又不至于把半年前的老价格混进来;
     * 这是一个经验值, 没有时间窗 (批次稀少的产品 5 批可能跨很久, 回复里「近 N 批」+ 批次日期可核对)。
     */
    public static final int CATALOG_COST_BATCHES = 5;

    /**
     * 单批的单位成本分母: 实际产量 (> 0); 没有实际产量才退到良品数; 都没有 → null。
     * 与 ReportCostQueryTool 的单位成本同口径 (成本分析页: 总成本 ÷ 实际产量, 不用良品数), 让同一个产品在
     * 「卤牛肉单位成本」和「卤牛肉理论毛利」两个问法里得到同一个单位成本。注意: 这和 ProductionBatch.unitCost
     * (良品数优先) 不是同一个分母, 所以这里用 总成本 ÷ 分母 重算, 不用 unitCost × 产量。
     */
    private static BigDecimal costDenominator(ProductionBatch b) {
        BigDecimal actual = b.getActualQuantity();
        if (actual != null && actual.signum() > 0) return actual;
        BigDecimal good = b.getGoodQuantity();
        return good != null && good.signum() > 0 ? good : null;
    }

    /** 单批的总成本: 优先用批次总成本; 缺则用 单位成本 × 分母 还原; 都没有 → null。 */
    private static BigDecimal batchTotalCost(ProductionBatch b, BigDecimal denom) {
        if (b.getTotalCost() != null) return b.getTotalCost();
        if (b.getUnitCost() != null && denom != null) return b.getUnitCost().multiply(denom);
        return null;
    }

    /**
     * 近 N 批单位成本 = Σ总成本 ÷ Σ实际产量 (见 {@link #costDenominator}), 与成本查询工具同口径。
     * 没有任何批次带产量时退回有单位成本批次的简单平均; 没有批次 → null。
     */
    static BigDecimal weightedUnitCost(List<ProductionBatch> batches) {
        if (batches == null || batches.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal denomSum = BigDecimal.ZERO;
        BigDecimal simpleSum = BigDecimal.ZERO;
        int simpleN = 0;
        for (ProductionBatch b : batches) {
            if (b.getUnitCost() == null) continue;
            simpleN++;
            simpleSum = simpleSum.add(b.getUnitCost());
            BigDecimal d = costDenominator(b);
            BigDecimal t = batchTotalCost(b, d);
            if (d != null && t != null) {
                total = total.add(t);
                denomSum = denomSum.add(d);
            }
        }
        if (simpleN == 0) return null;
        return denomSum.signum() > 0
                ? total.divide(denomSum, 4, RoundingMode.HALF_UP)
                : simpleSum.divide(BigDecimal.valueOf(simpleN), 4, RoundingMode.HALF_UP);
    }

    /** 实际参与 {@link #weightedUnitCost} 的批次数: 有分母和总成本的批次; 一批都没有时才数简单平均用到的批次 (有单位成本的)。 */
    static int weightedCostBatchCount(List<ProductionBatch> batches) {
        if (batches == null) return 0;
        int weighted = 0;
        int simple = 0;
        for (ProductionBatch b : batches) {
            if (b.getUnitCost() == null) continue;
            simple++;
            BigDecimal d = costDenominator(b);
            if (d != null && batchTotalCost(b, d) != null) weighted++;
        }
        return weighted > 0 ? weighted : simple;
    }

    /** {@link #catalogMarginFor} 的结果; 毛利率为百分数 (42.5 = 42.5%), 任一价缺失 → null。actualCost = 近 costBatchCount 批的加权单位成本。 */
    public record CatalogMargin(BigDecimal salesPrice, BigDecimal actualCost, BigDecimal grossMarginPct,
                                String costBatchNumber, java.time.LocalDateTime costAsOf,
                                int costBatchCount, BigDecimal latestBatchUnitCost) {
    }

    private ProductionBatch resolveLatestPricedBatch(String factoryId, String productTypeId) {
        List<ProductionBatch> recent = productionBatchRepository
                .findRecentPricedBatches(factoryId, productTypeId, PageRequest.of(0, 1));
        return recent.isEmpty() ? null : recent.get(0);
    }

    /**
     * 毛利率 % = (salesPrice - actualCost) / salesPrice × 100.
     * 诚实 null: 任一价缺失或 salesPrice<=0 → null (不臆造).
     */
    private BigDecimal computeGrossMarginPct(BigDecimal salesPrice, BigDecimal actualCost) {
        if (salesPrice == null || actualCost == null || salesPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return salesPrice.subtract(actualCost)
                .divide(salesPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(PCT_SCALE, RoundingMode.HALF_UP);
    }
}
