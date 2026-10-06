package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开始工序 Tool: PENDING → IN_PROGRESS.
 *
 * <p>触发: "开始工序任务 12" / "执行工序 12"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkProcessTaskStartTool extends AbstractBusinessTool {

    private final WorkProcessTaskService workProcessTaskService;

    @Override
    public String getToolName() {
        return "work_process_task_start";
    }

    @Override
    public String getDescription() {
        return "开始一个待开始的工序任务 (PENDING → IN_PROGRESS), 记录实际开始时间。需要 taskId。"
                + "适用场景: 责任人开工前确认开始, AI 流程触发自动开始。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("taskId", Map.of(
                "type", "integer",
                "description", "工序任务编号"));
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("taskId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("taskId");
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Long taskId = getLong(params, "taskId");
        Long operatorId = SecurityUtils.currentUserId();
        WorkProcessTaskDTO result = workProcessTaskService.start(factoryId, taskId, operatorId);
        log.info("AI 工具开始工序 task={}, operator={}", taskId, operatorId);
        return buildSimpleResult(
                // ⛔ 状态给人看中文 (枚举 getDisplayName), 不写枚举码 —— 2026-10-06 prod 实测一线看到「状态 → IN_PROGRESS」。
                String.format("工序任务 %d 已开始（状态：%s）", taskId,
                        WorkProcessTaskTransitionPreview.label(result.getStatus())),
                result);
    }

    /**
     * 只读预览 —— AI 对话里「预览 → 确认」的预览段 (RN / web 确认卡的数据源)。
     * ⛔ 只读任务, 不调 {@code workProcessTaskService.start}; 规则见
     * {@link WorkProcessTaskTransitionPreview}。
     */
    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        return WorkProcessTaskTransitionPreview.start(
                workProcessTaskService, factoryId, getLong(params, "taskId"));
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("taskId".equals(paramName)) {
            return "请问要开始哪道工序任务？说任务编号就行。";
        }
        return super.getParameterQuestion(paramName);
    }

    /**
     * 改的是已有任务的状态 → UPDATE。⛔ 不写这个覆盖会落到
     * {@code AbstractBusinessTool#getActionType} 的「按工具名后缀推断」: 名字里没有
     * _create/_update/_delete 就是 READ, 确认卡的影响说明于是写成「立即执行」而不是「将修改……现有数据」。
     */
    @Override
    public ActionType getActionType() {
        return ActionType.UPDATE;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
