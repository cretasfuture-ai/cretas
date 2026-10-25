package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报工 (开始 / 完成工序) 的<b>只读</b>预览 —— AI 对话里「预览 → 确认」的预览段。
 *
 * <p>⛔ 只调 {@link WorkProcessTaskService#getById}, 绝不调 {@code start / complete}:
 * 预览的全部意义在于「看清楚再决定」, 预览本身改了状态就没有可以反悔的东西了。
 *
 * <p>规则来源 (镜像写路径, 不另立一套):
 * <ul>
 *   <li>任务按 {@code factoryId + id} 读 ({@code WorkProcessTaskServiceImpl#getById} →
 *       {@code findByFactoryIdAndId}) —— 别厂的任务与「不存在」同一个长相, 不泄露存在性</li>
 *   <li>状态转换读 {@link WorkProcessTaskService#isTransitionAllowed} —— 写路径
 *       {@code assertTransition} 读的是同一张表</li>
 *   <li>完成必填实际产量, 且不能为负 (0 允许) —— 同 {@code WorkProcessTaskServiceImpl#complete} 前两条校验</li>
 *   <li>开始前上游产出的料要到 —— 读 {@code getById} 带回的 {@code unlocked} (与写路径同一个
 *       {@code WorkflowStepUnlockGuard})</li>
 * </ul>
 *
 * <p>返回形状与其它写工具预览一致 ({@code ReceiveWithLimitTool}):
 * {@code status / canDo / message} + 业务字段 + {@code currentValues / newValues}
 * (ToolDispatchService 据此填确认卡的「当前 → 修改后」对比)。
 */
final class WorkProcessTaskTransitionPreview {

    static final String ACTION_START = "START";
    static final String ACTION_COMPLETE = "COMPLETE";

    private WorkProcessTaskTransitionPreview() {
    }

    /** 开始工序: PENDING → IN_PROGRESS。 */
    static Map<String, Object> start(WorkProcessTaskService service, String factoryId, Long taskId) {
        WorkProcessTaskDTO task = load(service, factoryId, taskId);
        if (task == null) {
            return notFound(ACTION_START, taskId);
        }
        Map<String, Object> result = base(ACTION_START, task, Status.IN_PROGRESS);
        Status from = task.getStatus();
        if (!WorkProcessTaskService.isTransitionAllowed(from, Status.IN_PROGRESS)) {
            return cannot(result, "INVALID_STATUS", startRefusal(task));
        }
        // 镜像写路径 (缺口 04): 上游产出的料还没到不能开工 —— unlocked 由 getById 用写路径同一个
        // WorkflowStepUnlockGuard 算出; null = 没算, 不当成拒绝。
        if (Boolean.FALSE.equals(task.getUnlocked())) {
            return cannot(result, "UPSTREAM_NOT_READY", task.getBlockedReason());
        }

        Map<String, Object> current = identityRows(task);
        current.put("状态", label(from));
        current.put("负责人", assigneeText(task));
        Map<String, Object> next = identityRows(task);
        next.put("状态", label(Status.IN_PROGRESS));
        // 写路径: 未指派的任务由开始的人认领 (WorkProcessTaskServiceImpl#start)。
        next.put("负责人", task.getAssignedTo() == null ? "确认人（你）" : assigneeText(task));
        dropRowsEmptyOnBothSides(current, next);
        result.put("currentValues", current);
        result.put("newValues", next);
        result.put("status", "PREVIEW");
        result.put("canDo", true);
        result.put("message", String.format(
                "确认开始%s？状态将从「%s」变为「%s」，并记录开始时间。",
                taskTitle(task), label(from), label(Status.IN_PROGRESS)));
        return result;
    }

    /** 完成工序: IN_PROGRESS → COMPLETED, 必填实际产量。 */
    static Map<String, Object> complete(WorkProcessTaskService service, String factoryId,
                                        Long taskId, BigDecimal actualQuantity, String notes) {
        // 写路径先查实际产量再读任务 (WorkProcessTaskServiceImpl#complete), 这里同序。
        // 能走到这里而为 null 的情形: 传了值但不是数字 (getBigDecimal 解析失败返回 null)。
        if (actualQuantity == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("action", ACTION_COMPLETE);
            r.put("taskId", taskId);
            return cannot(r, "PREVIEW_INVALID",
                    "没有读到有效的实际产量（需要一个数字），不能完成工序。请告诉我这道工序实际做了多少。");
        }
        // 镜像写路径: 负数不是产量, 0 允许 (一道工序可以什么都没产出)。
        // 写路径 WorkProcessTaskServiceImpl#complete 同序同判据 —— 两处必须同改。
        if (actualQuantity.signum() < 0) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("action", ACTION_COMPLETE);
            r.put("taskId", taskId);
            return cannot(r, "PREVIEW_INVALID",
                    "实际产量不能是负数，不能完成工序。请告诉我这道工序实际做了多少（没做出来就填 0）。");
        }
        WorkProcessTaskDTO task = load(service, factoryId, taskId);
        if (task == null) {
            return notFound(ACTION_COMPLETE, taskId);
        }
        Map<String, Object> result = base(ACTION_COMPLETE, task, Status.COMPLETED);
        result.put("actualQuantity", actualQuantity);
        if (notes != null && !notes.isBlank()) {
            result.put("notes", notes);
        }
        Status from = task.getStatus();
        if (!WorkProcessTaskService.isTransitionAllowed(from, Status.COMPLETED)) {
            return cannot(result, "INVALID_STATUS", completeRefusal(task));
        }

        Map<String, Object> current = identityRows(task);
        current.put("状态", label(from));
        current.put("实际产量", quantityText(task.getActualQuantity(), unitOf(task)));
        Map<String, Object> next = identityRows(task);
        next.put("状态", label(Status.COMPLETED));
        next.put("实际产量", quantityText(actualQuantity, unitOf(task)));
        if (notes != null && !notes.isBlank()) {
            current.put("备注", task.getNotes() != null ? task.getNotes() : "—");
            next.put("备注", notes);
        }
        dropRowsEmptyOnBothSides(current, next);
        result.put("currentValues", current);
        result.put("newValues", next);
        result.put("status", "PREVIEW");
        result.put("canDo", true);
        result.put("message", String.format(
                "确认完成%s，实际产量 %s？状态将从「%s」变为「%s」，实际工时按开始时间自动计算。",
                taskTitle(task), quantityText(actualQuantity, unitOf(task)),
                label(from), label(Status.COMPLETED)));
        return result;
    }

    // ==================== helpers ====================

    private static WorkProcessTaskDTO load(WorkProcessTaskService service, String factoryId, Long taskId) {
        if (taskId == null) {
            return null;
        }
        try {
            return service.getById(factoryId, taskId);
        } catch (ResourceNotFoundException notFound) {
            return null;
        }
    }

    private static Map<String, Object> base(String action, WorkProcessTaskDTO task, Status target) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", action);
        r.put("taskId", task.getId());
        r.put("processName", task.getProcessName());
        r.put("productionBatchId", task.getProductionBatchId());
        r.put("batchNumber", task.getBatchNumber());
        r.put("productTypeId", task.getProductTypeId());
        r.put("productTypeName", task.getProductTypeName());
        r.put("plannedQuantity", task.getPlannedQuantity());
        r.put("plannedUnit", task.getPlannedUnit());
        r.put("assignedToName", task.getAssignedToName());
        r.put("currentStatus", task.getStatus() != null ? task.getStatus().name() : null);
        r.put("currentStatusLabel", label(task.getStatus()));
        r.put("newStatus", target.name());
        r.put("newStatusLabel", label(target));
        return r;
    }

    private static Map<String, Object> notFound(String action, Long taskId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", action);
        r.put("taskId", taskId);
        return cannot(r, "TASK_NOT_FOUND", String.format(
                "找不到工序任务 %s，或它不属于本工厂。请核对任务编号后再试。",
                taskId != null ? taskId : "（未提供编号）"));
    }

    private static Map<String, Object> cannot(Map<String, Object> r, String status, String message) {
        r.put("status", status);
        r.put("canDo", false);
        r.put("message", message);
        return r;
    }

    private static String startRefusal(WorkProcessTaskDTO task) {
        Status from = task.getStatus();
        if (from == Status.IN_PROGRESS) {
            return String.format("%s已经开始了（当前「进行中」），不需要再开始。做完后告诉我实际产量就能报完工。",
                    taskTitle(task));
        }
        if (from != null && from.isTerminal()) {
            return String.format("%s已经是「%s」，不能重新开始。", taskTitle(task), label(from));
        }
        return String.format("%s当前是「%s」，不能开始。", taskTitle(task), label(from));
    }

    private static String completeRefusal(WorkProcessTaskDTO task) {
        Status from = task.getStatus();
        if (from == Status.PENDING) {
            return String.format("%s还没有开始（当前「待开始」），请先开始这道工序，再报完工。", taskTitle(task));
        }
        if (from != null && from.isTerminal()) {
            return String.format("%s已经是「%s」，不能再次完成。", taskTitle(task), label(from));
        }
        return String.format("%s当前是「%s」，不能完成。", taskTitle(task), label(from));
    }

    /** 确认卡里每一行都带上身份信息 (防呆 Rule 2), 当前/修改后两侧相同。 */
    private static Map<String, Object> identityRows(WorkProcessTaskDTO task) {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("工序任务", taskTitleBare(task));
        // ⛔ 批次 / 产品只显示人能认的名字 (批次号、产品名)。名字查不到就写「—」,
        //    不回退成内部编号: 2026-10-06 prod 实测确认卡把产品写成 UUID、批次写成「#10805」,
        //    一线认不出那是哪一批 —— 内部 id 不是给人读的 (防呆 Rule 2 要的是「认得出」)。
        rows.put("批次", notBlank(task.getBatchNumber()) ? task.getBatchNumber() : EMPTY);
        rows.put("产品", notBlank(task.getProductTypeName()) ? task.getProductTypeName() : EMPTY);
        rows.put("计划数量", quantityText(task.getPlannedQuantity(), task.getPlannedUnit()));
        return rows;
    }

    /** 「没有值」的统一写法; 与 {@link #quantityText} 的缺值同一个字。 */
    private static final String EMPTY = "—";

    /**
     * 身份行 (工序任务 / 批次 / 产品) 即使两边都没有值也保留, 显示「—」: 它们是「你正在动的是哪一个」
     * 的确认依据 (防呆 Rule 2), 「这一项没查到」比整行悄悄消失更诚实。数据行 (计划数量等) 才按空丢弃。
     */
    private static final java.util.Set<String> IDENTITY_ROWS = java.util.Set.of("工序任务", "批次", "产品");

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 改前 / 改后两侧都没有值的行不展示 —— 「计划数量 — → —」这种行什么信息都没有,
     * 只会让人以为数据坏了。只丢「两边都空」的: 一边有值的行 (例如实际产量 — → 95 kg) 必须留。
     * 身份行 ({@link #IDENTITY_ROWS}) 不在此列。两个 map 的键序一致 (都由 {@link #identityRows} 起头),
     * 这里按键同步删除。
     */
    private static void dropRowsEmptyOnBothSides(Map<String, Object> current, Map<String, Object> next) {
        for (String key : new java.util.ArrayList<>(current.keySet())) {
            if (IDENTITY_ROWS.contains(key)) {
                continue;
            }
            if (isEmptyCell(current.get(key)) && isEmptyCell(next.get(key))) {
                current.remove(key);
                next.remove(key);
            }
        }
    }

    private static boolean isEmptyCell(Object value) {
        return value == null || EMPTY.equals(String.valueOf(value).trim())
                || String.valueOf(value).isBlank();
    }

    private static String taskTitle(WorkProcessTaskDTO task) {
        return "工序任务 " + taskTitleBare(task);
    }

    private static String taskTitleBare(WorkProcessTaskDTO task) {
        String name = task.getProcessName();
        return name != null && !name.isBlank()
                ? task.getId() + "（" + name + "）"
                : String.valueOf(task.getId());
    }

    private static String assigneeText(WorkProcessTaskDTO task) {
        if (task.getAssignedTo() == null) {
            return "未指派";
        }
        // 查不到姓名 (用户已删除) 时不写「#用户id」 —— 内部编号不是给人读的。
        return notBlank(task.getAssignedToName()) ? task.getAssignedToName() : "已指派";
    }

    private static String unitOf(WorkProcessTaskDTO task) {
        return task.getPlannedUnit() != null ? task.getPlannedUnit() : task.getOutputUnit();
    }

    /** ⛔ 没有数就写「—」, 不兜底成 0 —— 「没填计划量」不是「计划量为 0」。 */
    private static String quantityText(BigDecimal qty, String unit) {
        if (qty == null) {
            return "—";
        }
        String n = qty.stripTrailingZeros().toPlainString();
        return unit != null && !unit.isBlank() ? n + " " + unit : n;
    }

    static String label(Status status) {
        // 状态中文名的唯一来源是枚举本身 (Status#getDisplayName), 这里不再自带一份 switch。
        return status == null ? "未知" : status.getDisplayName();
    }
}
