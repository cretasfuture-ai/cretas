package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 半成品库存行（{@code semi_finished_inventory}）→ 产出它的生产批次 —— <b>只此一份</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>WIP_CONSUME 边的来源节点是「产出这份半成品的生产批次」。历史写法在
 * {@code sourceWip.getBatchId() == null} 时拿<b>目标</b>批次顶替来源，写出自环：
 * prod（F006 备份 2026-10-24）57 条 WIP_CONSUME 里 51 条自环。其中一部分是 App 同一生产批次内
 * 工序间领用的合理自环（来源批次确实就是目标批次），但 SFI 行 {@code batch_id} 为空的那些
 * 是<b>伪造</b>的 —— 血缘图上「这批半成品是自己产的」。
 *
 * <h2>判据（按可靠度依次尝试；⛔ 任何一条都不猜）</h2>
 *
 * <ol>
 *   <li>行上的 {@code batch_id}（App 报工建行时写入）。</li>
 *   <li>行上的 {@code source_work_process_task_id} → 本厂工序任务 → 其生产批次。</li>
 *   <li>App 工序批次号格式 {@code <产品>-B<批次id>-S<工序序>-<任务id>}：按<b>任务 id</b>查本厂任务，
 *       且任务的生产批次必须<b>等于</b>号里的 {@code B<批次id>} —— 两个独立来源一致才采纳。</li>
 *   <li>本厂有同号的生产批次（批次号即库存号）。</li>
 * </ol>
 *
 * <p>都不中 ⇒ {@link Resolution#unresolved}。典型是文员路径的池化锚
 * {@code CLK-SEMI-<计划前8>-<产品前8>}：一个锚由同计划同产品的多次产出（含不挂生产批次的纯 SFI 道）
 * 累加而成，「这次领走的是哪个批次产的」在数据里不存在。⛔ 不挑一个塞进去。
 * 解析不了的每一次都 warn（固定前缀 {@code [lineage] SEMI_SOURCE_UNRESOLVED}，可 grep）
 * 并计数（Micrometer {@value #METRIC_UNRESOLVED}，按 reason 打标签）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemiSourceBatchResolver {

    public static final String METRIC_UNRESOLVED = "cretas.lineage.semi_source.unresolved";

    /** 解析方式 —— 写进边的 meta，事后能分清每条边的来源是怎么定的。 */
    public static final String BY_ROW_BATCH = "SFI_ROW_BATCH";
    public static final String BY_SOURCE_TASK = "SFI_SOURCE_TASK";
    public static final String BY_TASK_BATCH_NO = "APP_TASK_BATCH_NO";
    public static final String BY_PRODUCTION_BATCH_NUMBER = "PRODUCTION_BATCH_NUMBER";

    public static final String REASON_POOLED_CLERK_ANCHOR = "POOLED_CLERK_ANCHOR";
    public static final String REASON_NO_PRODUCER_KEY = "NO_PRODUCER_KEY";
    public static final String REASON_SFI_ROW_MISSING = "SFI_ROW_MISSING";

    /** {@code YieldReportServiceImpl#generateBatchNo}: {@code %s-B%d-S%d-%d}。产品 id 本身含连字符，所以锚在尾部。 */
    private static final Pattern APP_TASK_BATCH_NO = Pattern.compile("-B(\\d+)-S(\\d+)-(\\d+)$");

    private final SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final WorkProcessTaskRepository workProcessTaskRepository;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /** 解析结果：{@code productionBatchId} 非空 = 可靠解析；为空时 {@code reason} 说明为什么。 */
    public record Resolution(Long productionBatchId, String method, String reason, String sourceUnit) {
        static Resolution resolved(Long productionBatchId, String method) {
            return new Resolution(productionBatchId, method, null, null);
        }

        static Resolution unresolved(String reason) {
            return new Resolution(null, null, reason, null);
        }

        Resolution withSourceUnit(String unit) {
            return new Resolution(productionBatchId, method, reason, unit);
        }

        public boolean isResolved() {
            return productionBatchId != null;
        }
    }

    /** 按库存号解析（调用方手里只有 {@code sourceBatchNumber} 的情形；只读查询，不加锁）。 */
    public Resolution resolve(String factoryId, String intermediateBatchNo, String origin) {
        if (isBlank(factoryId) || isBlank(intermediateBatchNo)) {
            return unresolved(factoryId, intermediateBatchNo, REASON_NO_PRODUCER_KEY, origin);
        }
        Optional<SemiFinishedInventory> row = semiFinishedInventoryRepository
                .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, intermediateBatchNo.trim());
        if (row.isEmpty()) {
            Resolution byNumber = resolveByKeys(factoryId, intermediateBatchNo.trim(), null);
            return byNumber.isResolved() ? byNumber
                    : unresolved(factoryId, intermediateBatchNo, REASON_SFI_ROW_MISSING, origin);
        }
        return resolve(factoryId, row.get(), origin).withSourceUnit(row.get().getUnit());
    }

    /** 按已加载的库存行解析。 */
    public Resolution resolve(String factoryId, SemiFinishedInventory sfi, String origin) {
        if (sfi == null) {
            return unresolved(factoryId, null, REASON_SFI_ROW_MISSING, origin);
        }
        if (sfi.getBatchId() != null) {
            return Resolution.resolved(sfi.getBatchId(), BY_ROW_BATCH);
        }
        if (sfi.getSourceWorkProcessTaskId() != null && !isBlank(factoryId)) {
            Optional<Long> viaTask = workProcessTaskRepository
                    .findByFactoryIdAndId(factoryId, sfi.getSourceWorkProcessTaskId())
                    .map(WorkProcessTask::getProductionBatchId);
            if (viaTask.isPresent()) {
                return Resolution.resolved(viaTask.get(), BY_SOURCE_TASK);
            }
        }
        Resolution byKeys = resolveByKeys(factoryId, sfi.getIntermediateBatchNo(), sfi);
        if (byKeys.isResolved()) {
            return byKeys;
        }
        String no = sfi.getIntermediateBatchNo();
        String reason = no != null && no.startsWith("CLK-SEMI-")
                ? REASON_POOLED_CLERK_ANCHOR : REASON_NO_PRODUCER_KEY;
        return unresolved(factoryId, no, reason, origin);
    }

    private Resolution resolveByKeys(String factoryId, String intermediateBatchNo, SemiFinishedInventory sfi) {
        if (isBlank(factoryId) || isBlank(intermediateBatchNo)) {
            return Resolution.unresolved(REASON_NO_PRODUCER_KEY);
        }
        Matcher m = APP_TASK_BATCH_NO.matcher(intermediateBatchNo);
        if (m.find()) {
            Long batchInNo = Long.valueOf(m.group(1));
            Long taskId = Long.valueOf(m.group(3));
            Optional<WorkProcessTask> task = workProcessTaskRepository.findByFactoryIdAndId(factoryId, taskId);
            if (task.isPresent() && Objects.equals(task.get().getProductionBatchId(), batchInNo)) {
                return Resolution.resolved(batchInNo, BY_TASK_BATCH_NO);
            }
        }
        Optional<ProductionBatch> sameNumber =
                productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, intermediateBatchNo);
        if (sameNumber.isPresent() && sameNumber.get().getId() != null) {
            return Resolution.resolved(sameNumber.get().getId(), BY_PRODUCTION_BATCH_NUMBER);
        }
        return Resolution.unresolved(REASON_NO_PRODUCER_KEY);
    }

    private Resolution unresolved(String factoryId, String intermediateBatchNo, String reason, String origin) {
        log.warn("[lineage] SEMI_SOURCE_UNRESOLVED 半成品来源生产批次解析不了, 不写边 (⛔ 不拿目标批次顶替): "
                        + "factory={} sourceWipNo={} reason={} origin={}",
                factoryId, intermediateBatchNo, reason, origin);
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_UNRESOLVED,
                    "reason", reason, "origin", origin == null ? "UNKNOWN" : origin).increment();
        }
        return Resolution.unresolved(reason);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
