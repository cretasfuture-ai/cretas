package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.shortage.RescheduleProposal;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.RescheduleProposalRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.repository.shortage.ShortageScheduleQueryRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 缺料实例上的局部重排 (S4 L1 切片 3, S4-03 局部 / 04 / 05)。Owner 裁定: 只做局部 —— 预览差异 → 确认 →
 * 改 LineSchedule 的产线和起止, 不做全局 APS。
 *
 * <p><b>候选</b> (确定性, 最多 3 个):
 * <ol>
 *   <li>同线顺延: 这张订单还没开工、且排在「到货次日」之前的任务, 整体平移到到货次日 (保持彼此先后与时长);</li>
 *   <li>顺延并换线: 同样的时段换到另一条在运行、按该产品同单位配了产能、且那段时间空着的线;</li>
 *   <li>转人工: 一直在, 走「转人工」决定, 不在这里应用。</li>
 * </ol>
 * 到货日 = 这种物料在走的采购单里最晚的预计到货日; 有单没填日期或根本没单 ⇒ 到货日未知, 不给顺延方案。
 * 这个日期只看「单在走」, 不核对单上的量够不够 —— 方案里如实写出。
 *
 * <p><b>应用</b> (S4-04 / 05): 锁住实例与任务, 逐个比对生成时的基线 (id + updatedAt + 产线 + 起止 + 状态),
 * 再按当前数据重算一遍所选候选; 任何一处对不上 (任务被改过、到货日变了、产线停了、时段被占了、订单取消了)
 * 就整体 409「方案已过期」, 一行都不写。对得上才改任务, 并记执行台账与事件。
 */
@Service
@RequiredArgsConstructor
public class ShortageRescheduleService {

    static final Duration PROPOSAL_TTL = Duration.ofMinutes(30);

    public static long ttlMinutes() {
        return PROPOSAL_TTL.toMinutes();
    }
    public static final String SAME_LINE = "SAME_LINE_POSTPONE";
    public static final String OTHER_LINE = "OTHER_LINE_POSTPONE";
    public static final String MANUAL = "MANUAL";

    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseLineRepository lineRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final ShortageCaseActionRepository actionRepository;
    private final RescheduleProposalRepository proposalRepository;
    private final ShortageScheduleQueryRepository scheduleQuery;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionLineRepository productionLineRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final LineCapacityEquipmentCheck capacityCheck;

    /** 生成预览并存下基线。 */
    @Transactional
    public RescheduleProposal propose(String factoryId, String caseId, ActorRef actor) {
        requireActor(actor);
        ShortageCase c = own(factoryId, caseId);
        if (c.getClosedAt() != null || c.getStage().isTerminal()) {
            throw new BusinessException(409, "缺料实例已结束 (" + c.getStage().label() + "), 不能再重排")
                    .withHint("已结束的实例只能查看; 需要调整排程请到「生产排程」");
        }
        Plan plan = plan(c);
        RescheduleProposal p = new RescheduleProposal();
        p.setId(UUID.randomUUID().toString());
        p.setFactoryId(factoryId);
        p.setCaseId(c.getId());
        p.setStatus(RescheduleProposal.Status.OPEN);
        p.setBaseline(plan.affected.stream().map(ShortageRescheduleService::fingerprint).toList());
        p.setCandidates(plan.candidates);
        p.setExpiresAt(LocalDateTime.now().plus(PROPOSAL_TTL));
        p.setCreatedByType(actor.type().name());
        p.setCreatedById(actor.id());
        proposalRepository.saveAndFlush(p);
        event(c, c.getStage(), c.getStage(), "RESCHEDULE_PROPOSED",
                plan.affected.size() + " 个排程任务受影响; " + (plan.readyDate == null ? "到货日未知" : "预计 " + plan.readyDate + " 到货"),
                actor);
        return p;
    }

