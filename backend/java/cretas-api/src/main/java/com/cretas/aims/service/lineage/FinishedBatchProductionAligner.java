package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 成品批次 → 生产批次的对齐 —— <b>只此一份</b>。
 *
 * <h2>问题本身</h2>
 *
 * <p>{@code finished_goods_batches} <b>没有 production_batch_id 这一列</b>，只有
 * {@code production_plan_id}。而一个生产计划下可以有多个生产批次，所以
 * 「这批成品是哪个生产批次产出的」这个信息<b>在数据里不一定存在</b>。
 *
 * <h2>判据（禁止降级）</h2>
 *
 * <ol>
 *   <li>计划下只有一个正式生产批次 → 就是它，唯一确定。</li>
 *   <li>多个批次 → 按成品的 {@code productTypeId} 再筛一次；筛剩唯一 → 是它。</li>
 *   <li>筛完仍 &gt; 1 或为 0 → <b>对不齐</b>。⛔ 不挑一个「看起来最像」的塞进去 ——
 *       那会造出一条事实上不存在的血缘边，而血缘边是 append-only 的，写错了删不掉。
 *       返回空，由调用方计入「对不齐行数」并报出来。</li>
 * </ol>
 *
 * <p>⚠️ 这个类<b>不能</b>被复制到回填脚本里。回填与在线路径必须用同一份对齐规则，
 * 否则两边对不齐的集合不同，而这件事没有任何测试会变红（形态 D）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinishedBatchProductionAligner {

    private final ProductionBatchRepository productionBatchRepository;

    /**
     * 对齐结果 —— 三态。{@code batch} 为空时，{@code ambiguous} 区分
     * 「计划下压根没有生产批次（没有载体）」与「有多个但分不出是哪个（对不齐）」。
     */
    public record Alignment(Optional<ProductionBatch> batch, boolean ambiguous, int candidateCount, String method) {

        /** 计划下唯一 / 按产品筛剩唯一。 */
        public static final String BY_PLAN = "PLAN";
        /** 成品批次带的生产批次号精确命中（同厂、同计划）。 */
        public static final String BY_BATCH_NUMBER = "BATCH_NUMBER";

        public static Alignment resolved(ProductionBatch batch, int candidateCount) {
            return resolved(batch, candidateCount, BY_PLAN);
        }

        public static Alignment resolved(ProductionBatch batch, int candidateCount, String method) {
            return new Alignment(Optional.of(batch), false, candidateCount, method);
        }

        public static Alignment noCarrier() {
            return new Alignment(Optional.empty(), false, 0, null);
        }

        public static Alignment ambiguous(int candidateCount) {
            return new Alignment(Optional.empty(), true, candidateCount, null);
        }

        /** 对不齐（有候选但分不出）→ AMBIGUOUS；没有候选 → SKIPPED。 */
        public LineageRecordOutcome asFailureOutcome() {
            return ambiguous ? LineageRecordOutcome.AMBIGUOUS : LineageRecordOutcome.SKIPPED;
        }
    }

    /**
     * @param productionPlanId       成品批次上记的计划 id
     * @param finishedProductTypeId  成品批次的产品类型（多批次时用来二次筛选，可为 null）
     */
    public Alignment align(String factoryId, String productionPlanId, String finishedProductTypeId) {
        return align(factoryId, productionPlanId, finishedProductTypeId, null);
    }

    /**
     * 带「产出它的生产批次号」线索的对齐。
     *
     * <p>第 0 条规则（先于计划级规则）：线索非空，且本厂有<b>同号</b>生产批次，且该批次挂在<b>同一个计划</b>上
     * → 就是它。这是精确键，不是猜：结单「按终端产出逐行入库」的成品批次号就是报工生产批次号
     * （{@code production_settlement_output_lines.reported_batch_number}）。
     *
     * <p>⚠️ 计划不一致时<b>不采纳</b>线索（同号但别的计划的批次 = 撞号，不是同一件事），退回计划级规则。
     * 线索对不上任何批次也退回计划级规则 —— 那是「线索无效」，不是「对不齐」。
     *
     * @param producingBatchNumberHint 可为 null（等价于三参版本）
     */
    public Alignment align(String factoryId, String productionPlanId, String finishedProductTypeId,
                           String producingBatchNumberHint) {
        if (isBlank(factoryId) || isBlank(productionPlanId)) {
            return Alignment.noCarrier();
        }
        if (!isBlank(producingBatchNumberHint)) {
            Optional<ProductionBatch> exact = productionBatchRepository
                    .findByFactoryIdAndBatchNumber(factoryId, producingBatchNumberHint.trim());
            if (exact.isPresent() && Objects.equals(exact.get().getProductionPlanId(), productionPlanId)) {
                return Alignment.resolved(exact.get(), 1, Alignment.BY_BATCH_NUMBER);
            }
        }
        List<ProductionBatch> candidates =
                productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, productionPlanId);
        if (candidates == null || candidates.isEmpty()) {
            return Alignment.noCarrier();
        }
        if (candidates.size() == 1) {
            return Alignment.resolved(candidates.get(0), 1);
        }
        if (!isBlank(finishedProductTypeId)) {
            List<ProductionBatch> sameProduct = candidates.stream()
                    .filter(b -> Objects.equals(b.getProductTypeId(), finishedProductTypeId))
                    .toList();
            if (sameProduct.size() == 1) {
                return Alignment.resolved(sameProduct.get(0), candidates.size());
            }
        }
        log.warn("[lineage] 成品→生产批次对不齐, 不写边(⛔ 不猜): factory={} plan={} productType={} 候选={}",
                factoryId, productionPlanId, finishedProductTypeId, candidates.size());
        return Alignment.ambiguous(candidates.size());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
