package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.FrontlineReadAudience;
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
    /**
     * 不是业务目标的选项: 「其他 / 域外」「能力不支持」「继续上次 / 翻页」。它们可以出现在澄清里,
     * 但不占业务候选的名额, 也不能单独构成一次澄清 (INTENT-DISTILL D2)。
     * ⛔ 不按 intent_category=SYSTEM 判: 那一类里还有排产模式、功能开关等真业务。
     */
    static final Set<String> NON_BUSINESS_OPTIONS = Set.of(
            "OUT_OF_DOMAIN", "UNSUPPORTED_CAPABILITY",
            "CONTEXT_CONTINUE", "CONTINUE_LAST_OPERATION", "SYSTEM_RESUME_LAST_ACTION",
            "PAGINATION_NEXT", "PAGINATION_PREVIOUS", "NAVIGATION_NEXT_PAGE", "NAVIGATION_PREVIOUS_PAGE");
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
        List<IntentExecuteResponse.SuggestedAction> conversationOptions = new ArrayList<>();
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
                    // 预算只按业务候选算: 「其他 / 不支持 / 继续 / 翻页」这类选项不占业务名额 (INTENT-DISTILL D2)
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
                            || !callerMayUse(config, factoryId, userId, userRole)
                            // 读意图没配 required_roles 时上面全放行; 仓库工作台那几个对一线角色按受众名单再收一次
                            || !FrontlineReadAudience.offeredTo(code, userRole)) {
                        continue;
                    }
                    if (NON_BUSINESS_OPTIONS.contains(code)) {
                        if (conversationOptions.size() < MAX_BUSINESS_CHOICES) {
                            conversationOptions.add(toSelectAction(config, factoryId, candidate));
                        }
                        continue;
                    }
                    actions.add(toSelectAction(config, factoryId, candidate));
                }
            }
        }

        // 业务候选为 0: 明确说没有可安全提供的业务选项 (只给「重新描述」), 不拿会话类选项或默认按钮顶替。
        if (actions.isEmpty()) {
            return rephraseOnly();
        }
        // 会话类选项只排在业务候选之后、补剩余名额
        for (IntentExecuteResponse.SuggestedAction option : conversationOptions) {
            if (actions.size() >= MAX_BUSINESS_CHOICES) break;
            actions.add(option);
        }

        // This is the only fallback that does not claim a business operation can run.
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE")
                .actionName("重新描述")
                .build());
        return actions;
    }

    static List<IntentExecuteResponse.SuggestedAction> rephraseOnly() {
        return List.of(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE").actionName("重新描述").build());
    }

    /**
     * 澄清话术: 普通入口与 SSE 同一份 (原在 SseStreamingService)。没有可选的业务候选时明确说没有,
     * 不拿默认业务按钮凑数。
     */
    static String clarificationMessage(List<IntentExecuteResponse.SuggestedAction> actions) {
        String choices = choicesLine(actions);
        if (choices.isBlank()) {
            return "当前没有可安全提供的业务选项，请重新描述您的需求。";
        }
        // 只剩一个选项时不说「可能匹配多个操作」—— 2026-10-06 真机: 一个按钮配「请选择」读起来像坏了。
        List<IntentExecuteResponse.SuggestedAction> intentChoices = actions.stream()
                .filter(x -> x != null && "SELECT_INTENT".equals(x.getActionCode())
                        && x.getActionName() != null && !x.getActionName().isBlank())
                .toList();
        if (intentChoices.size() == 1) {
            return "您是要「" + intentChoices.get(0).getActionName() + "」吗？点下面确认；不是的话请换个说法。";
        }
        return "您的请求可能匹配多个操作, 请选择您实际想做的:" + choices;
    }

    private static String choicesLine(List<IntentExecuteResponse.SuggestedAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return "";
        }
        StringBuilder line = new StringBuilder("\n");
        int index = 1;
        for (IntentExecuteResponse.SuggestedAction action : actions) {
            if (action == null || !"SELECT_INTENT".equals(action.getActionCode())
                    || action.getActionName() == null || action.getActionName().isBlank()) {
                continue;
            }
            line.append(index++).append(". ").append(action.getActionName()).append("\n");
            if (index > 3) {
                break;
            }
        }
        return index == 1 ? "" : line.toString();
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
