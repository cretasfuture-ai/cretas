package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.repository.InvoiceRecordRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.PaymentRecordRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.shortage.ShortageCaseActionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.discipline.FactoryDisciplinePolicy;
import com.cretas.aims.service.scheduling.CancelledPlanScheduleCascade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 销售单<b>财务批准后</b>取消 (2026-11-11 Steve 裁定: 做成工厂开关, 默认关 = 原行为「只能驳回 / 退款」)。
 *
 * <p>开了以后: 有「取消已批准销售单」权限 ({@link PermissionService#SALES_ORDER_CANCEL_APPROVED_PERMISSION},
 * 默认只有厂长, 工厂在阈值里配岗位名单) 的人带原因取消, 并连带取消它<b>自动生成</b>的生产计划
 * ({@link PlanSourceType#CUSTOMER_ORDER} 且 sourceOrderId = 本单)。生产计划走正式的
 * {@link ProductionPlanService#cancelProductionPlan} (经 Spring 代理 ⇒ {@code CancelledPlanScheduleCascadeAspect}
 * 用 #1574/#1579 的 {@link CancelledPlanScheduleCascade} / {@code SchedulingPlanTotals} 作废未开工排程并重算日计划),
 * ⛔ 这里不另写一套作废逻辑。
 *
 * <p><b>全有或全无</b>: 调用方在同一事务里, 任何一步被拦 (计划已开工有报工 / 已完成 / 锁定 / 撤回审批中, 发货单在走,
 * 已有收款或应收, 计划合并了别的订单) 整个取消回滚, 一条都不改, 并说清卡在哪。
 *
 * <p>并发: 调用方先拿销售单行锁 ({@code findByIdAndFactoryIdForUpdate}); 建发货单、登记收款也先拿同一把锁,
 * 所以「检查没有发货 / 收款」和「对方插进来一张」不会交错。
 */
@Slf4j
@Service
public class ApprovedSalesOrderCancellation {

    private final FactoryDisciplinePolicy disciplinePolicy;
    private final SalesOrderRepository salesOrderRepository;
    private final PermissionService permissionService;
    private final UserRepository userRepository;
    private final SalesDeliveryRecordRepository deliveryRecordRepository;
    private final ArApTransactionRepository arApTransactionRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionPlanService productionPlanService;
    private final CancelledPlanScheduleCascade scheduleCascade;
    private final ShortageCaseRepository shortageCaseRepository;
    private final ShortageCaseEventRepository shortageCaseEventRepository;

    // 独立审查 (#1589) 补的闸与走正式入口的依赖; 字段注入, 不改构造器 (测试按构造器装配)
    @Autowired
    private InvoiceRecordRepository invoiceRecordRepository;
    @Autowired
    private MaterialBatchRepository materialBatchRepository;
    /** 缺料实例取消走正式的「取消」决定 (数在走的采购单、写决定台账), 不在这里直接改阶段。 */
    @Autowired
    @Lazy
    private ShortageCaseActionService shortageCaseActionService;
    /** 交付风险实例随订单一并取消 (2026-11-13 补)。 */
    @Autowired
    @Lazy
    private com.cretas.aims.service.shortage.DeliveryRiskCaseStore deliveryRiskCaseStore;
    @Autowired
    private EntityManager entityManager;

    @Autowired
    public ApprovedSalesOrderCancellation(FactoryDisciplinePolicy disciplinePolicy,
                                          SalesOrderRepository salesOrderRepository,
                                          PermissionService permissionService,
                                          UserRepository userRepository,
                                          SalesDeliveryRecordRepository deliveryRecordRepository,
                                          ArApTransactionRepository arApTransactionRepository,
                                          PaymentRecordRepository paymentRecordRepository,
                                          ProductionPlanRepository productionPlanRepository,
                                          // @Lazy: ProductionPlanService 的依赖链会绕回销售服务, 打断循环
                                          @Lazy ProductionPlanService productionPlanService,
                                          CancelledPlanScheduleCascade scheduleCascade,
                                          ShortageCaseRepository shortageCaseRepository,
                                          ShortageCaseEventRepository shortageCaseEventRepository) {
        this.disciplinePolicy = disciplinePolicy;
        this.salesOrderRepository = salesOrderRepository;
        this.permissionService = permissionService;
        this.userRepository = userRepository;
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.arApTransactionRepository = arApTransactionRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.productionPlanRepository = productionPlanRepository;
        this.productionPlanService = productionPlanService;
        this.scheduleCascade = scheduleCascade;
        this.shortageCaseRepository = shortageCaseRepository;
        this.shortageCaseEventRepository = shortageCaseEventRepository;
    }

    /** 连带取消的结果, 说给用户听。 */
    public record Outcome(SalesOrder lockedOrder, List<String> cancelledPlanNumbers, long cancelledSchedules, long keptStartedSchedules,
                          List<String> manualPlansLeft, boolean shortageCaseCancelled, boolean deliveryRiskCancelled,
                          List<String> purchasesCancelled, List<String> purchasesLeftLive) {

        /** 兼容只关心缺料实例的旧调用。 */
        public Outcome(SalesOrder lockedOrder, List<String> cancelledPlanNumbers, long cancelledSchedules, long keptStartedSchedules,
                       List<String> manualPlansLeft, boolean shortageCaseCancelled) {
            this(lockedOrder, cancelledPlanNumbers, cancelledSchedules, keptStartedSchedules, manualPlansLeft,
                    shortageCaseCancelled, false, List.of(), List.of());
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("销售订单已取消");
            if (!cancelledPlanNumbers.isEmpty()) {
                sb.append("；已一并取消自动生成的生产计划 ").append(String.join("、", cancelledPlanNumbers));
            }
            if (cancelledSchedules > 0) {
                sb.append("；").append(cancelledSchedules).append(" 条未开工的排程任务已一并取消");
            }
            if (keptStartedSchedules > 0) {
                sb.append("；").append(keptStartedSchedules).append(" 条排程任务已开工，保留未动，请到调度计划里收尾");
            }
            if (!manualPlansLeft.isEmpty()) {
                sb.append("；手工关联到本单的生产计划 ").append(String.join("、", manualPlansLeft))
                        .append(" 没有自动取消，请到生产计划里确认是否还要做");
            }
            if (shortageCaseCancelled) {
                sb.append("；本单的缺料处理已一并取消");
            }
            if (deliveryRiskCancelled) {
                sb.append("；本单的交付风险跟踪已一并取消");
            }
            if (!purchasesCancelled.isEmpty()) {
                sb.append("；未审批的缺料采购单 ").append(String.join("、", purchasesCancelled)).append(" 已一并取消");
            }
            if (!purchasesLeftLive.isEmpty()) {
                sb.append("；缺料采购单 ").append(String.join("、", purchasesLeftLive))
                        .append(" 没有自动取消（只自动取消草稿和已提交的），请采购自行处理");
            }
            return sb.toString();
        }
    }

    /** 本厂是否开了「财务批准后可取消」。 */
    public boolean enabled(String factoryId) {
        return disciplinePolicy.cancelApprovedSalesOrderAllowed(factoryId);
    }

    /**
     * 锁住销售单 (与建发货单 / 登记收款同一把行锁), 锁后重读状态, 校验并连带取消下游。必须在调用方事务里调用 (MANDATORY),
     * 之后由调用方把返回的 {@link Outcome#lockedOrder()} 置为已取消 —— 同一事务, 任一步被拦整体回滚。
     *
     * @throws BusinessException 任一条件不满足
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome cancelDownstream(String factoryId, String orderId, String reason, Long operatorUserId) {
        SalesOrder order = salesOrderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "销售订单不存在或不属于当前组织"));
        // 调用方可能在拿锁之前已把这张单读进持久化上下文: 拿到锁后从库里重读, 状态判断才是锁后的真值
        refresh(order, null);
        // 缺料实例紧跟销售单锁住: 缺料重排是「实例 → 计划」的顺序, 这里也先实例后计划, 不交叉等锁
        java.util.Optional<ShortageCase> openCase = shortageCaseRepository.findOpenForUpdate(factoryId,
                ShortageCase.SourceType.SALES_ORDER, orderId);
        // 交付风险实例同样紧跟着锁 (实例 → 计划 的顺序不变), 订单取消时一并结束
        java.util.Optional<ShortageCase> openRisk = shortageCaseRepository.findOpenForUpdate(factoryId,
                ShortageCase.SourceType.DELIVERY_RISK, orderId);
        if (order.getStatus() != SalesOrderStatus.FINANCE_APPROVED) {
            throw new BusinessException(409, "订单当前是「" + order.getStatus().getDisplayName()
                    + "」, 只有财务已批准、尚未开始执行的订单能这样取消").withHint("请刷新订单列表查看最新状态");
        }
        if (!enabled(factoryId)) {
            throw new BusinessException(409, "当前订单状态(" + order.getStatus().getDisplayName()
                    + ")不允许取消。财务批准后请通过驳回/退款流程处理")
                    .withHint("如需允许财务批准后直接取消, 请厂长在「业务阈值 → 销售」开启 sales.order.cancel_after_approval");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new BusinessException(400, "财务批准后取消必须填写取消原因").withHintTarget("reason");
        }
        requirePermission(factoryId, operatorUserId);

        String soLabel = order.getOrderNumber() != null ? order.getOrderNumber() : order.getId();
        long liveDeliveries = deliveryRecordRepository.findBySalesOrderId(order.getId()).stream()
                .filter(d -> factoryId.equals(d.getFactoryId()))
                .filter(d -> d.getStatus() != SalesDeliveryStatus.CANCELLED)
                .count();
        if (liveDeliveries > 0) {
            throw new BusinessException(409, "销售订单 " + soLabel + " 已有 " + liveDeliveries + " 张未取消的发货单, 不能直接取消")
                    .withHint("先取消发货单; 已发货的请走退货");
        }
        if (arApTransactionRepository.countLiveBySalesOrder(factoryId, order.getId()) > 0
                || paymentRecordRepository.countLiveBySalesOrder(factoryId, order.getId()) > 0) {
            throw new BusinessException(409, "销售订单 " + soLabel + " 已有收款或应收记录, 不能直接取消")
                    .withHint("请走退款流程, 由财务处理已收款项");
        }
        // 发票: 申请 / 批准 / 已开都拦 (开票不写应收流水, 上面那道闸照不到; 开票审批也不复查订单状态)
        boolean invoiced = order.getInvoicedAmount() != null && order.getInvoicedAmount().compareTo(BigDecimal.ZERO) > 0;
        if (invoiced || !invoiceRecordRepository.findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(factoryId,
                order.getId(), List.of(InvoiceStatus.REQUESTED, InvoiceStatus.APPROVED, InvoiceStatus.ISSUED)).isEmpty()) {
            throw new BusinessException(409, "销售订单 " + soLabel + " 已申请或已开发票, 不能直接取消")
                    .withHint("先由财务驳回或作废发票, 再取消订单");
        }
        // 客供料: 客户的原料已经收进来、挂在本单上 ⇒ 先把客户的货处理掉 (退还客户 / 客户同意的处置), 不能悬在已取消的单上
        long customerBatches = materialBatchRepository.countCustomerOwnedWithStockForSalesOrder(factoryId, order.getId());
        if (customerBatches > 0) {
            throw new BusinessException(409, "销售订单 " + soLabel + " 已收到客户提供的原料 " + customerBatches
                    + " 批, 不能直接取消").withHint("先把客供料退还客户, 或按客户同意的方式处置, 再取消订单");
        }

        // 只碰本单自动生成的计划; 合并了别的订单的计划会连带取消别人的生产 ⇒ 拦住
        List<ProductionPlan> linked = productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(factoryId, order.getId());
        Set<String> linkedIds = new HashSet<>();
        linked.forEach(p -> linkedIds.add(p.getId()));
        // 本单被并进别的订单的计划 (只出现在 source_order_ids 里): 那个计划还在为本单生产 ⇒ 拦住, 不能悄悄留着
        String soIdJson = "[\"" + order.getId().replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
        for (ProductionPlan p : productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(factoryId, soIdJson)) {
            if (!linkedIds.contains(p.getId()) && p.getStatus() != ProductionPlanStatus.CANCELLED) {
                throw new BusinessException(409, "本单已并入生产计划 " + (p.getPlanNumber() != null ? p.getPlanNumber() : p.getId())
                        + " (合并了别的订单), 不能直接取消").withHint("先在生产计划里把本单拆出来, 再取消订单");
            }
        }
        List<ProductionPlan> toCancel = new ArrayList<>();
        List<String> manualLeft = new ArrayList<>();
        for (ProductionPlan p : linked) {
            // 锁住并从库里重读计划行: 状态 / 锁定 / 合并的判断都看锁后的真值 (计划没有乐观锁版本列)
            refresh(p, LockModeType.PESSIMISTIC_WRITE);
            if (p.getStatus() == ProductionPlanStatus.CANCELLED) {
                continue;
            }
            String label = p.getPlanNumber() != null ? p.getPlanNumber() : p.getId();
            if (p.getSourceType() != PlanSourceType.CUSTOMER_ORDER) {
                manualLeft.add(label);
                continue;
            }
            if (p.getSourceOrderIds() != null && p.getSourceOrderIds().stream()
                    .anyMatch(id -> id != null && !id.equals(order.getId()))) {
                throw new BusinessException(409, "生产计划 " + label + " 合并了其它销售订单, 不能随本单一起取消")
                        .withHint("先在生产计划里把本单拆出来, 再取消订单");
            }
            if (p.getStatus() == ProductionPlanStatus.COMPLETED) {
                throw new BusinessException(409, "生产计划 " + label + " 已完成, 不能随订单取消")
                        .withHint("货已做出来: 请走退货 / 改卖给别的订单, 或用「整单撤回」逐批次撤回后再取消");
            }
            toCancel.add(p);
        }

        String planReason = "销售订单 " + soLabel + " 财务批准后取消: " + why;
        List<String> cancelled = new ArrayList<>();
        long schedules = 0;
        long keptStarted = 0;
        for (ProductionPlan p : toCancel) {
            // 经代理调用: 计划自己的闸 (已开工有报工 / 锁定 / 撤回审批中) 照常拦, 拦了整单回滚;
            // 成功后切面级联作废未开工排程并重算日计划 (CancelledPlanScheduleCascade)
            // 带上销售单 id: 单子已按取消人的数据范围校验过, 它名下的计划不再按「计划创建人」二次拦
            productionPlanService.cancelProductionPlan(factoryId, p.getId(), planReason, operatorUserId, order.getId());
            CancelledPlanScheduleCascade.Outcome o = scheduleCascade.summarize(factoryId, p.getId());
            schedules += o.cancelledSchedules();
            keptStarted += o.keptStartedSchedules();
            cancelled.add(p.getPlanNumber() != null ? p.getPlanNumber() : p.getId());
        }

        ActorRef actor = new ActorRef(AuthenticatedActorType.FACTORY_USER, operatorUserId);
        // 先收缺料采购单再取消实例: 取消留痕里会数「还在走」的采购单, 数的应是收完之后剩下的
        com.cretas.aims.service.shortage.ShortageCaseActionService.DraftsCancelled drafts = openCase
                .map(c -> shortageCaseActionService.cancelDraftsForCancelledOrder(c, why, actor))
                .orElse(new com.cretas.aims.service.shortage.ShortageCaseActionService.DraftsCancelled(List.of(), List.of()));
        boolean shortageCancelled = cancelOpenShortageCase(factoryId, openCase, why, operatorUserId);
        // 交付风险实例 (source_type = DELIVERY_RISK) 与缺料实例是两条独立的实例, 订单取消后同样没有意义
        boolean riskCancelled = openRisk.map(c -> {
            deliveryRiskCaseStore.cancelWithSalesOrder(c, "销售订单财务批准后取消: " + why, actor);
            return true;
        }).orElse(false);
        Outcome outcome = new Outcome(order, cancelled, schedules, keptStarted, manualLeft, shortageCancelled,
                riskCancelled, drafts.cancelled(), drafts.keptLive());
        log.info("财务批准后取消销售单: factoryId={}, SO={}, 操作人={}, 计划={}, 排程作废={}, 已开工保留={}, 手工计划未动={}",
                factoryId, order.getId(), operatorUserId, cancelled, schedules, keptStarted, manualLeft);
        return outcome;
    }

    private void requirePermission(String factoryId, Long operatorUserId) {
        boolean allowed = operatorUserId != null && userRepository.findByIdAndFactoryId(operatorUserId, factoryId)
                .map(u -> permissionService.hasPermission(u, PermissionService.SALES_ORDER_CANCEL_APPROVED_PERMISSION))
                .orElse(false);
        if (!allowed) {
            throw new BusinessException(403, "没有「取消已批准销售单」的权限")
                    .withHint("默认只有厂长可以; 厂长可在「业务阈值」sales.permission.cancel_approved_order 里加岗位");
        }
    }

    /**
     * 本单还开着的缺料处理实例一并取消 (否则它会一直挂在已取消的订单上)。走正式的「取消」决定:
     * 和人在缺料页点取消同一条路 —— 写决定台账, 还在走的采购单数出来写进留痕 (采购单不自动撤, 要采购自己处理)。
     */
    private boolean cancelOpenShortageCase(String factoryId, java.util.Optional<ShortageCase> openCase, String reason,
                                           Long operatorUserId) {
        return openCase
                .map(c -> {
                    shortageCaseActionService.decide(factoryId, c.getId(), ShortageCaseAction.Decision.CANCEL,
                            "销售订单财务批准后取消: " + reason, null,
                            new ActorRef(AuthenticatedActorType.FACTORY_USER, operatorUserId));
                    return true;
                })
                .orElse(false);
    }

    /** 锁后从库里重读 (单测里没有持久化上下文时跳过)。 */
    private void refresh(Object entity, LockModeType lock) {
        if (entityManager == null) {
            return;
        }
        if (lock == null) {
            entityManager.refresh(entity);
        } else {
            entityManager.refresh(entity, lock);
        }
    }

    /**
     * 普通取消 (非「财务批准后取消」) 仍要销售写权限; 「取消已批准销售单」权限的持有人没有销售写权限时,
     * 只能走批准后取消这一条路, 不能顺带取消草稿 / 待审的单。
     */
    public void requireSalesWriteForOrdinaryCancel(String factoryId, Long operatorUserId) {
        if (operatorUserId == null) {
            return;
        }
        boolean allowed = userRepository.findByIdAndFactoryId(operatorUserId, factoryId)
                .map(u -> permissionService.hasPermission(u, "sales:read_write"))
                .orElse(false);
        if (!allowed) {
            throw new BusinessException(403, "没有取消销售订单的权限")
                    .withHint("你只有「取消已批准销售单」的权限, 只能取消财务已批准的订单");
        }
    }

    /** 给网页决定「财务已批准的单上要不要显示取消」: 本厂开了开关, 且当前用户有权限。 */
    public boolean currentUserMayCancelApproved(String factoryId, Long userId) {
        if (!enabled(factoryId) || userId == null) {
            return false;
        }
        return userRepository.findByIdAndFactoryId(userId, factoryId)
                .map(u -> permissionService.hasPermission(u, PermissionService.SALES_ORDER_CANCEL_APPROVED_PERMISSION))
                .orElse(false);
    }
}