    /** 应用某个候选。基线或重算结果有一处对不上就整体 409, 零写入。 */
    @Transactional
    public RescheduleProposal apply(String factoryId, String caseId, String proposalId, String candidateKey, ActorRef actor) {
        requireActor(actor);
        ShortageCase c = caseRepository.findOpenForUpdate(factoryId, ShortageCase.SourceType.SALES_ORDER,
                        own(factoryId, caseId).getSourceId())
                .filter(open -> open.getId().equals(caseId))
                .orElseThrow(() -> stale("缺料实例已结束", "刷新页面查看最新状态"));
        if (c.getStage().isTerminal()) {
            throw stale("缺料实例已结束 (" + c.getStage().label() + ")", "已结束的实例不能再重排");
        }
        RescheduleProposal p = proposalRepository.findByIdAndFactoryIdAndCaseId(proposalId, factoryId, caseId)
                .orElseThrow(() -> new BusinessException(404, "重排方案不存在"));
        if (p.getStatus() != RescheduleProposal.Status.OPEN) {
            throw stale("这个方案已" + (p.getStatus() == RescheduleProposal.Status.APPLIED ? "应用过" : "过期"),
                    "重新生成重排方案");
        }
        if (LocalDateTime.now().isAfter(p.getExpiresAt())) {
            throw stale("方案已过期 (生成超过 " + PROPOSAL_TTL.toMinutes() + " 分钟)", "重新生成重排方案");
        }
        Map<String, Object> chosen = p.getCandidates().stream()
                .filter(k -> Objects.equals(k.get("key"), candidateKey))
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "方案里没有这个候选"));
        if (!Boolean.TRUE.equals(chosen.get("applicable"))) {
            throw new BusinessException(409, "这个候选不能直接应用: " + chosen.get("label"))
                    .withHint("选一个可行的候选; 转人工请用「转人工」按钮");
        }

        // S4-04: 锁住任务后逐个比对基线
        List<String> ids = p.getBaseline().stream().map(b -> (String) b.get("scheduleId")).toList();
        Map<String, LineSchedule> locked = new HashMap<>();
        for (LineSchedule ls : scheduleQuery.lockByIds(factoryId, ids)) {
            locked.put(ls.getId(), ls);
        }
        for (Map<String, Object> b : p.getBaseline()) {
            LineSchedule now = locked.get((String) b.get("scheduleId"));
            if (now == null || !fingerprint(now).equals(b)) {
                throw stale("方案已过期: 排程任务在生成方案后被改动过", "重新生成重排方案, 再确认");
            }
        }
        // S4-05: 审批 / 资源 / 到货按当前数据再核一遍, 所选候选必须原样可行
        Plan now = plan(c);
        Map<String, Object> recomputed = now.candidates.stream()
                .filter(k -> Objects.equals(k.get("key"), candidateKey)).findFirst().orElse(null);
        if (recomputed == null || !Boolean.TRUE.equals(recomputed.get("applicable"))
                || !Objects.equals(recomputed.get("moves"), chosen.get("moves"))) {
            throw stale("方案已过期: 到货日期、产线状态或时段占用在生成方案后变了", "重新生成重排方案, 再确认");
        }

        LocalDateTime at = LocalDateTime.now();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moves = (List<Map<String, Object>>) chosen.get("moves");
        for (Map<String, Object> m : moves) {
            LineSchedule ls = locked.get((String) m.get("scheduleId"));
            ls.setProductionLineId((String) m.get("toLineId"));
            ls.setPlannedStartTime(LocalDateTime.parse((String) m.get("toStart")));
            ls.setPlannedEndTime(LocalDateTime.parse((String) m.get("toEnd")));
            ls.setAdjustmentCount((ls.getAdjustmentCount() == null ? 0 : ls.getAdjustmentCount()) + 1);
            ls.setLastAdjustmentTime(at);
            ls.setAdjustmentReason(truncate("缺料重排 (" + chosen.get("label") + "), 缺料实例 " + c.getId(), 200));
        }
        p.setStatus(RescheduleProposal.Status.APPLIED);
        p.setAppliedCandidate(candidateKey);
        p.setAppliedAt(at);
        proposalRepository.saveAndFlush(p);

        ShortageCaseAction a = new ShortageCaseAction();
        a.setCaseId(c.getId());
        a.setFactoryId(factoryId);
        a.setActionType(ShortageCaseAction.ActionType.RESCHEDULE_APPLIED);
        a.setStatus(ShortageCaseAction.Status.DONE);
        a.setResultRef(p.getId());
        a.setReason(truncate((String) chosen.get("label"), 500));
        a.setActorType(actor.type().name());
        a.setActorId(actor.id());
        actionRepository.saveAndFlush(a);

        Stage from = c.getStage();
        if (from == Stage.DETECTED || from == Stage.PARTIALLY_RESOLVED) {
            c.setStage(Stage.PLANNED);
            caseRepository.saveAndFlush(c);
        }
        event(c, from, c.getStage(), "RESCHEDULE_APPLIED", chosen.get("label") + ": 调整 " + moves.size() + " 个排程任务", actor);
        return p;
    }

    // ---------------------------------------------------------------- planning

    /** 一次重算: 受影响任务 + 候选。预览与应用共用, 所以「应用时原样可行」可以直接比对。 */
    private record Plan(List<LineSchedule> affected, LocalDate readyDate, List<Map<String, Object>> candidates) {
    }

    private Plan plan(ShortageCase c) {
        String factoryId = c.getFactoryId();
        var order = salesOrderRepository.findById(c.getSourceId())
                .filter(o -> factoryId.equals(o.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "缺料实例关联的销售订单不存在"));
        if (order.getStatus() == SalesOrderStatus.CANCELLED) {
            throw stale("销售订单已取消, 不需要重排", "取消这个缺料实例并写明原因");
        }
        List<ShortageCaseLine> gaps = lineRepository.findByCaseIdOrderByIdAsc(c.getId()).stream()
                .filter(l -> l.getGapQty() != null && l.getGapQty().signum() > 0)
                .toList();
        if (gaps.isEmpty()) {
            throw stale("缺口已解决, 不需要重排", "先点「按当前库存重新核对」; 缺口仍为 0 就关闭实例");
        }

        // 到货日: 每种缺料取在走采购单最晚的预计到货日; 任何一种未知 ⇒ 整体未知
        List<String> unknown = new ArrayList<>();
        LocalDate ready = null;
        for (ShortageCaseLine l : gaps) {
            String name = l.getMaterialName() != null ? l.getMaterialName() : l.getMaterialTypeId();
            Object[] row = scheduleQuery.findLiveOrderArrival(factoryId, l.getMaterialTypeId()).stream().findFirst().orElse(null);
            long count = row == null || row[2] == null ? 0 : ((Number) row[2]).longValue();
            long undated = row == null || row[1] == null ? 0 : ((Number) row[1]).longValue();
            LocalDate latest = row == null ? null : asDate(row[0]);
            if (count == 0) {
                unknown.add(name + " 没有在走的采购单");
            } else if (undated > 0 || latest == null) {
                unknown.add(name + " 有 " + Math.max(undated, 1) + " 张采购单没填预计到货日");
            } else if (ready == null || latest.isAfter(ready)) {
                ready = latest;
            }
        }
        if (!unknown.isEmpty()) {
            ready = null;
        }

        List<LineSchedule> notStarted = scheduleQuery.findNotStartedForSalesOrder(factoryId, c.getSourceId());
        if (notStarted.isEmpty()) {
            throw stale("这张订单没有还没开工的排程任务, 不需要重排", "已开工的任务请在「生产排程」里处理");
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<LineSchedule> affected;
        if (ready == null) {
            affected = notStarted;
            candidates.add(candidate(SAME_LINE, "同线顺延", false, List.of(), List.of("到货日未知, 无法确定顺延到哪天: " + String.join("; ", unknown))));
            candidates.add(candidate(OTHER_LINE, "顺延并换线", false, List.of(), List.of("到货日未知")));
        } else {
            LocalDate earliestStartDay = ready.plusDays(1); // 到货次日开工, 留出收货与质检
            affected = notStarted.stream()
                    .filter(ls -> ls.getPlannedStartTime().toLocalDate().isBefore(earliestStartDay))
                    .toList();
            if (affected.isEmpty()) {
                throw stale("这张订单的排程已在预计到货 (" + ready + ") 之后, 不需要重排", "到货有变化时再重新生成");
            }
            LocalDateTime first = affected.get(0).getPlannedStartTime();
            Duration shift = Duration.between(first, earliestStartDay.atTime(first.toLocalTime()));
            Set<String> movingIds = new HashSet<>(affected.stream().map(LineSchedule::getId).toList());
            candidates.add(sameLine(factoryId, affected, shift, movingIds, order.getRequiredDeliveryDate()));
            candidates.add(otherLine(factoryId, affected, shift, movingIds, order.getRequiredDeliveryDate()));
        }
        candidates.add(candidate(MANUAL, "转人工", false, List.of(), List.of("交给负责人手工排; 请用「转人工」按钮并写明原因")));
        return new Plan(affected, ready, candidates);
    }

    private Map<String, Object> sameLine(String factoryId, List<LineSchedule> affected, Duration shift, Set<String> movingIds,
                                         LocalDate due) {
        List<Map<String, Object>> moves = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, ProductionLine> lines = lines(factoryId);
        boolean ok = true;
        for (LineSchedule ls : affected) {
            LocalDateTime start = ls.getPlannedStartTime().plus(shift);
            LocalDateTime end = ls.getPlannedEndTime().plus(shift);
            ProductionLine line = lines.get(ls.getProductionLineId());
            if (line == null || line.getStatus() != ProductionLine.LineStatus.active) {
                ok = false;
                notes.add(name(line, ls.getProductionLineId()) + " 不在运行");
            }
            List<LineSchedule> clash = scheduleQuery.findOverlapping(factoryId, ls.getProductionLineId(), start, end, movingIds);
            if (!clash.isEmpty()) {
                ok = false;
                notes.add(name(line, ls.getProductionLineId()) + " 在 " + start + " – " + end + " 已排了 " + clash.size() + " 个任务");
            }
            moves.add(move(ls, lines, ls.getProductionLineId(), start, end));
        }
        deliveryNote(moves, due, notes);
        notes.add("到货日按在走采购单的最晚预计到货日估计, 没有核对单上数量够不够");
        return candidate(SAME_LINE, "同线顺延 " + days(shift) + " 天", ok, moves, notes);
    }

    private Map<String, Object> otherLine(String factoryId, List<LineSchedule> affected, Duration shift, Set<String> movingIds,
                                          LocalDate due) {
        List<Map<String, Object>> moves = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, ProductionLine> lines = lines(factoryId);
        boolean ok = true;
        for (LineSchedule ls : affected) {
            LocalDateTime start = ls.getPlannedStartTime().plus(shift);
            LocalDateTime end = ls.getPlannedEndTime().plus(shift);
            ProductionPlan pp = ls.getProductionPlanId() == null ? null
                    : productionPlanRepository.findByIdAndFactoryId(ls.getProductionPlanId(), factoryId).orElse(null);
            if (pp == null) {
                ok = false;
                notes.add("任务 " + ls.getId() + " 找不到生产计划, 无法核对别的线的产能");
                continue;
            }
            String target = null;
            List<String> why = new ArrayList<>();
            for (ProductionLine line : lines.values()) {
                if (line.getId().equals(ls.getProductionLineId()) || line.getStatus() != ProductionLine.LineStatus.active) {
                    continue;
                }
                ResourceCheckResult cap = capacityCheck.checkCapacity(factoryId, pp.getProductTypeId(), pp.getPlannedQuantity(),
                        pp.getPlannedUnit(), line.getId(), start, end);
                if (!LineCapacityEquipmentCheck.READY.equals(LineCapacityEquipmentCheck.status(cap))) {
                    why.add(line.getName() + ": " + cap.getMessage());
                    continue;
                }
                if (!scheduleQuery.findOverlapping(factoryId, line.getId(), start, end, movingIds).isEmpty()) {
                    why.add(line.getName() + ": 该时段已有任务");
                    continue;
                }
                target = line.getId();
                break;
            }
            if (target == null) {
                ok = false;
                notes.add("没有别的线能接 " + (pp.getPlanNumber() != null ? pp.getPlanNumber() : pp.getId())
                        + (why.isEmpty() ? " (本厂没有其它在运行的线)" : ": " + String.join("; ", why)));
                continue;
            }
            moves.add(move(ls, lines, target, start, end));
        }
        deliveryNote(moves, due, notes);
        return candidate(OTHER_LINE, "顺延 " + days(shift) + " 天并换线", ok && !moves.isEmpty(), ok ? moves : List.of(), notes);
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> fingerprint(LineSchedule ls) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scheduleId", ls.getId());
        m.put("updatedAt", ls.getUpdatedAt() == null ? null : ls.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS).toString());
        m.put("lineId", ls.getProductionLineId());
        m.put("start", ls.getPlannedStartTime() == null ? null : ls.getPlannedStartTime().toString());
        m.put("end", ls.getPlannedEndTime() == null ? null : ls.getPlannedEndTime().toString());
        m.put("status", ls.getStatus() == null ? null : ls.getStatus().name());
        return m;
    }

    private static Map<String, Object> candidate(String key, String label, boolean applicable, List<Map<String, Object>> moves,
                                                 List<String> notes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("applicable", applicable);
        m.put("moves", moves);
        m.put("notes", notes);
        return m;
    }

    private static Map<String, Object> move(LineSchedule ls, Map<String, ProductionLine> lines, String toLine,
                                            LocalDateTime start, LocalDateTime end) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scheduleId", ls.getId());
        m.put("productionPlanId", ls.getProductionPlanId());
        m.put("fromLineId", ls.getProductionLineId());
        m.put("fromLineName", name(lines.get(ls.getProductionLineId()), ls.getProductionLineId()));
        m.put("toLineId", toLine);
        m.put("toLineName", name(lines.get(toLine), toLine));
        m.put("fromStart", ls.getPlannedStartTime().toString());
        m.put("toStart", start.toString());
        m.put("fromEnd", ls.getPlannedEndTime().toString());
        m.put("toEnd", end.toString());
        return m;
    }

    private static void deliveryNote(List<Map<String, Object>> moves, LocalDate due, List<String> notes) {
        if (moves.isEmpty()) {
            return;
        }
        LocalDate lastEnd = moves.stream().map(m -> LocalDateTime.parse((String) m.get("toEnd")).toLocalDate())
                .max(Comparator.naturalOrder()).orElseThrow();
        if (due == null) {
            notes.add("订单没填要求交期, 无法判断是否延误");
        } else if (lastEnd.isAfter(due)) {
            notes.add("要求交期 " + due + ", 顺延后 " + lastEnd + " 才完工, 会延误 " + ChronoUnit.DAYS.between(due, lastEnd) + " 天");
        }
    }

    private Map<String, ProductionLine> lines(String factoryId) {
        Map<String, ProductionLine> m = new LinkedHashMap<>();
        for (ProductionLine l : productionLineRepository.findByFactoryIdAndDeletedAtIsNull(factoryId)) {
            m.put(l.getId(), l);
        }
        return m;
    }

    private static String name(ProductionLine line, String id) {
        return line != null && line.getName() != null ? line.getName() : id;
    }

    private static long days(Duration d) {
        return d.toDays();
    }

    private static LocalDate asDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        return v instanceof java.sql.Date s ? s.toLocalDate() : null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private ShortageCase own(String factoryId, String caseId) {
        return caseRepository.findByIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "缺料实例不存在或不属于本厂"));
    }

    private static void requireActor(ActorRef actor) {
        if (actor == null) {
            throw new BusinessException(401, "无法识别当前操作人");
        }
    }

    private static BusinessException stale(String message, String hint) {
        return new BusinessException(409, message).withHint(hint);
    }

    private void event(ShortageCase c, Stage from, Stage to, String reason, String detail, ActorRef actor) {
        ShortageCaseEvent e = new ShortageCaseEvent();
        e.setCaseId(c.getId());
        e.setFactoryId(c.getFactoryId());
        e.setEventType(from == to ? ShortageCaseEvent.EventType.NOTE : ShortageCaseEvent.EventType.STAGE_CHANGED);
        e.setFromStage(from);
        e.setToStage(to);
        e.setTriggerReason(reason);
        e.setDetail(truncate(detail, 1000));
        e.setActorType(actor.type().name());
        e.setActorId(actor.id());
        eventRepository.save(e);
    }
}
