package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.CostDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.shortage.CostDeviationEvaluator.Outcome;
import com.cretas.aims.service.shortage.CostDeviationEvaluator.Reading;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 成本 / 损耗异常实例的写入: 按读数建 / 刷新, 指派负责人, 开始核查, 写处理结果。每步留痕。 */
@Service
@RequiredArgsConstructor
public class CostDeviationCaseWriter {

    public static final List<ShortageCase.SourceType> SOURCES =
            List.of(ShortageCase.SourceType.COST_VARIANCE, ShortageCase.SourceType.MATERIAL_BALANCE);
    public static final String ROLE_FINANCE = "finance_manager";

    public enum Applied { CREATED, REFRESHED, BELOW_THRESHOLD, UNCHANGED, ALREADY_RESOLVED, NONE }

    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final CostDeviationCaseDetailRepository detailRepository;
    private final UserRepository userRepository;
    private final CostDeviationOwnerResolver ownerResolver;

    /** UNKNOWN 不进这里: 算不出既不开实例, 也不改已开实例的读数。 */
    @Transactional
    public Applied apply(String factoryId, Reading r, String trigger) {
        ShortageCase open = caseRepository.findOpenForUpdate(factoryId, r.sourceType(), r.planId()).orElse(null);
        if (open == null) {
            if (r.outcome() != Outcome.DEVIATED) return Applied.NONE;
            // 已有人下过结论且事实没变: 不重开, 否则每次扫描都把关掉的异常翻回来
            List<ShortageCase> closed = detailRepository.findClosedCases(factoryId, r.sourceType(), r.planId(),
                    PageRequest.of(0, 1));
            if (!closed.isEmpty() && r.fingerprint().equals(closed.get(0).getInputFingerprint())) {
                return Applied.ALREADY_RESOLVED;
            }
            create(factoryId, r, trigger);
            return Applied.CREATED;
        }
        if (r.fingerprint().equals(open.getInputFingerprint())) return Applied.UNCHANGED;

        CostDeviationCaseDetail d = detailRepository.findById(open.getId())
                .orElseThrow(() -> new IllegalStateException("成本异常实例缺读数: " + open.getId()));
        LocalDateTime now = LocalDateTime.now();
        open.setInputFingerprint(r.fingerprint());
        open.setLastRefreshedAt(now);
        open.setLastRefreshReason(trigger);
        open.setGapLineCount(r.deviatedCount());
        d.setMetricPct(r.metricPct());
        d.setThresholdPct(r.thresholdPct());
        d.setAmountVariance(r.amount());
        d.setUnknownItemCount(r.unknownItems());
        d.setSummary(r.summary());
        boolean within = r.outcome() == Outcome.WITHIN;
        d.setBelowThresholdAt(within ? now : null);
        caseRepository.save(open);
        detailRepository.save(d);
        event(open, ShortageCaseEvent.EventType.REFRESHED, open.getStage(), trigger,
                (within ? "读数已回到阈值内, 仍需负责人给出结论: " : "读数变化: ") + r.summary(), null);
        return within ? Applied.BELOW_THRESHOLD : Applied.REFRESHED;
    }

    private void create(String factoryId, Reading r, String trigger) {
        LocalDateTime now = LocalDateTime.now();
        ShortageCase c = new ShortageCase();
        c.setFactoryId(factoryId);
        c.setSourceType(r.sourceType());
        c.setSourceId(r.planId());
        c.setStage(ShortageCase.Stage.DETECTED);
        CostDeviationOwnerResolver.Owner owner = ownerResolver.resolve(factoryId, r.sourceType());
        c.setOwnerRole(owner.role());
        c.setOwnerUserId(owner.userId());
        c.setGapLineCount(r.deviatedCount());
        c.setInputFingerprint(r.fingerprint());
        c.setLastRefreshedAt(now);
        c.setLastRefreshReason(trigger);
        ShortageCase saved = caseRepository.save(c);

        CostDeviationCaseDetail d = new CostDeviationCaseDetail();
        d.setCaseId(saved.getId());
        d.setFactoryId(factoryId);
        d.setPlanId(r.planId());
        d.setPlanNumber(r.planNumber());
        d.setProductName(r.productName());
        d.setMetricPct(r.metricPct());
        d.setThresholdPct(r.thresholdPct());
        d.setAmountVariance(r.amount());
        d.setUnknownItemCount(r.unknownItems());
        d.setSummary(r.summary());
        detailRepository.save(d);
        event(saved, ShortageCaseEvent.EventType.CREATED, saved.getStage(), trigger, r.summary(), null);
        event(saved, ShortageCaseEvent.EventType.NOTE, saved.getStage(), "OWNER_ASSIGNED_BY_CONFIG",
                "按工厂配置派给 " + (owner.userId() != null ? "指定负责人 (用户 " + owner.userId() + ")" : "岗位 " + owner.role())
                        + " [" + owner.source() + "]", null);
    }

