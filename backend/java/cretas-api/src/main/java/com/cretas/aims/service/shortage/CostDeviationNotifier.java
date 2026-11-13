package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.repository.shortage.CostDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 新建异常后推给负责人: 走现有站内通知 (NotificationService → notifications 表, App 通知列表可见)。
 * 指定到人发给人, 只配了岗位发给岗位 (该岗位没人时由通知服务转厂长)。推送失败抛出, 不回滚已建实例 (事务独立),
 * 并由调用方计数, ⛔ 不当成已送达。
 */
@Service
@RequiredArgsConstructor
public class CostDeviationNotifier {

    private final ShortageCaseRepository caseRepository;
    private final CostDeviationCaseDetailRepository detailRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final NotificationService notificationService;

    /**
     * @return true = 已写入通知; false = 找不到刚建的实例。
     *         推送失败直接抛出, 由调用方计入「推送失败」: ⛔ 不当成已送达。
     */
    @Transactional
    public boolean notifyCreated(String factoryId, ShortageCase.SourceType type, String planId) {
        Optional<ShortageCase> opt = caseRepository.findOpenForUpdate(factoryId, type, planId);
        if (opt.isEmpty()) return false;
        ShortageCase c = opt.get();
        CostDeviationCaseDetail d = detailRepository.findById(c.getId()).orElse(null);
        if (d == null) return false;
        String title = type == ShortageCase.SourceType.COST_VARIANCE ? "成本差异异常待核查" : "物料损耗异常待核查";
        String body = CostDeviationExplanation.notificationBody(type, d);
        if (c.getOwnerUserId() != null) {
            notificationService.notifyUser(factoryId, c.getOwnerUserId(), title, body);
        } else {
            notificationService.notifyRole(factoryId, c.getOwnerRole(), title, body);
        }
        event(c, "NOTIFIED", "已推送站内通知给 "
                + (c.getOwnerUserId() != null ? "负责人 (用户 " + c.getOwnerUserId() + ")" : "岗位 " + c.getOwnerRole()));
        return true;
    }

    private void event(ShortageCase c, String reason, String detail) {
        ShortageCaseEvent e = new ShortageCaseEvent();
        e.setCaseId(c.getId());
        e.setFactoryId(c.getFactoryId());
        e.setEventType(ShortageCaseEvent.EventType.NOTE);
        e.setFromStage(c.getStage());
        e.setToStage(c.getStage());
        e.setTriggerReason(reason);
        e.setDetail(detail);
        e.setActorType("SYSTEM");
        eventRepository.save(e);
    }
}
