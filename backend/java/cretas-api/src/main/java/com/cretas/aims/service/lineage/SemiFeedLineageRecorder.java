package com.cretas.aims.service.lineage;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * web 逐道录入扣减常驻半成品（SFI）时的写边点 —— 三个调用方共用这<b>一份</b>：
 *
 * <ul>
 *   <li>{@code ProcessSheetServiceImpl} 正式报工严格扣回料（目标 = 本行物化的生产批次）；</li>
 *   <li>{@code InterimSettleServiceImpl} 小结 SFI 投料严格扣减（目标 = 未结行的生产批次）；</li>
 *   <li>{@code ProductionPlanServiceImpl} 结单族 SFI 投料严格扣减（目标 = 行的生产批次）。</li>
 * </ul>
 *
 * <p>边写在<b>调用点</b>而不是 {@code consumeClerkSemiStrict} 里：扣减方法只知道「从哪个库存号扣了多少」，
 * 不知道「扣给了谁」—— 目标批次只有调用方有。
 *
 * <h2>三种「没写」分开数</h2>
 * <ul>
 *   <li>目标批次为空（纯 SFI 中间道 SAVED_SFI 不物化生产批次）→ {@link LineageRecordOutcome#SKIPPED}：
 *       这条投料在血缘图上没有目标节点，不是故障；</li>
 *   <li>来源解析不了（池化锚等）→ {@link LineageRecordOutcome#AMBIGUOUS}：
 *       载体在、指向分不出，⛔ 不拿目标批次顶替（那就是自环）；</li>
 *   <li>写库失败 → recorder 的 FAILED / afterCommit warn。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemiFeedLineageRecorder {

    public static final String METRIC_TARGET_MISSING = "cretas.lineage.semi_feed.target_missing";

    private final LineageEdgeRecorder lineageEdgeRecorder;
    private final SemiSourceBatchResolver sourceBatchResolver;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /** 缺口 03: 回料池 (池化锚) 的来源批次归属。required=false: 单测直接 new 本类。 */
    @Autowired(required = false)
    private ReworkPoolProducerAttribution reworkPoolAttribution;

    @Autowired(required = false)
    private com.cretas.aims.repository.SemiFinishedInventoryRepository sfiRepository;

    /**
     * @param intermediateBatchNo 被扣的常驻半成品库存号（{@code sourceBatchNumber}）
     * @param targetProductionBatchId 消耗它的生产批次；为空 ⇒ SKIPPED
     * @param quantity  实际扣减量（来源原生单位）
     * @param unit      来源单位
     * @param refKey    调用方给的业务身份（同一扣减重放不重复写）
     * @param origin    调用方标识，进 meta 与日志
     */
    public LineageRecordOutcome recordSemiFeed(String factoryId,
                                               String intermediateBatchNo,
                                               Long targetProductionBatchId,
                                               BigDecimal quantity,
                                               String unit,
                                               Long operatorId,
                                               String refKey,
                                               String origin) {
        try {
            if (targetProductionBatchId == null) {
                log.info("[lineage] 半成品投料没有目标生产批次 (纯 SFI 道不物化批次), 不写边: factory={} sourceWipNo={} origin={}",
                        factoryId, intermediateBatchNo, origin);
                if (meterRegistry != null) {
                    meterRegistry.counter(METRIC_TARGET_MISSING, "origin", origin == null ? "UNKNOWN" : origin)
                            .increment();
                }
                return LineageRecordOutcome.SKIPPED;
            }
            SemiSourceBatchResolver.Resolution source =
                    sourceBatchResolver.resolve(factoryId, intermediateBatchNo, origin);
            if (!source.isResolved()) {
                if (SemiSourceBatchResolver.REASON_POOLED_CLERK_ANCHOR.equals(source.reason())) {
                    return recordReworkPoolFeed(factoryId, intermediateBatchNo, targetProductionBatchId,
                            quantity, unit, operatorId, refKey, origin);
                }
                return LineageRecordOutcome.AMBIGUOUS;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("origin", origin);
            meta.put("sourceWipNo", intermediateBatchNo);
            meta.put("sourceResolvedBy", source.method());
            return lineageEdgeRecorder.recordWipConsume(factoryId, source.productionBatchId(),
                    targetProductionBatchId, quantity, unit != null ? unit : source.sourceUnit(),
                    operatorId, refKey, meta);
        } catch (Exception e) {
            // 审计旁路: 绝不把扣减/小结/结单拖挂。
            log.warn("[lineage] 半成品投料写边失败 (fail-soft): factory={} sourceWipNo={} target={} origin={}",
                    factoryId, intermediateBatchNo, targetProductionBatchId, origin, e);
            return LineageRecordOutcome.FAILED;
        }
    }
    /**
     * 回料池 (池化锚) 领用: 按先进先出把这次领走的量归到产出它的生产批次, 一段一条边。
     * 只对回料池做; 归属不了 (找不到产出批次) ⇒ AMBIGUOUS, ⛔ 不拿目标批次顶替。
     * 调用点在扣减之后: 扣减前已领走量 = 现在的已领走量 − 本次量。
     */
    private LineageRecordOutcome recordReworkPoolFeed(String factoryId, String intermediateBatchNo,
                                                      Long targetProductionBatchId, BigDecimal quantity,
                                                      String unit, Long operatorId, String refKey, String origin) {
        if (reworkPoolAttribution == null || sfiRepository == null || quantity == null) {
            return LineageRecordOutcome.AMBIGUOUS;
        }
        var sfi = sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                factoryId, intermediateBatchNo.trim()).orElse(null);
        if (sfi == null || !Boolean.TRUE.equals(sfi.getIsRework()) || sfi.getConsumedQuantity() == null) {
            return LineageRecordOutcome.AMBIGUOUS;
        }
        BigDecimal consumedBefore = sfi.getConsumedQuantity().subtract(quantity);
        var slices = reworkPoolAttribution.attribute(factoryId, intermediateBatchNo, consumedBefore, quantity);
        if (slices.isEmpty()) {
            log.warn("[lineage] 回料池领用归属不了产出批次, 不写边: factory={} sourceWipNo={} target={} origin={}",
                    factoryId, intermediateBatchNo, targetProductionBatchId, origin);
            return LineageRecordOutcome.AMBIGUOUS;
        }
        LineageRecordOutcome result = LineageRecordOutcome.ALREADY_EXISTS;
        for (var slice : slices) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("origin", origin);
            meta.put("sourceWipNo", intermediateBatchNo);
            meta.put("sourceResolvedBy", "REWORK_POOL_FIFO");
            meta.put("rework", true);
            LineageRecordOutcome one = lineageEdgeRecorder.recordWipConsume(factoryId, slice.producerBatchId(),
                    targetProductionBatchId, slice.quantity(), unit, operatorId,
                    refKey == null ? null : refKey + ":producer" + slice.producerBatchId(), meta);
            if (one == LineageRecordOutcome.FAILED) {
                return one;
            }
            if (one == LineageRecordOutcome.INSERTED || one == LineageRecordOutcome.SCHEDULED) {
                result = one;
            }
        }
        return result;
    }
}
