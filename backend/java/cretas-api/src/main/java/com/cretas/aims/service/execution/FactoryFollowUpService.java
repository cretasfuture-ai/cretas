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

    private static final List<Candidate> INVENTORY_READS = List.of(
            new Candidate("MATERIAL_TODAY_RECEIVING_QUERY", "今日待收货", "查询今日待收货清单"),
            new Candidate("MATERIAL_LOW_STOCK_ALERT", "查看低库存", "查询低库存原材料预警"),
            new Candidate("MATERIAL_EXPIRING_ALERT", "查看临期原料", "查询未来7天即将过期的原材料"));
    private static final List<Candidate> QUALITY_READS = List.of(
            new Candidate("QUALITY_CHECK_QUERY", "查看质检记录", "查询质检记录"),
            new Candidate("RECEIVE_QUALITY_CHECK_TODAY", "今日待质检", "查询今天待质检的批次"),
            new Candidate("QUALITY_STATS", "本月质检统计", "查询本月质检统计"));

    private static final Map<String, List<Candidate>> BY_COMPLETED_INTENT = Map.ofEntries(
            Map.entry("WORK_PROCESS_TASK_START", List.of(
                    new Candidate("PROCESS_TASK_QUERY", "我的工序任务", "查询今天我的工序任务"))),
            Map.entry("WORK_PROCESS_TASK_COMPLETE", List.of(
                    new Candidate("PROCESS_TASK_QUERY", "我的工序任务", "查询今天我的工序任务"))),
            Map.entry("MATERIAL_BATCH_USE", List.of(
                    new Candidate("MATERIAL_BATCH_QUERY", "查询原料库存", "查询原材料库存"),
                    new Candidate("MATERIAL_TODAY_RECEIVING_QUERY", "查询今日待收货", "查询今日待收货清单"))),
            Map.entry("MATERIAL_BATCH_CONSUME", INVENTORY_READS),
            Map.entry("QUALITY_CHECK_CREATE", QUALITY_READS),
            Map.entry("QUALITY_DEFECT_RECORD", QUALITY_READS),
            Map.entry("MATERIAL_BATCH_QUERY", INVENTORY_READS),
            Map.entry("MATERIAL_TODAY_RECEIVING_QUERY", List.of(
                    new Candidate("MATERIAL_BATCH_QUERY", "查看原料库存", "查询原材料库存"),
                    new Candidate("MATERIAL_LOW_STOCK_ALERT", "查看低库存", "查询低库存原材料预警"))),
            Map.entry("QUALITY_CHECK_QUERY", QUALITY_READS),
            Map.entry("QUALITY_STATS", QUALITY_READS),
            Map.entry("RECEIVE_QUALITY_CHECK_TODAY", QUALITY_READS));

    private final AIIntentService aiIntentService;
    private final FrontlineAiWriteGrantService frontlineWriteGrants;
    private final ToolRegistry toolRegistry;
    private final IntentPermissionGate intentPermissionGate;
    private final BusinessTypeGate businessTypeGate;
    private final ToolPermissionChain toolPermissionChain;

    @org.springframework.beans.factory.annotation.Autowired
    public FactoryFollowUpService(AIIntentService aiIntentService,
                                  FrontlineAiWriteGrantService frontlineWriteGrants,
                                  ToolRegistry toolRegistry,
                                  IntentPermissionGate intentPermissionGate,
                                  BusinessTypeGate businessTypeGate,
                                  ToolPermissionChain toolPermissionChain) {
        this.aiIntentService = aiIntentService;
        this.frontlineWriteGrants = frontlineWriteGrants;
        this.toolRegistry = toolRegistry;
        this.intentPermissionGate = intentPermissionGate;
        this.businessTypeGate = businessTypeGate;
        this.toolPermissionChain = toolPermissionChain;
    }

    public List<IntentExecuteResponse.FollowUpSuggestion> suggest(
            String factoryId, Long userId, String userRole, AIIntentConfig completedIntent, String status) {
        if (completedIntent == null || userId == null || userRole == null || factoryId == null
                || !"FACTORY".equalsIgnoreCase(businessTypeGate.resolveDomain(factoryId))
                || !("COMPLETED".equals(status) || "SUCCESS".equals(status))) {
            return List.of();
        }
        if (BY_COMPLETED_INTENT.get(completedIntent.getIntentCode()) == null) {
            return List.of();
        }

        Map<String, IntentExecuteResponse.FollowUpSuggestion> unique = new LinkedHashMap<>();
        for (Candidate candidate : BY_COMPLETED_INTENT.getOrDefault(completedIntent.getIntentCode(), List.of())) {
            if (candidate.intentCode().equals(completedIntent.getIntentCode())) {
                continue;
            }
            if (!FrontlineReadAudience.offeredTo(candidate.intentCode(), userRole)) {
                continue;
            }
            Optional<AIIntentConfig> target = aiIntentService.getIntentByCode(factoryId, candidate.intentCode());
            if (target.isEmpty() || !Boolean.TRUE.equals(target.get().getIsActive())
                    || !toolRegistry.isToolEnabledForFactory(factoryId, target.get().getToolName())
                    || !permissionAllowed(factoryId, userId, target.get(), userRole)
                    || businessTypeGate.check(factoryId, target.get()).isPresent()) {
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
        IntentPermissionGate.PermissionCheck check = intentPermissionGate.check(intent, userId, userRole);
        if (check.isDenied() || (check.isLegacy()
                && !aiIntentService.hasPermission(factoryId, intent.getIntentCode(), userRole))) return false;
        ToolExecutor targetTool = executor(intent.getToolName()).orElse(null);
        if (toolPermissionChain.denyReason(targetTool, factoryId, userRole, intent,
                Map.of("factoryId", factoryId, "userId", userId, "userRole", userRole)).isPresent()) {
            return false;
        }
        return true;
    }

    private Optional<ToolExecutor> executor(String toolName) {
        return toolName == null || toolName.isBlank() ? Optional.empty() : toolRegistry.getExecutor(toolName);
    }

    private record Candidate(String intentCode, String label, String question) {}
}
