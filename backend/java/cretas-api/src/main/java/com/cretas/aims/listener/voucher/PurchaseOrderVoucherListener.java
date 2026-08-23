package com.cretas.aims.listener.voucher;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.event.PurchaseReceiveConfirmedEvent;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PurchaseReceive <b>确认入库</b>时 → 自动生成 PURCHASE_PAYMENT 凭证 (在 PurchaseOrder 上).
 * Idempotent: 同一 PO 多次 receive 只生成 1 张凭证 (uk_voucher_source_business 约束).
 *
 * <p>Bug 4 修复 (2026-07-04): 触发事件从 {@code PurchaseReceiveCreatedEvent} (入库单 DRAFT 创建)
 * 迁到 {@link PurchaseReceiveConfirmedEvent} (confirmReceive 确认入库)。未确认/被放弃的草稿入库
 * 不再生成幽灵凭证; 凭证时点与应付挂账一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderVoucherListener {

    private final PurchaseOrderRepository purchaseOrderRepo;
    private final VoucherService voucherService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPurchaseReceiveConfirmed(PurchaseReceiveConfirmedEvent event) {
        String orderId = event.getPurchaseOrderId();
        if (orderId == null) {
            log.debug("Receive event without purchaseOrderId, skip voucher hook");
            return;
        }
        try {
            handleVoucherGeneration(event.getFactoryId(), orderId);
        } catch (Exception e) {
            log.error("PurchaseOrder voucher hook failed: orderId={}", orderId, e);
        }
    }

    private void handleVoucherGeneration(String factoryId, String orderId) {
        PurchaseOrder po = purchaseOrderRepo.findById(orderId).orElse(null);
        if (po == null) {
            log.warn("PO not found after receive event: {}", orderId);
            return;
        }
        if (po.getVflag() != VoucherFlag.UNCREATED) {
            log.debug("PO {} vflag={}, skip", orderId, po.getVflag());
            return;
        }
        po.setVflag(VoucherFlag.PENDING);
        // 🔴 必须接住返回值 —— 本方法跑在 @Async 线程上, po 是 detached, save() 走 merge(),
        //    merge **返回一个新的托管实例**, 原对象的 @Version 不会被回写。丢掉返回值 ⇒
        //    下面第 2 次 save 拿旧 version 去 UPDATE ⇒ StaleObjectState, 单线程确定性触发。
        //    prod: purchase_orders 6/7 永久停在 PENDING(「凭证生成中」), FAILED 一条都没有 ——
        //    因为写 FAILED 的正是抛异常的那次 save。详见 SalesFinanceApproveVoucherListener 注释。
        po = purchaseOrderRepo.save(po);
        try {
            voucherService.createFromBusiness(factoryId, "PURCHASE_ORDER", orderId);
            po.setVflag(VoucherFlag.CREATED);
        } catch (Exception e) {
            po.setVflag(VoucherFlag.FAILED);
            log.error("Voucher generation failed for PO {}", orderId, e);
        }
        purchaseOrderRepo.save(po);
    }
}
