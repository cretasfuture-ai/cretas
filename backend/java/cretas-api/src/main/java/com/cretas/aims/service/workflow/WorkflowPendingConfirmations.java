package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 配工艺时「说不清、留给人补」的项 —— 发布前必须补齐。
 *
 * <p>载体就是画布节点自己的 data(同一份 nodesJson, 进 revisionHash), 不另造一套:
 * <ul>
 *   <li>{@code ccpPoints}: 工序上的 CCP 控制点。限值/单位为 null = AI 没给, 待人补。判据是**结构**
 *       (字段是不是空), 人在画布上把值填上, 待确认就自然消失, 没有第二个"已确认"开关可以忘记关。</li>
 *   <li>{@code pendingConfirmations}: 其余说不清的项(主料份额、回料比例等), 每项 {code,label},
 *       人核对后在画布上点"已确认"移除。</li>
 * </ul>
 */
public final class WorkflowPendingConfirmations {

    public static final String CCP_POINTS = "ccpPoints";
    /** 工序标准参数(油温/时间/孔径…): 发布时写入该工序 customFieldSchema, 见 WorkflowProcessParamMaterializer。 */
    public static final String PROCESS_PARAMS = "processParams";
    public static final String PENDING = "pendingConfirmations";
    public static final Set<String> HAZARD_TYPES = Set.of("BIOLOGICAL", "CHEMICAL", "PHYSICAL");

    private WorkflowPendingConfirmations() {
    }

    /** 返回所有阻止发布的待确认项, 每项一句人话; 空 = 可发布。 */
    public static List<String> blockers(ProductProcessWorkflowDTO definition) {
        List<String> result = new ArrayList<>();
        if (definition == null || definition.getNodes() == null) return result;
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || !"PROCESS".equals(node.getKind()) || node.getData() == null) continue;
            String process = label(node);
            if (node.getData().get(PENDING) instanceof List<?> pending) {
                for (Object item : pending) {
                    if (item instanceof Map<?, ?> row) {
                        Object text = row.get("label");
                        result.add("工序「" + process + "」" + (text == null ? "有待确认项" : text));
                    } else if (item != null) {
                        result.add("工序「" + process + "」" + item);
                    }
                }
            }
            if (node.getData().get(PROCESS_PARAMS) instanceof List<?> params) {
                result.addAll(processParamBlockers(process, params));
            }
            if (node.getData().get(CCP_POINTS) instanceof List<?> points) {
                int index = 0;
                for (Object raw : points) {
                    index++;
                    if (!(raw instanceof Map<?, ?> point)) {
                        result.add("工序「" + process + "」的控制点格式不对");
                        continue;
                    }
                    Object name = point.get("name");
                    String ccp = "控制点「" + (name instanceof String s && !s.isBlank() ? s : "#" + index) + "」";
                    BigDecimal min = number(point.get("criticalLimitMin"));
                    BigDecimal max = number(point.get("criticalLimitMax"));
                    if (min == null) result.add("工序「" + process + "」" + ccp + "的下限待确认");
                    if (max == null) result.add("工序「" + process + "」" + ccp + "的上限待确认");
                    if (min != null && max != null && min.compareTo(max) > 0) {
                        result.add("工序「" + process + "」" + ccp + "的下限大于上限");
                    }
                    Object unit = point.get("unit");
                    if (!(unit instanceof String u) || u.isBlank()) {
                        result.add("工序「" + process + "」" + ccp + "的单位待确认");
                    }
                    Object hazard = point.get("hazardType");
                    if (!(hazard instanceof String h) || !HAZARD_TYPES.contains(h)) {
                        result.add("工序「" + process + "」" + ccp + "的危害类型待确认");
                    }
                }
            }
        }
        return result;
    }

    /**
     * 工序标准参数的待确认项。判据是结构: 标准值/上下限一个都没有、单位空、或数值自相矛盾 = AI 没说清, 待人补。
     * 规则与 WorkProcessCustomFieldSchema 同向(下限≤上限、标准值在范围内), 这里负责拦发布。
     */
    static List<String> processParamBlockers(String process, List<?> params) {
        List<String> result = new ArrayList<>();
        int index = 0;
        for (Object raw : params) {
            index++;
            if (!(raw instanceof Map<?, ?> param)) {
                result.add("工序「" + process + "」的工艺参数格式不对");
                continue;
            }
            Object label = param.get("label");
            String name = label instanceof String s && !s.isBlank() ? s.trim() : null;
            String text = "工艺参数「" + (name != null ? name : "#" + index) + "」";
            if (name == null) result.add("工序「" + process + "」" + text + "缺少名称");
            BigDecimal standard = number(param.get("standardValue"));
            BigDecimal min = number(param.get("min"));
            BigDecimal max = number(param.get("max"));
            if (standard == null && min == null && max == null) {
                result.add("工序「" + process + "」" + text + "的标准值或上下限待确认");
            }
            if (min != null && max != null && min.compareTo(max) > 0) {
                result.add("工序「" + process + "」" + text + "的下限大于上限");
            }
            if (standard != null && ((min != null && standard.compareTo(min) < 0)
                    || (max != null && standard.compareTo(max) > 0))) {
                result.add("工序「" + process + "」" + text + "的标准值不在上下限范围内");
            }
            Object unit = param.get("unit");
            if (!(unit instanceof String u) || u.isBlank()) {
                result.add("工序「" + process + "」" + text + "的单位待确认");
            }
        }
        return result;
    }

    static BigDecimal number(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? new BigDecimal(String.valueOf(n)) : null;
        }
        return null;
    }

    private static String label(ProductProcessWorkflowDTO.Node node) {
        Object name = node.getData().get("processName");
        return name instanceof String s && !s.isBlank() ? s : node.getId();
    }
}
