package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 原料 / 在制批次「扣留」与「解除扣留」的<b>唯一实现</b> (#1590 遗留 M5)。
 *
 * <p>扣留 = 把还能用的批次改成质检中 (INSPECTING), 同时在 {@code material_batches.hold_prior_status} 记下原状态。
 * 入口: AI 冻结库存 ({@code InventoryFreezeTool} → {@link #freeze}), 仓库页 / AI 改批次状态成「质检中」,
 * CCP 偏离扣留 ({@link QcDeviationHold}), 冷链超温转待检 —— 后三者批次已在锁内, 调 {@link #hold}。
 *
 * <p>解除 = 放行。所有放行入口 (质检放行页、质检处置桥接、放行决策工具、仓库页改成「可用」) 请求的目标都是「可用」,
 * 一律经 {@link #releaseTarget} 换成该还原的状态: 扣留前是冻品就回冻品、鲜品回鲜品、已耗尽 (全被预留) 按当前数量
 * 回已耗尽 / 可用。没有记录 (收货 / 产出后的首检) 照旧放成「可用」。
 *
 * <p>判不合格 / 报废 / 其它非「可用」目标照常执行, 实体 {@code setStatus} 离开质检中时清掉记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialBatchHoldService {

    /** 扣留写入的状态。 */
    public static final MaterialBatchStatus HOLD_STATUS = MaterialBatchStatus.INSPECTING;

    /** 冻结时写进 notes 的审计标记 (召回损失估算据此认出「被冻结工具扣住」的批次; 老数据缺扣留前状态时也靠它认)。 */
    public static final String FREEZE_AUDIT_MARK = "[FREEZE by user=";

    private final MaterialBatchRepository materialBatchRepository;
    private final EntityManager entityManager;

    /** 冻结结果: {@code held=false} 时 {@code reason} 说明为什么没改。 */
    public record FreezeResult(boolean held, MaterialBatch batch, MaterialBatchStatus previousStatus, String reason) {
    }

    /**
     * 不改状态的原因; null = 可以扣留。
     *
     * <p>按货架实物判有没有东西可扣: 已耗尽 (全被预留, 货还在架上) 照样扣; 实物为 0 (已用完) 没东西可扣。
     * 已经不可投产的批次 (质检中 / 不良品 / 过期 / 报废) 不改: 扣留不能把「不合格」覆盖掉, 否则扣留就成了洗白途径。
     */
    public static String notHoldableReason(MaterialBatch batch) {
        MaterialBatchStatus st = batch.getStatus();
        if (st != null && st.blocksProduction()) {
            return String.format("批次 %s 当前是「%s」, 本来就不能用于生产/出货, 不改状态 (冻结不覆盖「%s」)",
                    batch.getBatchNumber(), label(st), label(st));
        }
        BigDecimal physical = batch.getPhysicalQuantity();
        if (physical == null || physical.signum() <= 0) {
            return String.format("批次 %s 当前是「%s」, 货架上没有实物, 没有可冻结的库存, 不改状态",
                    batch.getBatchNumber(), label(st));
        }
        return null;
    }

    /**
     * AI 冻结库存的入口: 带行锁取批次, 能扣就扣留并写审计行。
     *
     * @throws BusinessException 404 批次不存在 (别厂批次同样 404)
     */
    @Transactional
    public FreezeResult freeze(String factoryId, String batchNumber, String reason, Long userId) {
        MaterialBatch found = materialBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .orElseThrow(() -> new BusinessException(404, String.format("批次 %s 不存在, 无法冻结", batchNumber))
                        .withSeverity("BLOCKING"));
        // 先锁再判: 判定与写入之间不让并发的冻结 / 改状态插进来。⛔ 不能直接对上面读到的实体再查一次 ForUpdate:
        // 实体已在本事务的持久化上下文里, 那条查询虽然加了锁, 返回的仍是旧实例 (状态可能已被别的事务改掉;
        // refresh(PESSIMISTIC_WRITE) 在真库并发测试里同样没重读, 第二个冻结撞乐观锁)。
        // 所以先把它从上下文里摘掉, 再带锁重新加载 —— 拿到锁之后读到的才是别的事务提交后的状态。
        entityManager.detach(found);
        MaterialBatch batch = materialBatchRepository.findByIdAndFactoryIdForUpdate(found.getId(), factoryId)
                .orElseThrow(() -> new BusinessException(404, String.format("批次 %s 不存在, 无法冻结", batchNumber))
                        .withSeverity("BLOCKING"));
        MaterialBatchStatus prev = batch.getStatus();
        String blocked = notHoldableReason(batch);
        if (blocked != null) {
            return new FreezeResult(false, batch, prev, blocked);
        }
        hold(batch);
        String existingNotes = batch.getNotes() != null ? batch.getNotes() : "";
        String auditLine = String.format("[%s] " + FREEZE_AUDIT_MARK + "%s] %s (prev=%s)",
                LocalDateTime.now(), userId, reason, prev);
        batch.setNotes(existingNotes.isEmpty() ? auditLine : existingNotes + "\n" + auditLine);
        materialBatchRepository.save(batch);
        log.info("[批次扣留] factory={} batch={} {} → {} by={}", factoryId, batchNumber, prev, HOLD_STATUS, userId);
        return new FreezeResult(true, batch, prev, null);
    }

    /**
     * 把一个<b>已加载 (调用方负责加锁)</b> 的批次改成质检中。不保存 (调用方保存)。
     * <ul>
     *   <li>原状态能用 (可用 / 鲜品 / 冻品 / 已耗尽 …) ⇒ 扣留, 记下原状态, 放行时还原;</li>
     *   <li>原状态本身不可投产 (不良品 / 过期 / 报废) ⇒ 这是「送回复检」(见 {@link MaterialQcRejectionGuard#HINT}),
     *       不记原状态: 复检放行就是放成可用, 绝不还原成「不良品」之类, 也不能借扣留把它洗回原来的可用状态;</li>
     *   <li>已经在质检中 ⇒ 不动 (首检中的批次没有原状态可还原, 已有的扣留记录也不覆盖)。</li>
     * </ul>
     */
    public static void hold(MaterialBatch batch) {
        MaterialBatchStatus prev = batch.getStatus();
        if (prev == HOLD_STATUS) {
            return;
        }
        batch.setStatus(HOLD_STATUS);
        batch.setHoldPriorStatus(prev != null && prev.blocksProduction() ? null : prev);
    }

    /**
     * 放行时真正该写的状态。请求不是「可用」(判不合格 / 报废 / 指定状态) 或批次没有扣留记录 ⇒ 原样返回请求。
     * 有扣留记录 ⇒ 按扣留前状态还原, 数量状态按<b>当前</b>数量重算 (扣留期间数量可能变了):
     * 实物为 0 → 已用完; 可用为 0 → 已耗尽; 否则扣留前是已耗尽 → 可用; 扣留前已预留但预留量已为 0 → 可用;
     * 其余回扣留前状态 (生产预留由 {@link #followWip} 在在制结束时改写, 见下)。
     */
    public static MaterialBatchStatus releaseTarget(MaterialBatch batch, MaterialBatchStatus requested) {
        if (batch == null || requested != MaterialBatchStatus.AVAILABLE
                || batch.getStatus() != HOLD_STATUS || batch.getHoldPriorStatus() == null) {
            return requested;
        }
        MaterialBatchStatus prior = batch.getHoldPriorStatus();
        BigDecimal physical = batch.getPhysicalQuantity();
        if (physical == null || physical.signum() <= 0) {
            return MaterialBatchStatus.USED_UP;
        }
        BigDecimal current = batch.getCurrentQuantity();
        if (current == null || current.signum() <= 0) {
            return MaterialBatchStatus.DEPLETED;
        }
        if (prior == MaterialBatchStatus.DEPLETED || prior == MaterialBatchStatus.USED_UP || prior.blocksProduction()) {
            return MaterialBatchStatus.AVAILABLE;
        }
        // 已预留: 扣留期间预留可能被取消, 预留量已为 0 就不能再挂「已预留」(按可用筛的领料/FEFO/调拨都看不到它)
        if (prior == MaterialBatchStatus.RESERVED && !positive(batch.getReservedQuantity())) {
            return MaterialBatchStatus.AVAILABLE;
        }
        // 生产预留: 在制关联结束 (工序完工 / 取消) 时 followWip 已把扣留记录改成离开在制后的状态;
        // 这里还是生产预留 ⇒ 在制关联仍在, 原样还原
        return prior;
    }

    /**
     * 离开在制 (工序完工 / 取消) 时物料批次该回的状态: 可用为 0 → 已耗尽; 仍有预留 → 已预留; 否则可用。
     * 与 {@code ProcessingServiceImpl#releaseLinkedMaterialBatchesFromWip} 共用这一份口径。
     */
    public static MaterialBatchStatus wipExitStatus(MaterialBatch batch) {
        if (!positive(batch.getCurrentQuantity())) {
            return MaterialBatchStatus.DEPLETED;
        }
        return positive(batch.getReservedQuantity()) ? MaterialBatchStatus.RESERVED : MaterialBatchStatus.AVAILABLE;
    }

    /**
     * 被扣留的批次在扣留期间进入 / 离开在制: 状态仍是质检中不动, 只把扣留记录 (放行时要还原的状态) 跟着改,
     * 免得放行后还原成一个已经不成立的状态。进入在制 (已预留 / 可用 → 生产预留), 离开在制 (生产预留 →
     * {@link #wipExitStatus})。返回是否改了 (调用方负责保存)。没有扣留记录的批次不动。
     */
    public static boolean followWip(MaterialBatch batch, boolean entering) {
        if (batch == null || batch.getStatus() != HOLD_STATUS || batch.getHoldPriorStatus() == null) {
            return false;
        }
        MaterialBatchStatus prior = batch.getHoldPriorStatus();
        if (entering && (prior == MaterialBatchStatus.RESERVED || prior == MaterialBatchStatus.AVAILABLE)) {
            batch.setHoldPriorStatus(MaterialBatchStatus.PRODUCING_RESERVED);
            return true;
        }
        if (!entering && prior == MaterialBatchStatus.PRODUCING_RESERVED) {
            batch.setHoldPriorStatus(wipExitStatus(batch));
            return true;
        }
        return false;
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    /** 这批是不是被扣留的 (而不是首检中)。老数据没有扣留前状态, 冻结工具的审计标记也算。 */
    public static boolean isHeld(MaterialBatch batch) {
        return batch != null && batch.getStatus() == HOLD_STATUS
                && (batch.getHoldPriorStatus() != null
                || (batch.getNotes() != null && batch.getNotes().contains(FREEZE_AUDIT_MARK)));
    }

    private static String label(MaterialBatchStatus st) {
        return st == null ? "未知" : st.getDisplayName();
    }
}
