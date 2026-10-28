package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDisplayNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 缺料实例上「替缺口做事」(S4 L1 切片 2): 采购交接与人工决定, 都记进执行台账。
 *
 * <p>采购交接 (S4-06 / S5-03): 建议量 = 缺口 − 已在途; 低于供应商起订量就按起订量下并说明;
 * 供应商的采购单位与缺口单位不同 ⇒ 不换算、不自动建单, 交采购员手工处理。
 * 建单走原有的采购服务 ({@link PurchaseService#createPurchaseOrder}: 只到 DRAFT, 价格按它自己的带价链),
 * 同一实例同一物料只要那张草稿还在走就直接复用 —— 点几次都只有一张。
 *
 * <p>人工决定 (S4-09): 取消 / 转人工 / 关闭都必须写原因并留痕; 关闭只在缺口已解决时允许。
 */
@Service
@RequiredArgsConstructor
public class ShortageCaseActionService {

    /**
     * 采购单还「在走」的状态: 草稿到部分收货。取消 / 关闭 / 财务驳回的不算; 收完的也不算 ——
     * 货到了缺口还在 (比如质检没过), 就该允许再下一张。
     */
    private static final Set<PurchaseOrderStatus> LIVE_PO = EnumSet.of(
            PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED, PurchaseOrderStatus.WORKFLOW_RUNNING,
            PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PENDING_FINANCE_REVIEW,
            PurchaseOrderStatus.FINANCE_APPROVED, PurchaseOrderStatus.PARTIAL_RECEIVED);

    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseLineRepository lineRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final ShortageCaseActionRepository actionRepository;
    private final SupplierMaterialRepository supplierMaterialRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseService purchaseService;
    private final UnitContractService unitContractService;
    private final UserRepository userRepository;

    /** 某种物料的采购建议 (只读)。 */
    public record ProcurementProposal(String materialTypeId, String materialName, String unit, BigDecimal gapQty,
                                      BigDecimal onOrderQty, BigDecimal suggestedQty, String supplierId,
                                      String supplierMaterialId, String purchaseUnit, BigDecimal minOrderQty,
                                      Integer leadTimeDays, LocalDate expectedDeliveryDate, boolean canDraft,
                                      List<String> notes, String existingPurchaseOrderId,
                                      String existingPurchaseOrderNumber) {
    }

    public record DraftResult(String purchaseOrderId, String purchaseOrderNumber, boolean reused, BigDecimal quantity,
                              String unit, List<String> notes) {
    }

    @Transactional(readOnly = true)
    public List<ProcurementProposal> proposals(String factoryId, String caseId) {
        ShortageCase c = own(factoryId, caseId);
        List<ProcurementProposal> out = new ArrayList<>();
        for (ShortageCaseLine l : lineRepository.findByCaseIdOrderByIdAsc(c.getId())) {
            if (l.getGapQty().signum() > 0) {
                out.add(propose(c, l));
            }
        }
        return out;
    }

    /** 为一种物料生成 (或复用) 采购草稿。只有本厂用户能下 —— 采购单必须落在一个真实的工厂用户名下。 */
    @Transactional
    public DraftResult createProcurementDraft(String factoryId, String caseId, String materialTypeId, ActorRef actor) {
        requireFactoryUser(actor);
        ShortageCase c = caseRepository.findOpenForUpdate(factoryId, ShortageCase.SourceType.SALES_ORDER,
                        own(factoryId, caseId).getSourceId())
                .filter(open -> open.getId().equals(caseId))
                .orElseThrow(() -> new BusinessException(409, "缺料实例已关闭, 不能再生成采购草稿")
                        .withHint("刷新页面查看最新状态; 若还有缺口, 系统会在下一次财务审核 / 入库时建新实例"));
        if (c.getStage().isTerminal()) {
            throw new BusinessException(409, "缺料实例已结束 (" + c.getStage().label() + "), 不能再生成采购草稿")
                    .withHint("已结束的实例只能查看; 需要采购请直接到「采购订单」新建");
        }
        ShortageCaseLine line = lineRepository.findByCaseIdOrderByIdAsc(c.getId()).stream()
                .filter(l -> l.getMaterialTypeId().equals(materialTypeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "该实例里没有这种物料"));

        Optional<ShortageCaseAction> open = actionRepository.findByCaseIdAndMaterialTypeIdAndActionTypeAndStatus(
                c.getId(), materialTypeId, ShortageCaseAction.ActionType.PURCHASE_DRAFT, ShortageCaseAction.Status.DONE);
        if (open.isPresent()) {
            PurchaseOrder po = purchaseOrderRepository.findByIdAndFactoryId(open.get().getResultRef(), factoryId).orElse(null);
            if (po != null && LIVE_PO.contains(po.getStatus())) {
                return new DraftResult(po.getId(), po.getOrderNumber(), true, open.get().getQuantity(), open.get().getUnit(),
                        List.of("这种物料已经有一张在走的采购单, 没有重复生成"));
            }
            open.get().setStatus(ShortageCaseAction.Status.SUPERSEDED); // 那张单已取消 / 驳回 / 删除: 允许重新生成
            actionRepository.saveAndFlush(open.get());
        }

        if (line.getGapQty().signum() <= 0) {
            throw new BusinessException(409, "该物料已没有缺口, 不需要采购")
                    .withHint("先点「按当前库存重新核对」确认缺口; 仍要备货请直接到「采购订单」新建");
        }
        ProcurementProposal p = propose(c, line);
        if (!p.canDraft()) {
            throw new BusinessException(409, "不能自动生成采购草稿: " + String.join("; ", p.notes()))
                    .withHint("按提示补齐供应商关系或单位后再生成; 也可以到「采购订单」手工下单");
        }

        CreatePurchaseOrderRequest req = new CreatePurchaseOrderRequest();
        req.setSupplierId(p.supplierId());
        req.setOrderDate(LocalDate.now());
        req.setExpectedDeliveryDate(p.expectedDeliveryDate());
        req.setSalesOrderId(c.getSourceId());
        req.setRemark("缺料实例 " + c.getId() + " 的采购交接");
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO item = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        item.setMaterialTypeId(line.getMaterialTypeId());
        item.setMaterialName(line.getMaterialName());
        item.setSupplierMaterialId(p.supplierMaterialId());
        item.setQuantity(p.suggestedQty());
        item.setUnit(line.getUnit());
        item.setRemark(String.join("; ", p.notes()));
        req.setItems(List.of(item));

        PurchaseOrder po = purchaseService.createPurchaseOrder(factoryId, req, actor.id());

        ShortageCaseAction a = new ShortageCaseAction();
        a.setCaseId(c.getId());
        a.setFactoryId(factoryId);
        a.setActionType(ShortageCaseAction.ActionType.PURCHASE_DRAFT);
        a.setMaterialTypeId(materialTypeId);
        a.setStatus(ShortageCaseAction.Status.DONE);
        a.setResultRef(po.getId());
        a.setQuantity(p.suggestedQty());
        a.setUnit(line.getUnit());
        a.setActorType(actor.type().name());
        a.setActorId(actor.id());
        actionRepository.saveAndFlush(a);

        Stage from = c.getStage();
        if (from == Stage.DETECTED || from == Stage.PARTIALLY_RESOLVED) {
            c.setStage(Stage.EXECUTING);
            caseRepository.saveAndFlush(c);
        }
        event(c, from, c.getStage(), "PURCHASE_DRAFT_CREATED",
                (line.getMaterialName() != null ? line.getMaterialName() : materialTypeId) + " 采购草稿 " + po.getOrderNumber()
                        + " " + p.suggestedQty().stripTrailingZeros().toPlainString() + " " + UnitDisplayNames.display(line.getUnit()), actor);
        return new DraftResult(po.getId(), po.getOrderNumber(), false, p.suggestedQty(), line.getUnit(), p.notes());
    }

    /** 人工决定: 取消 / 转人工 / 关闭。原因必填; 关闭只在缺口已解决或已转人工时允许。 */
    @Transactional
    public ShortageCase decide(String factoryId, String caseId, ShortageCaseAction.Decision decision, String reason,
                               Long ownerUserId, ActorRef actor) {
        if (actor == null) {
            throw new BusinessException(401, "无法识别当前操作人");
        }
        if (decision == null) {
            throw new BusinessException(400, "缺少处理决定");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "请写明原因");
        }
        // 与生成草稿抢同一把行锁: 否则并发的「取消」和「生成草稿」会各自提交
        ShortageCase c = caseRepository.findOpenForUpdate(factoryId, ShortageCase.SourceType.SALES_ORDER,
                        own(factoryId, caseId).getSourceId())
                .filter(open -> open.getId().equals(caseId))
                .orElseThrow(() -> new BusinessException(409, "缺料实例已结束")
                        .withHint("已结束的实例不能再改; 刷新页面查看最新状态"));
        // 转人工 (MANUAL) 只停自动刷新, 实例仍未关闭 (closedAt 为空) —— 之后还要能取消 / 关闭, 否则它永远占着这张订单的位置
        Stage from = c.getStage();
        LocalDateTime now = LocalDateTime.now();
        String detail = reason.trim();
        switch (decision) {
            case CANCEL -> {
                c.setStage(Stage.CANCELLED);
                c.setClosedAt(now);
                long liveDrafts = liveDraftCount(c);
                if (liveDrafts > 0) {
                    detail += " (仍有 " + liveDrafts + " 张在走的采购单, 需到「采购订单」自行处理)";
                }
            }
            case MANUAL -> {
                if (ownerUserId != null && userRepository.findByIdAndFactoryId(ownerUserId, factoryId).isEmpty()) {
                    throw new BusinessException(400, "指定的负责人不是本厂用户");
                }
                c.setStage(Stage.MANUAL);
                c.setOwnerUserId(ownerUserId);
            }
            case CLOSE -> {
                // 转人工后由负责人确认结束, 不再要求系统算出的「缺口已解决」(人工处理期间不自动刷新)
                if (from != Stage.GAP_RESOLVED && from != Stage.MANUAL) {
                    throw new BusinessException(409, "缺口还没解决, 不能关闭; 不再处理请用「取消」并写明原因")
                            .withHint("等缺口解决后再关闭, 或改用「取消」/「转人工」");
                }
                c.setStage(Stage.CLOSED);
                c.setClosedAt(now);
            }
        }
        caseRepository.saveAndFlush(c);

        ShortageCaseAction a = new ShortageCaseAction();
        a.setCaseId(c.getId());
        a.setFactoryId(factoryId);
        a.setActionType(ShortageCaseAction.ActionType.DECISION);
        a.setStatus(ShortageCaseAction.Status.DONE);
        a.setDecision(decision);
        a.setReason(reason.trim());
        a.setActorType(actor.type().name());
        a.setActorId(actor.id());
        actionRepository.saveAndFlush(a);
        event(c, from, c.getStage(), "DECISION_" + decision.name(), detail, actor);
        return c;
    }

    private long liveDraftCount(ShortageCase c) {
        return actionRepository.findByCaseIdOrderByIdAsc(c.getId()).stream()
                .filter(a -> a.getActionType() == ShortageCaseAction.ActionType.PURCHASE_DRAFT
                        && a.getStatus() == ShortageCaseAction.Status.DONE && a.getResultRef() != null)
                .map(a -> purchaseOrderRepository.findByIdAndFactoryId(a.getResultRef(), c.getFactoryId()).orElse(null))
                .filter(po -> po != null && LIVE_PO.contains(po.getStatus()))
                .count();
    }

    // ---------------------------------------------------------------- internals

    ProcurementProposal propose(ShortageCase c, ShortageCaseLine l) {
        List<String> notes = new ArrayList<>();
        BigDecimal need = l.getGapQty().subtract(l.getOnOrderQty()).max(BigDecimal.ZERO);
        Optional<ShortageCaseAction> open = actionRepository.findByCaseIdAndMaterialTypeIdAndActionTypeAndStatus(
                c.getId(), l.getMaterialTypeId(), ShortageCaseAction.ActionType.PURCHASE_DRAFT, ShortageCaseAction.Status.DONE);
        PurchaseOrder existing = open.flatMap(a -> purchaseOrderRepository.findByIdAndFactoryId(a.getResultRef(), c.getFactoryId()))
                .filter(po -> LIVE_PO.contains(po.getStatus()))
                .orElse(null);

        SupplierMaterial sm = supplierMaterialRepository
                .findByFactoryIdAndMaterialTypeIdOrderByActiveDescPreferredDescCreatedAtDesc(c.getFactoryId(), l.getMaterialTypeId())
                .stream().filter(s -> Boolean.TRUE.equals(s.getActive())).findFirst().orElse(null);

        boolean canDraft = existing == null;
        if (existing != null) {
            notes.add("已生成采购单 " + existing.getOrderNumber());
        }
        if (need.signum() == 0) {
            notes.add("在途量已覆盖缺口, 不建议再下单");
            canDraft = false;
        }
        if (l.getOnOrderUnitMismatchLines() > 0) {
            // 单位不同的在途行没计进在途量: 照缺口再下一张就可能重复采购
            notes.add("有 " + l.getOnOrderUnitMismatchLines() + " 行在途采购单位不同, 未计入, 请人工核对");
            canDraft = false;
        }
        if (l.getUnit() == null) {
            notes.add("物料单位未知, 不能自动定量");
            canDraft = false;
        }
        BigDecimal suggested = need;
        LocalDate expected = null;
        if (sm == null) {
            notes.add("这种物料没有启用的供应商关系, 需采购员选择供应商");
            canDraft = false;
        } else {
            if (sm.getPurchaseUnit() != null && l.getUnit() != null
                    && !unitContractService.areEquivalent(c.getFactoryId(), sm.getPurchaseUnit(), l.getUnit())) {
                notes.add("供应商按「" + UnitDisplayNames.display(sm.getPurchaseUnit()) + "」采购, 缺口按「"
                        + UnitDisplayNames.display(l.getUnit()) + "」计, 不换算, 需采购员手工下单");
                canDraft = false;
            }
            boolean hasMoq = sm.getMinOrderQuantity() != null && sm.getMinOrderQuantity().signum() > 0;
            if (hasMoq && sm.getPurchaseUnit() == null) {
                // 起订量的单位就是采购单位; 没填就不知道「20」是 20 箱还是 20 kg, 不套用
                notes.add("供应商起订量 " + plain(sm.getMinOrderQuantity()) + " 没填采购单位, 未套用, 请采购员核对");
            } else if (hasMoq && suggested.signum() > 0 && suggested.compareTo(sm.getMinOrderQuantity()) < 0) {
                notes.add("缺口 " + plain(suggested) + " 低于起订量 " + plain(sm.getMinOrderQuantity()) + ", 按起订量下单");
                suggested = sm.getMinOrderQuantity();
            }
            if (sm.getLeadTimeDays() != null && sm.getLeadTimeDays() >= 0) {
                expected = LocalDate.now().plusDays(sm.getLeadTimeDays());
            } else {
                notes.add("供应商交期未填, 预计到货日期留空");
            }
        }
        suggested = suggested.setScale(Math.max(0, suggested.stripTrailingZeros().scale()), RoundingMode.UNNECESSARY);
        return new ProcurementProposal(l.getMaterialTypeId(), l.getMaterialName(), l.getUnit(), l.getGapQty(),
                l.getOnOrderQty(), suggested, sm == null ? null : sm.getSupplierId(), sm == null ? null : sm.getId(),
                sm == null ? null : sm.getPurchaseUnit(), sm == null ? null : sm.getMinOrderQuantity(),
                sm == null ? null : sm.getLeadTimeDays(), expected, canDraft && suggested.signum() > 0, notes,
                existing == null ? null : existing.getId(), existing == null ? null : existing.getOrderNumber());
    }

    private ShortageCase own(String factoryId, String caseId) {
        return caseRepository.findByIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "缺料实例不存在或不属于本厂"));
    }

    private static void requireFactoryUser(ActorRef actor) {
        if (actor == null) {
            throw new BusinessException(401, "无法识别当前操作人");
        }
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            throw new BusinessException(403, "采购草稿要由本厂的采购人员生成 (采购单必须落在一个工厂用户名下)");
        }
    }

    private void event(ShortageCase c, Stage from, Stage to, String reason, String detail, ActorRef actor) {
        ShortageCaseEvent e = new ShortageCaseEvent();
        e.setCaseId(c.getId());
        e.setFactoryId(c.getFactoryId());
        e.setEventType(from == to ? ShortageCaseEvent.EventType.NOTE : ShortageCaseEvent.EventType.STAGE_CHANGED);
        e.setFromStage(from);
        e.setToStage(to);
        e.setTriggerReason(reason);
        e.setDetail(detail);
        e.setActorType(actor.type().name());
        e.setActorId(actor.id());
        eventRepository.save(e);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
