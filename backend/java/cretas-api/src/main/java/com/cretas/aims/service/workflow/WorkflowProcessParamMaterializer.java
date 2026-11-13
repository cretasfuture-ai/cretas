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
 * 发布时把画布工序节点上的 {@code processParams}(AI 听来的「油温 150–160℃」) 落成该工序的标准参数,
 * 即 {@code WorkProcess.customFieldSchema} 里的一条数字字段(单位/标准值/下限/上限)。报工超标判定读的就是它。
 *
 * <p>只在 {@link WorkflowPendingConfirmations#blockers} 为空之后调用 = 用户已在画布上看过、补齐、点了发布,
 * 所以「不确认就不落库」由发布闸保证, 这里拿到的参数一定齐全。
 *
 * <p>按 label 匹配已有字段: 同名则更新其标准键(保留 key/enabled, 不动别的字段), 不同名则追加, 重复发布是更新不是重复。
 * 已有同 label 但不是数字的字段不能被改成数字(会让历史报工值失效), 直接报错, 不静默覆盖。
 * 落库前过 {@link WorkProcessCustomFieldSchema#validated}, 形状不合规整次发布失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowProcessParamMaterializer {

    private final WorkProcessRepository repository;

    /** @return 写入(新增或更新)的参数条数 */
    public int materialize(String factoryId, ProductProcessWorkflowDTO definition) {
        int written = 0;
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
            List<Map<String, Object>> schema = new ArrayList<>();
            if (process.getCustomFieldSchema() != null) {
                for (Map<String, Object> f : process.getCustomFieldSchema()) schema.add(new LinkedHashMap<>(f));
            }
            for (Object raw : params) {
                if (!(raw instanceof Map<?, ?> param)) continue;
                String label = String.valueOf(param.get("label")).trim();
                Map<String, Object> field = schema.stream()
                        .filter(f -> label.equals(String.valueOf(f.get("label")).trim()))
                        .findFirst().orElse(null);
                if (field == null) {
                    field = new LinkedHashMap<>();
                    field.put("key", "param_" + digest(label));
                    field.put("label", label);
                    field.put("type", "number");
                    field.put("enabled", true);
                    schema.add(field);
                } else if (!"number".equals(field.get("type"))) {
                    throw new BusinessException(409, "工序「" + processName(node) + "」已有同名字段「" + label
                            + "」且不是数字类型, 不能写入工艺标准")
                            .withCode("WORKFLOW_PROCESS_PARAM_TYPE_CONFLICT");
                }
                for (String k : WorkProcessCustomFieldSchema.STANDARD_KEYS) {
                    Object v = param.get(k);
                    if (v == null) {
                        field.remove(k);
                    } else {
                        field.put(k, v instanceof String s ? s.trim() : v);
                    }
                }
                written++;
            }
            process.setCustomFieldSchema(WorkProcessCustomFieldSchema.validated(schema));
            repository.save(process);
        }
        if (written > 0) {
            log.info("workflow publish materialized {} process params factory={}", written, factoryId);
        }
        return written;
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
