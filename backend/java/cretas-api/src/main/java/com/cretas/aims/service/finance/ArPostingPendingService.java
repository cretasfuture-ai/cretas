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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 发货后应收自动挂账失败的「待补账」处理 (D-S3, Steve 2026-11-15 裁决: 发货成功 + 写待补账 + 告警, 不回滚)。
 *
 * <p>不被 doom 的前提: 挂账异常穿过 {@code ArApService} 的 {@code @Transactional} 代理会把共享事务标成
 * rollback-only, 发货在提交时才炸。JpaTransactionManager 的 HibernateJpaDialect 不支持 savepoint(NESTED 不可用,
 * 实测 NestedTransactionNotSupportedException), 所以改由 {@code recordReceivableIfAbsent} 对 BusinessException
 * 声明 {@code noRollbackFor}: 它的 BusinessException 都发生在任何写库之前。
 *
 * <p>本类两件事:
 * <ol>
 *   <li>{@link #recordFailure}: 把真正的失败落成一条可查询的 {@link ArPostingPending}, 并给财务经理
 *       发站内告警(没有该角色的人则转发厂长, 见 DbNotificationServiceImpl);</li>
 *   <li>{@link #list}: 只读查询, 全部带 factory_id。</li>
 * </ol>
 * 补账动作与 RESOLVED 置位本期不做。
 */
@Slf4j
@Service
public class ArPostingPendingService {

    static final String ALERT_ROLE = "finance_manager";

    private final ArPostingPendingRepository repository;
    @Autowired(required = false)
    private NotificationService notificationService;
    @Autowired(required = false)
    private ArApTransactionRepository arApTransactionRepository;

    public ArPostingPendingService(ArPostingPendingRepository repository) {
        this.repository = repository;
    }

    /**
     * 该发运单应收是否已存在 —— 区分「幂等命中/并发已挂」与「真正失败」。
     * 判不了(没有仓库)返回 false, 即按真正失败处理: 宁可多一条待补账, 不静默漏记。
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

    @Transactional
    public ArPostingPending recordFailure(String factoryId, String deliveryId, String deliveryNumber,
                                          String salesOrderId, String customerId, BigDecimal amount,
                                          Long operatedBy, Throwable cause) {
        String reason = describe(cause);
        ArPostingPending row = repository
                .findFirstByFactoryIdAndDeliveryIdAndStatus(factoryId, deliveryId, ArPostingPendingStatus.PENDING)
                .orElseGet(ArPostingPending::new);
        row.setFactoryId(factoryId);
        row.setDeliveryId(deliveryId);
        row.setDeliveryNumber(deliveryNumber);
        row.setSalesOrderId(salesOrderId);
        row.setCustomerId(customerId);
        row.setAmount(amount);
        row.setFailureReason(reason);
        row.setStatus(ArPostingPendingStatus.PENDING);
        row.setOperatedBy(operatedBy);
        row = repository.save(row);

        log.error("应收自动挂账失败已转入待补账: factoryId={}, deliveryId={}, pendingId={}, reason={}",
                factoryId, deliveryId, row.getId(), reason);
        alert(row);
        return row;
    }

    @Transactional(readOnly = true)
    public Page<ArPostingPending> list(String factoryId, ArPostingPendingStatus status, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 200));
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
            notificationService.notifyRole(row.getFactoryId(), ALERT_ROLE,
                    "[严重] 发货应收挂账失败, 需补账",
                    "发货单 " + (row.getDeliveryNumber() != null ? row.getDeliveryNumber() : row.getDeliveryId())
                            + " 已发货, 但应收未能自动挂账"
                            + (row.getAmount() != null ? "(应挂金额 " + row.getAmount().toPlainString() + ")" : "")
                            + "。原因: " + row.getFailureReason()
                            + "。请到待补账列表处理, 编号 " + row.getId());
        } catch (RuntimeException e) {
            // 告警渠道故障不能吞掉账务失败: 记录已落库, 这里只能响亮地记日志。
            log.error("待补账告警发送失败(记录已保存): pendingId={}", row.getId(), e);
        }
    }

    private static String describe(Throwable cause) {
        String msg = cause == null ? "未知原因"
                : cause.getClass().getSimpleName() + ": " + (cause.getMessage() == null ? "" : cause.getMessage());
        return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
    }
}
