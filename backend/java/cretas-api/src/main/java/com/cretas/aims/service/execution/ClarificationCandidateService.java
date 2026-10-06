package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds safe choices for an intent clarification response.
 *
 * <p>The recognizer supplies candidate codes, but it is not the authority for the
 * display name, active state, tenant scope, business domain, or permissions. This
 * service resolves every candidate against the current factory configuration and
 * returns only actions that the current caller may select.</p>
 */
@Service
public class ClarificationCandidateService {

    private static final int MAX_BUSINESS_CHOICES = 3;
    private static final String MODE_READ = "READ";

    private final IntentConfigManagementService configService;
    private final IntentPermissionGate permissionGate;
    private final IntentAccessModeFilter accessModeFilter;
    private final WriteGuardService writeGuardService;
    /** 一线角色写操作开放名单 (#939)。为空只发生在裸构造的单测里 —— 那时一线角色的写意图一律不出按钮。 */
    private final FrontlineAiWriteGrantService frontlineWriteGrants;
    /** 判「写」要看意图绑定的工具 (与执行期 W0 同一判据)。 */
    private final ToolRegistry toolRegistry;

    public ClarificationCandidateService(
            IntentConfigManagementService configService,
            IntentPermissionGate permissionGate,
            IntentAccessModeFilter accessModeFilter,
            WriteGuardService writeGuardService) {
        this(configService, permissionGate, accessModeFilter, writeGuardService, null, null);
    }

    @Autowired
    public ClarificationCandidateService(
            IntentConfigManagementService configService,
            IntentPermissionGate permissionGate,
            IntentAccessModeFilter accessModeFilter,
            WriteGuardService writeGuardService,
            @Lazy FrontlineAiWriteGrantService frontlineWriteGrants,
            @Lazy ToolRegistry toolRegistry) {
        this.configService = configService;
        this.permissionGate = permissionGate;
        this.accessModeFilter = accessModeFilter;
        this.writeGuardService = writeGuardService;
        this.frontlineWriteGrants = frontlineWriteGrants;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Convert recognizer candidates to canonical, permission-filtered actions.
     * No default or synthetic business choice is added when the candidate cannot
     * be proven selectable. A rephrase affordance remains truthful in that case.
     */
    List<IntentExecuteResponse.SuggestedAction> buildCandidateActions(
            IntentMatchResult matchResult,
            String factoryId,
            Long userId,
            String userRole,
            String mode) {
        List<IntentExecuteResponse.SuggestedAction> actions = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        if (matchResult != null && hasIdentity(factoryId, userId, userRole)) {
            DomainResolution domainResolution = resolveFactoryDomain(factoryId);
            if (domainResolution.failed()) {
                return rephraseOnly();
            }
            String factoryDomain = domainResolution.domain();
            List<IntentMatchResult.CandidateIntent> candidates = matchResult.getTopCandidates();
            if (candidates != null) {
                for (IntentMatchResult.CandidateIntent candidate : candidates) {
                    if (actions.size() >= MAX_BUSINESS_CHOICES) {
                        break;
                    }
                    if (candidate == null) {
                        continue;
                    }
                    String code = candidate.getIntentCode();
                    if (code == null || code.isBlank() || !seenCodes.add(code)) {
                        continue;
                    }
                    AIIntentConfig config = currentActiveConfig(factoryId, code);
                    if (config == null
                            || !domainAllowed(config, factoryDomain)
                            || !modeAllowed(config, factoryId, mode)
                            || !callerMayUse(config, factoryId, userId, userRole)) {
                        continue;
                    }
                    actions.add(toSelectAction(config, factoryId, candidate));
                }
            }
        }

        // This is the only fallback that does not claim a business operation can run.
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE")
                .actionName("重新描述")
                .build());
        return actions;
    }

