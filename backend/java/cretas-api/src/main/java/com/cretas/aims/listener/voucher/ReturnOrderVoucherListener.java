package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.event.ReturnOrderCreatedEvent;
import com.cretas.aims.event.ReturnOrderRejectedEvent;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import com.cretas.aims.service.inventory.ReturnOrderRowLock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;
import java.util.Optional;

/**
 * ReturnOrder 创建时 → 自动生成 RETURN 凭证 (反向冲销原销售).
 *
 * <p>Bug 9 修复 (2026-07-04): 新增 {@link #onReturnOrderRejected} — 退货单驳回时作废对应凭证,
 * 消除被驳回退货的幽灵冲销凭证。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnOrderVoucherListener {

    private final ReturnOrderRepository returnOrderRepo;
    private final VoucherService voucherService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReturnOrderCreated(ReturnOrderCreatedEvent event) {
        try {
            handleVoucherGeneration(event.getFactoryId(), event.getReturnOrderId());
        } catch (Exception e) {
            log.error("ReturnOrder voucher hook failed: orderId={}", event.getReturnOrderId(), e);
        }
    }

    /**
     * Bug 9 修复: 退货单驳回 (rejectReturnOrder / financeRejectReturnOrder) → 作废其 RETURN 凭证。
     *
     * <p>幂等 + fail-soft: 无凭证 / 已 VOID / 已 REVERSED → 静默跳过; voidVoucher 异常仅日志告警不重抛。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReturnOrderRejected(ReturnOrderRejectedEvent event) {
        // The primary rejection transaction already voids/reverses the voucher.  This
        // callback is recovery/idempotency only; never invent an actor or hide a failed
        // primary rejection behind an asynchronous best-effort write.
        if (event.getActorId() == null || event.getActorId() <= 0) {
            log.warn("RO {} 驳回事件缺少认证操作者, 跳过异步凭证处理", event.getReturnOrderId());
            return;
        }
        try {
            Optional<Voucher> voucherOpt = voucherService.findBySourceBusiness("RETURN_ORDER", event.getReturnOrderId());
            if (voucherOpt.isEmpty()) {
                log.debug("RO {} 驳回, 无 RETURN 凭证需作废", event.getReturnOrderId());
                return;
            }
            Voucher voucher = voucherOpt.get();
            if (voucher.getStatus() == VoucherStatus.VOID || voucher.getStatus() == VoucherStatus.REVERSED) {
                log.debug("RO {} 凭证 {} 已是终态 {}, 跳过作废",
                        event.getReturnOrderId(), voucher.getVoucherNumber(), voucher.getStatus());
                return;
            }
            voucherService.voidVoucher(event.getFactoryId(), voucher.getId(), "退货单驳回自动作废", event.getActorId());
            log.info("✅ RO {} 驳回 → 凭证 {} 已作废", event.getReturnOrderId(), voucher.getVoucherNumber());
        } catch (Exception e) {
            log.error("RO {} 驳回后的幂等凭证回调失败 (主事务已负责成败): {}",
                    event.getReturnOrderId(), e.getMessage(), e);
        }
    }

    private void handleVoucherGeneration(String factoryId, String returnOrderId) {
        TransactionTemplate transaction = newRequiresNewTransaction();
        try {
            transaction.execute(status -> {
                com.cretas.aims.service.finance.ProductionCostConsistency.lockFactory(entityManager, factoryId);
                ReturnOrder r = loadAndLock(factoryId, returnOrderId);
                if (shouldSkip(r, returnOrderId)) return null;
                r.setVflag(VoucherFlag.PENDING);
                voucherService.createFromBusiness(factoryId, "RETURN_ORDER", returnOrderId);
                r.setVflag(VoucherFlag.CREATED);
                return null;
            });
        } catch (Exception e) {
            log.error("Voucher generation failed for RO {}", returnOrderId, e);
            markVoucherGenerationFailed(factoryId, returnOrderId, transaction);
        }
    }

    private void markVoucherGenerationFailed(
            String factoryId, String returnOrderId, TransactionTemplate transaction) {
        try {
            transaction.execute(status -> {
                com.cretas.aims.service.finance.ProductionCostConsistency.lockFactory(entityManager, factoryId);
                ReturnOrder r = loadAndLock(factoryId, returnOrderId);
                Optional<Voucher> existingVoucher = r == null
                        ? Optional.empty()
                        : Optional.ofNullable(voucherService.findBySourceBusiness(
                                "RETURN_ORDER", returnOrderId)).orElse(Optional.empty());
                if (isFailureWriteBlocked(r, returnOrderId) || existingVoucher.isPresent()) {
                    if (r != null && existingVoucher.isPresent()
                            && r.getStatus() != ReturnOrderStatus.REJECTED) {
                        r.setVflag(VoucherFlag.CREATED);
                    }
                    return null;
                }
                if (r.getVflag() == VoucherFlag.UNCREATED
                        || r.getVflag() == VoucherFlag.PENDING
                        || r.getVflag() == VoucherFlag.FAILED) {
                    r.setVflag(VoucherFlag.FAILED);
                }
                return null;
            });
        } catch (Exception markFailure) {
            log.error("Failed to persist voucher failure state for RO {}", returnOrderId, markFailure);
        }
    }

    private TransactionTemplate newRequiresNewTransaction() {
        if (transactionManager == null) {
            throw new IllegalStateException("退货凭证监听器缺少事务管理器，拒绝无事务生成凭证");
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private ReturnOrder loadAndLock(String factoryId, String returnOrderId) {
        ReturnOrder r = returnOrderRepo.findById(returnOrderId).orElse(null);
        if (r != null && !Objects.equals(factoryId, r.getFactoryId())) {
            log.warn("拒绝跨工厂退货凭证事件: eventFactory={}, orderFactory={}, orderId={}",
                    factoryId, r.getFactoryId(), returnOrderId);
            return null;
        }
        if (r != null && !ReturnOrderRowLock.lockAndRefresh(entityManager, r, factoryId)) {
            log.warn("ReturnOrder row lock unavailable after created event: {}", returnOrderId);
            return null;
        }
        return r;
    }

    private boolean shouldSkip(ReturnOrder r, String returnOrderId) {
        if (r == null) {
            log.warn("ReturnOrder not found after created event: {}", returnOrderId);
            return true;
        }
        if (r.getFactoryId() == null) {
            log.warn("拒绝工厂为空的退货凭证事件: orderId={}", returnOrderId);
            return true;
        }
        if (r.getStatus() == ReturnOrderStatus.REJECTED) {
            log.info("RO {} 已驳回，跳过凭证生成", returnOrderId);
            return true;
        }
        if (r.getVflag() != VoucherFlag.UNCREATED) {
            log.debug("RO {} vflag={}, skip", returnOrderId, r.getVflag());
            return true;
        }
        return false;
    }

    /** Failure recovery may handle a rolled-back PENDING snapshot; only scope/terminal state blocks it. */
    private boolean isFailureWriteBlocked(ReturnOrder r, String returnOrderId) {
        if (r == null) {
            log.warn("ReturnOrder not found while marking voucher failure: {}", returnOrderId);
            return true;
        }
        if (r.getFactoryId() == null) {
            log.warn("拒绝工厂为空的退货凭证失败状态: orderId={}", returnOrderId);
            return true;
        }
        if (r.getStatus() == ReturnOrderStatus.REJECTED) {
            log.info("RO {} 已驳回，跳过凭证失败状态", returnOrderId);
            return true;
        }
        return false;
    }
}
