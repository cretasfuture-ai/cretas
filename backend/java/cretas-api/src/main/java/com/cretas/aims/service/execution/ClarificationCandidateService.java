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
    /**
     * 执行期「这个人能不能用这个工具」的判定链 (一线门 → 旧角色串 → 中央 RBAC: 工具角色 / 权限码 /
     * 内置映射 / 「且」模块码)。候选按钮直接问它, ⛔ 不在本类再抄一份 (INTENT-DISTILL 红线:
     * 此前本类只查意图级权限 + 一线门, 漏了工具级 {@code getRequiredRoles()} 与 TOOL_PERMISSION_MAP,
     * viewer 的澄清里出现「商机超 SLA 警告」, 点了才 PERMISSION_DENIED)。
     * 为空只发生在裸构造的单测里 —— 那时绑定了已注册工具的意图一律不出按钮 (fail-closed)。
     */
    private final ToolPermissionChain toolPermissionChain;
    /**
     * 没绑工具的意图在执行期走显式 Skill 路由 (intent_code → skill_name, 见
     * {@link DynamicToolSelectionService#tryExplicitSkillRouteForIntent}), Skill 执行前对它能跑到的每个工具做
     * 权限预检 ({@link com.cretas.aims.service.skill.SkillExecutor#permissionRefusal})。候选按同一个预检过滤。
     * 生产上由 Spring 注入; 裸构造的单测里为空 = 不按 Skill 过滤 (那些测试的意图都不对应 Skill)。
     */
    @Autowired(required = false)
    @Lazy
    private com.cretas.aims.service.skill.SkillRegistry skillRegistry;
    @Autowired(required = false)
    @Lazy
    private com.cretas.aims.service.skill.SkillExecutor skillExecutor;

    public ClarificationCandidateService(
            IntentConfigManagementService configService,
            IntentPermissionGate permissionGate,
            IntentAccessModeFilter accessModeFilter,
            WriteGuardService writeGuardService) {
        this(configService, permissionGate, accessModeFilter, writeGuardService, null, null, null);
    }

    public ClarificationCandidateService(
            IntentConfigManagementService configService,
            IntentPermissionGate permissionGate,
            IntentAccessModeFilter accessModeFilter,
            WriteGuardService writeGuardService,
            FrontlineAiWriteGrantService frontlineWriteGrants,
            ToolRegistry toolRegistry) {
        this(configService, permissionGate, accessModeFilter, writeGuardService, frontlineWriteGrants, toolRegistry, null);
    }

    @Autowired
    public ClarificationCandidateService(
            IntentConfigManagementService configService,
            IntentPermissionGate permissionGate,
            IntentAccessModeFilter accessModeFilter,
            WriteGuardService writeGuardService,
            @Lazy FrontlineAiWriteGrantService frontlineWriteGrants,
            @Lazy ToolRegistry toolRegistry,
            @Lazy ToolPermissionChain toolPermissionChain) {
        this.toolPermissionChain = toolPermissionChain;
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
        return collectCandidateActions(matchResult, factoryId, userId, userRole, mode, new String[1]);
    }

    static final String STATUS_NEED_CLARIFICATION = "NEED_CLARIFICATION";
    static final String STATUS_PERMISSION_DENIED = "PERMISSION_DENIED";

    /** 一次澄清的结果: 选项 + 话术 + 状态。普通入口与 SSE 用同一份。 */
    public record Clarification(List<IntentExecuteResponse.SuggestedAction> actions, String message, String status) { }

    /**
     * 候选 + 话术 + 状态。业务候选一个都没剩、且至少有一个是<b>因为当前账号没有权限</b>被滤掉时, 不再说
     * 「没有可安全提供的业务选项」, 而是按执行期同一份拒答说明缺什么权限 (状态 PERMISSION_DENIED, 文案就是
     * 被滤掉的第一个候选在执行期会得到的那句: 工具 RBAC / 意图权限码 / 旧角色串 / 一线写门 / Skill 预检)。
     * 不是权限原因 (没有候选 / 本厂没配 / 业态不符 / 只读模式) ⇒ 照旧澄清, 话术给可问的示例。
     * 2026-11-16 复跑: sales_manager 问「哪个产品毛利率最高」候选全被财务权限滤掉, 只得到「重新描述」。
     */
    Clarification clarify(IntentMatchResult matchResult, String factoryId, Long userId, String userRole, String mode) {
        String[] firstPermissionDenial = new String[1];
        List<IntentExecuteResponse.SuggestedAction> actions =
                collectCandidateActions(matchResult, factoryId, userId, userRole, mode, firstPermissionDenial);
        boolean noBusinessChoice = actions.stream().noneMatch(a -> a != null && "SELECT_INTENT".equals(a.getActionCode()));
        if (noBusinessChoice && firstPermissionDenial[0] != null) {
            return new Clarification(actions, firstPermissionDenial[0], STATUS_PERMISSION_DENIED);
        }
        return new Clarification(actions, clarificationMessage(actions), STATUS_NEED_CLARIFICATION);
    }

    /** 服务缺失 (裸构造的单测) 时两个入口用的退路: 只给「重新描述」。 */
    static Clarification rephraseOnlyClarification() {
        List<IntentExecuteResponse.SuggestedAction> actions = rephraseOnly();
        return new Clarification(actions, clarificationMessage(actions), STATUS_NEED_CLARIFICATION);
    }

    private List<IntentExecuteResponse.SuggestedAction> collectCandidateActions(
            IntentMatchResult matchResult,
            String factoryId,
            Long userId,
            String userRole,
            String mode,
            String[] firstPermissionDenial) {
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
                            || !modeAllowed(config, factoryId, mode)) {
                        continue;
                    }
                    java.util.Optional<Denial> denial = callerDenial(config, factoryId, userId, userRole);
                    if (denial.isPresent()
                            // 读意图没配 required_roles 时上面全放行; 仓库工作台那几个对一线角色按受众名单再收一次
                            || !FrontlineReadAudience.offeredTo(code, userRole)) {
                        if (denial.isPresent() && denial.get().permission() && firstPermissionDenial[0] == null
                                && !NON_BUSINESS_OPTIONS.contains(code)) {
                            firstPermissionDenial[0] = denial.get().message();
                        }
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

    /**
     * 把这次澄清列出的选项落成一个会话, 并把会话 id 写进响应 (顶层 sessionId + metadata.sessionId)。
     * 没有它, 用户回「1」「完成工序」时服务端不知道「1」是哪一项 (prod 2026-11-13: 首轮澄清不建会话,
     * 回复「1」被当成新话走了闲聊)。普通入口与 SSE 共用这一个方法。
     *
     * <p>选项顺序 = {@link #choicesLine} 里用户看到的序号 (只有 SELECT_INTENT、最多 3 个)。
     * 响应里已经带了会话 id (旧的 LLM 多轮会话) / 没有可选项 / 缺身份 / 建不出来 ⇒ 原样返回、不带会话。
     */
    static IntentExecuteResponse attachChoiceSession(
            com.cretas.aims.service.ConversationService conversationService, IntentExecuteResponse response,
            String factoryId, Long userId, String userInput) {
        if (conversationService == null || response == null || userId == null
                || userInput == null || userInput.isBlank()
                || (response.getSessionId() != null && !response.getSessionId().isBlank())
                || (response.getMetadata() != null && response.getMetadata().get("sessionId") != null)
                || response.getSuggestedActions() == null) {
            return response;
        }
        List<com.cretas.aims.service.ConversationService.CandidateInfo> options = new ArrayList<>();
        for (IntentExecuteResponse.SuggestedAction action : response.getSuggestedActions()) {
            if (action == null || !"SELECT_INTENT".equals(action.getActionCode())
                    || action.getActionName() == null || action.getActionName().isBlank()
                    || action.getParameters() == null
                    || !(action.getParameters().get("intentCode") instanceof String code) || code.isBlank()) {
                continue;
            }
            options.add(com.cretas.aims.service.ConversationService.CandidateInfo.builder()
                    .intentCode(code).intentName(action.getActionName()).build());
            if (options.size() >= 3) {
                break;
            }
        }
        if (options.isEmpty()) {
            return response;
        }
        String sessionId = conversationService.startClarificationChoice(
                factoryId, userId, userInput, options, response.getMessage());
        if (sessionId == null || sessionId.isBlank()) {
            return response;
        }
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (response.getMetadata() != null) {
            metadata.putAll(response.getMetadata());
        }
        metadata.put("sessionId", sessionId);
        response.setMetadata(metadata);
        response.setSessionId(sessionId);
        return response;
    }

    /**
     * 把「用户回了选项序号/名字」改写成「点了那个按钮」: 原话 + 选中的意图码 + clarificationSelection 上下文
     * (RN 点按钮就是这样发的, 见 AIChatScreen)。之后两个入口走各自已有的显式意图路径 —— 权限 / 审批 /
     * 业态门 / W0 写确认全部照常。没选中返回 false, 请求原样不动。
     */
    static boolean applyChoicePick(com.cretas.aims.service.ConversationService conversationService,
                                   com.cretas.aims.dto.ai.IntentExecuteRequest request,
                                   String factoryId, Long userId) {
        if (conversationService == null || request == null || userId == null
                || (request.getIntentCode() != null && !request.getIntentCode().isBlank())
                || request.getSessionId() == null || request.getSessionId().isBlank()
                || request.getUserInput() == null || request.getUserInput().isBlank()
                // 长句不可能是在选序号/选项名: 不查会话表
                || request.getUserInput().strip().length() > com.cretas.aims.service.ClarificationChoiceMatcher.MAX_REPLY_CHARS) {
            return false;
        }
        java.util.Optional<com.cretas.aims.service.ConversationService.ClarificationPick> pick;
        try {
            pick = conversationService.resolveClarificationChoice(
                    factoryId, userId, request.getSessionId(), request.getUserInput());
        } catch (RuntimeException e) {
            return false;
        }
        if (pick == null || pick.isEmpty()) {
            return false;
        }
        java.util.Map<String, Object> context = new HashMap<>();
        if (request.getContext() != null) {
            context.putAll(request.getContext());
        }
        context.put("clarificationSelection", Boolean.TRUE);
        context.put("clarificationCandidates", pick.get().getCandidateCodes());
        request.setContext(context);
        request.setIntentCode(pick.get().getIntentCode());
        request.setUserInput(pick.get().getOriginalInput());
        return true;
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
            return NO_CANDIDATE_MESSAGE;
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

    /** 确实没有可选的业务候选 (不是权限原因): 说清楚, 并给几个可以直接问的说法。只是问法示例, 不含任何数据。 */
    static final String NO_CANDIDATE_MESSAGE = "没有找到与您的描述对得上的业务操作。请说清要查的对象和时间再问，例如："
            + "「本月生产了多少批次」「最近 30 天各批次的出成率」「现在库存里有哪些原料」。";

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
     * 当前调用者能不能用这个意图: 意图权限 (权限码 / 旧 requiredRoles) 且意图绑定的工具过得了
     * 执行期同一条工具判定链 ({@link ToolPermissionChain#denyReason})。
     *
     * <p>澄清按钮 (本类) 与 Skill 抢先路由的预检 ({@link DynamicToolSelectionService#trySkillRoute})
     * 共用这一份 —— 「按钮里出现的」与「Skill 能替你跑的」不许比执行期放得更宽。
     */
    boolean callerMayUse(AIIntentConfig config, String factoryId, Long userId, String userRole) {
        return callerDenial(config, factoryId, userId, userRole).isEmpty();
    }

    /** 不出这个选项的原因。{@code permission}=true: 当前账号没有权限 (消息即执行期那句拒答); false: 其它原因。 */
    record Denial(boolean permission, String message) {
        static java.util.Optional<Denial> permission(String message) {
            return java.util.Optional.of(new Denial(message != null && !message.isBlank(), message));
        }

        static java.util.Optional<Denial> other() {
            return java.util.Optional.of(new Denial(false, null));
        }
    }

    /** {@link #callerMayUse} 的判定本体: 意图权限在前, 工具 / Skill / 一线门在后 (与原短路顺序相同)。 */
    java.util.Optional<Denial> callerDenial(AIIntentConfig config, String factoryId, Long userId, String userRole) {
        java.util.Optional<Denial> permission = permissionDenial(config, factoryId, userId, userRole);
        return permission.isPresent() ? permission : toolGateDenial(config, factoryId, userId, userRole);
    }

    /**
     * 意图绑定了已注册工具 ⇒ 直接问执行期那条链 (一线门 + 旧角色串 + 中央 RBAC), 与
     * {@link ToolDispatchService#executeWithTool} 拒绝的是同一批人。身份按服务端主体绑定
     * ({@link TrustedExecutionContext#enforcePrincipal}), RBAC 按 userId 从库里取角色, 不信请求自带的角色。
     *
     * <p>没绑定工具时执行期走显式 Skill 路由, 按 Skill 的权限预检判 ({@link #skillGateAllowed});
     * 此外一线写门照旧: 一线角色 + 写意图 + 本厂没开放
     * ⇒ 不出这个选项 (2026-10-06: 此前按钮照出, 点了才被 #939 的门拒绝)。
     */
    private java.util.Optional<Denial> toolGateDenial(AIIntentConfig config, String factoryId, Long userId, String userRole) {
        try {
            ToolExecutor tool = null;
            String toolName = config.getToolName();
            if (toolRegistry != null && toolName != null && !toolName.isBlank()) {
                tool = toolRegistry.getExecutor(toolName).orElse(null);
                if (tool == null) {
                    // #1747 遗留 L2: 配了工具名但没注册 ⇒ 执行期只会回「Tool 未注册」(buildNoToolResponse), 不出按钮
                    return Denial.other();
                }
            }
            if (tool != null) {
                if (toolPermissionChain == null) {
                    return Denial.other();
                }
                // #1747 遗留 M1 / L1: 工厂关掉的工具、供应商比价的身份门 —— 与执行期同一个判定
                if (ToolExecutionAdmission.refusal(toolRegistry, tool, config, factoryId, userId).isPresent()) {
                    return Denial.other();
                }
                java.util.Map<String, Object> trusted = new HashMap<>();
                TrustedExecutionContext.enforcePrincipal(trusted, factoryId, userId, userRole);
                java.util.Optional<String> reason = toolPermissionChain.denyReason(tool, factoryId, userRole, config, trusted);
                return reason.isPresent() ? Denial.permission(reason.get()) : java.util.Optional.empty();
            }
            // 只有 tool_name 为空才走显式 Skill (IntentExecutionOrchestrator 3.5); 配了但没注册的工具上面已不出
            if (toolName == null || toolName.isBlank()) {
                java.util.Optional<String> skillRefusal = skillGateRefusal(config, factoryId, userId, userRole);
                if (skillRefusal.isPresent()) {
                    return Denial.permission(skillRefusal.get());
                }
            }
            if (!FrontlineAiWriteGrantService.isFrontline(userRole)) {
                return java.util.Optional.empty();
            }
            if (frontlineWriteGrants == null) {
                return Denial.other();
            }
            java.util.Optional<String> frontline = frontlineWriteGrants.denyReason(factoryId, userRole, null, config);
            return frontline.isPresent() ? Denial.permission(frontline.get()) : java.util.Optional.empty();
        } catch (Exception ignored) {
            return Denial.other();
        }
    }

    /**
     * 没绑工具的意图: 若本厂有它对应的 Skill (与执行期显式 Skill 路由同一个名字映射), 按 Skill 执行前的
     * 权限预检判 —— 预检会拒 ⇒ 不出按钮 (#1747 终审 M2: viewer 的候选里出现「今日客户跟进」, 点了 Skill 整个被拒)。
     * 没有对应 Skill ⇒ 执行期不会跑任何工具, 不按此过滤。Skill 存在但预检器缺失 ⇒ fail-closed。
     */
    private java.util.Optional<String> skillGateRefusal(AIIntentConfig config, String factoryId, Long userId, String userRole) {
        if (skillRegistry == null) {
            return java.util.Optional.empty();
        }
        String skillName = DynamicToolSelectionService.intentCodeToSkillName(config.getIntentCode());
        if (skillName == null || skillName.isBlank()) {
            return java.util.Optional.empty();
        }
        com.cretas.aims.dto.skill.SkillDefinition skill = skillRegistry.getSkillForFactory(factoryId, skillName);
        if (skill == null || !skill.isEnabled()) {
            return java.util.Optional.empty();
        }
        return skillPermissionRefusal(skill, factoryId, userId, userRole);
    }

    /**
     * Skill 的权限判定只有这一份: 执行期 {@link com.cretas.aims.service.skill.SkillExecutor} 第 2a 步的预检
     * (声明的工具 / DAG / 步骤; 受治理工具按网关准入; 库存分析按固定工作流真正会跑的工具)。
     * 澄清候选 (无工具意图 → 显式 Skill) 与关键词 Skill 抢先 ({@link DynamicToolSelectionService#trySkillRoute})
     * 都调这里 (#1747 遗留 L-b: 原来抢先自己按工具链判一遍, 与显式 Skill 的预检不是同一套)。
     * 预检器缺失 ⇒ fail-closed。
     */
    public java.util.Optional<String> skillPermissionRefusal(com.cretas.aims.dto.skill.SkillDefinition skill,
                                                             String factoryId, Long userId, String userRole) {
        if (skillExecutor == null) {
            return java.util.Optional.of("Skill 权限预检不可用");
        }
        com.cretas.aims.dto.skill.SkillContext context = com.cretas.aims.dto.skill.SkillContext.builder()
                .factoryId(factoryId)
                .userId(userId == null ? null : String.valueOf(userId))
                .userRole(userRole)
                .build();
        return skillExecutor.permissionRefusal(skill, context);
    }

    private java.util.Optional<Denial> permissionDenial(AIIntentConfig config, String factoryId,
                                                        Long userId, String userRole) {
        try {
            String requiredPermission = config.getRequiredPermission();
            if (requiredPermission != null && !requiredPermission.isBlank()) {
                if (permissionGate != null && permissionGate.check(config, userId, userRole).isAllowed()) {
                    return java.util.Optional.empty();
                }
                // 与执行期工具拒答同一格式 (ToolRbacGuard: 角色中文名 / 缺哪些权限的中文名 / 怎么办), 不露权限码
                return Denial.permission(com.cretas.aims.ai.tool.ToolRbacGuard.standardDenyMessage(
                        userRole, config.getIntentName(),
                        IntentPermissionGate.splitCodes(requiredPermission).toArray(String[]::new)));
            }
            // Empty required_permission retains the existing requiredRoles gate.
            return configService.hasPermission(factoryId, config.getIntentCode(), userRole)
                    ? java.util.Optional.empty()
                    : Denial.permission(ToolPermissionChain.legacyRoleDeniedMessage(userRole, config.getIntentName()));
        } catch (Exception ignored) {
            return Denial.other();
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
