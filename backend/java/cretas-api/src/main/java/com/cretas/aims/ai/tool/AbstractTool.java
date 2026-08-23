package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool 执行器抽象基类
 *
 * 提供通用工具方法：参数解析、错误处理、日志记录、结果构建。
 * 所有具体的 ToolExecutor 实现应继承此类以获得标准化能力。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Slf4j
public abstract class AbstractTool implements ToolExecutor {

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * 解析工具调用参数为 Map
     *
     * @param toolCall LLM 返回的工具调用
     * @return 参数 Map
     * @throws IllegalArgumentException 参数解析失败
     */
    protected Map<String, Object> parseArguments(ToolCall toolCall) {
        try {
            String argumentsJson = toolCall.getFunction().getArguments();
            if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("❌ 参数解析失败: toolName={}, arguments={}",
                    toolCall.getFunction().getName(), toolCall.getFunction().getArguments(), e);
            // 脱敏处理：不暴露具体解析错误
            throw new IllegalArgumentException("参数格式不正确");
        }
    }

    /**
     * 获取必需参数（不存在则抛异常）
     *
     * @param arguments 参数 Map
     * @param key 参数名
     * @return 参数值
     * @throws IllegalArgumentException 参数不存在
     */
    protected String getRequiredParam(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return value.toString();
    }

