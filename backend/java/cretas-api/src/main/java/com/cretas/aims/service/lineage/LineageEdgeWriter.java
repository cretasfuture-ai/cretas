package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 血缘边真正落库的那一层 —— <b>独立事务 + 立即 flush</b>。
 *
 * <h2>为什么必须单独一个 bean</h2>
 *
 * <p>Spring 的 {@code @Transactional} 走代理，<b>同类自调用不生效</b>。
 * 如果把 {@code REQUIRES_NEW} 标在 {@link LineageEdgeRecorder} 自己的私有方法上，
 * 注解会被完全忽略、边照样落在调用方的事务里 —— 那是一个「注解在、没接上」的经典长相
 * （形态 B），而且不报错。所以拆一个 bean 出来，让调用真的过代理。
 *
 * <h2>为什么必须 saveAndFlush 而不是 save</h2>
 *
 * <p>🔴 {@link BatchLineageEdge#getId()} 是<b>应用侧赋值</b>的 UUID（{@code @PrePersist}），
 * 没有 {@code @GeneratedValue}。Hibernate 对 assigned 主键的 persist <b>不需要</b>立刻拿到
 * 数据库生成的键，于是 INSERT 被放进 action queue，推迟到下一个 flush 边界才真正打到库里。
 *
 * <p>后果是致命的：{@code save()} 外面那圈 {@code try/catch} <b>结构性地捕不到真正的
 * DB 失败</b> —— 异常在调用方 commit 时才爆，那时候 catch 块早就退出了。
 * 「fail-soft 不阻塞业务」这句话在真库上根本不成立。
 * {@code saveAndFlush} 把 INSERT 拉回到本方法内部，异常才有机会被自己的 catch 接住。
 *
 * <p>⚠️ 这一条只有<b>真 DB + 真事务</b>的测试照得出来。Mockito 的
 * {@code when(repo.save(...)).thenThrow(...)} 让 save 同步抛异常，
 * 恰恰掩盖了真实 Hibernate 的行为 —— 那个测试绿，证明的只是
 * 「如果 save 同步抛，catch 能接住」，不是「真实 DB 错误会被吞掉」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineageEdgeWriter {

    private final BatchLineageEdgeRepository edgeRepository;

    /**
     * 在<b>独立事务</b>里查重 + 落库 + 立即 flush。
     *
     * <p>{@code REQUIRES_NEW}：调用方（报工/发货/结单）的事务与这里完全隔离 ——
     * 这里失败不会把调用方标成 rollback-only，调用方回滚也不会连坐这条边。
     *
     * <p>⚠️ 正因为 {@code REQUIRES_NEW} 会<b>独立提交</b>，直接在业务事务里调它会留下
     * 「调用方回滚了、边还在」的永久假边（边表是 append-only forensic，删不掉）。
     * 所以有活动事务时必须走 {@code afterCommit}，见
     * {@link LineageEdgeRecorder#record}。
     *
     * @return INSERTED / ALREADY_EXISTS / FAILED（⛔ 不往上抛）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LineageRecordOutcome writeInNewTransaction(BatchLineageEdge edge, String refKey) {
        try {
            List<BatchLineageEdge> existing = edgeRepository.findMatchingEdges(
                    edge.getFactoryId(), edge.getEdgeType(),
                    edge.getSourceType(), edge.getSourceId(),
                    edge.getTargetType(), edge.getTargetId());
            if (alreadyRecorded(existing, refKey, edge)) {
                return LineageRecordOutcome.ALREADY_EXISTS;
            }
            // saveAndFlush: 让 INSERT 在本方法内部真的打到库里, 异常才接得住。
            edgeRepository.saveAndFlush(edge);
            return LineageRecordOutcome.INSERTED;
        } catch (DataIntegrityViolationException e) {
            // 唯一索引 uq_ble_refkey 兜住了「先查后插」的并发窗口 —— 另一个请求抢先写了同一个
            // refKey。这不是失败, 是「已存在」：两条线程想写的是同一件事实。
            // ⚠️ 与其它 DB 异常分开处理, 否则并发去重会被报成 FAILED, 回填读数当场失真。
            log.debug("[lineage] refKey 唯一索引命中, 视为已存在 (并发去重): edge={} refKey={}",
                    edge.getEdgeType(), refKey);
            return LineageRecordOutcome.ALREADY_EXISTS;
        } catch (Exception e) {
            log.warn("[lineage] 边写入失败 (fail-soft, 不阻塞业务): edge={} {}:{} → {}:{} qty={} refKey={}",
                    edge.getEdgeType(), edge.getSourceType(), edge.getSourceId(),
                    edge.getTargetType(), edge.getTargetId(), edge.getQuantityUsed(), refKey, e);
            return LineageRecordOutcome.FAILED;
        }
    }

    /**
     * 只判定、不写 —— 回填 dry-run 用。
     *
     * <p>⚠️ 判据必须与 {@link #writeInNewTransaction} 用<b>同一个</b> {@code alreadyRecorded}：
     * dry-run 另写一份判定就是形态 D，两边对同一批数据给出不同的数，而没有任何测试会红。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public LineageRecordOutcome previewInNewTransaction(BatchLineageEdge edge, String refKey) {
        try {
            List<BatchLineageEdge> existing = edgeRepository.findMatchingEdges(
                    edge.getFactoryId(), edge.getEdgeType(),
                    edge.getSourceType(), edge.getSourceId(),
                    edge.getTargetType(), edge.getTargetId());
            return alreadyRecorded(existing, refKey, edge)
                    ? LineageRecordOutcome.ALREADY_EXISTS
                    : LineageRecordOutcome.INSERTED;
        } catch (Exception e) {
            log.warn("[lineage] 预演查重失败: edge={} refKey={}", edge.getEdgeType(), refKey, e);
            return LineageRecordOutcome.FAILED;
        }
    }

    /**
     * 幂等判定 —— 在线路径、回填、dry-run <b>共用这一份</b>。
     *
     * <p>有 refKey → 只有<b>同 refKey</b> 的边算「已写过」；同一对批次之间多次不同投料
     * 各自写各自的边（这是真实业务，不是重复）。
     *
     * <p>没有 refKey → 退化成「同一对批次同一 edgeType 已有任何边就算写过」。
     */
    private boolean alreadyRecorded(List<BatchLineageEdge> existing, String refKey, BatchLineageEdge incoming) {
        if (existing == null || existing.isEmpty()) {
            return false;
        }
        if (refKey == null || refKey.isBlank()) {
            return true;
        }
        Map<String,Object> wanted=incoming.getMeta();
        // Backfill has a separate key namespace and must not duplicate an already observed shipment.
        // Conversely, a real shipment may supplement an older synthetic backfill without inheriting its cost truth.
        if ("FINISHED_TO_SHIPMENT".equals(incoming.getEdgeType()) && wanted != null
                && wanted.get("deliveryItemId") != null) {
            boolean historical="BACKFILL".equals(wanted.get("origin"));
            if (existing.stream().anyMatch(e -> e.getMeta()!=null
                    && Objects.equals(String.valueOf(e.getMeta().get("deliveryItemId")),String.valueOf(wanted.get("deliveryItemId")))
                    && Objects.equals(e.getMeta().get("shipmentCarrier"),wanted.get("shipmentCarrier"))
                    && (historical || "SALES_DELIVERY_SHIPMENT".equals(e.getMeta().get("origin"))))) return true;
        }
        return existing.stream().anyMatch(e -> {
            Map<String, Object> meta = e.getMeta();
            return meta != null
                    && Objects.equals(String.valueOf(meta.get(LineageEdgeRecorder.META_REF_KEY)), refKey);
        });
    }
}
