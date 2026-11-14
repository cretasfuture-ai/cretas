package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessCustomFieldSchema;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.WorkProcessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发布工艺版本时, 给画布工序节点上的每个 {@code processParams}(例如「油温」) 在该工序的
 * {@code WorkProcess.customFieldSchema} 里补一个<b>只有名字和单位的可选数字字段</b>, 让报工页的录入框
 * 和录入 key 白名单认得它。
 *
 * <h2>标准值和上下限不进共享 schema</h2>
 *
 * 工序是工厂级、多个产品共用的; 「油温 150–160℃」是这个产品的工艺标准。标准值/上下限只存在各产品
 * 工艺画布节点的 {@code data.processParams} 里, 跟着产品工艺版本发布, 报工时按「本计划锁定的产品工艺版本」
 * 读取(见 {@link com.cretas.aims.entity.workprocess.ProcessParamStandards})。这里补出来的字段没有
 * 标准值和上下限, 所以对没配这个参数的其他产品: 可以不填, 也不会被判超标。
 *
 * <ul>
 *   <li>按 label 匹配已有字段。已有同名数字字段 → 不动(不改别人的字段, 也不覆盖它的单位/标准)。</li>
 *   <li>已有同名但已停用的数字字段 → 409(不静默放过, 也不替人重新启用)。</li>
 *   <li>已有同名但不是数字的字段 → 409, 不能把它改成数字(会让历史报工值失效)。</li>
 *   <li>幂等: 字段已存在就不再补; 重复发布不会重复。</li>
 *   <li>全部判定通过之后才写库, 任何一项 409 都不会留下半截 schema。</li>
 * </ul>
 *
 * <p>只在 {@link WorkflowPendingConfirmations#blockers} 为空之后调用(发布闸照旧)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowProcessParamMaterializer {

    public static final String TYPE_CONFLICT = "WORKFLOW_PROCESS_PARAM_TYPE_CONFLICT";
    public static final String FIELD_DISABLED = "WORKFLOW_PROCESS_PARAM_FIELD_DISABLED";

    private final WorkProcessRepository repository;

    /** @return 新补进工序 schema 的字段条数 */
    public int materialize(String factoryId, ProductProcessWorkflowDTO definition) {
        int added = 0;
        Map<WorkProcess, List<Map<String, Object>>> pendingSaves = new LinkedHashMap<>();
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (!"PROCESS".equals(node.getKind()) || node.getData() == null
                    || !(node.getData().get(WorkflowPendingConfirmations.PROCESS_PARAMS) instanceof List<?> params)
                    || params.isEmpty()) {
                continue;
            }
            Object workProcessId = node.getData().get("workProcessId");
            if (!(workProcessId instanceof String wp) || wp.isBlank()) continue;
            Optional<WorkProcess> found = repository.findByFactoryIdAndId(factoryId, wp);
            if (found.isEmpty()) continue;
            WorkProcess process = found.get();
            // 同一工序可能出现在多个节点: 复用已经在本次发布里攒好的 schema, 别各写各的
            List<Map<String, Object>> schema = pendingSaves.get(process);
            if (schema == null) {
                schema = new ArrayList<>();
                if (process.getCustomFieldSchema() != null) {
                    for (Map<String, Object> f : process.getCustomFieldSchema()) schema.add(new LinkedHashMap<>(f));
                }
            }
            boolean changed = false;
            for (Object raw : params) {
                if (!(raw instanceof Map<?, ?> param)) continue;
                String label = String.valueOf(param.get("label")).trim();
                Map<String, Object> field = schema.stream()
                        .filter(f -> label.equals(String.valueOf(f.get("label")).trim()))
                        .findFirst().orElse(null);
                if (field != null) {
                    if (Boolean.FALSE.equals(field.get("enabled"))) {
                        // 已停用的同名字段: 报工页不会给输入框, 画布标准也判不到; 不静默放过, 也不替人重新启用
                        throw new BusinessException(409, "工序「" + processName(node) + "」里同名字段「" + label
                                + "」已停用, 报工页没有这个参数的输入框")
                                .withCode(FIELD_DISABLED)
                                .withHint("到工序管理页重新启用该字段, 或给画布上的工艺参数改个名字后再发布");
                    }
                    if (!"number".equals(String.valueOf(field.get("type")).trim())) {
                        throw new BusinessException(409, "工序「" + processName(node) + "」已有同名字段「" + label
                                + "」且不是数字类型, 不能作为工艺参数")
                                .withCode(TYPE_CONFLICT)
                                .withHint("到工序管理页把该字段类型改成数字, 或给画布上的工艺参数改个名字后再发布");
                    }
                    continue; // 已有同名数字字段: 幂等, 不动
                }
                field = new LinkedHashMap<>();
                field.put("key", "param_" + digest(label));
                field.put("label", label);
                field.put("type", "number");
                field.put("enabled", true);
                Object unit = param.get("unit");
                if (unit instanceof String u && !u.isBlank()) field.put("unit", u.trim());
                schema.add(field);
                added++;
                changed = true;
            }
            if (changed) pendingSaves.put(process, schema);
        }
        for (Map.Entry<WorkProcess, List<Map<String, Object>>> e : pendingSaves.entrySet()) {
            e.getKey().setCustomFieldSchema(WorkProcessCustomFieldSchema.validated(e.getValue()));
            repository.save(e.getKey());
        }
        if (added > 0) {
            log.info("workflow publish added {} optional process param fields factory={}", added, factoryId);
        }
        return added;
    }

    private static String processName(ProductProcessWorkflowDTO.Node node) {
        Object name = node.getData().get("processName");
        return name instanceof String s && !s.isBlank() ? s : node.getId();
    }

    private static String digest(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 4; i++) out.append(String.format("%02x", hash[i]));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
