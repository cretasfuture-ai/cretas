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
}
