package com.cretas.aims.service.scheduling.dag;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按工艺图排工序时间 (APS v1, Steve 2026-10-28 选定口径)。纯计算, 不读库不写库。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>一道工序等它在工艺图上的全部上游排完才开始 (完工-开工); 并行分支各自从最早可开的时刻开始。</li>
 *   <li>时长 = 该工序的预计工时 (分钟)。<b>缺工时的工序不排</b>, 标 {@link Status#MISSING_DURATION};
 *       它下游的工序也排不出 (不知道上游什么时候完), 标 {@link Status#BLOCKED_BY_MISSING}。⛔ 不拿默认值猜。</li>
 *   <li>同一种工序 (同一个工序定义 = 同一个工位) 同一时刻只做一个任务: 多个作业按传入顺序先到先排。</li>
 *   <li>已经完工 / 跳过 / 取消的工序不再占时间, 视为已经完成 ({@link Status#DONE})。</li>
 *   <li>有缺工时工序的作业整体不占工位 (它不会被落成排程, 不能把后面的作业挤后)。</li>
 * </ul>
 *
 * <p>「作业」({@link JobIn}) 是一个整体落 / 不落排程的单位 —— 调用方按生产计划组装 (一个计划下的全部批次
 * 一起算), 这样一个计划只要有一道缺工时, 它的所有批次都不占工位。任务 id 在一次调用内必须唯一。
 */
public final class DagScheduleEngine {

    private DagScheduleEngine() {
    }

    public enum Status { SCHEDULED, DONE, MISSING_DURATION, BLOCKED_BY_MISSING }

    /**
     * 一道工序任务。{@code predecessors} 是同一作业里它的上游任务 id;
     * {@code stationKey} 是工位 (同一工序定义); {@code minutes} 为空或 &le; 0 即「缺工时」。
     */
    public record TaskIn(Long taskId, String stationKey, String name, int processOrder, Integer minutes,
                         Set<Long> predecessors, boolean finished) {
        public TaskIn {
            predecessors = predecessors == null ? Set.of() : Set.copyOf(predecessors);
        }
    }

    public record JobIn(String jobKey, List<TaskIn> tasks) {
    }

    public record TaskOut(Long taskId, String name, int processOrder, LocalDateTime start, LocalDateTime end,
                          Status status, String waitingOn) {
    }

    /** {@code complete}: 本作业每道工序都排出来了 (或已完成), 才有可信的起止时间。 */
    public record JobOut(String jobKey, LocalDateTime start, LocalDateTime end,
                         boolean complete, List<TaskOut> tasks) {

        /** 缺工时的工序名 (去重、按工序顺序)。 */
        public List<String> missingDurationNames() {
            return tasks.stream()
                    .filter(t -> t.status() == Status.MISSING_DURATION)
                    .map(TaskOut::name)
                    .distinct()
                    .toList();
        }
    }

    static boolean missingDuration(TaskIn t) {
        return t.minutes() == null || t.minutes() <= 0;
    }

    public static List<JobOut> schedule(List<JobIn> jobs, LocalDateTime startAt) {
        Objects.requireNonNull(startAt, "startAt");
        Map<String, LocalDateTime> stationFree = new HashMap<>();
        List<JobOut> out = new ArrayList<>();
        for (JobIn job : jobs) {
            List<TaskIn> tasks = new ArrayList<>(job.tasks());
            tasks.sort(Comparator.comparingInt(TaskIn::processOrder).thenComparing(TaskIn::taskId));
            Map<Long, LocalDateTime> endOf = new HashMap<>();
            Map<Long, String> unknown = new HashMap<>(); // taskId -> 卡住它的那道缺工时工序名
            Set<Long> ids = new HashSet<>();
            tasks.forEach(t -> ids.add(t.taskId()));
            List<TaskOut> results = new ArrayList<>();
            LocalDateTime first = null;
            LocalDateTime last = null;
            boolean complete = true;
            // 有缺工时的作业整体不落排程, 也就不该占工位 (否则后面的作业被一个不存在的排程挤后)
            boolean reserve = tasks.stream().noneMatch(t -> !t.finished() && missingDuration(t));
            for (TaskIn t : tasks) {
                if (t.finished()) {
                    endOf.put(t.taskId(), startAt);
                    results.add(new TaskOut(t.taskId(), t.name(), t.processOrder(), null, null, Status.DONE, null));
                    continue;
                }
                String blockedBy = null;
                LocalDateTime earliest = startAt;
                for (Long p : t.predecessors()) {
                    if (!ids.contains(p)) {
                        continue; // 不在本作业图里的任务不约束本作业
                    }
                    if (unknown.containsKey(p)) {
                        blockedBy = unknown.get(p);
                        break;
                    }
                    LocalDateTime pe = endOf.get(p);
                    if (pe != null && pe.isAfter(earliest)) {
                        earliest = pe;
                    }
                }
                // 自己缺工时先于「被上游卡住」判: 否则只有每条链上第一道缺工时的工序被报出来,
                // 用户补一道、排一次、再冒出下一道 (2026-10-28 F006 线上实测: 卤制补上后才报分切/成型)。
                if (blockedBy != null && !missingDuration(t)) {
                    unknown.put(t.taskId(), blockedBy);
                    complete = false;
                    results.add(new TaskOut(t.taskId(), t.name(), t.processOrder(), null, null,
                            Status.BLOCKED_BY_MISSING, blockedBy));
                    continue;
                }
                if (missingDuration(t)) {
                    unknown.put(t.taskId(), t.name());
                    complete = false;
                    results.add(new TaskOut(t.taskId(), t.name(), t.processOrder(), null, null,
                            Status.MISSING_DURATION, null));
                    continue;
                }
                LocalDateTime free = t.stationKey() == null ? null : stationFree.get(t.stationKey());
                LocalDateTime start = free != null && free.isAfter(earliest) ? free : earliest;
                LocalDateTime end = start.plusMinutes(t.minutes());
                if (reserve && t.stationKey() != null) {
                    stationFree.put(t.stationKey(), end);
                }
                endOf.put(t.taskId(), end);
                results.add(new TaskOut(t.taskId(), t.name(), t.processOrder(), start, end, Status.SCHEDULED, null));
                first = first == null || start.isBefore(first) ? start : first;
                last = last == null || end.isAfter(last) ? end : last;
            }
            out.add(new JobOut(job.jobKey(), complete ? first : null, complete ? last : null, complete, results));
        }
        return out;
    }
}
