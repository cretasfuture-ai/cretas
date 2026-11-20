package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.shortage.RescheduleProposal;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
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
import com.cretas.aims.service.discipline.FactoryDisciplinePolicy;
import com.cretas.aims.service.discipline.FactoryDisciplinePolicy.PackageUnitCapacityMode;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.ScheduleLineLocks;
import com.cretas.aims.service.unit.UnitContractService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final ScheduleLineLocks lineLocks;
    private final UnitContractService unitContractService;
    private final EntityManager entityManager;
    /** 包装单位 (袋 / 盒) 计划换线怎么核对产能: 工厂开关 production.capacity.package_unit_mode (2026-11-11)。 */
    private final FactoryDisciplinePolicy disciplinePolicy;
    private final ProductTypeRepository productTypeRepository;

    /** 生成预览并存下基线。 */
    @Transactional
    public RescheduleProposal propose(String factoryId, String caseId, ActorRef actor) {
        requireActor(actor);
        ShortageCase c = own(factoryId, caseId);
        if (c.getClosedAt() != null || c.getStage().isTerminal()) {
            throw new BusinessException(409, "缺料实例已结束 (" + c.getStage().label() + "), 不能再重排")
                    .withHint("已结束的实例只能查看; 需要调整排程请到「生产排程」");
        }
        if (c.getSourceType() != ShortageCase.SourceType.SALES_ORDER) {
            throw new BusinessException(409, "只有来自销售订单的缺料实例能重排")
                    .withHint("其它来源的缺料请到「生产排程」手工调整");
        }
        Plan plan = plan(c);
        RescheduleProposal p = new RescheduleProposal();
        p.setId(UUID.randomUUID().toString());
        p.setFactoryId(factoryId);
        p.setCaseId(c.getId());
        p.setStatus(RescheduleProposal.Status.OPEN);
        p.setBaseline(plan.affected.stream().map(ls -> fingerprint(factoryId, ls)).toList());
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

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moves = (List<Map<String, Object>>) chosen.get("moves");
        // 先按 (工厂, 产线) 取锁, 涉及的源线与目标线一起、排序后取: 否则重算看到的空档在写之前可能被别人占掉
        Set<String> touchedLines = new TreeSet<>();
        p.getBaseline().forEach(b -> touchedLines.add((String) b.get("lineId")));
        moves.forEach(m -> touchedLines.add((String) m.get("toLineId")));
        lineLocks.lock(factoryId, touchedLines);

        // S4-04: 锁住任务后逐个比对基线
        List<String> ids = p.getBaseline().stream().map(b -> (String) b.get("scheduleId")).toList();
        Map<String, LineSchedule> locked = new HashMap<>();
        for (LineSchedule ls : scheduleQuery.lockByIds(factoryId, ids)) {
            locked.put(ls.getId(), ls);
        }
        List<String> planIds = locked.values().stream()
                .map(LineSchedule::getProductionPlanId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .sorted()
                .toList();
        for (String planId : planIds) {
            productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                    .orElseThrow(() -> stale("方案已过期: 生产计划不存在或不属于本厂", "重新生成重排方案, 再确认"));
            ProductionPlan lockedPlan = productionPlanRepository.findByIdForUpdate(planId)
                    .orElseThrow(() -> stale("方案已过期: 生产计划不存在", "重新生成重排方案, 再确认"));
            // The scoped pre-read may already be managed. Taking a JPQL lock does not
            // replace its cached fields; refresh under the lock before checking the basis.
            entityManager.refresh(lockedPlan, LockModeType.PESSIMISTIC_WRITE);
            if (!factoryId.equals(lockedPlan.getFactoryId())) {
                throw stale("方案已过期: 生产计划不属于本厂", "重新生成重排方案, 再确认");
            }
        }
        for (LineSchedule ls : locked.values()) {
            if (ls.getProductionPlanId() == null || ls.getProductionPlanId().isBlank()) {
                throw stale("方案已过期: 生产计划依据已缺失", "重新生成重排方案, 再确认");
            }
        }
        for (Map<String, Object> b : p.getBaseline()) {
            LineSchedule now = locked.get((String) b.get("scheduleId"));
            if (now == null || !fingerprint(factoryId, now).equals(b)) {
                throw stale("方案已过期: 排程任务或排程依据在生成方案后被改动过", "重新生成重排方案, 再确认");
            }
        }
        // S4-05: 审批 / 资源 / 到货按当前数据再核一遍, 所选候选必须原样可行
        Plan now = plan(c);
        Map<String, Object> recomputed = now.candidates.stream()
                .filter(k -> Objects.equals(k.get("key"), candidateKey)).findFirst().orElse(null);
        if (recomputed == null || !Boolean.TRUE.equals(recomputed.get("applicable"))
                || !Objects.equals(recomputed.get("moves"), chosen.get("moves"))
                || !Objects.equals(recomputed.get("basis"), chosen.get("basis"))) {
            throw stale("方案已过期: 到货日期、产线状态、时段占用或排程依据在生成方案后变了", "重新生成重排方案, 再确认");
        }

        LocalDateTime at = LocalDateTime.now();
        List<String> outsidePlanDay = new ArrayList<>();
        for (Map<String, Object> m : moves) {
            LineSchedule ls = locked.get((String) m.get("scheduleId"));
            LocalDateTime toStart = LocalDateTime.parse((String) m.get("toStart"));
            Duration delta = Duration.between(ls.getPlannedStartTime(), toStart);
            ls.setProductionLineId((String) m.get("toLineId"));
            ls.setPlannedStartTime(toStart);
            ls.setPlannedEndTime(LocalDateTime.parse((String) m.get("toEnd")));
            // APS 的按天分配与工序时间线跟着平移同样的量, 否则日视图还把量算在旧日期上
            ls.setTaskTimeline(shiftTimeline(ls.getTaskTimeline(), delta));
            ls.setDailyAllocations(shiftDailyAllocations(ls.getDailyAllocations(), delta));
            ls.setAdjustmentCount((ls.getAdjustmentCount() == null ? 0 : ls.getAdjustmentCount()) + 1);
            ls.setLastAdjustmentTime(at);
            ls.setAdjustmentReason(truncate("缺料重排 (" + chosen.get("label") + "), 缺料实例 " + c.getId(), 200));
            LocalDate planDay = ls.getPlan() == null ? null : ls.getPlan().getPlanDate();
            if (planDay != null && !planDay.equals(toStart.toLocalDate())) {
                outsidePlanDay.add(String.valueOf(m.get("scheduleId")));
            }
        }
        String planDayNote = outsidePlanDay.isEmpty() ? ""
                : "; " + outsidePlanDay.size() + " 个任务移出了所属排程计划的日期 (排程计划日期未改)";
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
        a.setReason(truncate(chosen.get("label") + planDayNote, 500));
        a.setActorType(actor.type().name());
        a.setActorId(actor.id());
        actionRepository.saveAndFlush(a);

        Stage from = c.getStage();
        if (from == Stage.DETECTED || from == Stage.PARTIALLY_RESOLVED) {
            c.setStage(Stage.PLANNED);
            caseRepository.saveAndFlush(c);
        }
        event(c, from, c.getStage(), "RESCHEDULE_APPLIED",
                chosen.get("label") + ": 调整 " + moves.size() + " 个排程任务" + planDayNote, actor);
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
        List<Map<String, Object>> basis = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, ProductionLine> lines = lines(factoryId);
        List<Slot> claimed = new ArrayList<>();   // 本方案里已经占下的 (线, 时段), 被挪的任务之间也不能撞
        boolean ok = true;
        for (LineSchedule ls : affected) {
            BasisCheck basisCheck = basis(factoryId, ls);
            if (!basisCheck.valid()) {
                ok = false;
                notes.add(basisCheck.reason());
            } else {
                basis.add(basisCheck.display());
            }
            LocalDateTime start = ls.getPlannedStartTime().plus(shift);
            LocalDateTime end = ls.getPlannedEndTime().plus(shift);
            ProductionLine line = lines.get(ls.getProductionLineId());
            if (line == null || line.getStatus() != ProductionLine.LineStatus.active) {
                ok = false;
                notes.add(name(line, ls.getProductionLineId()) + " 不在运行");
            }
            List<LineSchedule> clash = scheduleQuery.findOverlapping(factoryId, ls.getProductionLineId(), start, end, movingIds);
            if (!clash.isEmpty() || Slot.collides(claimed, ls.getProductionLineId(), start, end)) {
                ok = false;
                notes.add(name(line, ls.getProductionLineId()) + " 在 " + start + " – " + end + " 已有任务");
            }
            claimed.add(new Slot(ls.getProductionLineId(), start, end));
            if (basisCheck.valid()) {
                moves.add(move(ls, lines, ls.getProductionLineId(), start, end));
            }
        }
        deliveryNote(moves, due, notes);
        notes.add("到货日按在走采购单的最晚预计到货日估计, 没有核对单上数量够不够");
        notes.add("Workflow/BOM 为生产计划固定快照; APS 仅提供日产能日期依据, 未验证工序级精确耗时");
        return candidate(SAME_LINE, "同线顺延 " + days(shift) + " 天", ok,
                basis.size() == affected.size() ? moves : List.of(), notes, basis);
    }

    private Map<String, Object> otherLine(String factoryId, List<LineSchedule> affected, Duration shift, Set<String> movingIds,
                                          LocalDate due) {
        List<Map<String, Object>> moves = new ArrayList<>();
        List<Map<String, Object>> basis = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, ProductionLine> lines = lines(factoryId);
        List<Slot> claimed = new ArrayList<>();
        boolean ok = true;
        for (LineSchedule ls : affected) {
            BasisCheck basisCheck = basis(factoryId, ls);
            if (!basisCheck.valid()) {
                ok = false;
                notes.add(basisCheck.reason());
                continue;
            }
            basis.add(basisCheck.display());
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
                // 这个任务自己的量 (一个计划可拆成多段任务); 单位按单位字典认, 认不出 (盒 / 袋 …) 就如实判产能未验证
                BigDecimal qty = ls.getPlannedQuantity() != null ? BigDecimal.valueOf(ls.getPlannedQuantity()) : pp.getPlannedQuantity();
                ResourceCheckResult cap = targetLineCapacity(factoryId, pp, qty, line.getId(), start, end);
                if (!LineCapacityEquipmentCheck.READY.equals(LineCapacityEquipmentCheck.status(cap))) {
                    why.add(line.getName() + ": " + cap.getMessage());
                    continue;
                }
                if (!scheduleQuery.findOverlapping(factoryId, line.getId(), start, end, movingIds).isEmpty()
                        || Slot.collides(claimed, line.getId(), start, end)) {
                    why.add(line.getName() + ": 该时段已有任务");
                    continue;
                }
                target = line.getId();
                break;
            }
            if (target != null) {
                claimed.add(new Slot(target, start, end));
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
        notes.add("Workflow/BOM 为生产计划固定快照; APS 仅提供日产能日期依据, 换线仍需重新核对目标线产能");
        return candidate(OTHER_LINE, "顺延 " + days(shift) + " 天并换线", ok && !moves.isEmpty(), ok ? moves : List.of(), notes, basis);
    }

    // ---------------------------------------------------------------- helpers

    /** 本方案里已占下的一段 (线, [start, end))。 */
    private record Slot(String lineId, LocalDateTime start, LocalDateTime end) {
        static boolean collides(List<Slot> claimed, String lineId, LocalDateTime start, LocalDateTime end) {
            return claimed.stream().anyMatch(s -> s.lineId.equals(lineId) && s.start.isBefore(end) && s.end.isAfter(start));
        }
    }

    private record BasisCheck(boolean valid, Map<String, Object> snapshot, Map<String, Object> display, String reason) {
    }

    /**
     * 同线顺延也必须能回读正式计划快照和 APS 日产能日期依据。
     * APS 只证明按天的日期窗口，不把它冒充成工序级精确时长；未知单位不做换算。
     */
    private BasisCheck basis(String factoryId, LineSchedule ls) {
        if (ls.getProductionPlanId() == null || ls.getProductionPlanId().isBlank()) {
            return invalid("任务 " + ls.getId() + " 缺少生产计划, 无法核对重排依据");
        }
        ProductionPlan pp = productionPlanRepository.findByIdAndFactoryId(ls.getProductionPlanId(), factoryId)
                .orElse(null);
        if (pp == null || (pp.getFactoryId() != null && !factoryId.equals(pp.getFactoryId()))) {
            return invalid("任务 " + ls.getId() + " 的生产计划不存在或不属于本厂, 无法核对重排依据");
        }
        if (pp.getWorkflowSelectionMode() != ProductionBatch.WorkflowSelectionMode.WORKFLOW
                || pp.getSelectedWorkflowId() == null || pp.getSelectedWorkflowVersion() == null
                || pp.getSelectedWorkflowRevisionId() == null
                || pp.getSelectedWorkflowRevisionHash() == null
                || pp.getSelectedWorkflowRevisionHash().isBlank()
                || pp.getWorkflowOutputUnitsByProduct() == null || pp.getWorkflowOutputUnitsByProduct().isEmpty()
                || pp.getWorkflowOutputUnitsByProduct().entrySet().stream()
                .anyMatch(e -> e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank())) {
            return invalid("任务 " + ls.getId() + " 缺少正式 Workflow revision/输出快照, 不能核对重排依据");
        }
        boolean familyBom = pp.getSelectedBomFamilyId() != null && !pp.getSelectedBomFamilyId().isBlank()
                && pp.getSelectedBomRecipeIdsByProduct() != null && !pp.getSelectedBomRecipeIdsByProduct().isEmpty()
                && pp.getSelectedBomVersionsByProduct() != null && !pp.getSelectedBomVersionsByProduct().isEmpty()
                && pp.getSelectedBomRecipeIdsByProduct().keySet().equals(pp.getSelectedBomVersionsByProduct().keySet())
                && pp.getSelectedBomRecipeIdsByProduct().keySet().equals(pp.getWorkflowOutputUnitsByProduct().keySet())
                && pp.getSelectedBomRecipeIdsByProduct().entrySet().stream()
                .allMatch(e -> e.getKey() != null && !e.getKey().isBlank()
                        && e.getValue() != null && !e.getValue().isBlank()
                        && pp.getSelectedBomVersionsByProduct().get(e.getKey()) != null
                        && pp.getSelectedBomVersionsByProduct().get(e.getKey()) > 0);
        boolean singleBom = !familyBom && pp.getSelectedBomRecipeId() != null && !pp.getSelectedBomRecipeId().isBlank()
                && pp.getSelectedBomVersion() != null && pp.getSelectedBomVersion() > 0
                && pp.getWorkflowOutputUnitsByProduct().containsKey(pp.getProductTypeId());
        if (!singleBom && !familyBom) {
            return invalid("任务 " + ls.getId() + " 缺少正式 BOM 版本快照或成品映射, 不能核对重排依据");
        }
        Map<String, Object> daily = ls.getDailyAllocations();
        if (daily == null) {
            return invalid("任务 " + ls.getId() + " 缺少 APS 日产能依据, 不能确定顺延日期");
        }
        BigDecimal capacityPerDay = decimal(daily.get("capacityPerDay"));
        String capacitySource = text(daily.get("capacitySource"));
        String unit = text(daily.get("unit"));
        if (capacityPerDay == null || capacityPerDay.signum() <= 0
                || !("MANUAL".equals(capacitySource) || "EXPERIENCE".equals(capacitySource))
                || unit == null || unit.isBlank()
                || pp.getPlannedUnit() == null || pp.getPlannedUnit().isBlank()
                || !unitContractService.areEquivalent(factoryId, unit, pp.getPlannedUnit())) {
            return invalid("任务 " + ls.getId()
                    + " 的 APS 日产能依据缺失、单位不一致或来源不可核对, 不能猜测袋/盒换算");
        }
        BigDecimal plannedQuantity = decimal(daily.get("plannedQuantity"));
        BigDecimal reportedQuantity = decimal(daily.get("reportedQuantity"));
        BigDecimal remainingQuantity = decimal(daily.get("remainingQuantity"));
        if (plannedQuantity == null || pp.getPlannedQuantity() == null || !close(plannedQuantity, pp.getPlannedQuantity())
                || reportedQuantity == null || reportedQuantity.signum() < 0
                || remainingQuantity == null || remainingQuantity.signum() <= 0
                || !close(remainingQuantity, plannedQuantity.subtract(reportedQuantity))) {
            // ProductionPlan.remainingQuantity subtracts allocated raw materials;
            // APS.remainingQuantity subtracts reported output. They are different facts.
            return invalid("任务 " + ls.getId() + " 的 APS 计划量、已报量与待排产量不一致, 不能确定顺延日期");
        }
        Object rawDays = daily.get("days");
        if (!(rawDays instanceof List<?> days) || days.isEmpty()) {
            return invalid("任务 " + ls.getId() + " 缺少 APS 日产能日期分配, 不能确定顺延日期");
        }
        List<Map<String, String>> dayBasis = new ArrayList<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;
        Set<LocalDate> dates = new HashSet<>();
        for (Object rawDay : days) {
            if (!(rawDay instanceof Map<?, ?> day) || text(day.get("date")) == null) {
                return invalid("任务 " + ls.getId() + " 的 APS 日产能日期分配不可核对");
            }
            LocalDate date;
            try {
                date = LocalDate.parse(text(day.get("date")));
            } catch (java.time.format.DateTimeParseException e) {
                return invalid("任务 " + ls.getId() + " 的 APS 日产能日期无效, 不能确定顺延日期");
            }
            BigDecimal quantity = decimal(day.get("quantity"));
            BigDecimal share = decimal(day.get("shareOfDay"));
            BigDecimal expectedShare = quantity == null ? null
                    : quantity.divide(capacityPerDay, 6, java.math.RoundingMode.UP).min(BigDecimal.ONE);
            if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(capacityPerDay) > 0
                    || share == null || share.signum() <= 0 || share.compareTo(BigDecimal.ONE) > 0
                    || expectedShare == null || share.subtract(expectedShare).abs().compareTo(new BigDecimal("0.000001")) > 0
                    || !dates.add(date)) {
                return invalid("任务 " + ls.getId() + " 的 APS 日产能日量或占比不可核对");
            }
            totalQuantity = totalQuantity.add(quantity);
            Map<String, String> normalized = new LinkedHashMap<>();
            normalized.put("date", date.toString());
            normalized.put("quantity", quantity.stripTrailingZeros().toPlainString());
            normalized.put("shareOfDay", share.stripTrailingZeros().toPlainString());
            dayBasis.add(normalized);
        }
        dayBasis.sort(Comparator.comparing(d -> LocalDate.parse(d.get("date"))));
        LocalDate firstDay = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate lastDay = dates.stream().max(LocalDate::compareTo).orElseThrow();
        if (!close(totalQuantity, remainingQuantity)
                || ls.getPlannedStartTime() == null || ls.getPlannedEndTime() == null
                || !ls.getPlannedStartTime().isBefore(ls.getPlannedEndTime())
                || !firstDay.equals(ls.getPlannedStartTime().toLocalDate())
                || !lastDay.equals(ls.getPlannedEndTime().toLocalDate())) {
            return invalid("任务 " + ls.getId() + " 的 APS 日期窗口与排程窗口或待排数量不一致");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("productionPlanId", pp.getId());
        snapshot.put("workflowSelectionMode", pp.getWorkflowSelectionMode().name());
        snapshot.put("workflowId", String.valueOf(pp.getSelectedWorkflowId()));
        snapshot.put("workflowVersion", String.valueOf(pp.getSelectedWorkflowVersion()));
        snapshot.put("workflowRevisionId", String.valueOf(pp.getSelectedWorkflowRevisionId()));
        snapshot.put("workflowRevisionHash", pp.getSelectedWorkflowRevisionHash());
        snapshot.put("workflowOutputUnitsByProduct", new TreeMap<>(pp.getWorkflowOutputUnitsByProduct()));
        snapshot.put("productTypeId", pp.getProductTypeId());
        snapshot.put("plannedUnit", pp.getPlannedUnit().trim());
        snapshot.put("plannedQuantity", pp.getPlannedQuantity().stripTrailingZeros().toPlainString());
        snapshot.put("remainingQuantity", remainingQuantity.stripTrailingZeros().toPlainString());
        if (familyBom) {
            snapshot.put("bomFamilyId", pp.getSelectedBomFamilyId());
            snapshot.put("bomRecipeIdsByProduct", new TreeMap<>(pp.getSelectedBomRecipeIdsByProduct()));
            snapshot.put("bomVersionsByProduct", new TreeMap<>(pp.getSelectedBomVersionsByProduct()));
        } else {
            snapshot.put("bomRecipeId", pp.getSelectedBomRecipeId());
            snapshot.put("bomVersion", String.valueOf(pp.getSelectedBomVersion()));
        }
        snapshot.put("capacityPerDay", capacityPerDay.stripTrailingZeros().toPlainString());
        snapshot.put("capacitySource", capacitySource);
        snapshot.put("unit", unit.trim());
        snapshot.put("dateBasis", "APS_DAILY_CAPACITY");
        snapshot.put("days", dayBasis);
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("productionPlanId", pp.getId());
        display.put("workflowId", String.valueOf(pp.getSelectedWorkflowId()));
        display.put("workflowVersion", String.valueOf(pp.getSelectedWorkflowVersion()));
        display.put("workflowRevisionId", String.valueOf(pp.getSelectedWorkflowRevisionId()));
        if (familyBom) {
            display.put("bomFamilyId", pp.getSelectedBomFamilyId());
            display.put("bomRecipeIdsByProduct", new TreeMap<>(pp.getSelectedBomRecipeIdsByProduct()));
            display.put("bomVersionsByProduct", new TreeMap<>(pp.getSelectedBomVersionsByProduct()));
        } else {
            display.put("bomRecipeId", pp.getSelectedBomRecipeId());
            display.put("bomVersion", String.valueOf(pp.getSelectedBomVersion()));
        }
        display.put("capacityPerDay", capacityPerDay.stripTrailingZeros().toPlainString());
        display.put("capacitySource", capacitySource);
        display.put("unit", unit.trim());
        display.put("dateBasis", "APS_DAILY_CAPACITY");
        display.put("days", dayBasis);
        return new BasisCheck(true, snapshot, display, null);
    }

    private static BasisCheck invalid(String reason) {
        return new BasisCheck(false, Map.of(), Map.of(), reason);
    }

    private static boolean close(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.subtract(right).abs().compareTo(new BigDecimal("0.01")) <= 0;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal v) return v;
        if (value instanceof Number v) return BigDecimal.valueOf(v.doubleValue());
        if (value instanceof String v) {
            try {
                return new BigDecimal(v.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 计划单位 → 产能单位代码 (PIECE / KG / BATCH)。先认代码本身, 再按单位字典认「等同于 件 / 个 / kg / 批」的写法;
     * 都不是 (盒 / 袋 等包装单位) 原样返回, 由产能核对如实判「未验证」(Owner 10-28: 不猜、不换算)。
     */
    private String capacityUnit(String factoryId, String raw) {
        if (raw == null || raw.isBlank() || LineCapacityEquipmentCheck.parseUnit(raw) != null) {
            return raw;
        }
        if (unitContractService.areEquivalent(factoryId, raw, "kg")) {
            return "KG";
        }
        if (unitContractService.areEquivalent(factoryId, raw, "件") || unitContractService.areEquivalent(factoryId, raw, "个")) {
            return "PIECE";
        }
        if (unitContractService.areEquivalent(factoryId, raw, "批")) {
            return "BATCH";
        }
        return raw;
    }

    /**
     * 目标线产能核对。计划单位是 件 / kg / 批 (或与之等价的写法) ⇒ 原样核对; 是包装单位 (袋 / 盒 …) 时按工厂开关:
     * <ul>
     *   <li>NONE (默认) —— 原样交给产能核对, 如实判「产能未验证」, 只能同线顺延 (= 原行为);</li>
     *   <li>NET_CONTENT —— 计划单位必须与产品单位等价, 产品净含量是质量单位 (kg / g), 折成 kg 后核对 kg 产能;
     *       任何一项缺 / 对不上都判未验证并说清缺什么, ⛔ 不猜;</li>
     *   <li>FACTORY_UNIT —— 只认按同一包装单位配置的产线产能 (单位按单位契约判等价, ⛔ 不比字符串)。</li>
     * </ul>
     */
    ResourceCheckResult targetLineCapacity(String factoryId, ProductionPlan pp, BigDecimal qty, String lineId,
                                           LocalDateTime start, LocalDateTime end) {
        String code = capacityUnit(factoryId, pp.getPlannedUnit());
        if (code == null || code.isBlank() || LineCapacityEquipmentCheck.parseUnit(code) != null) {
            return capacityCheck.checkCapacity(factoryId, pp.getProductTypeId(), qty, code, lineId, start, end);
        }
        PackageUnitCapacityMode mode = disciplinePolicy.packageUnitCapacityMode(factoryId);
        return switch (mode) {
            case NONE -> capacityCheck.checkCapacity(factoryId, pp.getProductTypeId(), qty, code, lineId, start, end);
            case FACTORY_UNIT -> capacityCheck.checkCapacityInPackageUnit(factoryId, pp.getProductTypeId(), qty, code,
                    (a, b) -> unitContractService.areEquivalent(factoryId, a, b), lineId, start, end);
            case NET_CONTENT -> netContentCapacity(factoryId, pp, qty, code.trim(), lineId, start, end);
        };
    }

    private ResourceCheckResult netContentCapacity(String factoryId, ProductionPlan pp, BigDecimal qty, String planUnit,
                                                   String lineId, LocalDateTime start, LocalDateTime end) {
        String prefix = "产能未验证: 本厂按产品净含量折算「" + planUnit + "」, 但";
        if (qty == null || pp.getProductTypeId() == null) {
            return ResourceCheckResult.unverified("CAPACITY", "产能未验证: 缺少产品或计划数量", qty);
        }
        ProductType product = productTypeRepository.findByIdAndFactoryId(pp.getProductTypeId(), factoryId).orElse(null);
        if (product == null) {
            return ResourceCheckResult.unverified("CAPACITY", prefix + "找不到产品", qty);
        }
        BigDecimal net = product.getNetContentQuantity();
        String netUnit = product.getNetContentUnit();
        if (net == null || net.signum() <= 0 || netUnit == null || netUnit.isBlank()) {
            return ResourceCheckResult.unverified("CAPACITY", prefix + "产品「" + product.getName() + "」没有填净含量", qty);
        }
        if (product.getUnit() == null || !unitContractService.areEquivalent(factoryId, planUnit, product.getUnit())) {
            return ResourceCheckResult.unverified("CAPACITY", prefix + "计划单位与产品单位「"
                    + (product.getUnit() == null ? "未填" : product.getUnit()) + "」不同, 净含量是按产品单位填的, 无法折算", qty);
        }
        BigDecimal perUnitKg;
        if (unitContractService.areEquivalent(factoryId, netUnit, "kg")) {
            perUnitKg = net;
        } else if (unitContractService.areEquivalent(factoryId, netUnit, "g")) {
            perUnitKg = net.movePointLeft(3);
        } else {
            return ResourceCheckResult.unverified("CAPACITY", prefix + "净含量单位「" + netUnit
                    + "」不是质量单位, 不能折成 kg 核对产能", qty);
        }
        BigDecimal kg = qty.multiply(perUnitKg);
        ResourceCheckResult r = capacityCheck.checkCapacity(factoryId, pp.getProductTypeId(), kg, "KG", lineId, start, end);
        String msg = "按净含量 " + net.stripTrailingZeros().toPlainString() + " " + netUnit + "/" + planUnit + " 折合 "
                + kg.stripTrailingZeros().toPlainString() + " kg; " + r.getMessage();
        if (!r.isVerified()) {
            return ResourceCheckResult.unverified(r.getResourceType(), msg, r.getRequiredAmount());
        }
        return r.isAvailable()
                ? ResourceCheckResult.available(r.getResourceType(), r.getAvailableAmount(), r.getRequiredAmount(), msg)
                : ResourceCheckResult.unavailable(r.getResourceType(), msg, r.getAvailableAmount(), r.getRequiredAmount());
    }

    /** 工序时间线 start / end (ISO 本地时间) 平移 delta; 读不懂的条目原样保留。 */
    static List<Map<String, Object>> shiftTimeline(List<Map<String, Object>> timeline, Duration delta) {
        if (timeline == null || delta.isZero()) {
            return timeline;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> step : timeline) {
            Map<String, Object> copy = new LinkedHashMap<>(step);
            for (String k : List.of("start", "end")) {
                if (copy.get(k) instanceof String v) {
                    try {
                        copy.put(k, LocalDateTime.parse(v).plus(delta).toString());
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // 不是本地时间字符串: 不动
                    }
                }
            }
            out.add(copy);
        }
        return out;
    }

    /** APS v2 按天分配 days[].date 平移 delta 的天数; 其它键原样保留。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> shiftDailyAllocations(Map<String, Object> daily, Duration delta) {
        long days = delta.toDays();
        if (daily == null || days == 0 || !(daily.get("days") instanceof List<?> list)) {
            return daily;
        }
        Map<String, Object> out = new LinkedHashMap<>(daily);
        List<Object> shifted = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> d && d.get("date") instanceof String date) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) d);
                try {
                    copy.put("date", LocalDate.parse(date).plusDays(days).toString());
                } catch (java.time.format.DateTimeParseException ignored) {
                    // 不是日期: 不动
                }
                shifted.add(copy);
            } else {
                shifted.add(o);
            }
        }
        out.put("days", shifted);
        return out;
    }

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

    private Map<String, Object> fingerprint(String factoryId, LineSchedule ls) {
        Map<String, Object> m = fingerprint(ls);
        m.put("basis", basis(factoryId, ls).snapshot());
        return m;
    }

    private static Map<String, Object> candidate(String key, String label, boolean applicable, List<Map<String, Object>> moves,
                                                 List<String> notes) {
        return candidate(key, label, applicable, moves, notes, List.of());
    }

    private static Map<String, Object> candidate(String key, String label, boolean applicable, List<Map<String, Object>> moves,
                                                 List<String> notes, List<Map<String, Object>> basis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("applicable", applicable);
        m.put("moves", moves);
        m.put("notes", notes);
        m.put("basis", basis);
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
