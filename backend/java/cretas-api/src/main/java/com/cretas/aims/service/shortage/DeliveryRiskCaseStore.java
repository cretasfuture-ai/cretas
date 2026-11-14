package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.shortage.DeliveryRiskCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.SourceType;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.DeliveryRiskCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseOutcomeCheckRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.shortage.DeliveryRiskService.OrderAssessment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 交付风险实例的写入: 建 / 刷新、指派处理任务、核对、结束。落在缺料同一组实例表上 (source_type = DELIVERY_RISK),
 * 一张订单同时只有一条未结束的实例。风险消失不自动关闭: 结果要由负责人写。
 */
@Service
@RequiredArgsConstructor
public class DeliveryRiskCaseStore {

    public enum Written { CREATED, REFRESHED, NONE }

    private static final String DEFAULT_OWNER = "production_manager";
    private static final String MATERIAL_OWNER = "procurement_manager";

    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final ShortageCaseActionRepository actionRepository;
    private final ShortageCaseOutcomeCheckRepository checkRepository;
    private final DeliveryRiskCaseDetailRepository detailRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ShortageCase own(String factoryId, String caseId) {
        return caseRepository.findByIdAndFactoryIdAndSourceTypeIn(caseId, factoryId, List.of(SourceType.DELIVERY_RISK))
                .orElseThrow(() -> new BusinessException(404, "交付风险实例不存在或不属于本厂"));
    }

    @Transactional
    public Written record(String factoryId, OrderAssessment a, String fingerprint, ActorRef actor) {
        Optional<ShortageCase> open = caseRepository.findOpenForUpdate(factoryId, SourceType.DELIVERY_RISK, a.salesOrderId());
        if (open.isPresent()) {
            ShortageCase c = open.get();
            if (fingerprint.equals(c.getInputFingerprint())) {
                saveDetail(c, a);
                return Written.NONE;
            }
            refresh(c, a, fingerprint, actor);
            return Written.REFRESHED;
        }
        if (!"RISK".equals(a.level())) {
            return Written.NONE;
        }
        boolean sameAsClosed = caseRepository
                .findFirstByFactoryIdAndSourceTypeAndSourceIdAndClosedAtIsNotNullOrderByClosedAtDesc(
                        factoryId, SourceType.DELIVERY_RISK, a.salesOrderId())
                .filter(last -> fingerprint.equals(last.getInputFingerprint())).isPresent();
        if (sameAsClosed) {
            return Written.NONE; // 这个风险已经处理并写过结果; 交期或信号变了才重开
        }
        ShortageCase c = new ShortageCase();
        c.setFactoryId(factoryId);
        c.setSourceType(SourceType.DELIVERY_RISK);
        c.setSourceId(a.salesOrderId());
        c.setStage(Stage.DETECTED);
        boolean materialOnly = a.signals().size() == 1 && "MATERIAL_LATE".equals(a.signals().get(0).code());
        c.setOwnerRole(materialOnly ? MATERIAL_OWNER : DEFAULT_OWNER);
        c.setInputFingerprint(fingerprint);
        c.setLastRefreshedAt(LocalDateTime.now());
        c.setLastRefreshReason("DELIVERY_RISK_SCAN");
        c = caseRepository.saveAndFlush(c);
        saveDetail(c, a);
        event(c, ShortageCaseEvent.EventType.CREATED, null, c.getStage(), "DELIVERY_RISK_SCAN", summary(a), actor);
        return Written.CREATED;
    }

    @Transactional
    public void recordCheck(String factoryId, String caseId, OrderAssessment a, String fingerprint, ActorRef actor) {
        ShortageCase c = lockOpen(factoryId, caseId);
        refresh(c, a, fingerprint, actor);
        check(c, a, null, actor);
    }

