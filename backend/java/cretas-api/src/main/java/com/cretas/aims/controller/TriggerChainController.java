package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.config.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.*;
import com.cretas.aims.service.config.FactoryConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile/{factoryId}/config/v2")
@RequiredArgsConstructor
@Tag(name = "Canvas V2 Config", description = "Tool/Skill/TriggerChain/Template 工厂级配置")
public class TriggerChainController {

    private final FactoryToolConfigRepository toolConfigRepo;
    private final FactorySkillConfigRepository skillConfigRepo;
    private final FactoryTriggerChainRepository triggerChainRepo;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired(required = false)
    private FactoryTemplateRepository templateRepo;

    @Autowired(required = false)
    @Qualifier("canvasFactoryConfigService")
    private FactoryConfigService configService;

    @Autowired(required = false)
    private com.cretas.aims.service.MobileService mobileService;

    /**
     * AUD-5 B-A3 sister sweep batch 4a (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code factory_trigger_chains} table (see {@link FactoryTriggerChain}
     * {@code @Column(length=...)}). Without these, over-length input lets the request reach PG
     * and surfaces as {@code DataIntegrityViolationException} → generic 409 "数据处理异常".
     * Pre-check at controller boundary delivers a specific 400 with a hintTarget instead.
     *
     * <p>{@link FactoryTriggerChain} is accepted as raw {@code @RequestBody} (no DTO wrapper /
     * no {@code @Size}), so controller-side pre-check is the safety net for
     * {@code eventType} (100) and {@code errorStrategy} (20). The {@code description} column
     * is {@code TEXT} (unbounded), so no length check needed for it.
     *
     * <p>Mirrors PR #48 / PR #76 / PR #78 / PR #92 length-pre-check pattern.
     */
    private static final int EVENT_TYPE_MAX_LENGTH = 100;
    private static final int ERROR_STRATEGY_MAX_LENGTH = 20;

    private Long extractUserId(String authorization) {
        if (authorization == null || mobileService == null) return null;
        try {
            String token = com.cretas.aims.utils.TokenUtils.extractToken(authorization);
            return mobileService.getUserFromToken(token).getId();
        } catch (Exception e) { return null; }
    }

    // ========== Tool Config ==========

    @GetMapping("/tools")
    @Operation(summary = "获取工厂 Tool 配置列表")
    public ApiResponse<List<FactoryToolConfig>> getToolConfigs(@PathVariable String factoryId) {
        return ApiResponse.success(toolConfigRepo.findByFactoryId(factoryId));
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/tools/{toolName}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "设置 Tool 开关/参数覆盖")
    public ApiResponse<FactoryToolConfig> setToolConfig(
            @PathVariable String factoryId, @PathVariable String toolName,
            @RequestBody Map<String, Object> body) {
        FactoryToolConfig config = toolConfigRepo.findByFactoryIdAndToolName(factoryId, toolName)
                .orElseGet(() -> {
                    FactoryToolConfig c = new FactoryToolConfig();
                    c.setFactoryId(factoryId);
                    c.setToolName(toolName);
                    return c;
                });
        if (body.containsKey("enabled")) config.setEnabled((Boolean) body.get("enabled"));
        if (body.containsKey("paramOverrides")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> overrides = (Map<String, Object>) body.get("paramOverrides");
            config.setParamOverrides(overrides);
        }
        return ApiResponse.success(toolConfigRepo.save(config));
    }

    // ========== Skill Config ==========

    @GetMapping("/skills")
    @Operation(summary = "获取工厂 Skill 配置列表")
    public ApiResponse<List<FactorySkillConfig>> getSkillConfigs(@PathVariable String factoryId) {
        return ApiResponse.success(skillConfigRepo.findByFactoryId(factoryId));
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/skills/{skillName}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "设置 Skill 开关/自定义 DAG")
    public ApiResponse<FactorySkillConfig> setSkillConfig(
            @PathVariable String factoryId, @PathVariable String skillName,
            @RequestBody Map<String, Object> body) {
        FactorySkillConfig config = skillConfigRepo.findByFactoryIdAndSkillName(factoryId, skillName)
                .orElseGet(() -> {
                    FactorySkillConfig c = new FactorySkillConfig();
                    c.setFactoryId(factoryId);
                    c.setSkillName(skillName);
                    return c;
                });
        if (body.containsKey("enabled")) config.setEnabled((Boolean) body.get("enabled"));
        if (body.containsKey("customDag")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dag = (Map<String, Object>) body.get("customDag");
            config.setCustomDag(dag);
        }
        return ApiResponse.success(skillConfigRepo.save(config));
    }

    // ========== Trigger Chains ==========

