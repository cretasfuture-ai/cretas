package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 普通入口 ({@link IntentExecutionOrchestrator}) 与流式入口 ({@link SseStreamingService}) 共用的每轮步骤
 * (意图蒸馏第③步 PR-2a)。
 *
 * <p>此前这些步骤只在普通入口有, 流式入口缺或写了另一份口径 —— 同一句话两条入口判定不同。这里每个方法是
 * 唯一实现, 两个入口都调它; 判定口径改这里, 两边一起变。
 *
 * <ul>
 *   <li>{@link #answersGeneralQuestionDirectly}: 识别不出 + 通用咨询/闲聊 + 非业务问法 → 直接回答, 不追问澄清</li>
 *   <li>{@link #readModeWriteBlocked}: 咨询 tab (mode=READ) 命中写意图 → 跳转提示卡, 不进审批/追问/执行</li>
 *   <li>{@link #stopsForApproval} / {@link #approvalResponse}: 审批谓词与回复 (一份)</li>
 *   <li>{@link #applyCallerPriceView}: 格式化之前把结果换成这个人看得到的那一份</li>
 *   <li>{@link #sanitizeInternalReasoningLeak}: 模型内部推理 (意图列表 + 意图码) 不当回答下发</li>
 *   <li>{@link #applyAiModeStamp}: 响应补 aiMode (READ|WRITE)</li>
 *   <li>{@link #recognitionPermissionFilter}: 识别时按权限收窄候选 (仅操作 tab)</li>
 * </ul>
 */
@Slf4j
public final class IntentTurnSharedSteps {

    private IntentTurnSharedSteps() {
    }

    // ==================== 通用问法直答 ====================

    /**
     * 识别之后是否直接回答 (通用咨询 / 闲聊), 而不是走澄清候选。
     *
     * <p>口径 (两入口一致): 只在<b>识别不出意图</b>时直答 —— 识别到了就照常执行 (Steve 偏执行少追问,
     * 执行期还有权限 / 写确认 / 审批闸); 业务问法 (「这个月卖得怎么样」) 不直答, 由澄清候选接住 (不编数);
     * 否定否决 (REJECTED, 「别给我看订单」) 不直答, 那是用户在取消, 澄清优先。
     */
    public static boolean answersGeneralQuestionDirectly(IntentMatchResult matchResult, String userInput) {
        if (matchResult == null || matchResult.hasMatch()) {
            return false;
        }
        if (matchResult.getMatchMethod() == IntentMatchResult.MatchMethod.REJECTED) {
            return false;
        }
        QuestionType type = matchResult.getQuestionType();
        if (type != QuestionType.GENERAL_QUESTION && type != QuestionType.CONVERSATIONAL) {
            return false;
        }
        return userInput != null && !userInput.isBlank()
                && !com.cretas.aims.ai.grounding.ChatBusinessFactGuard.isBusinessQuestion(userInput);
    }

    // ==================== 咨询 tab 拦写 ====================

    /**
     * P1 读写分块 §4.4: 咨询 tab (mode=READ) 命中写意图 → 返回跳转提示卡; 否则 null。
     * 写判定 = 声明优先 + 启发式兜底 ({@link IntentExecutionOrchestrator#isWriteIntentDeclarationAware})。
     */
    public static IntentExecuteResponse readModeWriteBlocked(IntentExecuteRequest request, AIIntentConfig intent,
                                                             IntentAccessModeFilter accessModeFilter,
                                                             WriteGuardService writeGuard) {
        if (request == null || intent == null || !"READ".equalsIgnoreCase(request.getMode())) {
            return null;
        }
        if (accessModeFilter == null && writeGuard == null) {
            // 判定器都没装配: 判不了写不写, 按写拦 (fail closed)
            return readModeWriteBlockedResponse(intent);
        }
        boolean write = accessModeFilter != null
                ? IntentExecutionOrchestrator.isWriteIntentDeclarationAware(intent, accessModeFilter, writeGuard)
                : writeGuard.isWriteIntent(intent);
        return write ? readModeWriteBlockedResponse(intent) : null;
    }

    static IntentExecuteResponse readModeWriteBlockedResponse(AIIntentConfig intent) {
        String msg = "这是操作类请求，请切换到【操作】页处理。";
        return IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("READ_MODE_WRITE_BLOCKED").message(msg).formattedText(msg)
                .aiMode("WRITE")
                .executedAt(LocalDateTime.now()).build();
    }

    // ==================== 审批 ====================

    /**
     * 审批谓词 (两入口一份): 意图声明了需要审批, 且这次不是只读预览。
     * 不看敏感级别 (声明了就要审批); 不看 forceExecute (那是客户端带来的字段, 不能替审批放行)。
     */
    public static boolean stopsForApproval(AIIntentConfig intent, IntentExecuteRequest request) {
        return intent != null && Boolean.TRUE.equals(intent.getRequiresApproval())
                && !(request != null && Boolean.TRUE.equals(request.getPreviewOnly()));
    }

    /** 审批回复: AI 入口不提交、不核验审批, 如实说没执行。 */
    public static IntentExecuteResponse approvalResponse(AIIntentConfig intent, WriteGuardService writeGuard,
                                                         ToolRegistry toolRegistry) {
        String msg = "此操作需要审批，当前 AI 入口尚不支持提交和核验审批。未提交审批、未执行操作，请通过业务页面完成审批。";
        IntentExecuteResponse response = IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("PENDING_APPROVAL").message(msg).formattedText(msg)
                .requiresApproval(true).approvalChainId(intent.getApprovalChainId())
                .executedAt(LocalDateTime.now()).build();
        applyAiModeStamp(response, intent, intent.getToolName(), writeGuard, toolRegistry);
        return response;
    }

    // ==================== 遮价 ====================

    /**
     * 把 resultData 换成这个人看得到的那一份 (ERP-S6-LLM-PROMPT-PRICE-LEAK)。必须在格式化 / 摘要之前调:
     * 格式化按 resultData 写 formattedText, 没遮过的数会写进文字, 下发时再遮 resultData 也遮不掉文字。
     *
     * @return true = 已按这个人遮过 (或本来就没有数据); false = 判定组件没装配, 调用方不许把数据交给模型
     */
    public static boolean applyCallerPriceView(PriceFieldResponseAdvice priceView, IntentExecuteResponse response,
                                               Long userId) {
        if (response == null || response.getResultData() == null) {
            return true;
        }
        if (priceView == null) {
            log.warn("价格可见性组件未装配: 结果数据不交给摘要模型 (fail closed)");
            return false;
        }
        response.setResultData(priceView.maskedViewFor(response.getResultData(), userId));
        return true;
    }

    // ==================== 内部推理泄漏消毒 ====================

    private static final Pattern INTERNAL_CODE_TOKEN = Pattern.compile("[A-Z]{2,}_[A-Z0-9_]{2,}");

    static final String INTERNAL_REASONING_SAFE_REPLY = "这个问题我还没有把握直接回答。请换一种问法，"
            + "或说明想看的指标（如营收/销量/毛利）和时间范围，我再帮您查。";

    /**
     * 客户可见文本内部泄漏消毒 (Sheet 7/22 V7): LLM 意图分类/rerank 的推理文本
     * (「在提供的可用意图列表中, 只有 PRODUCT_XXX…」) 偶发被当回答返回。
     * 同时含意图元讨论词与 CODE 形状 token 的消息不可能是合法业务答案。
     *
     * @return true = 替换了
     */
    public static boolean sanitizeInternalReasoningLeak(IntentExecuteResponse response) {
        if (response == null) {
            return false;
        }
        boolean replaced = false;
        for (String text : new String[]{response.getMessage(), response.getFormattedText()}) {
            if (text != null && isInternalReasoning(text)) {
                replaced = true;
                log.warn("[sanitize] internal intent reasoning leaked to customer text, replaced. head={}",
                        text.substring(0, Math.min(80, text.length())));
                break;
            }
        }
        if (replaced) {
            response.setMessage(INTERNAL_REASONING_SAFE_REPLY);
            response.setFormattedText(INTERNAL_REASONING_SAFE_REPLY);
        }
        return replaced;
    }

    private static boolean isInternalReasoning(String text) {
        boolean metaTalk = text.contains("意图列表") || text.contains("可用意图")
                || text.contains("候选意图") || text.contains("intent_code")
                || text.contains("该意图覆盖");
        return metaTalk && INTERNAL_CODE_TOKEN.matcher(text).find();
    }

    // ==================== aiMode ====================

    /**
     * P1 读写分块: 补 aiMode (READ|WRITE), 前端据此选卡片形态。已显式设置的保持不变;
     * 其余按意图判写, 意图未判写时以已匹配工具兜底。展示元数据, 判定失败不阻断响应。
     */
    public static void applyAiModeStamp(IntentExecuteResponse response, AIIntentConfig intent, String matchedToolName,
                                        WriteGuardService writeGuard, ToolRegistry toolRegistry) {
        if (response == null || response.getAiMode() != null) {
            return;
        }
        boolean write = intent != null && writeGuard != null && writeGuard.isWriteIntent(intent);
        if (!write && writeGuard != null && toolRegistry != null
                && matchedToolName != null && !matchedToolName.isEmpty()) {
            try {
                write = toolRegistry.getExecutor(matchedToolName)
                        .map(writeGuard::isWriteTool)
                        .orElse(false);
            } catch (Exception e) {
                // aiMode 是展示元数据, 判定失败不阻断响应
            }
        }
        response.setAiMode(write ? "WRITE" : "READ");
    }

    // ==================== 识别候选按权限收窄 ====================

    /**
     * spec §8.2: 只在操作 tab (mode=OPERATE) 解析权限全集给识别层收窄候选 —— 咨询 tab 的规则是「写意图一律剔除」,
     * 与权限无关; mode 为空同样不查。
     *
     * @return 权限码集合; null = 不按权限过滤 (真正的鉴权仍在执行期的意图权限门 / ToolRbacEnforcer)
     */
    public static Set<String> recognitionPermissionFilter(String mode, Long userId, String factoryId,
                                                          IntentPermissionGate permissionGate) {
        if (userId == null || permissionGate == null
                || !IntentAccessModeFilter.MODE_OPERATE.equalsIgnoreCase(mode)) {
            return null;
        }
        try {
            return permissionGate.resolveUserPermissions(userId, factoryId);
        } catch (Exception e) {
            log.warn("候选过滤权限集解析失败, 跳过权限过滤: userId={}, err={}", userId, e.getMessage());
            return null;
        }
    }
}