    /** 指派处理任务: 只记录谁去做什么, 不动排程和采购。可以改派。 */
    @Transactional
    public ShortageCase assign(String factoryId, String caseId, Long ownerUserId, String task, ActorRef actor) {
        if (ownerUserId == null) {
            throw new BusinessException(400, "请选择负责人");
        }
        if (task == null || task.isBlank()) {
            throw new BusinessException(400, "请写明要做什么");
        }
        ShortageCase c = lockOpen(factoryId, caseId);
        var user = userRepository.findByIdAndFactoryId(ownerUserId, factoryId)
                .orElseThrow(() -> new BusinessException(400, "指定的负责人不是本厂用户"));
        Stage from = c.getStage();
        c.setOwnerUserId(ownerUserId);
        c.setStage(Stage.EXECUTING);
        caseRepository.saveAndFlush(c);
        ShortageCaseAction a = action(c, ShortageCaseAction.ActionType.HANDLING_TASK, task.trim(), actor);
        a.setResultRef(String.valueOf(ownerUserId));
        actionRepository.saveAndFlush(a);
        event(c, from == Stage.EXECUTING ? ShortageCaseEvent.EventType.NOTE : ShortageCaseEvent.EventType.STAGE_CHANGED,
                from, c.getStage(), "HANDLING_TASK_ASSIGNED", "指派给 " + user.getFullName() + ": " + task.trim(), actor);
        return c;
    }

    /** 写处理结果并结束。CLOSE 要先指派过; 风险还在也能关 (比如已和客户改期), 核对结论如实留在实例上。 */
    @Transactional
    public ShortageCaseOutcomeCheck finish(String factoryId, String caseId, ShortageCaseAction.Decision decision,
                                           String result, OrderAssessment a, ActorRef actor) {
        if (decision != ShortageCaseAction.Decision.CLOSE && decision != ShortageCaseAction.Decision.CANCEL) {
            throw new BusinessException(400, "交付风险只能关闭或取消");
        }
        if (result == null || result.isBlank()) {
            throw new BusinessException(400, "请写明处理结果");
        }
        ShortageCase c = lockOpen(factoryId, caseId);
        if (decision == ShortageCaseAction.Decision.CLOSE && c.getStage() != Stage.EXECUTING) {
            throw new BusinessException(409, "还没有指派负责人, 不能关闭")
                    .withHint("先指派处理任务; 如果是误报, 请用「取消」并写明原因");
        }
        Stage from = c.getStage();
        c.setStage(decision == ShortageCaseAction.Decision.CLOSE ? Stage.CLOSED : Stage.CANCELLED);
        c.setClosedAt(LocalDateTime.now());
        caseRepository.saveAndFlush(c);
        saveDetail(c, a);
        ShortageCaseAction act = action(c, ShortageCaseAction.ActionType.DECISION, result.trim(), actor);
        act.setDecision(decision);
        act = actionRepository.saveAndFlush(act);
        event(c, ShortageCaseEvent.EventType.STAGE_CHANGED, from, c.getStage(), "DECISION_" + decision.name(), result.trim(), actor);
        return check(c, a, act.getId(), actor);
    }

    /**
     * 订单取消时结束它还开着的交付风险实例 (调用方已持有销售单行锁): 阶段置「已取消」、写决定台账和事件留痕。
     * 订单都取消了, 「有没有延期风险」不再有意义, 不要求负责人再写处理结果, 也不做风险重评。
     *
     * @param c 调用方已用 {@code findOpenForUpdate(factoryId, DELIVERY_RISK, 订单id)} 锁住的未结束实例
     */
    @Transactional
    public void cancelWithSalesOrder(ShortageCase c, String reason, ActorRef actor) {
        if (c.getSourceType() != SourceType.DELIVERY_RISK || c.getClosedAt() != null) {
            throw new BusinessException(409, "实例已结束或不是交付风险实例").withHint("已结束的实例只能查看, 不需要再处理");
        }
        Stage from = c.getStage();
        c.setStage(Stage.CANCELLED);
        c.setClosedAt(LocalDateTime.now());
        caseRepository.saveAndFlush(c);
        ShortageCaseAction act = action(c, ShortageCaseAction.ActionType.DECISION, reason.trim(), actor);
        act.setDecision(ShortageCaseAction.Decision.CANCEL);
        actionRepository.saveAndFlush(act);
        event(c, ShortageCaseEvent.EventType.STAGE_CHANGED, from, c.getStage(), "SALES_ORDER_CANCELLED", reason.trim(), actor);
    }

