package com.cretas.aims.service.scheduling;

import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview;
import com.cretas.aims.dto.scheduling.DailyCapacityRequiredDTO;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.PlanResult;
import com.cretas.aims.dto.scheduling.SchedulePreviewRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.scheduling.daily.DailyCapacityScheduleGenerator;
import com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl;
import com.cretas.aims.service.shortage.DeliveryRiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 紧急插单批准后真正调整排程 (缺口 17 第 2 项)。
 *
 * <p>调缺口 18 的约束排程引擎 ({@link DailyCapacityScheduleGenerator#preview}): 插单计划钉在最前,
 * 同一条线上还没开工的排程当「可挪」一起重排, 开工的 (in_progress) 当固定占用不动。
 * 结果两处用: 预览 (dry-run, 建一条临时计划算完就回滚, 不留任何行) 和确认 (真计划建好之后重算,
 * 与预览指纹比对, 不一致整个确认回滚)。
 *
 * <p>排不进去 (没填日产能 / 设备报废 / 超期 / 依赖没排出) 一律 BLOCKED, 说清原因, 不把「排不了」
 * 当成「排进去了」。被挤计划新的完成日写进它的排程行和计划起止时间; 交期被突破的订单在提交之后
 * 登记到交付风险闭环 (#1443), 不放在确认事务里 (它的评估可能抛业务异常, 会把确认事务标成回滚)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrgentInsertScheduleService {

    public static final String OK = "OK";
    /** 引擎说排不进去 / 没填日产能等: 确认必须拦。 */
    public static final String BLOCKED = "BLOCKED";
    /** 没有足够信息去排 (没绑产线等) 或没评估 (前置的原料已不满足)。 */
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    static final LocalTime DAY_END = LocalTime.of(23, 59);
    static final int MAX_MOVED_PLANS = 50;

    /** 被挤计划: 原完成日 → 新完成日。 */
    public record Moved(String planId, String planNumber, LocalDate oldEnd, LocalDate newEnd, long delayDays,
                        LocalDate dueDate, boolean exceedsDue, List<String> orderIds, List<String> reasons) {
    }

    public record Proposal(String status, String message, LocalDate insertFirstDay, LocalDate insertLastDay,
                           List<Moved> moved, List<String> warnings, String fingerprint) {

        public boolean ok() {
            return OK.equals(status);
        }

        public static Proposal notEvaluated(String message) {
            return new Proposal(NOT_EVALUATED, message, null, null, List.of(), List.of(), null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("message", message);
            m.put("insertFirstDay", insertFirstDay);
            m.put("insertLastDay", insertLastDay);
            m.put("warnings", warnings);
            m.put("movedPlans", moved.stream().map(x -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("planId", x.planId());
                row.put("planNumber", x.planNumber());
                row.put("oldEnd", x.oldEnd());
                row.put("newEnd", x.newEnd());
                row.put("delayDays", x.delayDays());
                row.put("dueDate", x.dueDate());
                row.put("exceedsDue", x.exceedsDue());
                row.put("reasons", x.reasons());
                return row;
            }).collect(Collectors.toList()));
            m.put("exceedsDueCount", moved.stream().filter(Moved::exceedsDue).count());
            return m;
        }
    }

    private final DailyCapacityScheduleGenerator generator;
    private final ApsConstraintQueryRepository constraints;
    private final ProductionPlanRepository planRepository;
    private final LineScheduleRepository lineScheduleRepository;
    private final SchedulingPlanRepository schedulingPlanRepository;
    private final PlatformTransactionManager transactionManager;
    private final DeliveryRiskService deliveryRiskService;

    // ---------------------------------------------------------------- 预览: 建临时计划算完回滚

    /**
     * 只算不留痕: 在独立事务里把临时插单计划存进去 (引擎按计划 id 读计划), 算完标回滚。
     * 独立事务看不到外层未提交的东西, 外层此时也没有写任何排程相关的行。
     */
    public Proposal dryRun(String factoryId, InsertSlot slot, ProductionPlan transientPlan) {
        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.setName("UrgentInsertScheduleService.dryRun");
        try {
            return isolated.execute(status -> {
                try {
                    ProductionPlan saved = planRepository.saveAndFlush(transientPlan);
                    return compute(factoryId, slot, saved).proposal();
                } finally {
                    status.setRollbackOnly();
                }
            });
        } catch (BusinessException e) {
            log.warn("插单排程预览失败: factoryId={}, reason={}", factoryId, e.getMessage());
            return new Proposal(BLOCKED, "排程无法预览: " + e.getMessage(), null, null, List.of(), List.of(), null);
        } catch (Exception e) {
            log.error("插单排程预览异常: factoryId={}", factoryId, e);
            return Proposal.notEvaluated("排程未验证: 排程引擎读取失败");
        }
    }

    // ---------------------------------------------------------------- 确认: 重算、比对、落库

    /**
     * 在确认 / 批准的事务里调用 (插单计划已保存)。重算排程; {@code expectedFingerprint} 非空时必须与预览一致,
     * 不一致抛 409 (事务整体回滚, 时段占用和计划都不留)。通过后写入排程。
     */
    public Proposal apply(String factoryId, InsertSlot slot, ProductionPlan insertPlan,
                          String expectedFingerprint, Long userId) {
        Computation c = compute(factoryId, slot, insertPlan);
        Proposal p = c.proposal();
        if (!p.ok()) {
            throw new BusinessException(409, "排程排不进去，未创建计划: " + p.message())
                    .withCode("INSERT_SCHEDULE_BLOCKED")
                    .withHint("按提示补齐日产能或处理设备/依赖后重新预览");
        }
        if (expectedFingerprint != null && !expectedFingerprint.equals(p.fingerprint())) {
            throw new BusinessException(409, "预览之后排程结果已变化（排程、设备占用或原料到货变了），请重新预览，未创建计划")
                    .withCode("INSERT_PREVIEW_SCHEDULE_CHANGED")
                    .withHint("重新创建确认预览，按最新排程确认");
        }
        write(factoryId, slot, insertPlan, c, userId);
        registerOverdueReminders(factoryId, c, userId);
        return p;
    }

    // ---------------------------------------------------------------- 计算

    private record Computation(Proposal proposal, ConstrainedSchedulePreview raw, ProductionPlan insertPlan,
                               Map<String, List<LineSchedule>> movableByPlan, Map<String, ProductionPlan> plansById,
                               LocalDate firstDay) {
    }

    private Computation compute(String factoryId, InsertSlot slot, ProductionPlan insertPlan) {
        if (slot.getProductionLineId() == null || slot.getProductionLineId().isBlank() || slot.getStartTime() == null) {
            return new Computation(Proposal.notEvaluated("排程未验证: 时段没有绑定实际产线"), null, insertPlan,
                    Map.of(), Map.of(), null);
        }
        LocalDate today = LocalDate.now();
        LocalDate firstDay = slot.getStartTime().toLocalDate().isBefore(today) ? today : slot.getStartTime().toLocalDate();

        // 被挤的候选 = 计划名下所有在用排程行都是「还没开工 (pending)」且都在这条线上。
        // delayed 一定是已开工后超时的行 (只有 in_progress 才会被改成 delayed), 和 in_progress 一样是固定占用;
        // 同一计划只要有一行已开工 / 在别的线上, 整个计划都不动 (引擎按计划整体剩余量重排, 动一半会重复占量)。
        Map<String, List<LineSchedule>> livePerPlan = new LinkedHashMap<>();
        for (LineSchedule ls : constraints.findLiveEndingFrom(factoryId, firstDay.atStartOfDay())) {
            if (ls.getProductionPlanId() == null || Objects.equals(ls.getProductionPlanId(), insertPlan.getId())) {
                continue;
            }
            livePerPlan.computeIfAbsent(ls.getProductionPlanId(), k -> new ArrayList<>()).add(ls);
        }
        Map<String, List<LineSchedule>> movableByPlan = new LinkedHashMap<>();
        livePerPlan.forEach((planId, rows) -> {
            boolean movable = rows.stream().allMatch(ls ->
                    ls.getStatus() == LineSchedule.ScheduleStatus.pending
                            && Objects.equals(ls.getProductionLineId(), slot.getProductionLineId()));
            if (movable) {
                movableByPlan.put(planId, rows);
            }
        });
        if (movableByPlan.size() > MAX_MOVED_PLANS) {
            return new Computation(new Proposal(BLOCKED, "同一产线上待重排的计划有 " + movableByPlan.size()
                    + " 个，超过 " + MAX_MOVED_PLANS + " 个，请先缩小范围", null, null, List.of(), List.of(), null),
                    null, insertPlan, movableByPlan, Map.of(), firstDay);
        }
        List<String> planIds = new ArrayList<>();
        planIds.add(insertPlan.getId());
        planIds.addAll(movableByPlan.keySet());
        Set<String> movableIds = movableByPlan.values().stream().flatMap(List::stream).map(LineSchedule::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        ConstrainedSchedulePreview raw = generator.preview(factoryId, new SchedulePreviewRequest(firstDay,
                slot.getProductionLineId(), planIds, List.of(insertPlan.getId()), movableIds, List.of()));

        Map<String, ProductionPlan> plansById = new HashMap<>();
        plansById.put(insertPlan.getId(), insertPlan);
        for (String id : movableByPlan.keySet()) {
            planRepository.findByIdAndFactoryId(id, factoryId).ifPresent(p -> plansById.put(id, p));
        }

        if (!"SCHEDULED".equals(raw.outcome())) {
            String products = raw.capacityRequired() == null ? "" : raw.capacityRequired().stream()
                    .map(UrgentInsertScheduleService::describeCapacityRequired).collect(Collectors.joining("；"));
            return new Computation(new Proposal(BLOCKED, "有产品没填日产能，排不出: " + products, null, null,
                    List.of(), raw.warnings() == null ? List.of() : raw.warnings(), null),
                    raw, insertPlan, movableByPlan, plansById, firstDay);
        }

        Map<String, PlanResult> byId = new HashMap<>();
        raw.plans().forEach(r -> byId.put(r.planId(), r));
        PlanResult mine = byId.get(insertPlan.getId());
        if (mine == null || !"SCHEDULED".equals(mine.status())) {
            return new Computation(new Proposal(BLOCKED, "插单排不进去: " + describe(mine), null, null, List.of(),
                    raw.warnings() == null ? List.of() : raw.warnings(), null),
                    raw, insertPlan, movableByPlan, plansById, firstDay);
        }

        List<Moved> moved = new ArrayList<>();
        for (Map.Entry<String, List<LineSchedule>> e : movableByPlan.entrySet()) {
            PlanResult r = byId.get(e.getKey());
            ProductionPlan plan = plansById.get(e.getKey());
            String number = plan == null ? e.getKey() : plan.getPlanNumber();
            if (r == null || "NOTHING_REMAINING".equals(r.status()) || "PLAN_CLOSED".equals(r.status())) {
                continue; // 已无剩余 / 已关闭: 不动它的排程行
            }
            if (!"SCHEDULED".equals(r.status())) {
                return new Computation(new Proposal(BLOCKED, "被挤的计划 " + number + " 排不进去: " + describe(r), null,
                        null, List.of(), raw.warnings() == null ? List.of() : raw.warnings(), null),
                        raw, insertPlan, movableByPlan, plansById, firstDay);
            }
            LocalDate oldEnd = e.getValue().stream().map(LineSchedule::getPlannedEndTime).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).map(LocalDateTime::toLocalDate).orElse(null);
            LocalDate newEnd = lastDay(r);
            LocalDate due = plan == null ? null : plan.getExpectedCompletionDate();
            boolean exceeds = due != null && newEnd != null && newEnd.isAfter(due);
            long delay = oldEnd == null || newEnd == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(oldEnd, newEnd);
            moved.add(new Moved(e.getKey(), number, oldEnd, newEnd, delay, due, exceeds, orderIdsOf(plan),
                    r.delays() == null ? List.of() : r.delays().stream().map(ConstrainedSchedulePreview.Delay::text).toList()));
        }
        // 插入首日的调度计划: 已完成排不进去; 已取消的可以重新启用 (取消后没有任何入口能重开, 而同一天只能有一个
        // 调度计划, 不重用这一天就永远插不了); 预览阶段就说清, 不等确认才失败
        List<String> warnings = new ArrayList<>(raw.warnings() == null ? List.of() : raw.warnings());
        SchedulingPlanRepository.DayPlanHead container = schedulingPlanRepository
                .findDayPlanHead(factoryId, firstDayOf(mine)).orElse(null);
        String blocked = containerBlockReason(container == null ? null : container.getId(),
                container == null ? null : container.getStatus(), firstDayOf(mine));
        if (blocked != null) {
            return new Computation(new Proposal(BLOCKED, blocked, null, null, List.of(), warnings, null),
                    raw, insertPlan, movableByPlan, plansById, firstDay);
        }
        if (container != null && container.getStatus() == SchedulingPlan.PlanStatus.cancelled) {
            // 预览阶段说的是「将要」: 数量按此刻名下没开工的 pending 排程算 (确认时以数据库现数为准)
            long willVoid = lineScheduleRepository.findByPlanId(container.getId()).stream()
                    .filter(ls -> ls.getStatus() == LineSchedule.ScheduleStatus.pending && ls.getActualStartTime() == null)
                    .count();
            warnings.add(SchedulingPlanCrudServiceImpl.reopenWarning((int) willVoid, 0));
        }
        Proposal proposal = new Proposal(OK, null, firstDayOf(mine), lastDay(mine), moved,
                warnings, fingerprint(mine, raw, moved, movableByPlan, container));
        return new Computation(proposal, raw, insertPlan, movableByPlan, plansById, firstDay);
    }

    /**
     * 插入首日的调度计划能不能承接插单。null = 能 (没有 / 进行中 / 已取消且名下没有已开工的排程)。
     * 已完成: 整天做完了, 不塞。已取消但名下还有已开工 (in_progress / delayed) 的排程: 动它等于动在做的活, 不碰。
     */
    private String containerBlockReason(String containerId, SchedulingPlan.PlanStatus status, LocalDate day) {
        if (containerId == null) {
            return null;
        }
        if (status == SchedulingPlan.PlanStatus.completed) {
            return "插入首日 " + day + " 的调度计划已完成，无法排入，请改选其他时段";
        }
        if (status == SchedulingPlan.PlanStatus.cancelled) {
            boolean started = lineScheduleRepository.findByPlanId(containerId).stream().anyMatch(ls ->
                    ls.getStatus() == LineSchedule.ScheduleStatus.in_progress
                            || ls.getStatus() == LineSchedule.ScheduleStatus.delayed
                            || ls.getActualStartTime() != null && ls.getStatus() != LineSchedule.ScheduleStatus.cancelled
                            && ls.getStatus() != LineSchedule.ScheduleStatus.completed);
            if (started) {
                return "插入首日 " + day + " 的调度计划已取消，但名下还有已开工的排程，无法重新启用，请先处理这些排程或改选其他时段";
            }
        }
        return null;
    }

    /** 给人看的「缺哪个产品的日产能」: 产品名 + 单位 + 缺的原因 + 涉及计划号, 不是 DTO 的 toString。 */
    private static String describeCapacityRequired(DailyCapacityRequiredDTO d) {
        if (d == null) {
            return "（未知产品）";
        }
        String name = d.getProductName() != null && !d.getProductName().isBlank() ? d.getProductName()
                : d.getProductTypeId() != null ? d.getProductTypeId() : "（未知产品）";
        String unit = d.getUnit() == null || d.getUnit().isBlank() ? "" : "（单位 " + d.getUnit() + "）";
        String why = "UNIT_MISMATCH".equals(d.getReason()) ? "日产能的单位和计划单位对不上" : "没填日产能";
        String plans = d.getPlanNumbers() == null || d.getPlanNumbers().isEmpty() ? ""
                : "，涉及计划 " + String.join("、", d.getPlanNumbers());
        return name + unit + why + plans;
    }

    private static String describe(PlanResult r) {
        if (r == null) {
            return "引擎没有返回这个计划的结果";
        }
        String delays = r.delays() == null ? "" : r.delays().stream().map(ConstrainedSchedulePreview.Delay::text)
                .collect(Collectors.joining("；"));
        String notes = r.notes() == null ? "" : String.join("；", r.notes());
        return r.status() + (delays.isBlank() ? "" : " — " + delays) + (notes.isBlank() ? "" : " — " + notes);
    }

    private static LocalDate firstDayOf(PlanResult r) {
        return r.days() == null || r.days().isEmpty() ? null : r.days().get(0).date();
    }

    private static LocalDate lastDay(PlanResult r) {
        return r.days() == null || r.days().isEmpty() ? null : r.days().get(r.days().size() - 1).date();
    }

    private static List<String> orderIdsOf(ProductionPlan plan) {
        if (plan == null) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        if (plan.getSourceOrderIds() != null) {
            ids.addAll(plan.getSourceOrderIds());
        }
        if (plan.getSourceOrderId() != null && !plan.getSourceOrderId().isBlank()) {
            ids.add(plan.getSourceOrderId());
        }
        return new ArrayList<>(ids);
    }

    /**
     * 指纹: 插单每天做多少 + 每个被挤计划每天做多少 / 状态, 再加上预览那一刻已存的东西:
     * 被挤计划名下排程行当前的起止 / 数量 / 状态 / 调整次数, 以及插入首日调度计划的 id 与状态。
     * 只哈希引擎重算结果会漏掉「预览之后有人手工改了被挤计划的排程」: 引擎把它们当可挪的从头重排,
     * 手改的日期在确认时被悄悄覆盖 (统一验收 2026-11-10 实测)。
     */
    private static String fingerprint(PlanResult mine, ConstrainedSchedulePreview raw, List<Moved> moved,
                                      Map<String, List<LineSchedule>> movableByPlan, SchedulingPlanRepository.DayPlanHead container) {
        Set<String> movedIds = moved.stream().map(Moved::planId).collect(Collectors.toSet());
        StringBuilder canon = new StringBuilder(daysOf(mine));
        canon.append("|DAY=").append(container == null ? "none" : container.getId() + ":" + container.getStatus());
        movedIds.stream().sorted().forEach(planId -> {
            List<LineSchedule> rows = movableByPlan.get(planId);
            if (rows == null) {
                return;
            }
            rows.stream().sorted(Comparator.comparing(LineSchedule::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .forEach(ls -> canon.append("|ROW=").append(ls.getId()).append(':').append(ls.getStatus())
                            .append(':').append(ls.getPlannedStartTime()).append(':').append(ls.getPlannedEndTime())
                            .append(':').append(ls.getPlannedQuantity()).append(':').append(ls.getAdjustmentCount()));
        });
        raw.plans().stream().filter(r -> movedIds.contains(r.planId()))
                .sorted(Comparator.comparing(PlanResult::planId))
                .forEach(r -> canon.append('|').append(r.planId()).append('=').append(r.status()).append(':')
                        .append(daysOf(r)));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canon.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String daysOf(PlanResult r) {
        if (r.days() == null) {
            return "";
        }
        return r.days().stream().map(d -> d.date() + ":" + plain(d.quantity())).collect(Collectors.joining(","));
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------- 落库

    private void write(String factoryId, InsertSlot slot, ProductionPlan insertPlan, Computation c, Long userId) {
        Map<String, PlanResult> byId = new HashMap<>();
        c.raw().plans().forEach(r -> byId.put(r.planId(), r));
        LocalDateTime now = LocalDateTime.now();
        PlanResult mine = byId.get(insertPlan.getId());
        LocalDate day = firstDayOf(mine);

        // 0) 固定的加锁顺序: 先锁当天的调度计划行, 再动被挤计划的排程行。锁只用来排队 (和别的重开 / 取消 / 建当天计划的
        //    路径串行); 取得锁之后的状态以数据库的标量读为准, 不信锁前可能已载入过的旧副本。
        //    (确认 / 批准入口本身已先拿全厂写锁, 两个插单之间天然串行; 这把行锁防的是不拿全厂锁的路径。)
        SchedulingPlan container = schedulingPlanRepository.lockDayPlan(factoryId, day).orElse(null);

        // 1) 被挤计划: 第一条排程行改成新的起止 / 数量 / 按天分配, 其余行取消 (引擎按计划整体剩余量排, 一行就够)
        for (Moved m : c.proposal().moved()) {
            PlanResult r = byId.get(m.planId());
            List<LineSchedule> rows = c.movableByPlan().get(m.planId());
            LineSchedule head = rows.get(0);
            head.setPlannedStartTime(firstDayOf(r).atStartOfDay());
            head.setPlannedEndTime(lastDay(r).atTime(DAY_END));
            head.setPlannedQuantity(roundedQuantity(r));
            head.setDailyAllocations(r.dailyAllocations());
            head.setAdjustmentCount((head.getAdjustmentCount() == null ? 0 : head.getAdjustmentCount()) + 1);
            head.setLastAdjustmentTime(now);
            head.setAdjustmentReason(trim("紧急插单 " + insertPlan.getPlanNumber() + " 挤占，顺延 " + m.delayDays() + " 天", 200));
            lineScheduleRepository.save(head);
            for (int i = 1; i < rows.size(); i++) {
                LineSchedule extra = rows.get(i);
                extra.setStatus(LineSchedule.ScheduleStatus.cancelled);
                extra.setAdjustmentReason(trim("紧急插单 " + insertPlan.getPlanNumber() + " 重排，合并到同计划的另一条排程", 200));
                lineScheduleRepository.save(extra);
            }
        }
        // 被挤计划的改动单独先 flush: 之后建当天调度计划时的 flush 若抛唯一约束冲突, 就一定是那一个, 不会被错标
        lineScheduleRepository.flush();

        // 2) 插单: 挂在插入首日的调度计划下 (没有就建一个), 起止时间也写回计划
        if (container == null) {
            container = new SchedulingPlan();
            container.setFactoryId(factoryId);
            container.setPlanDate(day);
            container.setPlanName(day + " 紧急插单排程");
            container.setNotes("紧急插单自动排入");
            container.setCreatedBy(userId);
            container.setStatus(SchedulingPlan.PlanStatus.draft);
            try {
                // 立刻 flush: 同一天只能有一个调度计划 (唯一约束), 和自动排程在同一个新日子上竞争时,
                // 在这里就把唯一约束冲突翻成 409, 而不是拖到提交时变成 500
                container = schedulingPlanRepository.saveAndFlush(container);
            } catch (org.springframework.dao.DataIntegrityViolationException race) {
                throw new BusinessException(409, "插入首日 " + day + " 的调度计划刚被另一处创建，请重新预览")
                        .withCode("INSERT_SCHEDULE_CONTAINER_RACE")
                        .withHint("重新创建确认预览，会接到那个调度计划下");
            }
        } else {
            SchedulingPlanRepository.DayPlanHead fresh = schedulingPlanRepository
                    .findDayPlanHead(factoryId, day).orElseThrow(() -> new BusinessException(409,
                            "插入首日 " + day + " 的调度计划刚被改动，请重新预览")
                            .withCode("INSERT_SCHEDULE_CONTAINER_CLOSED").withHint("重新创建确认预览"));
            if (fresh.getStatus() == SchedulingPlan.PlanStatus.completed) {
                throw new BusinessException(409, containerBlockReason(fresh.getId(), fresh.getStatus(), day))
                        .withCode("INSERT_SCHEDULE_CONTAINER_CLOSED")
                        .withHint("先到调度计划页处理该日调度计划，或改选其他时段");
            }
            if (fresh.getStatus() == SchedulingPlan.PlanStatus.cancelled) {
                reopenCancelledDay(factoryId, day, fresh, insertPlan, userId, now);
            }
        }
        LineSchedule created = new LineSchedule();
        created.setPlan(container);
        created.setProductionLineId(slot.getProductionLineId());
        created.setProductionPlanId(insertPlan.getId());
        created.setSequenceOrder(0);
        created.setPlannedStartTime(day.atStartOfDay());
        created.setPlannedEndTime(lastDay(mine).atTime(DAY_END));
        created.setPlannedQuantity(roundedQuantity(mine));
        created.setDailyAllocations(mine.dailyAllocations());
        created.setStatus(LineSchedule.ScheduleStatus.pending);
        lineScheduleRepository.save(created);
        // ⛔ 不改计划的 startTime / endTime: 影响分析按它们判重叠, 引擎自己的 generate 也不写它们;
        //    新的完成日以排程行 (LineSchedule) 为准, 交付风险闭环读的也是排程行。
        log.info("插单已排入: planId={}, 日期 {}~{}, 被挤计划 {} 个, 交期被突破 {} 个", insertPlan.getId(), day,
                lastDay(mine), c.proposal().moved().size(),
                c.proposal().moved().stream().filter(Moved::exceedsDue).count());
    }

    /**
     * 重新启用已取消的日调度计划 (取消后没有任何入口能重开, 而同一天只能有一个调度计划)。
     * 已持有当天行锁。先作废名下没开工的 pending 排程 (一条条件 UPDATE, 此时行被锁住, 并发的开工要等), 再数「已开工」的:
     * 有就整体回滚 (409); 没有才改回草稿并留痕 (谁、何时、因哪个插单、作废几条)。
     */
    private void reopenCancelledDay(String factoryId, LocalDate day, SchedulingPlanRepository.DayPlanHead fresh,
                                    ProductionPlan insertPlan, Long userId, LocalDateTime now) {
        String reason = trim(SchedulingPlanCrudServiceImpl.REOPEN_VOIDED_ROW_REASON
                + "（紧急插单 " + insertPlan.getPlanNumber() + "）", 200);
        int voided = schedulingPlanRepository.voidPendingRowsOnReopen(fresh.getId(), reason);
        if (schedulingPlanRepository.countStartedRows(fresh.getId()) > 0) {
            throw new BusinessException(409, "插入首日 " + day + " 的调度计划已取消，但名下还有已开工的排程，无法重新启用")
                    .withCode("INSERT_SCHEDULE_CONTAINER_CLOSED")
                    .withHint("先处理这些排程或改选其他时段");
        }
        // 留痕措辞与方法同缺口 18 (自动排程 / 手动新建的重开) 共用, 两边说法一致
        String notes = (fresh.getNotes() == null || fresh.getNotes().isBlank() ? "" : fresh.getNotes() + "\n")
                + SchedulingPlanCrudServiceImpl.reopenTrail(now, "紧急插单 " + insertPlan.getPlanNumber(), userId, voided, 0);
        if (schedulingPlanRepository.reopenCancelled(fresh.getId(), notes, now) != 1) {
            throw new BusinessException(409, "插入首日 " + day + " 的调度计划刚被改动，请重新预览")
                    .withCode("INSERT_SCHEDULE_CONTAINER_RACE")
                    .withHint("重新创建确认预览");
        }
        log.info("紧急插单重开已取消的日调度计划: factoryId={}, day={}, containerId={}, insertPlan={}, userId={}, 作废旧排程 {} 条",
                factoryId, day, fresh.getId(), insertPlan.getPlanNumber(), userId, voided);
    }

    private static Integer roundedQuantity(PlanResult r) {
        BigDecimal total = r.days() == null ? BigDecimal.ZERO : r.days().stream()
                .map(ConstrainedSchedulePreview.Day::quantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.setScale(0, java.math.RoundingMode.CEILING).intValue();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------------------------------------------------------- 交期提醒 (提交之后)

    /**
     * 交期被突破的订单登记到交付风险闭环 (#1443): 它按订单当前排程和交期重新评估, 有风险就开处理实例。
     * 放在提交之后: 评估里缺料分析可能抛业务异常, 在确认事务里会把整个确认标成回滚。
     */
    private void registerOverdueReminders(String factoryId, Computation c, Long userId) {
        // 凡是被顺延的计划所属订单都交给闭环重新评估: 闭环按订单自己的交期和当前排程判风险,
        // 不能只看计划交期 (计划交期可能为空或晚于订单交期)。没风险的它不开实例。
        Set<String> orderIds = c.proposal().moved().stream().filter(m -> m.delayDays() > 0)
                .flatMap(m -> m.orderIds().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderIds.isEmpty()) {
            return;
        }
        if (userId == null || userId <= 0) {
            log.error("插单交期提醒无法登记: 缺少操作人, factoryId={}, orders={}", factoryId, orderIds);
            return;
        }
        ActorRef actor = new ActorRef(AuthenticatedActorType.FACTORY_USER, userId);
        Runnable remind = () -> {
            try {
                // afterCommit 里原事务已提交, 在它里面做数据写入不会再提交 ⇒ 必须开新事务
                TransactionTemplate fresh = new TransactionTemplate(transactionManager);
                fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                Integer n = fresh.execute(s -> deliveryRiskService.refreshOrders(factoryId, orderIds, actor));
                log.info("插单交期提醒已登记: factoryId={}, 订单 {} 个, 处理 {} 个", factoryId, orderIds.size(), n);
            } catch (RuntimeException e) {
                log.error("插单交期提醒登记失败: factoryId={}, orders={}", factoryId, orderIds, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    remind.run();
                }
            });
        } else {
            remind.run();
        }
    }
}
