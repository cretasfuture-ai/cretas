package com.cretas.aims.service.scheduling.daily;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按产品日产能按天排 (APS v2, Steve 2026-10-28 裁定口径) + 缺口 18 约束 (Steve 2026-11-09 裁定)。纯计算, 不读库不写库。
 *
 * <h3>日产能 (v2, 不变)</h3>
 * <ul>
 *   <li>一个作业 (一个生产计划) 要做 {@code quantity}, 它的产品一天能做 {@code capacityPerDay}。
 *       一天做不完顺延到下一天 (日历日连续, 不跳周末 —— 没有工厂日历数据, ⛔ 不猜)。</li>
 *   <li>同一条产线一天的产能被多个作业按顺序分着用: 用量按「占当天的比例」记, 下一个作业从这天剩下的比例开始。</li>
 *   <li>日产能为空或 &le; 0: {@link Status#NEEDS_CAPACITY}; 要做的量 &le; 0: {@link Status#NOTHING_REMAINING};
 *       超过 {@link #MAX_DAYS} 天还排不完: {@link Status#EXCEEDS_HORIZON}, 不占产能。</li>
 * </ul>
 *
 * <h3>约束 (缺口 18)</h3>
 * <ul>
 *   <li><b>排序</b>: 按传入顺序 (调用方已按优先级排好), 但被依赖的作业一定先排 (稳定拓扑序); 成环的依赖丢掉并标出。</li>
 *   <li><b>跨计划依赖</b>: B 依赖 A ⇒ B 最早在 A 最后一天开始, 不留缓冲 (Steve: 留不留时间看工厂自己的纪律)。
 *       A 本次没有排出来 (超期 / 设备不可用 / 自己的依赖没排出) ⇒ B 也不排 ({@link Status#PREDECESSOR_NOT_SCHEDULED});
 *       A 已经做完 ({@link Status#NOTHING_REMAINING}) ⇒ 不约束。</li>
 *   <li><b>最早开工</b>: {@code earliestStart} (原料到货次日等, 调用方算好), 早于它的日子不排。</li>
 *   <li><b>设备</b>: 同一台设备同一天只做一个产品; 同一产品的多个作业可以共用。固定占用 (已落库的排程) 与本次排出的都算。
 *       设备停用日 ({@link EquipmentDayBlock}, 如保养日) 当天不排。</li>
 *   <li><b>产线</b>: 已落库的同线排程占掉的比例先扣掉。</li>
 * </ul>
 * 每个作业比「第一天」晚开工或中途跳过的日子都记一条 {@link Delay} (合并连续同因的日子), 给人看为什么。
 *
 * <p>数量保留 2 位小数 (与计划量同精度), 占当天比例保留 6 位。
 */
public final class DailyCapacityEngine {

    public static final int MAX_DAYS = 366;
    static final int QTY_SCALE = 2;
    static final int SHARE_SCALE = 6;

    private DailyCapacityEngine() {
    }

    public enum Status {
        SCHEDULED, NEEDS_CAPACITY, NOTHING_REMAINING, EXCEEDS_HORIZON,
        /** 所需设备报废 / 不存在: 调用方在 {@link Job#unavailableReason()} 里给原因。 */
        EQUIPMENT_UNAVAILABLE,
        /** 依赖的计划本次没有排出来。 */
        PREDECESSOR_NOT_SCHEDULED
    }

    public enum DelayCode {
        /** 原料要等到货 (earliestStart 的原因)。 */
        MATERIAL_ARRIVAL,
        /** 等依赖的计划。 */
        PREDECESSOR,
        /** 设备那天被别的产品占着。 */
        EQUIPMENT_BUSY,
        /** 设备那天停用 (保养)。 */
        MAINTENANCE,
        /** 产线那天被已有排程占满。 */
        LINE_FULL
    }

    /** {@code from..to} (含) 这几天因为 {@code code} 没排; {@code detail} 是给人看的一句话。 */
    public record Delay(DelayCode code, LocalDate from, LocalDate to, String detail) {
    }

    /** v2 旧入参: 只有量与日产能 (没有约束)。 */
    public record JobIn(String key, BigDecimal quantity, BigDecimal capacityPerDay) {
    }

    /**
     * 带约束的作业。
     *
     * @param productTypeId     设备「同一产品可共用」按它判; 空时按 key 判 (只和自己共用)
     * @param earliestStart     最早哪天能开工 (空 = 不限); 晚于第一天时记 {@code earliestStartCode} 的推迟
     * @param earliestStartText 推迟原因的话
     * @param equipmentIds      要用的设备 (空 = 没配置, 不核对设备)
     * @param predecessorKeys   要等哪些作业 (同一批里的 key; 不在本批的忽略)
     * @param unavailableReason 非空 ⇒ 直接 {@link Status#EQUIPMENT_UNAVAILABLE}, 不排
     */
    public record Job(String key, String label, String productTypeId, BigDecimal quantity, BigDecimal capacityPerDay,
                      LocalDate earliestStart, DelayCode earliestStartCode, String earliestStartText,
                      Set<String> equipmentIds, Set<String> predecessorKeys, String unavailableReason) {
        public Job {
            equipmentIds = equipmentIds == null ? Set.of() : Set.copyOf(equipmentIds);
            predecessorKeys = predecessorKeys == null ? Set.of() : Set.copyOf(predecessorKeys);
            label = label == null || label.isBlank() ? key : label;
        }

        static Job of(JobIn in) {
            return new Job(in.key(), in.key(), null, in.quantity(), in.capacityPerDay(), null, null, null, null, null,
                    null);
        }
    }

    /**
     * 已经落库、本次不挪的占用 (人工排的、之前自动排的)。
     *
     * @param sameLine   是不是本次要排的这条线 (是才扣产线比例; 设备不论哪条线都算)
     * @param shareOfDay 占这条线当天的比例
     * @param label      给人看的「谁占着」, 如「计划 PLAN-7（酱牛肉）」
     */
    public record FixedOccupancy(LocalDate date, boolean sameLine, BigDecimal shareOfDay, String productTypeId,
                                 Set<String> equipmentIds, String label) {
        public FixedOccupancy {
            equipmentIds = equipmentIds == null ? Set.of() : Set.copyOf(equipmentIds);
        }
    }

    /** 这台设备这天不能用 (保养等)。 */
    public record EquipmentDayBlock(String equipmentId, LocalDate date, String label) {
    }

    /** @param equipmentLabels 设备 id → 给人看的名字 (缺的用 id) */
    public record Input(LocalDate firstDay, List<Job> jobs, List<FixedOccupancy> fixed, List<EquipmentDayBlock> blocks,
                        Map<String, String> equipmentLabels) {
        public Input(LocalDate firstDay, List<Job> jobs, List<FixedOccupancy> fixed, List<EquipmentDayBlock> blocks) {
            this(firstDay, jobs, fixed, blocks, Map.of());
        }

        public Input {
            Objects.requireNonNull(firstDay, "firstDay");
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            fixed = fixed == null ? List.of() : List.copyOf(fixed);
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            equipmentLabels = equipmentLabels == null ? Map.of() : Map.copyOf(equipmentLabels);
        }
    }

    /** 某一天给这个作业的量, 以及它占这天产线产能的比例 (0, 1]。 */
    public record DayOut(LocalDate date, BigDecimal quantity, BigDecimal shareOfDay) {
    }

    /**
     * 一个作业的结果。{@code notes}: 引擎层面的说明 (依赖成环被丢掉等), 调用方再拼上自己的。
     */
    public record JobOut(String key, Status status, List<DayOut> days, List<Delay> delays, List<String> notes) {
        public JobOut(String key, Status status, List<DayOut> days) {
            this(key, status, days, List.of(), List.of());
        }

        public LocalDate firstDay() {
            return days.isEmpty() ? null : days.get(0).date();
        }

        public LocalDate lastDay() {
            return days.isEmpty() ? null : days.get(days.size() - 1).date();
        }

        public BigDecimal totalQuantity() {
            return days.stream().map(DayOut::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    static boolean missingCapacity(BigDecimal capacity) {
        return capacity == null || capacity.signum() <= 0;
    }

    /** v2 旧入口: 没有约束, 结果与传入同序。 */
    public static List<JobOut> allocate(List<JobIn> jobs, LocalDate firstDay) {
        return schedule(new Input(firstDay, jobs.stream().map(Job::of).toList(), List.of(), List.of()));
    }

    /** 带约束排; 结果与 {@code input.jobs()} <b>同序</b> (不是执行顺序)。 */
    public static List<JobOut> schedule(Input input) {
        LocalDate firstDay = input.firstDay();
        LocalDate horizonEnd = firstDay.plusDays(MAX_DAYS - 1L);
        Map<String, Job> byKey = new LinkedHashMap<>();
        for (Job j : input.jobs()) {
            if (byKey.putIfAbsent(j.key(), j) != null) {
                throw new IllegalArgumentException("duplicate job key " + j.key());
            }
        }

        // 占用表: 产线比例 (本线) / 设备那天被哪个产品用 / 设备那天停用
        Map<LocalDate, BigDecimal> lineUsed = new HashMap<>();
        Map<LocalDate, String> lineUsedBy = new HashMap<>();
        Map<String, Map<LocalDate, EquipUse>> equipUse = new HashMap<>();
        for (FixedOccupancy f : input.fixed()) {
            if (f.sameLine() && f.shareOfDay() != null && f.shareOfDay().signum() > 0) {
                lineUsed.merge(f.date(), f.shareOfDay(), BigDecimal::add);
                lineUsedBy.merge(f.date(), f.label(), (a, b) -> a.contains(b) ? a : a + "、" + b);
            }
            for (String e : f.equipmentIds()) {
                equipUse.computeIfAbsent(e, k -> new HashMap<>())
                        .putIfAbsent(f.date(), new EquipUse(productKey(f.productTypeId(), f.label()), f.label()));
            }
        }
        Map<String, Map<LocalDate, String>> equipBlocked = new HashMap<>();
        for (EquipmentDayBlock b : input.blocks()) {
            equipBlocked.computeIfAbsent(b.equipmentId(), k -> new HashMap<>()).putIfAbsent(b.date(), b.label());
        }

        Map<String, List<String>> engineNotes = new HashMap<>();
        List<Job> order = executionOrder(input.jobs(), byKey, engineNotes);
        Map<String, JobOut> results = new HashMap<>();

        for (Job job : order) {
            List<String> notes = engineNotes.getOrDefault(job.key(), List.of());
            if (missingCapacity(job.capacityPerDay())) {
                results.put(job.key(), new JobOut(job.key(), Status.NEEDS_CAPACITY, List.of(), List.of(), notes));
                continue;
            }
            if (job.quantity() == null || job.quantity().signum() <= 0) {
                results.put(job.key(), new JobOut(job.key(), Status.NOTHING_REMAINING, List.of(), List.of(), notes));
                continue;
            }
            if (job.unavailableReason() != null) {
                List<String> n = new ArrayList<>(notes);
                n.add(job.unavailableReason());
                results.put(job.key(), new JobOut(job.key(), Status.EQUIPMENT_UNAVAILABLE, List.of(), List.of(), n));
                continue;
            }
            DelayRecorder delays = new DelayRecorder();
            LocalDate start = firstDay;
            if (job.earliestStart() != null && job.earliestStart().isAfter(start)) {
                start = job.earliestStart();
                delays.add(job.earliestStartCode() == null ? DelayCode.MATERIAL_ARRIVAL : job.earliestStartCode(),
                        firstDay, start.minusDays(1), job.earliestStartText());
            }
            String blockedBy = null;
            for (String p : job.predecessorKeys()) {
                if (!byKey.containsKey(p) || p.equals(job.key())) {
                    continue;
                }
                JobOut pre = results.get(p);
                if (pre == null) {
                    continue; // 成环被丢掉的边: executionOrder 已记 note
                }
                if (pre.status() == Status.NOTHING_REMAINING) {
                    continue;
                }
                if (pre.status() != Status.SCHEDULED) {
                    blockedBy = p;
                    break;
                }
                LocalDate ready = pre.lastDay(); // 不留缓冲: A 最后一天 B 就可以开始
                if (ready.isAfter(start)) {
                    delays.add(DelayCode.PREDECESSOR, start, ready.minusDays(1),
                            "要用「" + byKey.get(p).label() + "」做的半成品，它 " + ready + " 做完");
                    start = ready;
                }
            }
            if (blockedBy != null) {
                List<String> n = new ArrayList<>(notes);
                n.add("要用的半成品由「" + byKey.get(blockedBy).label() + "」做，它这次没有排出来，所以这个计划也没有排");
                results.put(job.key(), new JobOut(job.key(), Status.PREDECESSOR_NOT_SCHEDULED, List.of(), List.of(), n));
                continue;
            }

            String myProduct = productKey(job.productTypeId(), job.key());
            BigDecimal capacity = job.capacityPerDay();
            BigDecimal remaining = job.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            Map<LocalDate, BigDecimal> tentativeLine = new HashMap<>();
            List<DayOut> days = new ArrayList<>();
            LocalDate day = start;
            boolean exceeded = false;
            while (remaining.signum() > 0) {
                if (day.isAfter(horizonEnd)) {
                    exceeded = true;
                    break;
                }
                // 设备: 停用日 / 被别的产品占
                String skip = null;
                DelayCode skipCode = null;
                for (String e : sorted(job.equipmentIds())) {
                    String blockLabel = equipBlocked.getOrDefault(e, Map.of()).get(day);
                    if (blockLabel != null) {
                        skip = blockLabel;
                        skipCode = DelayCode.MAINTENANCE;
                        break;
                    }
                    EquipUse use = equipUse.getOrDefault(e, Map.of()).get(day);
                    if (use != null && !use.product().equals(myProduct)) {
                        skip = "设备「" + input.equipmentLabels().getOrDefault(e, e) + "」这天被「" + use.label() + "」占用";
                        skipCode = DelayCode.EQUIPMENT_BUSY;
                        break;
                    }
                }
                if (skip != null) {
                    delays.add(skipCode, day, day, skip);
                    day = day.plusDays(1);
                    continue;
                }
                BigDecimal used = lineUsed.getOrDefault(day, BigDecimal.ZERO);
                BigDecimal free = BigDecimal.ONE.subtract(used);
                BigDecimal producible = free.signum() <= 0 ? BigDecimal.ZERO
                        : free.multiply(capacity).setScale(QTY_SCALE, RoundingMode.DOWN);
                if (producible.signum() <= 0) {
                    String by = lineUsedBy.get(day);
                    if (by != null && !tentativeLine.containsKey(day)) {
                        delays.add(DelayCode.LINE_FULL, day, day, "产线这天已被「" + by + "」排满");
                    }
                    day = day.plusDays(1);
                    continue;
                }
                BigDecimal take;
                BigDecimal share;
                if (remaining.compareTo(producible) < 0) {
                    take = remaining;
                    share = take.divide(capacity, SHARE_SCALE, RoundingMode.UP).min(free);
                } else {
                    take = producible;
                    share = free; // 用掉这天剩下的全部
                }
                days.add(new DayOut(day, take, share));
                tentativeLine.put(day, used.add(share));
                remaining = remaining.subtract(take);
                if (remaining.signum() > 0) {
                    day = day.plusDays(1);
                }
            }
            if (exceeded) {
                results.put(job.key(), new JobOut(job.key(), Status.EXCEEDS_HORIZON, List.of(), List.of(), notes));
                continue;
            }
            lineUsed.putAll(tentativeLine);
            for (DayOut d : days) {
                lineUsedBy.merge(d.date(), job.label(), (a, b) -> a.contains(b) ? a : a + "、" + b);
                for (String e : job.equipmentIds()) {
                    equipUse.computeIfAbsent(e, k -> new HashMap<>())
                            .putIfAbsent(d.date(), new EquipUse(myProduct, job.label()));
                }
            }
            results.put(job.key(), new JobOut(job.key(), Status.SCHEDULED, List.copyOf(days), delays.list(), notes));
        }

        List<JobOut> out = new ArrayList<>();
        for (Job j : input.jobs()) {
            out.add(results.get(j.key()));
        }
        return out;
    }

    private record EquipUse(String product, String label) {
    }

    private static String productKey(String productTypeId, String fallback) {
        return productTypeId != null && !productTypeId.isBlank() ? "P:" + productTypeId : "K:" + fallback;
    }

    private static List<String> sorted(Collection<String> c) {
        return c.stream().sorted().toList();
    }

    /**
     * 执行顺序: 尽量保持传入顺序, 但依赖先排 (每轮取传入顺序里第一个「依赖都已排」的作业)。
     * 卡住 (成环) 时取剩下的第一个, 丢掉它指向还没排的作业的边并记 note。
     */
    static List<Job> executionOrder(List<Job> jobs, Map<String, Job> byKey, Map<String, List<String>> notes) {
        List<Job> remaining = new ArrayList<>(jobs);
        Set<String> done = new HashSet<>();
        List<Job> order = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Job pick = null;
            for (Job j : remaining) {
                boolean ready = true;
                for (String p : j.predecessorKeys()) {
                    if (byKey.containsKey(p) && !p.equals(j.key()) && !done.contains(p)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    pick = j;
                    break;
                }
            }
            if (pick == null) {
                pick = remaining.get(0);
                Set<String> dropped = new LinkedHashSet<>();
                for (String p : pick.predecessorKeys()) {
                    if (byKey.containsKey(p) && !done.contains(p)) {
                        dropped.add(p);
                    }
                }
                List<String> droppedLabels = dropped.stream().map(k -> byKey.get(k).label()).toList();
                notes.computeIfAbsent(pick.key(), k -> new ArrayList<>()).add("和「" + String.join("、", droppedLabels)
                        + "」互相依赖半成品（成环），没法定先后，这次没按依赖排；请核对配方");
            }
            remaining.remove(pick);
            done.add(pick.key());
            order.add(pick);
        }
        return order;
    }

    /** 把连续同因同话的推迟日子合并成一段。 */
    private static final class DelayRecorder {
        private final List<Delay> list = new ArrayList<>();

        void add(DelayCode code, LocalDate from, LocalDate to, String detail) {
            if (to.isBefore(from)) {
                return;
            }
            if (!list.isEmpty()) {
                Delay last = list.get(list.size() - 1);
                if (last.code() == code && Objects.equals(last.detail(), detail)
                        && !last.to().plusDays(1).isBefore(from)) {
                    list.set(list.size() - 1, new Delay(code, last.from(), to.isAfter(last.to()) ? to : last.to(), detail));
                    return;
                }
            }
            list.add(new Delay(code, from, to, detail));
        }

        List<Delay> list() {
            return List.copyOf(list);
        }
    }
}
