package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务 Tool 抽象基类
 *
 * 为业务操作类 Tool 提供通用功能：
 * 1. 自动参数校验
 * 2. 缺失参数提示
 * 3. 标准化执行流程
 * 4. 类型转换工具方法
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
public abstract class AbstractBusinessTool extends AbstractTool {

    /**
     * 获取必需参数列表
     *
     * @return 必需参数名称列表
     */
    protected abstract List<String> getRequiredParameters();

    /**
     * 执行业务逻辑（子类实现）
     *
     * @param factoryId 工厂ID
     * @param params 已校验的参数
     * @param context 执行上下文
     * @return 执行结果（Map格式）
     * @throws Exception 执行异常
     */
    protected abstract Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception;

    /**
     * 预览业务操作（支持预览的子类必须覆盖）
     *
     * 默认实现必须 fail closed，绝不能委托到 doExecute()。子类只有在实现了真正的
     * 只读预览后，才能同时覆盖 {@link #supportsPreview()} 返回 true。
     *
     * @param factoryId 工厂ID
     * @param params 已校验的参数
     * @param context 执行上下文
     * @return 预览结果（Map格式），应包含 status=PREVIEW
     * @throws Exception 预览异常
     */
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "Tool '" + getToolName() + "' does not implement a safe business preview");
    }

    /**
     * 统一执行入口
     */
    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        logExecutionStart(toolCall, context);
        validateContext(context);

        // 写入确认三洞 #2: 出错时要分清「确定没写」和「可能已写」。doExecute 前后各数一次本线程成功提交的事务;
        // 业务逻辑已经开始后的意外异常 (超时、空指针、连接断开) 也按可能已写处理 —— 外部副作用和不经事务管理器的
        // 写入数不到, 宁可让用户先核对, 也不能报「失败」诱导重做。
        long commitsBefore = ToolWriteCommitObserver.commitsOnThisThread();
        boolean businessStarted = false;
        try {
            // 1. 解析参数
            Map<String, Object> params = parseArguments(toolCall);
            String factoryId = getFactoryId(context);
            // 1.5 用户在对话里说的状态 → status (工具在 schema 里声明了状态词表时; 见 StatusFilter)
            StatusFilter.apply(getParametersSchema(), params);
            TimeRangeFilter.prepare(getParametersSchema(), params);

            // 2. 校验必需参数
            List<String> missingParams = validateRequiredParams(params);
            if (!missingParams.isEmpty()) {
                // 这条早退以前什么都不记 —— 日志里既没有 ✅ 也没有 ❌, 一次失败的调用
                // 长得和"从来没被调用过"一模一样。实测代价: canvas_work_process_catalog
                // 在 prod 上每次都走到这里, 而 MDC 追踪只看得到"开始执行工具"孤零零一行。
                log.warn("⚠️  工具提前返回 NEED_MORE_INFO: toolName={}, missingParams={}",
                        getToolName(), missingParams);
                return buildNeedMoreInfoResult(missingParams);
            }

            // 3. 执行业务逻辑
            businessStarted = true;
            Map<String, Object> result = doExecute(factoryId, params, context);
            // 记下这次按哪个状态筛的, 0 条时回复要点名它, 不能说成「暂无记录」
            result = StatusFilter.annotate(getParametersSchema(), params, result);
            // 用户说的时间用没用上 (工具没声明 startDate/endDate 就是没用上, 回复要说出来)
            result = TimeRangeFilter.annotate(getParametersSchema(), params, result);

            // 4. 构建成功响应
            String response = buildSuccessResult(result);
            logExecutionSuccess(toolCall, response);
            return response;

        } catch (IllegalArgumentException e) {
            log.warn("⚠️  参数验证失败: {}", e.getMessage());
            // 脱敏处理：只返回经过安全检查的消息
            String safeMessage = sanitizeErrorMessage(e.getMessage());
            return markOutcome(buildErrorResult("参数验证失败: " + safeMessage),
                    committedSince(commitsBefore));
        } catch (com.cretas.aims.exception.BusinessException e) {
            // BusinessException 消息本就面向用户(GlobalExceptionHandler 也直接展示), 不脱敏,
            // 让工具的友好提示(如"找不到原料 X"/"匹配到多条")透传给用户, 而非笼统"操作执行失败"。
            log.warn("业务规则: {}", e.getMessage());
            // 带上业务码: 403 (权限) 要在对话里报成 PERMISSION_DENIED, 不是笼统的失败
            return markOutcome(buildErrorResult(e.getMessage(), e.getCode()), committedSince(commitsBefore));
        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            // 脱敏处理：不暴露具体异常信息
            return markOutcome(buildSanitizedErrorResult(e), businessStarted || committedSince(commitsBefore));
        }
    }

    /** 结果里的标记: 这次失败发生时写入可能已经落下 (由确认流程记「结果不明」, 不许按失败重做)。 */
    public static final String OUTCOME_UNKNOWN_FLAG = "outcomeUnknown";

    static final String OUTCOME_UNKNOWN_ERROR =
            "操作结果暂时无法确认（可能已经写入），请先在业务页面核对，勿重复提交";

    private static boolean committedSince(long commitsBefore) {
        return ToolWriteCommitObserver.commitsOnThisThread() != commitsBefore;
    }

    private String markOutcome(String errorJson, boolean possiblyWritten) {
        if (!possiblyWritten) {
            return errorJson;
        }
        log.error("⚠️ 工具失败时写入可能已经落下, 标记结果不明: toolName={}", getToolName());
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(errorJson);
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.put(OUTCOME_UNKNOWN_FLAG, true);
                object.put("error", OUTCOME_UNKNOWN_ERROR);
                return objectMapper.writeValueAsString(object);
            }
        } catch (Exception markFailure) {
            log.warn("结果不明标记写入失败, 退回固定结构: {}", markFailure.getMessage());
        }
        return "{\"success\":false,\"" + OUTCOME_UNKNOWN_FLAG + "\":true,\"error\":\"" + OUTCOME_UNKNOWN_ERROR + "\"}";
    }

    /**
     * 校验必需参数
     *
     * @param params 输入参数
     * @return 缺失的参数列表
     */
    protected List<String> validateRequiredParams(Map<String, Object> params) {
        List<String> required = getRequiredParameters();
        if (required == null || required.isEmpty()) {
            return new ArrayList<>();
        }

        return required.stream()
                .filter(p -> !params.containsKey(p) || params.get(p) == null ||
                             (params.get(p) instanceof String && ((String) params.get(p)).trim().isEmpty()))
                .collect(Collectors.toList());
    }

    /**
     * 构建需要更多信息的响应
     *
     * @param missingParams 缺失的参数列表
     * @return JSON响应
     */
    protected String buildNeedMoreInfoResult(List<String> missingParams) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", "NEED_MORE_INFO");
            result.put("needMoreInfo", true);
            result.put("missingParameters", missingParams);

            // 生成友好的提示问题
            List<String> questions = generateClarificationQuestions(missingParams);
            result.put("clarificationQuestions", questions);

            // 构建友好消息（替代 "缺少必需参数: x, y"）
            StringBuilder friendlyMsg = new StringBuilder();
            friendlyMsg.append("为了完成操作，还需要以下信息：\n");
            for (int i = 0; i < questions.size(); i++) {
                friendlyMsg.append(i + 1).append(". ").append(questions.get(i)).append("\n");
            }
            friendlyMsg.append("\n请直接告诉我，我来帮您处理。");
            result.put("message", friendlyMsg.toString());
            // 失败契约必须只有一种形状: success=false ⇒ error 一定在。
            // 以前这里只写 message, 而 buildErrorResult 写的是 error —— 同一个"工具失败了"
            // 有两种长相, 于是每个读 error 的消费方都把澄清问题替换成了一句通用文案
            // (CanvasAIController 的两处 narrow route 就是这么丢掉真实原因的)。
            result.put("error", friendlyMsg.toString());

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("❌ 构建NEED_MORE_INFO结果失败", e);
            // 脱敏处理：不暴露序列化错误
            return buildErrorResult("响应处理失败");
        }
    }

    /**
     * 根据缺失参数生成澄清问题
     *
     * @param missingParams 缺失参数列表
     * @return 问题列表
     */
    protected List<String> generateClarificationQuestions(List<String> missingParams) {
        List<String> questions = new ArrayList<>();

        for (String param : missingParams) {
            String question = getParameterQuestion(param);
            if (question != null) {
                questions.add(question);
            } else {
                // 默认问题格式
                questions.add("请提供" + getParameterDisplayName(param));
            }
        }

        return questions;
    }

    /**
     * 获取参数的提示问题（子类可覆盖）
     *
     * @param paramName 参数名
     * @return 提示问题
     */
    protected String getParameterQuestion(String paramName) {
        // 通用参数问题映射
        Map<String, String> commonQuestions = Map.of(
            "batchId", "请问您要操作哪个批次？说批次号就行",
            "batchNumber", "请问您要操作哪个批次？请提供批次号。",
            "quantity", "请问数量是多少？",
            "materialTypeId", "请问是哪种原材料？说名称就行",
            "supplierId", "请问是哪个供应商？说名称就行",
            "productionPlanId", "请问是哪个生产计划？",
            "reason", "请说明原因。",
            "status", "请问要设置为什么状态？"
        );

        return commonQuestions.get(paramName);
    }

    /**
     * 获取参数显示名称（子类可覆盖）
     *
     * @param paramName 参数名
     * @return 显示名称
     */
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.ofEntries(
            Map.entry("batchId", "批次号"),
            Map.entry("batchNumber", "批次号"),
            Map.entry("quantity", "数量"),
            // ⚠️ 实体引用型参数一律用**人话**, 不带「ID」: 这些文案会原样发给手机端操作员,
            // 而后端本来就接受名称(填槽续轮会解析成 ID)。管操作员要 UUID 是纯粹的自伤。
            Map.entry("materialTypeId", "原材料"),
            Map.entry("productionPlanId", "生产计划"),
            Map.entry("reason", "原因"),
            Map.entry("status", "状态"),
            Map.entry("supplierId", "供应商"),
            Map.entry("warningDays", "预警天数"),
            Map.entry("page", "页码"),
            Map.entry("size", "每页数量")
        );

        return displayNames.getOrDefault(paramName, paramName);
    }

    /**
     * 预览阶段缺哪些必填信息 (默认 = {@link #validateRequiredParams}, 与 execute 同一判据)。
     *
     * <p>只有 execute 自己<b>不靠 {@link #getRequiredParameters()} 拦缺参</b>的工具才需要覆盖:
     * 例如 {@code MaterialBatchCreateTool} 的必填表是空的 (缺参时 execute 返回「请选供应商」的选择卡,
     * 不写任何东西), 预览却必须把「缺原料 / 缺供应商」问出来, 否则确认卡就是一张空卡。
     * 覆盖时仍只许<b>读</b> params, 返回的名字必须有 {@link #getParameterQuestion} 能问的人话。
     */
    protected List<String> previewMissingParameters(Map<String, Object> params) {
        return validateRequiredParams(params);
    }

    /**
     * 预览入口 — 走 doPreview() 钩子
     */
    @Override
    public String preview(ToolCall toolCall, Map<String, Object> context) throws Exception {
        logExecutionStart(toolCall, context);
        validateContext(context);

        try {
            Map<String, Object> params = parseArguments(toolCall);
            String factoryId = getFactoryId(context);
            StatusFilter.apply(getParametersSchema(), params);
            TimeRangeFilter.prepare(getParametersSchema(), params);

            List<String> missingParams = previewMissingParameters(params);
            if (!missingParams.isEmpty()) {
                // 同 execute(): 这条早退以前是完全静默的。preview 是 Canvas narrow route
                // 走的那条路, 静默早退让"工具拒绝了"和"工具没跑"无法区分。
                log.warn("⚠️  工具预览提前返回 NEED_MORE_INFO: toolName={}, missingParams={}",
                        getToolName(), missingParams);
                return buildNeedMoreInfoResult(missingParams);
            }

            Map<String, Object> result = doPreview(factoryId, params, context);
            return buildSuccessResult(result);
        } catch (IllegalArgumentException e) {
            log.warn("⚠️  预览参数验证失败: {}", e.getMessage());
            String safeMessage = sanitizeErrorMessage(e.getMessage());
            return buildErrorResult("参数验证失败: " + safeMessage);
        } catch (com.cretas.aims.exception.BusinessException e) {
            // 同 execute(): BusinessException 友好提示透传, 不脱敏
            log.warn("业务规则(预览): {}", e.getMessage());
            return buildErrorResult(e.getMessage(), e.getCode());
        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            return buildSanitizedErrorResult(e);
        }
    }

    // ==================== Governance: Convention-based Defaults ====================

    /**
     * Auto-derive ActionType from tool name convention.
     * Tools named *_create → WRITE, *_delete → DELETE, *_update → UPDATE,
     * *_analyze / *_analysis → ANALYZE, *_notify → NOTIFY, *_generate → GENERATE.
     * Everything else defaults to READ.
     */
    @Override
    public ActionType getActionType() {
        String name = getToolName();
        if (name == null) return ActionType.READ;
        if (name.endsWith("_create") || name.contains("_create_")) return ActionType.WRITE;
        if (name.endsWith("_delete") || name.contains("_delete_")) return ActionType.DELETE;
        if (name.endsWith("_update") || name.contains("_update_")) return ActionType.UPDATE;
        if (name.contains("_analyze") || name.contains("_analysis")) return ActionType.ANALYZE;
        if (name.contains("_notify") || name.contains("_alert")) return ActionType.NOTIFY;
        if (name.contains("_generate")) return ActionType.GENERATE;
        return ActionType.READ;
    }

    /**
     * Auto-derive RiskLevel from ActionType convention.
     * DELETE → MEDIUM, WRITE → MEDIUM (data creation), UPDATE → LOW,
     * everything else → LOW.
     */
    @Override
    public RiskLevel getRiskLevel() {
        ActionType action = getActionType();
        switch (action) {
            case DELETE: return RiskLevel.MEDIUM;
            case WRITE: return RiskLevel.MEDIUM;
            default: return RiskLevel.LOW;
        }
    }

    /**
     * Auto-derive domain tag from tool name prefix (first segment before '_').
     */
    @Override
    public Set<String> getDomainTags() {
        String name = getToolName();
        if (name == null || !name.contains("_")) return Set.of();
        return Set.of(name.substring(0, name.indexOf('_')));
    }

    // ==================== 类型转换工具方法 ====================

    /**
     * 获取String参数
     */
    protected String getString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 获取String参数（带默认值）
     */
    protected String getString(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 获取Integer参数
     */
    protected Integer getInteger(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取Integer参数（带默认值）
     */
    protected Integer getInteger(Map<String, Object> params, String key, Integer defaultValue) {
        Integer value = getInteger(params, key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取Long参数
     */
    protected Long getLong(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取BigDecimal参数
     */
    protected BigDecimal getBigDecimal(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取Boolean参数
     */
    protected Boolean getBoolean(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * 获取Boolean参数（带默认值）
     */
    protected Boolean getBoolean(Map<String, Object> params, String key, Boolean defaultValue) {
        Boolean value = getBoolean(params, key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取List参数
     */
    @SuppressWarnings("unchecked")
    protected <T> List<T> getList(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof List) return (List<T>) value;
        return null;
    }

    // ==================== 结果构建工具方法 ====================

    /**
     * 构建分页结果
     */
    protected Map<String, Object> buildPageResult(List<?> content, long totalElements, int totalPages, int currentPage) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", content);
        result.put("totalElements", totalElements);
        result.put("totalPages", totalPages);
        result.put("currentPage", currentPage);
        result.put("hasMore", currentPage < totalPages);
        return result;
    }

    /**
     * 构建简单成功结果
     */
    protected Map<String, Object> buildSimpleResult(String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", message);
        result.put("data", data);
        return result;
    }
}
