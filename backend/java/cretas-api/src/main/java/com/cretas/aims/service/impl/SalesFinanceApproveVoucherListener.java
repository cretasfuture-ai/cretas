package com.cretas.aims.service.impl;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * SP5 / #754: 财务审核通过 → 自动生成销售凭证 (三行价税分离) + 更新 vflag.
 *
 * <p>监听 {@link SalesOrderFinanceApprovedEvent}:
 * <ol>
 *   <li>调 {@link VoucherService#createFromBusiness} 自动生成销售凭证
 *       (复用 {@code SalesReceiptVoucherGenerator} 三行模板 借应收/贷收入/贷销项税).
 *       {@code createFromBusiness} 本身幂等 — 已有凭证直接返回, 不重复生成.</li>
 *   <li>成功 → vflag = CREATED; 失败 → vflag = FAILED (财审主流程不受影响).</li>
 * </ol>
 *
 * <p>设计选择:
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}: 财审事务提交后才触发,
 *       SalesOrder 已落库 → createFromBusiness 能 load 到实体; 父事务 rollback 时不生成凭证.</li>
 *   <li>{@code @Async}: 凭证生成不阻塞财审主流程 (response 不等凭证).</li>
 *   <li>幂等 (双层): (a) vflag 已 CREATED/FAILED → skip; (b) createFromBusiness 内部
 *       findBySourceBusiness 幂等命中直接返回 existing.</li>
 *   <li>fail-soft: 凭证生成异常被 catch, 仅标 vflag=FAILED + 日志告警, <b>绝不抛出</b>
 *       (AFTER_COMMIT 监听器抛异常也无法回滚已提交的财审, 反而污染异步线程).</li>
 * </ul>
 *
 * <p>🔒 资金红线: 凭证借贷平衡由 {@code AbstractVoucherGenerator.generate} 末尾的
 * {@code validateBalanced()} 强制保证 (借应收 = 贷收入 + 贷销项税, SalesReceiptVoucherGenerator
 * 用加法构造 receivable = net + tax 无舍入裂缝). 期间结账 gate (assertPeriodOpen) 仍生效.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesFinanceApproveVoucherListener {

    private static final String BUSINESS_TYPE = "SALES_ORDER";

    private final SalesOrderRepository salesOrderRepository;
    private final VoucherService voucherService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFinanceApproved(SalesOrderFinanceApprovedEvent event) {
        String orderId = event.getSalesOrderId();
        String factoryId = event.getFactoryId();
        Optional<SalesOrder> orderOpt = salesOrderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("SP5 SalesFinanceApproveVoucherListener: order {} not found, skipped", orderId);
            return;
        }

        SalesOrder order = orderOpt.get();

        // 幂等层 (a): 已经是 CREATED / FAILED 状态, 不重复生成凭证
        VoucherFlag currentFlag = order.getVflag();
        if (currentFlag == VoucherFlag.CREATED || currentFlag == VoucherFlag.FAILED) {
            log.debug("SP5 VoucherListener: order {} already in terminal vflag {}, skipped",
                    orderId, currentFlag);
            return;
        }

        // 状态机 (VoucherFlag): UNCREATED → PENDING → CREATED/FAILED. 先标 PENDING (生成中).
        //
        // 🔴 2026-08-19: 这里原来没有把 save 的返回值赋回 order —— 返回值被丢掉,
        //    于是下面第二次 save 用的还是这个**旧对象**(还带着旧 version) → StaleObjectState。
        //
        //    ⚠️ 不是并发。本方法跑在 @Async 线程上, 没有绑定的 EntityManager
        //    (OSIV 只绑请求线程) ⇒ order 是 detached ⇒ save() 走 merge(),
        //    **merge 返回的是一个新的托管实例**, 原对象的 version 不会被回写。
        //    所以第二次 save 必然拿旧 version 去 UPDATE ... WHERE version = V,
        //    而库里已经是 V+1 —— **单线程、确定性、每次首发必炸**。
        //
        //    自然实验(prod 实测): TransferVoucherListener 是逐字相同的双 save 形状、
        //    同样 @Async + AFTER_COMMIT, 但 InternalTransfer **没有 @Version** ⇒
        //    internal_transfers 零次冲突, 而它内层 catch 触发过 10 次(阳性对照, 证明那条路真跑了)。
        //    判别量是 @Version, 不是并发。
        //
        //    后果是静默的: 全部 476 次冲突都在 SimpleAsyncTaskExecutor 线程上, http-nio 上 0 次,
        //    GlobalExceptionHandler 的 409 OPTIMISTIC_LOCK_CONFLICT 一次都没触发过。
        //    丢掉的正是 PENDING → CREATED/FAILED 那次写 ⇒ prod 上 sales_orders 19/38、
        //    purchase_orders 6/7、return_orders 10/29 永久停在 PENDING(「凭证生成中」),
        //    而三张表里 FAILED 都是 0 —— 因为写 FAILED 的就是抛异常的那次 save。
        order = salesOrderRepository.save(order);

        // 自动生成销售凭证 (三行价税分离). createFromBusiness 幂等 — 已有凭证直接返回.
        // fail-soft: 任何异常仅标 FAILED + 告警, 不抛出 (AFTER_COMMIT 抛异常无法回滚财审,
        // 且会污染 @Async 线程; 财务可后续手动 POST /vouchers/generate 补单, FAILED→PENDING retry).
        try {
            Voucher voucher = voucherService.createFromBusiness(factoryId, BUSINESS_TYPE, orderId);
            order.setVflag(VoucherFlag.CREATED);
            order = salesOrderRepository.save(order);
            log.info("SP5 VoucherListener: order {} 自动生成销售凭证 {} (vflag=CREATED, factoryId={}, total={})",
                    orderId, voucher.getVoucherNumber(), factoryId, voucher.getTotalDebit());
        } catch (Exception e) {
            order.setVflag(VoucherFlag.FAILED);
            order = salesOrderRepository.save(order);
            log.error("SP5 VoucherListener: order {} 自动生成销售凭证失败 (vflag=FAILED, 不影响财审; "
                    + "财务可手动 POST /vouchers/generate 补单): {}", orderId, e.getMessage(), e);
        }
    }
}