    /**
     * 获取可选参数（不存在返回默认值）
     *
     * @param arguments 参数 Map
     * @param key 参数名
     * @param defaultValue 默认值
     * @return 参数值或默认值
     */
    protected String getOptionalParam(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 构建成功结果 JSON
     *
     * @param data 结果数据
     * @return JSON 字符串
     */
    protected String buildSuccessResult(Object data) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", data);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("❌ 构建结果失败", e);
            // 脱敏处理：不暴露具体序列化错误
            return buildErrorResult("结果处理失败");
        }
    }

    /**
     * 构建错误结果 JSON（已脱敏的消息）
     *
     * @param message 错误消息（应为用户友好消息，不含敏感信息）
     * @return JSON 字符串
     */
    protected String buildErrorResult(String message) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", sanitizeErrorMessage(message));
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("❌ 构建错误结果失败", e);
            return "{\"success\":false,\"error\":\"操作失败\"}";
        }
    }

    /**
     * 构建脱敏的错误结果（用于捕获未预期的异常）
     *
     * @param e 异常
     * @return JSON 字符串
     */
    protected String buildSanitizedErrorResult(Exception e) {
        log.error("❌ 工具执行异常", e);
        return buildErrorResult("操作执行失败，请稍后重试");
    }

    /**
     * 脱敏错误消息
     * 检测并移除可能包含敏感信息的内容
     *
     * @param message 原始消息
     * @return 脱敏后的消息
     */
    protected String sanitizeErrorMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "操作失败";
        }
        String lowerMsg = message.toLowerCase();
        // 检测敏感关键词
        if (lowerMsg.contains("exception") ||
            lowerMsg.contains("sql") ||
            lowerMsg.contains("jdbc") ||
            lowerMsg.contains("hibernate") ||
            lowerMsg.contains("connection") ||
            lowerMsg.contains("localhost") ||
            lowerMsg.contains("127.0.0.1") ||
            lowerMsg.contains("null pointer") ||
            lowerMsg.contains("stack trace") ||
            lowerMsg.contains(".java:") ||
            lowerMsg.contains("at com.") ||
            lowerMsg.contains("at org.")) {
            return "操作失败，请稍后重试";
        }
        return message;
    }

    /**
     * 从上下文获取工厂ID
     *
     * @param context 执行上下文
     * @return 工厂ID
     */
    protected String getFactoryId(Map<String, Object> context) {
        return (String) context.get("factoryId");
    }

    /**
     * 从上下文获取用户ID
     *
     * @param context 执行上下文
     * @return 用户ID
     */
    protected Long getUserId(Map<String, Object> context) {
        Object userId = context.get("userId");
        if (userId instanceof Long) {
            return (Long) userId;
        } else if (userId instanceof Integer) {
            return ((Integer) userId).longValue();
        } else if (userId instanceof String) {
            return Long.parseLong((String) userId);
        }
        return null;
    }

    /**
     * 取操作人；<b>取不到就显式失败</b>，⛔ 不兜底一个假 id。
     *
     * <p>写<b>NOT NULL</b> 的 actor 列时用这个，不要用 {@link #getUserId(Map)} ——
     * 那个取不到会返回 {@code null}，落到 NOT NULL 列上是一次数据库约束违例（500），
     * 报出来的是 {@code null value in column "created_by" violates not-null constraint}，
     * 与「这个操作没有操作人」这件事完全对不上。
     *
     * <p>⛔ 更不要退回写死的 {@code 0L}：prod 的 {@code users} 表里没有 id=0
     * （79 行，{@code min(id) = 1309}），那个 0 只是「没人填」伪装成一个用户 id，
     * 而且因为它满足 NOT NULL，**它不会报错** —— 错的数据就这么安静地留在库里。
     *
     * <p>⚠️ 这条路真的会走到：{@code CanvasAIController#buildToolContext} 解析
     * token 失败时只 {@code log.warn}，{@code userId} 根本不会进 context。
     *
     * <p>列<b>可空</b>时不要用本方法 —— 那种情况 {@code null} 才是诚实的答案
     * （如 {@code internal_transfers.requested_by} / {@code finished_goods_batches.created_by}）。
     *
     * @param context 执行上下文
     * @param action  给用户看的动作名，如「修改工厂配置」
     * @return 非 null 的用户 id
     * @throws BusinessException 取不到操作人时
     */
    protected Long requireUserId(Map<String, Object> context, String action) {
        Long userId = context == null ? null : getUserId(context);
        if (userId == null) {
            throw new BusinessException(401, "无法确定操作人，" + action + "已取消")
                    .withHint("请重新登录后再试；该操作会记入配置变更审计，必须有明确的操作人");
        }
        return userId;
    }

    /**
     * 从上下文获取用户角色
     *
     * @param context 执行上下文
     * @return 用户角色
     */
    protected String getUserRole(Map<String, Object> context) {
        return (String) context.get("userRole");
    }

    /**
     * 记录工具执行开始
     *
     * @param toolCall 工具调用
     * @param context 执行上下文
     */
    protected void logExecutionStart(ToolCall toolCall, Map<String, Object> context) {
        log.info("🔧 开始执行工具: toolName={}, toolCallId={}, factoryId={}, userId={}",
                toolCall.getFunction().getName(),
                toolCall.getId(),
                getFactoryId(context),
                getUserId(context));
    }

    /**
     * 记录工具执行成功
     *
     * @param toolCall 工具调用
     * @param result 执行结果
     */
    protected void logExecutionSuccess(ToolCall toolCall, String result) {
        log.info("✅ 工具执行成功: toolName={}, toolCallId={}, resultLength={}",
                toolCall.getFunction().getName(),
                toolCall.getId(),
                result != null ? result.length() : 0);
    }

    /**
     * 记录工具执行失败
     *
     * @param toolCall 工具调用
     * @param error 异常
     */
    protected void logExecutionFailure(ToolCall toolCall, Exception error) {
        log.error("❌ 工具执行失败: toolName={}, toolCallId={}, error={}",
                toolCall.getFunction().getName(),
                toolCall.getId(),
                error.getMessage(),
                error);
    }

    /**
     * 验证上下文必需字段
     *
     * @param context 执行上下文
     * @throws IllegalArgumentException 缺少必需字段
     */
    protected void validateContext(Map<String, Object> context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (!context.containsKey("factoryId")) {
            throw new IllegalArgumentException("Missing factoryId in context");
        }
        if (!context.containsKey("userId")) {
            throw new IllegalArgumentException("Missing userId in context");
        }
    }

    /**
     * 默认实现：启用所有工具
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * 默认实现：不需要特殊权限
     */
    @Override
    public boolean requiresPermission() {
        return false;
    }

    /**
     * 默认实现：所有角色都有权限
     */
    @Override
    public boolean hasPermission(String userRole) {
        return true;
    }
}
