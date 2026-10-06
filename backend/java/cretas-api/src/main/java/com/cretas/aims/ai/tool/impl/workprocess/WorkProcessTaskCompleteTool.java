package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 完成工序 Tool: IN_PROGRESS → COMPLETED, 必填 actualQuantity.
 *
 * <p>触发: "完成工序任务 12, 产量 100" / "工序 12 做完了, 产出 50 个"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkProcessTaskCompleteTool extends AbstractBusinessTool {

    private final WorkProcessTaskService workProcessTaskService;

    @Override
    public String getToolName() {
        return "work_process_task_complete";
    }

    @Override
    public String getDescription() {
        return "完成一个工序任务 (IN_PROGRESS → COMPLETED), 必须录入实际产量 (actualQuantity)。"
                + "自动计算实际工时。可选 notes 备注。适用场景: 操作员完工录产量。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("taskId", Map.of(
                "type", "integer",
                "description", "工序任务编号"));
        properties.put("actualQuantity", Map.of(
                "type", "number",
                "description", "实际产量（一个数字，单位按这道工序的产出单位，如公斤或件）"));
        properties.put("notes", Map.of(
                "type", "string",
                "description", "备注 (选填), 如质量异常 / 特殊情况说明"));
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("taskId", "actualQuantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("taskId", "actualQuantity");
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        Long taskId = getLong(params, "taskId");
        BigDecimal actualQuantity = getBigDecimal(params, "actualQuantity");
        String notes = getString(params, "notes");
        Long operatorId = SecurityUtils.currentUserId();

        WorkProcessTaskDTO.CompleteRequest req = WorkProcessTaskDTO.CompleteRequest.builder()
                .actualQuantity(actualQuantity)
                .notes(notes)
                .build();
        WorkProcessTaskDTO result = workProcessTaskService.complete(factoryId, taskId, operatorId, req);
        log.info("AI 工具完成工序 task={}, actualQuantity={}, operator={}",
                taskId, actualQuantity, operatorId);
        return buildSimpleResult(
                String.format("工序任务 %d 已完成 (产量 %s, 实际工时 %s 分钟)",
                        taskId, actualQuantity,
                        result.getActualMinutes() != null ? result.getActualMinutes() : "-"),
                result);
    }

    /**
     * 只读预览 —— AI 对话里「预览 → 确认」的预览段 (RN / web 确认卡的数据源)。
     * ⛔ 只读任务, 不调 {@code workProcessTaskService.complete}; 规则见
     * {@link WorkProcessTaskTransitionPreview}。
     *
     * <p>⚠️ 完全没给实际产量时走不到这里: 基类 {@code preview()} 先按必填参数早退
     * NEED_MORE_INFO (success=false), 问句见 {@link #getParameterQuestion}。
     */
    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        return WorkProcessTaskTransitionPreview.complete(
                workProcessTaskService, factoryId,
                getLong(params, "taskId"),
                getBigDecimal(params, "actualQuantity"),
                getString(params, "notes"));
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("taskId".equals(paramName)) {
            return "请问要完成哪道工序任务？说任务编号就行。";
        }
        if ("actualQuantity".equals(paramName)) {
            return "这道工序实际做了多少？请告诉我实际产量。";
        }
        return super.getParameterQuestion(paramName);
    }

    /**
     * 改的是已有任务的状态与实际产量 → UPDATE。⛔ 不写这个覆盖会落到
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
