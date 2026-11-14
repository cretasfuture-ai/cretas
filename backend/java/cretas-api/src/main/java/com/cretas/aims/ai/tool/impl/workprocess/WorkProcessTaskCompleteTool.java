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
public class WorkProcessTaskCompleteTool extends AbstractBusinessTool
        implements com.cretas.aims.ai.tool.ParameterChoiceResolver {

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

    // ==================== 先确定报哪一道, 再记这一轮的产量 (Steve 2026-11-14) ====================

    /** 一次最多列几项; 再多一屏看不完, 提示说产品名缩小范围。 */
    static final int MAX_LISTED = 5;

    @Override
    public String choiceParameter() {
        return "taskId";
    }

    /**
     * 缺 taskId 时查「这个人现在能报工的工序任务」: 本厂 IN_PROGRESS, 且是本人的或还没指派的。口径与
     * {@link WorkProcessTaskService#list} (= RN 报工屏第一跳, 见 repository 注释「我的 + 未指派」) 同一份,
     * 指派给别人的不出现 (越权); ⛔ 不新写查询。只读。
     */
    @Override
    public String choiceLabel() {
        return "哪一道工序";
    }

    @Override
    public Resolution resolveChoices(String factoryId, Long userId, Map<String, Object> params, String userInput) {
        if (userId == null) {
            // 不知道是谁就不查: 传 null 会列全厂的 (口径是「我的 + 未指派」), 宁可按没有处理
            return Resolution.none("您现在没有进行中的工序任务。要报工的话，请先对要做的那道工序说「开工」。");
        }
        List<WorkProcessTaskDTO> mine = workProcessTaskService
                .list(factoryId, com.cretas.aims.entity.workprocess.WorkProcessTask.Status.IN_PROGRESS,
                        null, userId, org.springframework.data.domain.PageRequest.of(0, 50))
                .getContent();
        if (mine == null || mine.isEmpty()) {
            return Resolution.none("您现在没有进行中的工序任务。要报工的话，请先对要做的那道工序说「开工」。");
        }
        List<WorkProcessTaskDTO> candidates = narrowByUserInput(mine, userInput);
        if (candidates.size() == 1) {
            return Resolution.single(toChoice(candidates.get(0)));
        }
        List<Choice> listed = candidates.stream().limit(MAX_LISTED).map(WorkProcessTaskCompleteTool::toChoice).toList();
        StringBuilder message = new StringBuilder();
        BigDecimal quantity = params == null ? null : getBigDecimal(params, "actualQuantity");
        if (quantity != null) {
            message.append("产量 ").append(quantity.stripTrailingZeros().toPlainString()).append(" 我先记着。");
        }
        message.append("您要给哪一道工序报工？\n");
        for (int i = 0; i < listed.size(); i++) {
            message.append(i + 1).append(". ").append(listed.get(i).label()).append("\n");
        }
        if (candidates.size() > listed.size()) {
            message.append("还有 ").append(candidates.size() - listed.size()).append(" 道没列出，说产品名或工序名可以缩小范围。\n");
        }
        return Resolution.many(listed, message.toString().stripTrailing(),
                "回前面的数字，或者直接说产品名、工序名都行。",
                "请重新说一遍，带上产品名或工序名，比如「红烧肉切块报工」。");
    }

    /** 用户话里已经带了产品名 / 批次号 / 工序名: 先按它缩小候选 (一个都对不上就不缩, 照样让他选)。 */
    private static List<WorkProcessTaskDTO> narrowByUserInput(List<WorkProcessTaskDTO> tasks, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return tasks;
        }
        String text = userInput.toLowerCase(java.util.Locale.ROOT);
        List<WorkProcessTaskDTO> hits = tasks.stream()
                .filter(t -> keywordsOf(t).stream().anyMatch(k -> text.contains(k.toLowerCase(java.util.Locale.ROOT))))
                .toList();
        return hits.isEmpty() ? tasks : hits;
    }

    private static List<String> keywordsOf(WorkProcessTaskDTO t) {
        List<String> keywords = new java.util.ArrayList<>();
        for (String k : new String[]{t.getProductTypeName(), t.getProcessName(), t.getBatchNumber()}) {
            if (k != null && k.strip().length() >= 2) {
                keywords.add(k.strip());
            }
        }
        return keywords;
    }

    /** 一线看得懂的一行: 产品 · 批次 · 工序 · 计划量/已报量。没有的部分不写, ⛔ 不编。 */
    static Choice toChoice(WorkProcessTaskDTO t) {
        List<String> parts = new java.util.ArrayList<>();
        if (t.getProductTypeName() != null && !t.getProductTypeName().isBlank()) {
            parts.add(t.getProductTypeName().strip());
        }
        if (t.getBatchNumber() != null && !t.getBatchNumber().isBlank()) {
            parts.add("批次 " + t.getBatchNumber().strip());
        }
        if (t.getProcessName() != null && !t.getProcessName().isBlank()) {
            parts.add(t.getProcessName().strip());
        }
        String unit = t.getPlannedUnit() == null ? "" : t.getPlannedUnit().strip();
        String reported = t.getActualQuantity() == null ? null : plain(t.getActualQuantity()) + unit;
        if (t.getPlannedQuantity() != null) {
            parts.add("计划 " + plain(t.getPlannedQuantity()) + unit + (reported != null ? "，已报 " + reported : "，还没报过"));
        } else if (reported != null) {
            parts.add("已报 " + reported);
        }
        String label = parts.isEmpty() ? "一道进行中的工序" : String.join(" · ", parts);
        return new Choice(String.valueOf(t.getId()), label, keywordsOf(t));
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("taskId".equals(paramName)) {
            return "请问要给哪一道工序报工？说产品名或工序名就行。";
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
