package com.cretas.aims.service.config;

import com.cretas.aims.dto.config.FieldConfigDTO;
import com.cretas.aims.entity.config.FactorySkillConfig;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.service.config.FactoryConfigService;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Executes one bound Canvas mutation and proves it with a fresh post-commit read. */
@Service
public class CanvasConfigCommandService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private ToolRegistry toolRegistry;
    private final CanvasConfigPlanBindingService bindings;
    private final CanvasConfigStateReader states;
    private final FactoryConfigService configService;
    private final FactoryToolConfigRepository tools;
    private final FactorySkillConfigRepository skills;
    private final FactoryTriggerChainRepository triggers;
    private final com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock writeLock;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    public CanvasConfigCommandService(
            CanvasConfigPlanBindingService bindings,
            CanvasConfigStateReader states,
            FactoryConfigService configService,
            FactoryToolConfigRepository tools,
            FactorySkillConfigRepository skills,
            FactoryTriggerChainRepository triggers,
            com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock writeLock,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.bindings = bindings;
        this.states = states;
        this.configService = configService;
        this.tools = tools;
        this.skills = skills;
        this.triggers = triggers;
        this.writeLock = writeLock;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> execute(String factoryId, String toolName,
                                       Map<String, Object> internalParams,
                                       Map<String, Object> context) {
        Map<String, Object> publicParams = bindings.publicParameters(internalParams);
        Long operatorId = requireUserId(context);
        CanvasConfigStateReader.Snapshot[] before = new CanvasConfigStateReader.Snapshot[1];
        CanvasConfigStateReader.Snapshot[] after = new CanvasConfigStateReader.Snapshot[1];

        transaction.executeWithoutResult(status -> {
            // FactoryFeatureMenuWriteLock is the first lock in every Canvas write transaction.
            writeLock.lock(factoryId);
            bindings.verifyBeforeWrite(factoryId, toolName, internalParams);
            before[0] = states.read(factoryId, toolName, publicParams);
            apply(factoryId, toolName, publicParams, operatorId);
            entityManager.flush();
            entityManager.clear();
            after[0] = states.read(factoryId, toolName, publicParams);
            bindings.verifyReadback(factoryId, toolName, internalParams, before[0], after[0]);
        });

        // The transaction above has committed. This read is deliberately outside it, so a
        // successful Gateway result is never based on an uncommitted or first-level-cached row.
        CanvasConfigStateReader.Snapshot committed = states.read(factoryId, toolName, publicParams);
        bindings.verifyReadback(factoryId, toolName, internalParams, before[0], committed);
        return Map.of("factoryId", factoryId, "tool", toolName,
                "revision", committed.revision(), "stateDigest", committed.fingerprint());
    }

    private void apply(String factoryId, String toolName, Map<String, Object> params, Long operatorId) {
        switch (toolName) {
            case "canvas_toggle_module" -> configService.toggleModule(
                    factoryId, requiredText(params, "moduleCode"), requiredBoolean(params, "enabled"), operatorId);
            case "canvas_update_field" -> configService.updateFieldConfig(
                    factoryId, requiredText(params, "moduleCode"), requiredText(params, "fieldCode"),
                    fieldConfig(params), operatorId);
            case "canvas_apply_template" -> configService.applyTemplate(
                    factoryId, requiredText(params, "templateCode"), operatorId);
            case "canvas_toggle_tool" -> toggleTool(factoryId, params);
            case "canvas_toggle_skill" -> toggleSkill(factoryId, params);
            case "canvas_update_trigger_chain" -> updateTrigger(factoryId, params);
            default -> throw new IllegalArgumentException("unsupported Canvas configuration tool");
        }
    }

    private void toggleTool(String factoryId, Map<String, Object> params) {
        String name = requiredText(params, "toolName");
        Boolean enabled = requiredBoolean(params, "enabled");
        FactoryToolConfig config = tools.findByFactoryIdAndToolName(factoryId, name)
                .orElseGet(() -> FactoryToolConfig.builder().factoryId(factoryId).toolName(name).build());
        config.setEnabled(enabled);
        tools.save(config);
    }

    private void toggleSkill(String factoryId, Map<String, Object> params) {
        String name = requiredText(params, "skillName");
        Boolean enabled = requiredBoolean(params, "enabled");
        FactorySkillConfig config = skills.findByFactoryIdAndSkillName(factoryId, name)
                .orElseGet(() -> FactorySkillConfig.builder().factoryId(factoryId).skillName(name).build());
        config.setEnabled(enabled);
        skills.save(config);
    }

    private void updateTrigger(String factoryId, Map<String, Object> params) {
        String code = requiredText(params, "chainCode");
        FactoryTriggerChain chain = triggers.findByFactoryIdAndChainCode(factoryId, code)
                .orElseThrow(() -> new BusinessException(404, "未找到触发链: " + code));
        Integer order = integer(params.get("stepOrder"));
        if (order != null) {
            Boolean enabled = requiredBoolean(params, "stepEnabled");
            List<Map<String, Object>> steps = chain.getSteps() == null ? List.of() : chain.getSteps();
            List<Map<String, Object>> updated = new ArrayList<>();
            boolean found = false;
            for (Map<String, Object> raw : steps) {
                Map<String, Object> step = new HashMap<>(raw);
                if (integer(step.get("order")) != null && integer(step.get("order")).equals(order)) {
                    step.put("enabled", enabled);
                    found = true;
                }
                updated.add(step);
            }
            if (!found && order >= 1 && order <= updated.size()) {
                updated.get(order - 1).put("enabled", enabled);
                found = true;
            }
            if (!found) throw new BusinessException(400, "触发链中不存在步骤序号: " + order);
            chain.setSteps(updated);
        } else {
            chain.setEnabled(requiredBoolean(params, "enabled"));
        }
        if (Boolean.TRUE.equals(chain.getEnabled())) {
            CanvasTriggerChainWritePolicy.validateEnabledSteps(chain.getSteps(), toolRegistry);
            // A stored run-as identity is never inherited by the current editor.
            chain.setRunAsUserId(AutonomousToolPolicy.requireRunAsUser(factoryId));
        }
        triggers.save(chain);
    }

    private FieldConfigDTO fieldConfig(Map<String, Object> params) {
        String property = requiredText(params, "property");
        Object value = params.get("value");
        FieldConfigDTO.FieldConfigDTOBuilder builder = FieldConfigDTO.builder();
        switch (property) {
            case "visible" -> builder.visible(requiredBoolean(params, "value"));
            case "required" -> builder.required(requiredBoolean(params, "value"));
            case "label" -> builder.label(String.valueOf(value));
            case "defaultValue" -> builder.defaultValue(value);
            default -> throw new BusinessException(400, "无效的字段属性: " + property);
        }
        return builder.build();
    }

    private Long requireUserId(Map<String, Object> context) {
        Object raw = context == null ? null : context.get("userId");
        if (raw instanceof Number n && n.longValue() > 0) return n.longValue();
        if (raw instanceof String s && s.matches("[1-9][0-9]*")) return Long.valueOf(s);
        throw new BusinessException(401, "无法确定操作人，本次未执行 Canvas 配置变更");
    }

    private String requiredText(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new BusinessException(400, "缺少参数: " + key);
        return String.valueOf(value);
    }

    private Boolean requiredBoolean(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s && ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s))) return Boolean.valueOf(s);
        throw new BusinessException(400, "参数必须是布尔值: " + key);
    }

    private Integer integer(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && s.matches("-?[0-9]+")) return Integer.valueOf(s);
        return null;
    }
}
