package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.unit.UnitDisplayNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * S4-08 开工前的缺料闸 (S4 L1 切片 4)。只在工厂开了「缺料时拦开工」时生效 ——
 * 默认关, 开工照旧, 即六膳门 N1「开工无条件化」(Owner 2026-11-03 裁定按工厂开关)。
 *
 * <p>开了以后: 计划所属销售订单有<b>未解决</b>的缺料实例 (未关闭、阶段不是「缺口已解决」、还有缺口行)
 * ⇒ 409 拦开工, 告诉用户缺什么、去哪处理。用户确需开工可以带原因强制开工, 原因记进那个缺料实例的处理记录。
 *
 * <p>在开工事务里调用 (加入调用方事务): 拦下时整次开工回滚; 强制开工时处理记录与开工一起提交。
 */
@Component
@RequiredArgsConstructor
public class ShortageStartGate {

    /** 前端据此弹「填写强制开工原因」的错误码。 */
    public static final String CODE = "SHORTAGE_CASE_OPEN";

    private final FactorySettingsRepository settingsRepository;
    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseLineRepository lineRepository;
    private final ShortageCaseEventRepository eventRepository;

    public void check(String factoryId, ProductionPlan plan, String forceStartReason, Long operatorId) {
        if (!Boolean.TRUE.equals(settingsRepository.findBlockStartOnOpenShortageByFactoryId(factoryId))) {
            return; // 默认: N1 开工无条件化
        }
        String salesOrderId = plan.getSourceOrderId();
        if (salesOrderId == null || salesOrderId.isBlank()) {
            return; // 不是订单驱动的计划, 没有缺料实例可关联
        }
        ShortageCase c = caseRepository.findOpenForUpdate(factoryId, ShortageCase.SourceType.SALES_ORDER, salesOrderId)
                .orElse(null);
        if (c == null || c.getStage() == Stage.GAP_RESOLVED) {
            return;
        }
        List<ShortageCaseLine> gaps = lineRepository.findByCaseIdOrderByIdAsc(c.getId()).stream()
                .filter(l -> l.getGapQty() != null && l.getGapQty().signum() > 0)
                .toList();
        if (gaps.isEmpty()) {
            return;
        }
        String planLabel = plan.getPlanNumber() != null ? plan.getPlanNumber() : plan.getId();
        if (forceStartReason == null || forceStartReason.isBlank()) {
            throw new BusinessException(409, "计划 " + planLabel + " 关联的缺料还没解决 (" + gaps.size() + " 种物料: "
                    + summary(gaps) + "), 本厂设置为缺料时不能开工")
                    .withCode(CODE)
                    .withHint("到「缺料异常」处理缺口; 确需先开工, 请填写强制开工原因后再开工")
                    .withHintTarget(c.getId())
                    .withSeverity("BLOCKING");
        }
        ShortageCaseEvent e = new ShortageCaseEvent();
        e.setCaseId(c.getId());
        e.setFactoryId(factoryId);
        e.setEventType(ShortageCaseEvent.EventType.NOTE);
        e.setFromStage(c.getStage());
        e.setToStage(c.getStage());
        e.setTriggerReason("FORCED_START");
        String detail = "计划 " + planLabel + " 在缺料未解决时强制开工: " + forceStartReason.trim();
        e.setDetail(detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        e.setActorType(operatorId == null ? "SYSTEM" : AuthenticatedActorType.FACTORY_USER.name());
        e.setActorId(operatorId);
        eventRepository.save(e);
    }

    private static String summary(List<ShortageCaseLine> gaps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gaps.size() && i < 3; i++) {
            ShortageCaseLine l = gaps.get(i);
            if (i > 0) sb.append("、");
            sb.append(l.getMaterialName() != null ? l.getMaterialName() : l.getMaterialTypeId())
                    .append(" 缺 ").append(l.getGapQty().stripTrailingZeros().toPlainString())
                    .append(" ").append(UnitDisplayNames.display(l.getUnit()));
        }
        if (gaps.size() > 3) sb.append(" 等");
        return sb.toString();
    }
}