    @Transactional
    public void assign(String factoryId, String caseId, Long ownerUserId, ActorRef actor) {
        ShortageCase c = open(factoryId, caseId);
        var user = userRepository.findByIdAndFactoryId(ownerUserId, factoryId)
                .orElseThrow(() -> new BusinessException(400, "负责人不属于本厂"));
        c.setOwnerUserId(ownerUserId);
        caseRepository.save(c);
        event(c, ShortageCaseEvent.EventType.NOTE, c.getStage(), "OWNER_ASSIGNED",
                "负责人: " + (user.getFullName() != null ? user.getFullName() : user.getUsername()), actor);
    }

    @Transactional
    public void startVerify(String factoryId, String caseId, ActorRef actor) {
        ShortageCase c = open(factoryId, caseId);
        if (c.getStage() != ShortageCase.Stage.DETECTED) {
            throw new BusinessException(409, "这条异常已在核查中").withHint("核查完在下方填写处理结果");
        }
        if (c.getOwnerUserId() == null) {
            throw new BusinessException(409, "请先指定负责人, 再开始核查").withHint("在「负责人」处选人并点指定");
        }
        ShortageCase.Stage from = c.getStage();
        c.setStage(ShortageCase.Stage.EXECUTING);
        caseRepository.save(c);
        event(c, ShortageCaseEvent.EventType.STAGE_CHANGED, from, "VERIFY_STARTED", "开始核查", actor);
    }

    @Transactional
    public void resolve(String factoryId, String caseId, CostDeviationCaseDetail.Resolution code, String note, ActorRef actor) {
        ShortageCase c = open(factoryId, caseId);
        if (c.getStage() != ShortageCase.Stage.EXECUTING) {
            throw new BusinessException(409, "请先开始核查, 再填写处理结果").withHint("指定负责人后点「开始核查」");
        }
        if (code == null || note == null || note.isBlank()) {
            throw new BusinessException(400, "处理结果和说明都要填写");
        }
        CostDeviationCaseDetail d = detailRepository.findById(c.getId())
                .orElseThrow(() -> new IllegalStateException("成本异常实例缺读数: " + c.getId()));
        LocalDateTime now = LocalDateTime.now();
        ShortageCase.Stage from = c.getStage();
        c.setStage(ShortageCase.Stage.CLOSED);
        c.setClosedAt(now);
        d.setResolutionCode(code);
        d.setResolutionNote(note.trim());
        d.setResolvedBy(actor.id());
        d.setResolvedAt(now);
        caseRepository.save(c);
        detailRepository.save(d);
        event(c, ShortageCaseEvent.EventType.STAGE_CHANGED, from, "RESOLVED", code.label() + ": " + note.trim(), actor);
    }

    private ShortageCase open(String factoryId, String caseId) {
        ShortageCase c = caseRepository.findByIdAndFactoryIdAndSourceTypeIn(caseId, factoryId, SOURCES)
                .orElseThrow(() -> new BusinessException(404, "异常不存在或不属于本厂"));
        if (c.getStage().isTerminal()) {
            throw new BusinessException(409, "这条异常已结束 (" + c.getStage().label() + ")")
                    .withHint("已结束的异常只能查看; 读数再变化时扫描会新建一条");
        }
        return c;
    }

    private void event(ShortageCase c, ShortageCaseEvent.EventType type, ShortageCase.Stage from, String reason,
                       String detail, ActorRef actor) {
        ShortageCaseEvent e = new ShortageCaseEvent();
        e.setCaseId(c.getId());
        e.setFactoryId(c.getFactoryId());
        e.setEventType(type);
        e.setFromStage(from);
        e.setToStage(c.getStage());
        e.setTriggerReason(reason);
        e.setDetail(detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        e.setActorType(actor == null ? "SYSTEM" : actor.type().name());
        e.setActorId(actor == null ? null : actor.id());
        eventRepository.save(e);
    }
}
