package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.finance.ArPostingPending;
import com.cretas.aims.entity.finance.ArPostingPendingStatus;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.finance.ArPostingPendingRepository;
import com.cretas.aims.service.notification.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

/**
 * 发货后应收自动挂账失败的「待补账」处理 (D-S3, Steve 2026-11-15 裁决: 发货成功 + 写待补账 + 告警, 不回滚)。
 *
 * <h2>为什么挂账放在发货事务【提交之后】、独立事务里</h2>
 * 第一版把挂账留在发货事务内, 靠 {@code noRollbackFor} 防 doom, 独立审查指出三个洞:
 * ① 只覆盖 BusinessException, {@code persistReceivable} 里的 ArithmeticException / DB 异常照样穿过事务代理
 * 把共享事务标 rollback-only, 发货与待补账记录一起回滚; ② PG 上语句失败后事务 aborted, catch 里再查库也会抛;
 * ③ 挂账里 {@code lockFactory} 与发货事务自锁风险, 所以也不能简单用 REQUIRES_NEW 内联。
 * 现在: 发货事务提交 → afterCommit 里用 REQUIRES_NEW 跑挂账; 失败再开新事务写待补账, 再开新事务发告警。
 * 发货事务从头到尾碰不到挂账的任何异常。没有事务同步(单测直调)时立即执行。
 *
 * <h2>崩溃保护: 发货事务内同步写「意图行」</h2>
 * afterCommit 回调在内存里, 进程在「发货已提交、回调未执行」之间被杀(蓝绿切换/OOM)就会无痕丢失。
 * 所以 {@link #postAfterShipCommit} 先在<b>调用方(发货)事务内</b>写一行 PENDING(原因「挂账进行中」),
 * 与发货同提交/同回滚; 挂账成功后独立事务把它置 RESOLVED(自动关闭), 失败则更新原因并告警, 崩溃则保留可见。
 * 仍可能无痕的只剩「记录失败本身又失败」(DB 持续不可用), 此时仅有 log.error。
 *
 * <p>人工补账动作本期不做。
 */
@Slf4j
@Service
public class ArPostingPendingService {

    static final String ALERT_ROLE = "finance_manager";
    static final String IN_FLIGHT_REASON = "挂账进行中(尚未确认结果; 若长时间停留在此说明进程中断, 需人工核查)";

    private final ArPostingPendingRepository repository;
    private final PlatformTransactionManager transactionManager;
    @Autowired(required = false)
    private NotificationService notificationService;
    @Autowired(required = false)
    private ArApTransactionRepository arApTransactionRepository;
    @Autowired(required = false)
    private FinanceRowScopeQuery financeRowScopeQuery;

    public ArPostingPendingService(ArPostingPendingRepository repository,
                                   PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionManager = transactionManager;
    }