    @GetMapping("/trigger-chains")
    @Operation(summary = "获取工厂触发链列表")
    public ApiResponse<List<FactoryTriggerChain>> getTriggerChains(@PathVariable String factoryId) {
        // Merge: factory-specific chains + global chains (where factory doesn't override)
        List<FactoryTriggerChain> factoryChains = triggerChainRepo.findByFactoryId(factoryId);
        List<FactoryTriggerChain> globalChains = triggerChainRepo.findByFactoryId(null);
        java.util.Set<String> factoryCodes = factoryChains.stream()
                .map(FactoryTriggerChain::getChainCode).collect(java.util.stream.Collectors.toSet());
        List<FactoryTriggerChain> merged = new java.util.ArrayList<>(factoryChains);
        for (FactoryTriggerChain g : globalChains) {
            if (!factoryCodes.contains(g.getChainCode())) merged.add(g);
        }
        return ApiResponse.success(merged);
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/trigger-chains/{chainCode}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "配置触发链步骤")
    public ApiResponse<FactoryTriggerChain> setTriggerChain(
            @PathVariable String factoryId, @PathVariable String chainCode,
            @RequestBody FactoryTriggerChain body) {
        // AUD-5 B-A3 sister sweep batch 4a: length pre-check for eventType (VARCHAR 100) +
        // errorStrategy (VARCHAR 20) BEFORE persisting to PG.
        validateTriggerChainLengths(body);
        FactoryTriggerChain chain = triggerChainRepo.findByFactoryIdAndChainCode(factoryId, chainCode)
                .orElseGet(() -> {
                    FactoryTriggerChain global = triggerChainRepo.findByFactoryIdAndChainCode(null, chainCode)
                            .orElse(null);
                    FactoryTriggerChain c = new FactoryTriggerChain();
                    c.setFactoryId(factoryId);
                    c.setChainCode(chainCode);
                    if (global != null) {
                        c.setEventType(global.getEventType());
                        c.setSteps(global.getSteps());
                        c.setErrorStrategy(global.getErrorStrategy());
                        c.setDescription(global.getDescription());
                    }
                    return c;
                });
        if (body.getEnabled() != null) chain.setEnabled(body.getEnabled());
        if (body.getSteps() != null) chain.setSteps(body.getSteps());
        if (body.getErrorStrategy() != null) chain.setErrorStrategy(body.getErrorStrategy());
        if (body.getEventType() != null) chain.setEventType(body.getEventType());
        if (body.getDescription() != null) chain.setDescription(body.getDescription());
        if (Boolean.TRUE.equals(chain.getEnabled())) {
            requireAutonomousSteps(chain.getSteps());
            chain.setRunAsUserId(AutonomousToolPolicy.requireRunAsUser(factoryId));
        }
        return ApiResponse.success(triggerChainRepo.save(chain));
    }

    /**
     * An enabled chain runs with no user behind it, so its steps must pass the same policy the
     * executor applies at run time (read-only tools, no identity parameters).
     */
    @SuppressWarnings("unchecked")
    private void requireAutonomousSteps(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) return;
        if (toolRegistry == null) {
            throw new BusinessException(500, "工具注册表不可用，不能校验事件链步骤");
        }
        for (Map<String, Object> step : steps) {
            if (step == null || Boolean.FALSE.equals(step.get("enabled"))) continue;
            if (!(step.get("tool") instanceof String toolName) || toolName.isBlank()) continue;
            var tool = toolRegistry.getExecutor(toolName);
            if (tool.isEmpty()) continue;
            Object rawParams = step.getOrDefault("params", Map.of());
            if (!(rawParams instanceof Map<?, ?> map)) {
                throw new BusinessException(400, "事件链步骤「" + toolName + "」的参数必须是对象")
                        .withHint("把 params 改成键值形式，例如 {\"limit\": 10}");
            }
            String refusal = AutonomousToolPolicy.rejection(toolName, tool.get(), (Map<String, Object>) map);
            if (refusal != null) throw AutonomousToolPolicy.refusal(refusal);
            AutonomousToolPolicy.requireSourceAllowed(toolName, ToolExecutionSource.TRIGGER);
        }
    }

    // ========== Templates ==========

    @GetMapping("/templates")
    @Operation(summary = "获取行业模板列表")
    public ApiResponse<List<FactoryTemplate>> getTemplates(@PathVariable String factoryId) {
        if (templateRepo == null) return ApiResponse.success(List.of());
        return ApiResponse.success(templateRepo.findByIsActiveTrue());
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/apply-template/{templateCode}")
    @RequireRole({"factory_super_admin"})
    @Operation(summary = "应用行业模板到工厂")
    public ApiResponse<String> applyTemplate(
            @PathVariable String factoryId, @PathVariable String templateCode,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (configService == null) throw new com.cretas.aims.exception.BusinessException("配置服务未就绪");
        Long operatorId = extractUserId(authorization);
        configService.applyTemplate(factoryId, templateCode, operatorId != null ? operatorId : 0L);
        return ApiResponse.success("模板 " + templateCode + " 已应用到工厂 " + factoryId);
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep batch 4a) ====================

    /**
     * AUD-5 B-A3 sister sweep batch 4a (edge audit 2026-05-20): length pre-check for
     * {@code eventType} (PG VARCHAR 100) and {@code errorStrategy} (PG VARCHAR 20) on
     * the setTriggerChain PUT path. Mirrors PR #48 / PR #78 / PR #92 pattern.
     *
     * <p>Without this, an over-length input lets the request reach PG and surfaces as
     * {@link org.springframework.dao.DataIntegrityViolationException} → generic 409
     * "数据处理异常". Pre-check delivers a specific 400 with the actual vs allowed length.
     *
     * <p>{@code description} column is {@code TEXT} (unbounded), so no pre-check needed.
     */
    private void validateTriggerChainLengths(FactoryTriggerChain body) {
        String eventType = body.getEventType();
        if (eventType != null && eventType.length() > EVENT_TYPE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "事件类型最长 " + EVENT_TYPE_MAX_LENGTH + " 字符 (当前 " + eventType.length() + ")")
                    .withHint("请使用更短的事件类型名称 (上限 100 字符)")
                    .withSeverity("warning")
                    .withHintTarget("eventType");
        }
        String errorStrategy = body.getErrorStrategy();
        if (errorStrategy != null && errorStrategy.length() > ERROR_STRATEGY_MAX_LENGTH) {
            throw new BusinessException(400,
                    "错误处理策略最长 " + ERROR_STRATEGY_MAX_LENGTH + " 字符 (当前 " + errorStrategy.length() + ")")
                    .withHint("请使用预定义策略 (CONTINUE / STOP / RETRY)")
                    .withSeverity("warning")
                    .withHintTarget("errorStrategy");
        }
    }
}
