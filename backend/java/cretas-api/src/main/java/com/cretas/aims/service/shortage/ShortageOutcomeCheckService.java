package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck;
import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck.Trigger;
import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck.Verdict;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseOutcomeCheckRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.repository.shortage.ShortageScheduleQueryRepository;
import com.cretas.aims.security.ActorRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 缺料处理的执行后核对: 重排 / 转采购做完之后, 按执行台账指向的订单与当前数据核对目标是否达成 ——
 * 受影响订单未开工的排程是否都在交期内完工、缺口是否关闭 (没关闭时补货能否在开工前到)。
 *
 * <p>结论如实写回处理实例 ({@code shortage_case_outcome_checks}): 只有 {@link Verdict#ACHIEVED} 算达成;
 * 缺交期 / 到货日没填之类判断不了的给 {@link Verdict#UNKNOWN}, 不默认成功。
 * 采购草稿还没下给供应商, 到货日不作数 (与重排同一口径, 见 ShortageScheduleQueryRepository#findLiveOrderArrival)。
 */
@Service
@RequiredArgsConstructor
public class ShortageOutcomeCheckService {

    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseLineRepository lineRepository;
    private final ShortageScheduleQueryRepository scheduleQuery;
    private final SalesOrderRepository salesOrderRepository;
    private final ShortageCaseOutcomeCheckRepository checkRepository;

    @Transactional
    public ShortageCaseOutcomeCheck check(String factoryId, String caseId, Trigger trigger, Long actionId, ActorRef actor) {
        ShortageCase c = caseRepository.findByIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "缺料实例不存在或不属于本厂"));
        if (c.getSourceType() != ShortageCase.SourceType.SALES_ORDER) {
            throw new BusinessException(409, "只有来自销售订单的缺料实例能核对交期");
        }
        var order = salesOrderRepository.findById(c.getSourceId())
                .filter(o -> factoryId.equals(o.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "缺料实例关联的销售订单不存在"));

        List<ShortageCaseLine> gaps = lineRepository.findByCaseIdOrderByIdAsc(c.getId()).stream()
                .filter(l -> l.getGapQty() != null && l.getGapQty().signum() > 0)
                .toList();
        List<LineSchedule> pending = scheduleQuery.findNotStartedForSalesOrder(factoryId, c.getSourceId());
        LocalDate due = order.getRequiredDeliveryDate();
        LocalDateTime latestEnd = pending.stream().map(LineSchedule::getPlannedEndTime)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        int late = due == null ? 0 : (int) pending.stream()
                .filter(ls -> ls.getPlannedEndTime() != null && ls.getPlannedEndTime().toLocalDate().isAfter(due))
                .count();

        List<String> notes = new ArrayList<>();
        LocalDate ready = null;
        Verdict verdict;
        if (due == null) {
            verdict = Verdict.UNKNOWN;
            notes.add("订单没有要求交期, 无法核对交期");
        } else if (late > 0) {
            verdict = Verdict.NOT_ACHIEVED;
            notes.add(late + " 个排程任务晚于交期 " + due + " 完工 (最晚 " + latestEnd + ")");
        } else if (gaps.isEmpty()) {
            verdict = Verdict.ACHIEVED;
            notes.add("缺口已关闭; " + (pending.isEmpty() ? "订单没有未开工的排程任务" : pending.size() + " 个未开工排程都在交期 " + due + " 内"));
        } else {
            List<String> missing = new ArrayList<>();
            List<String> undatedNames = new ArrayList<>();
            for (ShortageCaseLine l : gaps) {
                String name = l.getMaterialName() != null ? l.getMaterialName() : l.getMaterialTypeId();
                Object[] row = scheduleQuery.findLiveOrderArrival(factoryId, l.getMaterialTypeId()).stream().findFirst().orElse(null);
                long count = row == null || row[2] == null ? 0 : ((Number) row[2]).longValue();
                long undated = row == null || row[1] == null ? 0 : ((Number) row[1]).longValue();
                LocalDate latest = row == null ? null : asDate(row[0]);
                if (count == 0) {
                    missing.add(name);
                } else if (undated > 0 || latest == null) {
                    undatedNames.add(name);
                } else if (ready == null || latest.isAfter(ready)) {
                    ready = latest;
                }
            }
            LocalDateTime firstStart = pending.stream().map(LineSchedule::getPlannedStartTime)
                    .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
            if (!missing.isEmpty()) {
                verdict = Verdict.NOT_ACHIEVED;
                notes.add("缺口未关闭, " + String.join("、", missing) + " 没有已下给供应商的采购单 (草稿不算)");
            } else if (!undatedNames.isEmpty()) {
                verdict = Verdict.UNKNOWN;
                notes.add("缺口未关闭, " + String.join("、", undatedNames) + " 的采购单没填预计到货日");
            } else if (firstStart == null) {
                verdict = Verdict.UNKNOWN;
                notes.add("缺口未关闭, 订单没有未开工的排程任务, 无法核对到货是否赶得上");
            } else if (ready.plusDays(1).isAfter(firstStart.toLocalDate())) {
                verdict = Verdict.NOT_ACHIEVED;
                notes.add("预计 " + ready + " 到货, 赶不上 " + firstStart.toLocalDate() + " 开工 (到货次日才能开工)");
            } else {
                verdict = Verdict.PENDING_SUPPLY;
                notes.add("缺口未关闭, 预计 " + ready + " 到货, 早于首个排程 " + firstStart.toLocalDate()
                        + "; 排程都在交期 " + due + " 内");
            }
            if (!undatedNames.isEmpty() || !missing.isEmpty()) {
                ready = null;
            }
        }
        notes.add("缺口数据核对于 " + c.getLastRefreshedAt());

        ShortageCaseOutcomeCheck chk = new ShortageCaseOutcomeCheck();
        chk.setCaseId(c.getId());
        chk.setFactoryId(factoryId);
        chk.setActionId(actionId);
        chk.setTrigger(trigger);
        chk.setVerdict(verdict);
        chk.setGapClosed(gaps.isEmpty());
        chk.setOpenGapLines(gaps.size());
        chk.setDueDate(due);
        chk.setLatestPlannedEnd(latestEnd);
        chk.setLateScheduleCount(late);
        chk.setSupplyReadyDate(ready);
        chk.setDetail(verdict.label() + ": " + String.join("; ", notes));
        chk.setActorType(actor == null ? "SYSTEM" : actor.type().name());
        chk.setActorId(actor == null ? null : actor.id());
        return checkRepository.saveAndFlush(chk);
    }

    @Transactional(readOnly = true)
    public List<ShortageCaseOutcomeCheck> list(String factoryId, String caseId) {
        caseRepository.findByIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "缺料实例不存在或不属于本厂"));
        return checkRepository.findByFactoryIdAndCaseIdOrderByIdAsc(factoryId, caseId);
    }

    private static LocalDate asDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof LocalDateTime t) {
            return t.toLocalDate();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return null;
    }
}
