package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.lineage.LineageEdgeOutboxEntry;
import com.cretas.aims.repository.lineage.LineageEdgeOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 血缘边补写台账（transactional outbox）—— 让「写边失败」从一条 WARN 变成一行能重试、能计数的记录。
 *
 * <h2>三个入口，对应三个时刻</h2>
 * <ul>
 *   <li>{@link #enqueue}：<b>在业务事务里</b>登记 PENDING。与业务同提交、同回滚 ——
 *       业务回滚就没有这一行，所以补写器永远不会补出一条「投进了一个不存在的批次」的假边。</li>
 *   <li>{@link #settle}：边写完之后（提交后的即时写入 / 补写器），在<b>独立事务</b>里结掉这一行。</li>
 *   <li>{@link #enqueueFailedInNewTransaction}：无事务直调（回填 runner）写失败时补登一行，
 *       让它也进同一个补写队列。</li>
 * </ul>
 *
 * <p>⚠️ 必须是单独的 bean：{@code REQUIRES_NEW} 走代理，同类自调用不生效（与 {@link LineageEdgeWriter} 同一个理由）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineageEdgeOutbox {

    /** 连续失败到这个次数转 DEAD（按下面的退避约 1 天）。DEAD 仍计入积压。 */
    public static final int MAX_ATTEMPTS = 20;

    /** 退避上限：一小时一次。 */
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final LineageEdgeOutboxRepository repository;

    /**
     * 在<b>当前</b>事务里登记一条待写的边（不开新事务）。
     *
     * <p>⚠️ 这是一次普通 INSERT，与业务行同库同事务 —— 它要是写不进去，业务那几行也写不进去；
     * 「审计边写不进去不拖挂业务」的承诺由提交<b>之后</b>的写边兑现，不由这里。
     *
     * <p>🔴 所以登记前先按列宽校验：台账行与边同列同宽，一条<b>永远写不进去</b>的边（例如 factoryId
     * 超过 50 字符）登记进来，会在业务提交时撞列宽、把整单业务一起回滚 —— 那就把「审计旁路」变成了
     * 业务的新故障点。这种边不登记（返回 null，留 warn），提交后的即时写入照样失败、照样留 warn。
     * 剩下能让这里失败的只有库本身不可用，那时业务那几行也写不进去。
     *
     * @return 台账行 id，提交后用它结账；入参写不进台账时为 null
     */
    public String enqueue(BatchLineageEdge edge, String refKey) {
        String misfit = misfit(edge);
        if (misfit != null) {
            log.warn("[lineage-outbox] 这条边按列宽永远写不进去, 不登记补写 (不拖垮业务): {} edge={} {}:{} → {}:{}",
                    misfit, edge.getEdgeType(), edge.getSourceType(), edge.getSourceId(),
                    edge.getTargetType(), edge.getTargetId());
            return null;
        }
        LineageEdgeOutboxEntry entry = fromEdge(edge, refKey);
        repository.save(entry);
        return entry.getId();
    }

    /** 与 {@code lineage_edge_outbox} / {@code batch_lineage_edges} 的列宽同口径；都放得下返回 null。 */
    static String misfit(BatchLineageEdge edge) {
        if (tooLong(edge.getFactoryId(), 50)) {
            return "factory_id>50";
        }
        if (tooLong(edge.getEdgeType(), 30) || tooLong(edge.getSourceType(), 30) || tooLong(edge.getTargetType(), 30)) {
            return "type>30";
        }
        if (tooLong(edge.getSourceId(), 191) || tooLong(edge.getTargetId(), 191)) {
            return "id>191";
        }
        if (tooLong(edge.getUnit(), 20)) {
            return "unit>20";
        }
        return null;
    }

    private static boolean tooLong(String s, int max) {
        return s != null && s.length() > max;
    }

    /** 无事务直调写失败时补登一行（独立事务）。登记本身失败只能留日志 —— 那是库整体不可用。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueFailedInNewTransaction(BatchLineageEdge edge, String refKey, String error) {
        if (misfit(edge) != null) {
            return;   // 永远写不进去的边, 补写也没用 (writer 已 warn)
        }
        LineageEdgeOutboxEntry entry = fromEdge(edge, refKey);
        entry.setAttempts(1);
        entry.setLastError(truncate(error));
        entry.setNextAttemptAt(LocalDateTime.now().plus(backoff(1)));
        repository.save(entry);
    }

    /**
     * 按 writer 的结论结账（独立事务）。
     *
     * <p>INSERTED / ALREADY_EXISTS ⇒ DONE；其它（FAILED）⇒ attempts+1、按退避排下次，到上限转 DEAD。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(String entryId, LineageRecordOutcome outcome, String error) {
        LineageEdgeOutboxEntry entry = repository.findById(entryId).orElse(null);
        if (entry == null) {
            // 登记它的业务事务回滚了 —— 不该走到这里（afterCommit 只在提交后跑），留痕即可。
            log.warn("[lineage-outbox] 结账时台账行不存在: id={} outcome={}", entryId, outcome);
            return;
        }
        if (!LineageEdgeOutboxEntry.STATUS_PENDING.equals(entry.getStatus())) {
            return;   // 已被另一路结掉（即时写入与补写器不会同时取同一行, 这里只是防御）
        }
        LocalDateTime now = LocalDateTime.now();
        if (outcome == LineageRecordOutcome.INSERTED || outcome == LineageRecordOutcome.ALREADY_EXISTS) {
            entry.setStatus(LineageEdgeOutboxEntry.STATUS_DONE);
            entry.setResolvedOutcome(outcome.name());
            entry.setDoneAt(now);
        } else {
            int attempts = (entry.getAttempts() == null ? 0 : entry.getAttempts()) + 1;
            entry.setAttempts(attempts);
            entry.setLastError(truncate(error != null ? error : "writer outcome=" + outcome));
            if (attempts >= MAX_ATTEMPTS) {
                entry.setStatus(LineageEdgeOutboxEntry.STATUS_DEAD);
                log.error("LINEAGE_OUTBOX_DEAD 血缘边连续 {} 次写不进去, 停止自动补写 (仍计入积压): "
                                + "id={} factory={} edge={} {}:{} → {}:{}",
                        attempts, entry.getId(), entry.getFactoryId(), entry.getEdgeType(),
                        entry.getSourceType(), entry.getSourceId(), entry.getTargetType(), entry.getTargetId());
            } else {
                entry.setNextAttemptAt(now.plus(backoff(attempts)));
            }
        }
        repository.save(entry);
    }

    /** 台账行还原成一条待写的边（补写器用；id 由 @PrePersist 新分配）。 */
    public BatchLineageEdge toEdge(LineageEdgeOutboxEntry entry) {
        BatchLineageEdge edge = new BatchLineageEdge();
        edge.setFactoryId(entry.getFactoryId());
        edge.setEdgeType(entry.getEdgeType());
        edge.setSourceType(entry.getSourceType());
        edge.setSourceId(entry.getSourceId());
        edge.setTargetType(entry.getTargetType());
        edge.setTargetId(entry.getTargetId());
        edge.setQuantityUsed(entry.getQuantityUsed());
        edge.setUnit(entry.getUnit());
        edge.setEventTime(entry.getEventTime());
        edge.setOperatorId(entry.getOperatorId());
        edge.setMeta(entry.getMeta() == null ? new HashMap<>() : new HashMap<>(entry.getMeta()));
        return edge;
    }

    /** 1, 2, 4, 8 … 分钟，封顶一小时。 */
    static Duration backoff(int attempts) {
        int exp = Math.max(0, Math.min(attempts - 1, 10));
        Duration d = Duration.ofMinutes(1L << exp);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static LineageEdgeOutboxEntry fromEdge(BatchLineageEdge edge, String refKey) {
        LineageEdgeOutboxEntry entry = new LineageEdgeOutboxEntry();
        entry.setFactoryId(edge.getFactoryId());
        entry.setEdgeType(edge.getEdgeType());
        entry.setSourceType(edge.getSourceType());
        entry.setSourceId(edge.getSourceId());
        entry.setTargetType(edge.getTargetType());
        entry.setTargetId(edge.getTargetId());
        entry.setQuantityUsed(edge.getQuantityUsed());
        entry.setUnit(edge.getUnit());
        entry.setEventTime(edge.getEventTime());
        entry.setOperatorId(edge.getOperatorId());
        entry.setRefKey(refKey == null || refKey.isBlank() ? null : refKey);
        Map<String, Object> meta = edge.getMeta();
        entry.setMeta(meta == null ? new HashMap<>() : new HashMap<>(meta));
        entry.setStatus(LineageEdgeOutboxEntry.STATUS_PENDING);
        entry.setAttempts(0);
        return entry;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
