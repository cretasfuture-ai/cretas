package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.FrontlineReadAudience;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds truthful, read-only next questions for the factory SSE result. */
@Service
public class FactoryFollowUpService {

    private static final Map<String, List<Candidate>> BY_COMPLETED_INTENT = Map.of(
            "WORK_PROCESS_TASK_START", List.of(
                    new Candidate("PRODUCTION_STATUS_QUERY", "查看生产进度", "要不要查看当前生产进度？")),
            "WORK_PROCESS_TASK_COMPLETE", List.of(
                    new Candidate("PRODUCTION_STATUS_QUERY", "查询生产进度", "查询当前生产进度")),
            "MATERIAL_BATCH_USE", List.of(
                    new Candidate("MATERIAL_BATCH_QUERY", "查询原料库存", "查询原材料库存"),
                    new Candidate("MATERIAL_TODAY_RECEIVING_QUERY", "查询今日待收货", "查询今日待收货清单")),
            "MATERIAL_BATCH_CONSUME", List.of(
                    new Candidate("MATERIAL_BATCH_QUERY", "查询原料库存", "查询原材料库存")),
            "QUALITY_CHECK_CREATE", List.of(
                    new Candidate("QUALITY_CHECK_QUERY", "查询质检记录", "查询我的质检记录")),
            "QUALITY_DEFECT_RECORD", List.of(
                    new Candidate("QUALITY_CHECK_QUERY", "查询质检记录", "查询我的质检记录")),
            "MATERIAL_BATCH_QUERY", List.of(
                    new Candidate("INVENTORY_QUERY", "查询库存不足", "查询库存不足的原料")),
            "QUALITY_CHECK_QUERY", List.of(
                    new Candidate("QUALITY_INSPECTION_RESULT_QUERY", "查询检验结果", "查询我的质检结果")));

    private final AIIntentService aiIntentService;
    private final FrontlineAiWriteGrantService frontlineWriteGrants;
    private final ToolRegistry toolRegistry;
    private final IntentPermissionGate intentPermissionGate;
    private final BusinessTypeGate businessTypeGate;

    public FactoryFollowUpService(AIIntentService aiIntentService,
                                  FrontlineAiWriteGrantService frontlineWriteGrants,
                                  ToolRegistry toolRegistry) {
        this(aiIntentService, frontlineWriteGrants, toolRegistry, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FactoryFollowUpService(AIIntentService aiIntentService,
                                  FrontlineAiWriteGrantService frontlineWriteGrants,
                                  ToolRegistry toolRegistry,
                                  IntentPermissionGate intentPermissionGate,
                                  BusinessTypeGate businessTypeGate) {
        this.aiIntentService = aiIntentService;
        this.frontlineWriteGrants = frontlineWriteGrants;
        this.toolRegistry = toolRegistry;
        this.intentPermissionGate = intentPermissionGate;
        this.businessTypeGate = businessTypeGate;
    }

    public List<IntentExecuteResponse.FollowUpSuggestion> suggest(
            String factoryId, String userRole, AIIntentConfig completedIntent, String status) {
        return suggest(factoryId, null, userRole, completedIntent, status);
    }

    public List<IntentExecuteResponse.FollowUpSuggestion> suggest(
            String factoryId, Long userId, String userRole, AIIntentConfig completedIntent, String status) {
        if (completedIntent == null || !("COMPLETED".equals(status) || "SUCCESS".equals(status))) {
            return List.of();
        }
        if (BY_COMPLETED_INTENT.get(completedIntent.getIntentCode()) == null) {
            return List.of();
        }

        Map<String, IntentExecuteResponse.FollowUpSuggestion> unique = new LinkedHashMap<>();
        for (Candidate candidate : BY_COMPLETED_INTENT.getOrDefault(completedIntent.getIntentCode(), List.of())) {
            if (!FrontlineReadAudience.offeredTo(candidate.intentCode(), userRole)) {
                continue;
            }
            Optional<AIIntentConfig> target = aiIntentService.getIntentByCode(factoryId, candidate.intentCode());
            if (target.isEmpty() || !Boolean.TRUE.equals(target.get().getIsActive())
                    || !toolRegistry.isToolEnabledForFactory(factoryId, target.get().getToolName())
                    || !permissionAllowed(factoryId, userId, target.get(), userRole)
                    || businessTypeGateBlocked(factoryId, target.get())) {
                continue;
            }
            AIIntentConfig targetIntent = target.get();
            if (frontlineWriteGrants.isWrite(executor(targetIntent.getToolName()).orElse(null), targetIntent)) {
                continue;
            }
            if (targetIntent.getToolName() == null || executor(targetIntent.getToolName()).isEmpty()) {
                continue;
            }
            unique.put(candidate.intentCode(), IntentExecuteResponse.FollowUpSuggestion.builder()
                    .label(candidate.label()).question(candidate.question()).intentCode(candidate.intentCode()).build());
            if (unique.size() == 3) break;
        }
        return List.copyOf(unique.values());
    }

    private boolean permissionAllowed(String factoryId, Long userId, AIIntentConfig intent, String userRole) {
        if (intentPermissionGate != null) {
            IntentPermissionGate.PermissionCheck check = intentPermissionGate.check(intent, userId, userRole);
            if (check.isDenied()) return false;
            if (check.isAllowed()) return true;
        }
        return aiIntentService.hasPermission(factoryId, intent.getIntentCode(), userRole);
    }

    private boolean businessTypeGateBlocked(String factoryId, AIIntentConfig intent) {
        return businessTypeGate != null && businessTypeGate.check(factoryId, intent).isPresent();
    }

    private Optional<ToolExecutor> executor(String toolName) {
        return toolName == null || toolName.isBlank() ? Optional.empty() : toolRegistry.getExecutor(toolName);
    }

    private record Candidate(String intentCode, String label, String question) {}
}