    private TransactionTemplate newTx() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /**
     * 发货事务提交后执行挂账; 任何失败(含准备阶段已失败的 prepFailure)落待补账 + 告警。本方法绝不向调用方抛异常。
     *
     * @param prepFailure 发货事务内算金额/查订单阶段已发生的失败(非 null 则不再尝试挂账)
     * @param poster      挂账动作(调 ArApService.recordReceivableIfAbsent); prepFailure 非 null 时可为 null
     */
    public void postAfterShipCommit(String factoryId, String deliveryId, String deliveryNumber,
                                    String salesOrderId, String customerId, Long ownerId, BigDecimal amount,
                                    Long operatedBy, RuntimeException prepFailure,
                                    Supplier<?> poster) {
        // 发货事务内同步写意图行(与发货同事务): 崩溃/回调丢失时仍留有 PENDING 线索
        openIntent(factoryId, deliveryId, deliveryNumber, salesOrderId, customerId, ownerId, amount, operatedBy);
        Runnable task = () -> {
            try {
                runPosting(factoryId, deliveryId, deliveryNumber, salesOrderId, customerId, ownerId, amount,
                        operatedBy, prepFailure, poster);
            } catch (Throwable t) {
                // afterCommit 回调里抛出会冲到发货调用方, 而发货此时已提交: 只能响亮地记日志
                log.error("应收自动挂账后处理异常(发货已提交): factoryId={}, deliveryId={}", factoryId, deliveryId, t);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void runPosting(String factoryId, String deliveryId, String deliveryNumber, String salesOrderId,
                            String customerId, Long ownerId, BigDecimal amount, Long operatedBy,
                            RuntimeException prepFailure, Supplier<?> poster) {
        Throwable failure = prepFailure;
        if (failure == null && poster != null) {
            try {
                newTx().executeWithoutResult(s -> poster.get());
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (failure == null) {
            closeIntent(factoryId, deliveryId, "自动挂账成功, 意图行自动关闭");
            return;
        }
        // 区分「已挂过(幂等/并发)」与「真正失败」。复查本身失败则按真正失败处理: 宁可多一条待补账。
        boolean posted = false;
        try {
            posted = Boolean.TRUE.equals(newTx().execute(s ->
                    receivableAlreadyPosted(factoryId, "SALES_DELIVERY", deliveryId)));
        } catch (RuntimeException e) {
            log.warn("应收挂账复查失败, 按真正失败处理: deliveryId={}", deliveryId, e);
        }
        if (posted) {
            log.info("应收自动挂账幂等命中(异常后复查已存在, 不算失败): deliveryId={}", deliveryId);
            closeIntent(factoryId, deliveryId, "应收已存在(幂等命中), 意图行自动关闭");
            return;
        }
        recordFailure(factoryId, deliveryId, deliveryNumber, salesOrderId, customerId, ownerId, amount,
                operatedBy, failure);
    }

    /** 在当前(发货)事务内写/复用该发运单的 PENDING 意图行; 本方法的异常会随发货事务一起失败(同库同事务, 概率极低)。 */
    private void openIntent(String factoryId, String deliveryId, String deliveryNumber, String salesOrderId,
                            String customerId, Long ownerId, BigDecimal amount, Long operatedBy) {
        ArPostingPending r = repository
                .findFirstByFactoryIdAndDeliveryIdAndStatus(factoryId, deliveryId, ArPostingPendingStatus.PENDING)
                .orElseGet(ArPostingPending::new);
        r.setFactoryId(factoryId);
        r.setDeliveryId(deliveryId);
        r.setDeliveryNumber(deliveryNumber);
        r.setSalesOrderId(salesOrderId);
        r.setCustomerId(customerId);
        r.setOwnerId(ownerId);
        r.setAmount(amount);
        r.setFailureReason(IN_FLIGHT_REASON);
        r.setStatus(ArPostingPendingStatus.PENDING);
        r.setOperatedBy(operatedBy);
        repository.save(r);
    }

    /** 挂账成功(或幂等命中)后把意图行置 RESOLVED; 关闭失败只记日志(行保持 PENDING, 可见可查)。 */
    private void closeIntent(String factoryId, String deliveryId, String note) {
        try {
            newTx().executeWithoutResult(s ->
                    repository.findFirstByFactoryIdAndDeliveryIdAndStatus(
                            factoryId, deliveryId, ArPostingPendingStatus.PENDING).ifPresent(r -> {
                        r.setStatus(ArPostingPendingStatus.RESOLVED);
                        r.setFailureReason(note);
                        r.setResolvedAt(java.time.LocalDateTime.now());
                        repository.save(r);
                    }));
        } catch (RuntimeException e) {
            log.error("待补账意图行关闭失败(保持 PENDING): deliveryId={}", deliveryId, e);
        }
    }

    /**
     * 该发运单应收是否已存在。判不了(没有仓库)返回 false, 即按真正失败处理。
     */
    public boolean receivableAlreadyPosted(String factoryId, String sourceType, String deliveryId) {
        if (arApTransactionRepository == null) {
            return false;
        }
        return arApTransactionRepository
                .findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                        factoryId, sourceType, deliveryId, ArApTransactionType.AR_INVOICE)
                .isPresent();
    }

    /** 写待补账(独立事务, 同发运单未处理的只更新不堆叠), 再在另一个独立事务里发告警。 */
    public ArPostingPending recordFailure(String factoryId, String deliveryId, String deliveryNumber,
                                          String salesOrderId, String customerId, Long ownerId, BigDecimal amount,
                                          Long operatedBy, Throwable cause) {
        String reason = describe(cause);
        ArPostingPending row = newTx().execute(s -> {
            ArPostingPending r = repository
                    .findFirstByFactoryIdAndDeliveryIdAndStatus(factoryId, deliveryId, ArPostingPendingStatus.PENDING)
                    .orElseGet(ArPostingPending::new);
            r.setFactoryId(factoryId);
            r.setDeliveryId(deliveryId);
            r.setDeliveryNumber(deliveryNumber);
            r.setSalesOrderId(salesOrderId);
            r.setCustomerId(customerId);
            r.setOwnerId(ownerId);
            r.setAmount(amount);
            r.setFailureReason(reason);
            r.setStatus(ArPostingPendingStatus.PENDING);
            r.setOperatedBy(operatedBy);
            return repository.save(r);
        });
        log.error("应收自动挂账失败已转入待补账: factoryId={}, deliveryId={}, pendingId={}, reason={}",
                factoryId, deliveryId, row.getId(), reason);
        alert(row);
        return row;
    }

    /** 列表按工厂过滤; 财务行范围受限的账号只看到本人名下销售订单产生的待补账。 */
    public Page<ArPostingPending> list(String factoryId, ArPostingPendingStatus status, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 200));
        List<Long> owners = financeRowScopeQuery == null ? null : financeRowScopeQuery.creators(factoryId);
        if (owners != null && owners.isEmpty()) {
            return new PageImpl<>(List.of(), pr, 0);
        }
        if (owners != null) {
            return status == null
                    ? repository.findByFactoryIdAndOwnerIdInOrderByCreatedAtDesc(factoryId, owners, pr)
                    : repository.findByFactoryIdAndStatusAndOwnerIdInOrderByCreatedAtDesc(factoryId, status, owners, pr);
        }
        return status == null
                ? repository.findByFactoryIdOrderByCreatedAtDesc(factoryId, pr)
                : repository.findByFactoryIdAndStatusOrderByCreatedAtDesc(factoryId, status, pr);
    }

    private void alert(ArPostingPending row) {
        if (notificationService == null) {
            log.error("待补账告警未发出(通知服务不可用), 仅有待补账记录: pendingId={}", row.getId());
            return;
        }
        try {
            // 告警自己一个事务: notifyRole 是 @Transactional, 它抛异常不能牵连待补账记录
            newTx().executeWithoutResult(s -> notificationService.notifyRole(row.getFactoryId(), ALERT_ROLE,
                    "[严重] 发货应收挂账失败, 需补账",
                    "发货单 " + (row.getDeliveryNumber() != null ? row.getDeliveryNumber() : row.getDeliveryId())
                            + " 已发货, 但应收未能自动挂账"
                            + (row.getAmount() != null ? "(应挂金额 " + row.getAmount().toPlainString() + ")" : "")
                            + "。原因: " + row.getFailureReason()
                            + "。请到待补账列表处理, 编号 " + row.getId()));
        } catch (RuntimeException e) {
            log.error("待补账告警发送失败(记录已保存): pendingId={}", row.getId(), e);
        }
    }

    private static String describe(Throwable cause) {
        String msg = cause == null ? "未知原因"
                : cause.getClass().getSimpleName() + ": " + (cause.getMessage() == null ? "" : cause.getMessage());
        return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
    }
}
