package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessCustomFieldSchema;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 发布时把画布工序节点上的 {@code processParams}(AI 听来的「油温 150–160℃」) 落成该工序的标准参数,
 * 即 {@code WorkProcess.customFieldSchema} 里的一条数字字段(单位/标准值/下限/上限)。报工超标判定读的就是它。
 *
 * <p>只在 {@link WorkflowPendingConfirmations#blockers} 为空之后调用 = 用户已在画布上看过、补齐、点了发布,
 * 所以「不确认就不落库」由发布闸保证, 这里拿到的参数一定齐全。
 *
 * <h2>不静默改掉别人配好的值</h2>
 * <ul>
 *   <li>画布没给(null/缺省)的键<b>一律保留原值</b>, 从不删除。</li>
 *   <li>与上一次发布时画布上的值相比<b>没有变化</b>的参数不写 —— 否则画布里留着的旧值会在每次重发布时
 *       覆盖期间在工序管理页的人工修改。</li>
 *   <li>WorkProcess 是工厂级、多产品共用: 要把已有的非空值改成另一个值时, 必须该参数带
 *       {@code overwriteConfirmed=true}(人在画布上点了「确认覆盖」), 否则整次发布 409,
 *       消息里列出现有值→新值以及同样使用该工序的其他已发布产品。确认写入留 WARN 审计日志。</li>
 * </ul>
 *
 * <p>按 label 匹配已有字段; 已有同 label 但不是数字的字段不能被改成数字(会让历史报工值失效), 直接 409。
 * 落库前过 {@link WorkProcessCustomFieldSchema#validated}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowProcessParamMaterializer {

    public static final String OVERWRITE_UNCONFIRMED = "WORKFLOW_PROCESS_PARAM_OVERWRITE_UNCONFIRMED";
    public static final String OVERWRITE_CONFIRMED_KEY = "overwriteConfirmed";

    private final WorkProcessRepository repository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductTypeRepository productTypeRepository;

    /** @return 写入(新增或更新)的参数条数 */
    public int materialize(String factoryId, String productTypeId, ProductProcessWorkflowDTO definition,
                           ProductProcessWorkflowDTO previousPublished, Long operatorId) {
        int written = 0;
        List<String> unconfirmed = new ArrayList<>();
        Set<String> touchedProcessIds = new LinkedHashSet<>();
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
            List<Map<String, Object>> schema = new ArrayList<>();
            if (process.getCustomFieldSchema() != null) {
                for (Map<String, Object> f : process.getCustomFieldSchema()) schema.add(new LinkedHashMap<>(f));
            }
            boolean changed = false;
            for (Object raw : params) {
                if (!(raw instanceof Map<?, ?> param)) continue;
                String label = String.valueOf(param.get("label")).trim();
                if (unchangedSincePreviousPublish(previousPublished, node.getId(), label, param)) continue;
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
                } else if (!"number".equals(String.valueOf(field.get("type")).trim())) {
                    throw new BusinessException(409, "工序「" + processName(node) + "」已有同名字段「" + label
                            + "」且不是数字类型, 不能写入工艺标准")
                            .withCode("WORKFLOW_PROCESS_PARAM_TYPE_CONFLICT");
                }
                List<String> diffs = new ArrayList<>();
                for (String k : WorkProcessCustomFieldSchema.STANDARD_KEYS) {
                    Object v = normalized(param.get(k));
                    Object old = field.get(k);
                    if (v != null && old != null && !sameValue(old, v)) diffs.add(k + " " + old + "→" + v);
                }
                boolean confirmed = confirmationStillValid(param);
                if (!diffs.isEmpty() && !confirmed) {
                    unconfirmed.add("工序「" + processName(node) + "」参数「" + label + "」: " + String.join("，", diffs));
                    touchedProcessIds.add(wp);
                    continue;
                }
                if (!diffs.isEmpty()) {
                    log.warn("PROCESS_PARAM_OVERWRITE_CONFIRMED factory={} operator={} product={} workProcess={} label={} diffs={}",
                            factoryId, operatorId, productTypeId, wp, label, diffs);
                }
                for (String k : WorkProcessCustomFieldSchema.STANDARD_KEYS) {
                    Object v = normalized(param.get(k));
                    if (v != null) field.put(k, v); // 画布没给的键保留原值, 绝不删除
                }
                written++;
                changed = true;
            }
            if (changed) pendingSaves.put(process, schema);
        }
        if (!unconfirmed.isEmpty()) {
            throw new BusinessException(409, "这次发布会改掉工序上已有的工艺标准, 需要先在画布上逐项确认覆盖: "
                    + String.join("；", unconfirmed) + otherProductsNote(factoryId, productTypeId, touchedProcessIds))
                    .withCode(OVERWRITE_UNCONFIRMED)
                    .withHint("工序标准是工厂级的, 多个产品共用; 在对应工艺参数上勾选「确认覆盖」后再发布");
        }
        // 全部判定通过之后才写: 任何一项 409 都不会留下半截 schema
        for (Map.Entry<WorkProcess, List<Map<String, Object>>> e : pendingSaves.entrySet()) {
            e.getKey().setCustomFieldSchema(WorkProcessCustomFieldSchema.validated(e.getValue()));
            repository.save(e.getKey());
        }
        if (written > 0) {
            log.info("workflow publish materialized {} process params factory={}", written, factoryId);
        }
        return written;
    }

    /**
     * 确认只对「人勾选那一刻看到的数值」有效: 勾选时前端记下 confirmedFor(单位/标准值/上下限快照),
     * 之后数值/单位任何一处改动(含旧勾留存)快照就对不上, 确认作废, 必须重新勾。
     * AI 补丁路径会把这两个键丢弃, 所以它打不出一个有效确认。
     * 局限: 快照绑的是画布上的拟写入值, 不是工序在勾选时的现值; 勾选之后到发布之前有人在工序管理页改了现值, 不会重新要求确认。
     */
    private static boolean confirmationStillValid(Map<?, ?> param) {
        if (!Boolean.TRUE.equals(param.get(OVERWRITE_CONFIRMED_KEY))
                || !(param.get("confirmedFor") instanceof Map<?, ?> snapshot)) {
            return false;
        }
        for (String k : WorkProcessCustomFieldSchema.STANDARD_KEYS) {
            Object a = normalized(snapshot.get(k));
            Object b = normalized(param.get(k));
            if (a == null ? b != null : (b == null || !sameValue(a, b))) return false;
        }
        return true;
    }

    /** 同一工序节点上同名参数, 与上一次发布时画布上的值完全一样 = 本次没改, 不写。 */
    private static boolean unchangedSincePreviousPublish(ProductProcessWorkflowDTO previous, String nodeId,
                                                         String label, Map<?, ?> param) {
        if (previous == null || previous.getNodes() == null) return false;
        for (ProductProcessWorkflowDTO.Node n : previous.getNodes()) {
            if (!Objects.equals(n.getId(), nodeId) || n.getData() == null
                    || !(n.getData().get(WorkflowPendingConfirmations.PROCESS_PARAMS) instanceof List<?> old)) {
                continue;
            }
            for (Object o : old) {
                if (!(o instanceof Map<?, ?> prev) || !label.equals(String.valueOf(prev.get("label")).trim())) continue;
                for (String k : WorkProcessCustomFieldSchema.STANDARD_KEYS) {
                    Object a = normalized(prev.get(k));
                    Object b = normalized(param.get(k));
                    if (a == null ? b != null : (b == null || !sameValue(a, b))) return false;
                }
                return true;
            }
        }
        return false;
    }

    private String otherProductsNote(String factoryId, String productTypeId, Set<String> workProcessIds) {
        if (workProcessIds.isEmpty()) return "";
        Set<String> products = new LinkedHashSet<>();
        try {
            for (ProductProcessWorkflow w : workflowRepository
                    .findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(factoryId)) {
                if (w.getStatus() != ProductProcessWorkflow.Status.PUBLISHED
                        || Objects.equals(w.getProductTypeId(), productTypeId) || w.getNodesJson() == null) {
                    continue;
                }
                for (String id : workProcessIds) {
                    if (w.getNodesJson().contains("\"" + id + "\"")) products.add(w.getProductTypeId());
                }
            }
        } catch (RuntimeException e) {
            log.warn("process param other-product lookup failed factory={}: {}", factoryId, e.toString());
            return "（其他产品是否共用该工序未能查出，请人工核对）";
        }
        if (products.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        try {
            Map<String, String> byId = new LinkedHashMap<>();
            productTypeRepository.findByFactoryIdAndIdIn(factoryId, products)
                    .forEach(p -> byId.put(p.getId(), p.getName()));
            for (String id : products) names.add(byId.getOrDefault(id, id));
        } catch (RuntimeException e) {
            names.addAll(products);
        }
        return "。同样使用这些工序的其他已发布产品: " + String.join("、", names);
    }

    private static Object normalized(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s.trim();
        return v;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(String.valueOf(a).trim(), String.valueOf(b).trim());
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