    private ShortageCase lockOpen(String factoryId, String caseId) {
        return caseRepository.findOpenForUpdate(factoryId, SourceType.DELIVERY_RISK, own(factoryId, caseId).getSourceId())
                .filter(c -> c.getId().equals(caseId))
                .orElseThrow(() -> new BusinessException(409, "实例已结束").withHint("已结束的实例只能查看"));
    }

    private void refresh(ShortageCase c, OrderAssessment a, String fingerprint, ActorRef actor) {
        boolean changed = !fingerprint.equals(c.getInputFingerprint());
        c.setInputFingerprint(fingerprint);
        c.setLastRefreshedAt(LocalDateTime.now());
        c.setLastRefreshReason("DELIVERY_RISK_SCAN");
        caseRepository.saveAndFlush(c);
        saveDetail(c, a);
        if (changed) {
            event(c, ShortageCaseEvent.EventType.REFRESHED, c.getStage(), c.getStage(), "DELIVERY_RISK_SCAN", summary(a), actor);
        }
    }

    private ShortageCaseOutcomeCheck check(ShortageCase c, OrderAssessment a, Long actionId, ActorRef actor) {
        ShortageCaseOutcomeCheck k = new ShortageCaseOutcomeCheck();
        k.setCaseId(c.getId());
        k.setFactoryId(c.getFactoryId());
        k.setActionId(actionId);
        k.setTrigger(ShortageCaseOutcomeCheck.Trigger.RISK_RECHECK);
        k.setVerdict(switch (a.level()) {
            case "OK" -> ShortageCaseOutcomeCheck.Verdict.ACHIEVED;
            case "RISK" -> ShortageCaseOutcomeCheck.Verdict.NOT_ACHIEVED;
            default -> ShortageCaseOutcomeCheck.Verdict.UNKNOWN;
        });
        k.setGapClosed(a.materialGapCount() == 0);
        k.setOpenGapLines(a.materialGapCount());
        k.setDueDate(a.dueDate());
        k.setLatestPlannedEnd(a.latestPlannedEnd());
        k.setLateScheduleCount(a.lateSchedules());
        k.setDetail(summary(a));
        k.setActorType(actor.type().name());
        k.setActorId(actor.id());
        return checkRepository.saveAndFlush(k);
    }

    private static String summary(OrderAssessment a) {
        if (!a.signals().isEmpty()) {
            return "风险: " + String.join("、", a.signals().stream().map(DeliveryRiskService.SignalView::label).toList());
        }
        return "OK".equals(a.level()) ? "当前数据未发现延期风险" : "无法判定: " + String.join("; ", a.unknowns());
    }

    private void saveDetail(ShortageCase c, OrderAssessment a) {
        DeliveryRiskCaseDetail d = detailRepository.findById(c.getId()).orElseGet(DeliveryRiskCaseDetail::new);
        d.setCaseId(c.getId());
        d.setFactoryId(c.getFactoryId());
        d.setDueDate(a.dueDate());
        d.setRiskLevel(a.level());
        try {
            d.setAssessmentJson(objectMapper.writeValueAsString(a));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("交付风险评估无法序列化", e);
        }
        d.setAssessedAt(a.assessedAt());
        detailRepository.saveAndFlush(d);
    }

    private ShortageCaseAction action(ShortageCase c, ShortageCaseAction.ActionType type, String reason, ActorRef actor) {
        ShortageCaseAction a = new ShortageCaseAction();
        a.setCaseId(c.getId());
        a.setFactoryId(c.getFactoryId());
        a.setActionType(type);
        a.setStatus(ShortageCaseAction.Status.DONE);
        a.setReason(reason);
        a.setActorType(actor.type().name());
        a.setActorId(actor.id());
        return a;
    }

    private void event(ShortageCase c, ShortageCaseEvent.EventType type, Stage from, Stage to, String reason, String detail,
                       ActorRef actor) {
        ShortageCaseEvent e = new ShortageCaseEvent();
        e.setCaseId(c.getId());
        e.setFactoryId(c.getFactoryId());
        e.setEventType(type);
        e.setFromStage(from);
        e.setToStage(to);
        e.setTriggerReason(reason);
        e.setDetail(detail);
        e.setActorType(actor.type().name());
        e.setActorId(actor.id());
        eventRepository.save(e);
    }
}
