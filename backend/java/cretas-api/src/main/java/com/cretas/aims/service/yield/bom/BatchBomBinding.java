package com.cretas.aims.service.yield.bom;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;

import java.util.Map;
import java.util.Objects;

/**
 * 一个生产批次的成本该按<b>哪一版 BOM</b> 算。
 *
 * <h2>业务口径</h2>
 *
 * <p><b>批次的账按它创建时绑定的那一版走。</b> 8 月做的货，成本就该是 8 月那版配方算出来的；
 * 9 月改了配方，不该让 8 月的账跟着变 —— 否则毛利报表纵向不可比、对账拿不出当时的依据、
 * 报价没有锚。</p>
 *
 * <h2>为什么优先读批次而不是计划</h2>
 *
 * <p>2026-08-27 生产实测：</p>
 * <ul>
 *   <li>批次侧绑定 <b>61/61 全部填了</b>（workflow id/version/revision_hash、bom family、
 *       bom versions by product）</li>
 *   <li>计划侧 43 个计划里<b>只有 36 个</b>有 {@code selected_bom_recipe_id} / {@code version}</li>
 * </ul>
 *
 * <p>而成本归集此前读的是<b>计划</b>那份 —— 覆盖率更低，语义也不对
 * （一个计划可以对多个批次，批次才是「这批货」）。</p>
 *
 * <h2>⚠️ 版本号目前钉不死内容</h2>
 *
 * <p>workflow 侧有 {@code selected_workflow_revision_hash}（<b>内容指纹</b>，改一个字就变）；
 * 而 BOM 侧只有版本号，且 {@code addItem}/{@code updateItem}/{@code deleteItem}
 * <b>都不升版本</b>。实测 58 条明细里 9 条建完之后被改过（ACTIVE 3 条、ARCHIVED 3 条）。
 * ⇒ 同一个版本号下内容可能已经变了。给 BOM 补内容指纹是下一步，本类只解决「读谁的绑定」。</p>
 */
public final class BatchBomBinding {

    private BatchBomBinding() {
    }

    /** 绑定来源 —— ⛔ 必须可观测：读数不带来源，就分不清「批次钉的」和「退回计划的」。 */
    public enum Source { BATCH, PLAN, NONE }

    public record Resolved(String recipeId, Integer version, Source source) {
        public boolean pinned() {
            return recipeId != null && version != null;
        }
    }

    /**
     * 优先取批次创建时钉下的绑定；批次没钉才退回计划。
     *
     * <p>批次侧是<b>按产品</b>存的 map（一个批次理论上可含多产品），
     * 所以要用 {@code batch.productTypeId} 去查。</p>
     */
    public static Resolved resolve(ProductionBatch batch, ProductionPlan plan) {
        if (batch != null) {
            Map<String, String> ids = batch.getSelectedBomRecipeIdsByProduct();
            Map<String, Integer> versions = batch.getSelectedBomVersionsByProduct();
            boolean hasIds = ids != null && !ids.isEmpty();
            boolean hasVersions = versions != null && !versions.isEmpty();
            if (hasIds || hasVersions) {
                if (!hasIds || !hasVersions || !Objects.equals(ids.keySet(), versions.keySet())
                        || ids.values().stream().anyMatch(value -> value == null || value.isBlank())
                        || versions.values().stream().anyMatch(value -> value == null || value <= 0)
                        || batch.getProductTypeId() == null || batch.getProductTypeId().isBlank()) {
                    throw incompleteBatchBinding();
                }
                String productTypeId = batch.getProductTypeId();
                if (ids.containsKey(productTypeId)) {
                    return new Resolved(ids.get(productTypeId), versions.get(productTypeId), Source.BATCH);
                }
                // A non-empty pair of maps is the immutable batch snapshot. A runtime owner/raw
                // placeholder may legitimately be absent from a terminal multi-output A/B map;
                // falling through to the plan scalar would falsely bind it to output A.
                return new Resolved(null, null, Source.NONE);
            }
        }
        if (plan != null && plan.getSelectedBomRecipeId() != null
                && plan.getSelectedBomVersion() != null) {
            return new Resolved(plan.getSelectedBomRecipeId(), plan.getSelectedBomVersion(),
                    Source.PLAN);
        }
        return new Resolved(null, null, Source.NONE);
    }

    private static BusinessException incompleteBatchBinding() {
        return new BusinessException(409, "批次锁定的 BOM 配方与版本映射不完整")
                .withCode("BATCH_BOM_BINDING_INCOMPLETE")
                .withHint("请保留该批次并联系管理员核对创建时的 BOM 快照");
    }

    /**
     * 同一产品的多个批次，绑的 BOM 版本一样吗。
     *
     * <p>不一样就说明<b>期间改过配方</b>，这几批的成本/毛利<b>不可横向比较</b> ——
     * 必须提示，否则「这批毛利低」会被读成生产问题，而实际是换了配方。</p>
     *
     * <p>2026-08-27 实测：`CLAUDEPF-成品甲`/`成品乙` 各 3 个批次绑了 3 种不同的 BOM，
     * 而真实业务产品（黄油鸡 22 批、卤猪蹄 4 批）目前一致。⇒ 已有实例，不是防将来。</p>
     *
     * @return 版本不一致时返回排序去重后的版本列表；一致或不足两批时返回空
     */
    public static java.util.List<Integer> mixedVersions(java.util.Collection<Integer> versions) {
        if (versions == null) {
            return java.util.List.of();
        }
        java.util.TreeSet<Integer> distinct = new java.util.TreeSet<>();
        for (Integer v : versions) {
            if (v != null) {
                distinct.add(v);
            }
        }
        return distinct.size() > 1 ? java.util.List.copyOf(distinct) : java.util.List.of();
    }
}