    private List<IntentExecuteResponse.SuggestedAction> rephraseOnly() {
        return List.of(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE").actionName("重新描述").build());
    }

    private boolean hasIdentity(String factoryId, Long userId, String userRole) {
        return factoryId != null && !factoryId.isBlank()
                && userId != null && userId > 0
                && userRole != null && !userRole.isBlank();
    }

    private AIIntentConfig currentActiveConfig(String factoryId, String intentCode) {
        try {
            return configService.getIntentByCode(factoryId, intentCode)
                    .filter(config -> Boolean.TRUE.equals(config.getIsActive()))
                    .filter(config -> intentCode.equals(config.getIntentCode()))
                    .filter(config -> config.getFactoryId() == null || factoryId.equals(config.getFactoryId()))
                    .filter(config -> config.getIntentName() != null && !config.getIntentName().isBlank())
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DomainResolution resolveFactoryDomain(String factoryId) {
        try {
            String domain = configService.resolveBusinessDomain(factoryId);
            if (domain == null || domain.isBlank()) {
                return new DomainResolution(null, false);
            }
            String normalized = domain.trim().toUpperCase(Locale.ROOT);
            return new DomainResolution(
                    "FACTORY".equals(normalized) || "RESTAURANT".equals(normalized)
                            ? normalized : null,
                    false);
        } catch (Exception ignored) {
            return new DomainResolution(null, true);
        }
    }

    private record DomainResolution(String domain, boolean failed) { }

    private boolean domainAllowed(AIIntentConfig config, String factoryDomain) {
        String businessType = config.getBusinessType();
        if (businessType == null || businessType.isBlank()
                || "COMMON".equalsIgnoreCase(businessType)) {
            return true;
        }
        // Domain-exclusive choices must never be shown while the factory domain is unknown.
        return factoryDomain != null && businessType.equalsIgnoreCase(factoryDomain);
    }

    private boolean modeAllowed(AIIntentConfig config, String factoryId, String mode) {
        if (!MODE_READ.equalsIgnoreCase(mode)) {
            return true;
        }
        try {
            return accessModeFilter != null
                    ? !accessModeFilter.isWriteIntent(config)
                    : !writeGuardService.isWriteIntent(config);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 当前调用者能不能用这个意图: 意图权限 (权限码 / 旧 requiredRoles) 且没被一线写操作门挡住。
     *
     * <p>澄清按钮 (本类) 与 Skill 抢先路由的预检 ({@link DynamicToolSelectionService#trySkillRoute})
     * 共用这一份 —— 「按钮里出现的」与「Skill 能替你跑的」不许比执行期放得更宽。
     */
    boolean callerMayUse(AIIntentConfig config, String factoryId, Long userId, String userRole) {
        return permissionAllowed(config, factoryId, userId, userRole)
                && frontlineAllowed(config, factoryId, userRole);
    }

    /**
     * 一线角色 + 写意图 + 本厂没开放 ⇒ 不出这个选项 (2026-10-06: 此前按钮照出, 点了才被 #939 的门拒绝)。
     * 与执行期同一个判定 ({@link FrontlineAiWriteGrantService#denyReason}: 默认清单 + 本厂开放记录)。
     */
    private boolean frontlineAllowed(AIIntentConfig config, String factoryId, String userRole) {
        if (!FrontlineAiWriteGrantService.isFrontline(userRole)) {
            return true;
        }
        if (frontlineWriteGrants == null) {
            return false;
        }
        try {
            ToolExecutor tool = null;
            String toolName = config.getToolName();
            if (toolRegistry != null && toolName != null && !toolName.isBlank()) {
                tool = toolRegistry.getExecutor(toolName).orElse(null);
            }
            return frontlineWriteGrants.denyReason(factoryId, userRole, tool, config).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean permissionAllowed(AIIntentConfig config, String factoryId,
                                      Long userId, String userRole) {
        try {
            String requiredPermission = config.getRequiredPermission();
            if (requiredPermission != null && !requiredPermission.isBlank()) {
                return permissionGate != null
                        && permissionGate.check(config, userId, userRole).isAllowed();
            }
            // Empty required_permission retains the existing requiredRoles gate.
            return configService.hasPermission(factoryId, config.getIntentCode(), userRole);
        } catch (Exception ignored) {
            return false;
        }
    }

    private IntentExecuteResponse.SuggestedAction toSelectAction(
            AIIntentConfig config, String factoryId, IntentMatchResult.CandidateIntent candidate) {
        return toSelectAction(factoryId, config.getIntentCode(), config.getIntentName(),
                config.getDescription(), candidate.getConfidence());
    }

    static IntentExecuteResponse.SuggestedAction toSelectAction(
            String factoryId, String intentCode, String intentName, String description, Double confidence) {
        // 副标题只给人话: 没有描述 → 不显示 (⛔ 不再用「置信度: 39%」兜底, 识别分数不是给一线看的);
        // 描述里是开发者备注 (Sprint / Loop / 驼峰字段名 …) 也不显示。规则见 ClarificationButtonText。
        // confidence 形参保留是为了不动调用方签名, 不再进入任何用户可见文本。
        String canonicalDescription = ClarificationButtonText.subtitle(description);
        HashMap<String, Object> parameters = new HashMap<>();
        parameters.put("intentCode", intentCode);
        return IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("SELECT_INTENT")
                .actionName(intentName)
                .description(canonicalDescription)
                .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute")
                .parameters(parameters)
                .build();
    }
}
