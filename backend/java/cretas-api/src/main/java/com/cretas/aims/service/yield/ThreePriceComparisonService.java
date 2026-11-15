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
     * 三列<b>同一口径、同一组私有 helper</b> (目录价 = {@code ProductType.unitPrice},
     * 实际成本 = 最近一条已算出 unitCost 的批次, 毛利率 = (价 - 成本) / 价 × 100)。
     *
     * <p>不做任何兜底: 缺目录价 / 无已定价批次 → 对应字段 null, 毛利率 null, 由调用方说明缺哪一边。
     * 不查标准成本 (只有 compareBySku 的超支判定才需要)。
     */
    @Transactional(readOnly = true)
    public CatalogMargin catalogMarginFor(String factoryId, ProductType pt) {
        BigDecimal salesPrice = pt.getUnitPrice();
        // 理论毛利的成本不取「最近一批」(单批产量异常会把毛利带到 -101%, 2026-11-15 财务复跑 Q16),
        // 取最近 CATALOG_COST_BATCHES 批已算单位成本的批次, 按实际产量加权; 没有产量的批次权重按等权处理。
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
                recent == null ? 0 : recent.size(),
                latestPriced != null ? latestPriced.getUnitCost() : null);
    }

    /** 理论毛利取最近几批的加权单位成本。 */
    public static final int CATALOG_COST_BATCHES = 5;

    /** 近 N 批单位成本: 有实际产量的按产量加权 (Σ单位成本×产量 ÷ Σ产量); 全都没有产量时取简单平均; 没有批次 → null。 */
    static BigDecimal weightedUnitCost(List<ProductionBatch> batches) {
        if (batches == null || batches.isEmpty()) return null;
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (ProductionBatch b : batches) {
            if (b.getUnitCost() == null) continue;
            n++;
            sum = sum.add(b.getUnitCost());
            BigDecimal q = b.getActualQuantity();
            if (q != null && q.signum() > 0) {
                weighted = weighted.add(b.getUnitCost().multiply(q));
                qty = qty.add(q);
            }
        }
        if (n == 0) return null;
        return qty.signum() > 0
                ? weighted.divide(qty, 4, RoundingMode.HALF_UP)
                : sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
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
