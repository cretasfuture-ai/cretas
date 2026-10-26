package com.cretas.aims.aspect;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber;
import com.cretas.aims.service.processsecret.ProcessSecretReadTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 工具的工艺保密出口。工具结果在离开工具时已经是 JSON 字符串, HTTP 响应裁剪看不到它;
 * 工具又有 6 条以上直接调用 {@code execute()} 的路径 (见 ToolRbacEnforcer), 没有单一调用点。
 * 所以挂在 {@link ToolExecutor#execute} / {@link ToolExecutor#preview} 本身: 每次调用打开一个
 * 读取作用域 ({@link ProcessSecretReadTracker}), 结果出来后交给同一个裁剪器。
 *
 * <p>调用者身份取当前请求 / 受信异步作用域; 取不到时受保护产品按无权处理 (fail-closed)。
 * 结果不是 JSON 或解析失败时原样返回 —— 工具自己的错误文本里没有配方数值。</p>
 */
@Slf4j
@Aspect
@Component
public class ProcessSecretToolOutputAspect {

    private final ProcessSecretPayloadScrubber scrubber;
    private final ObjectMapper objectMapper;

    public ProcessSecretToolOutputAspect(ProcessSecretPayloadScrubber scrubber, ObjectMapper objectMapper) {
        this.scrubber = scrubber;
        this.objectMapper = objectMapper;
    }

    @Around("execution(String com.cretas.aims.ai.tool.ToolExecutor+.execute(..)) "
            + "|| execution(String com.cretas.aims.ai.tool.ToolExecutor+.preview(..))")
    public Object guardToolOutput(ProceedingJoinPoint pjp) throws Throwable {
        try (ProcessSecretReadTracker.Scope ignored = ProcessSecretReadTracker.open()) {
            Object out = pjp.proceed();
            if (!(out instanceof String raw) || raw.isBlank()) return out;
            return scrub(raw, pjp);
        }
    }

    String scrub(String raw, ProceedingJoinPoint pjp) {
        JsonNode tree;
        try {
            tree = objectMapper.readTree(raw);
        } catch (Exception notJson) {
            return raw;
        }
        String factoryId = factoryOf(pjp.getArgs());
        String toolName = pjp.getTarget() instanceof ToolExecutor tool ? tool.getToolName() : "unknown";
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(null);
        boolean changed = scrubber.scrub(tree, actor, factoryId, ProcessSecretReadTracker.reads(),
                "AI tool " + toolName);
        if (!changed) return raw;
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (Exception e) {
            // 裁剪后的树写不出来: 不能退回原文 (原文含配比)
            log.error("[process-secret] scrubbed tool output could not be serialized: {}", toolName);
            return "{\"success\":false,\"error\":\"该产品工艺已设为保密，结果无法显示\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private static String factoryOf(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Map<?, ?> context && context.get("factoryId") instanceof String factoryId) {
                return factoryId;
            }
        }
        return AuthenticatedActor.fromCurrentExecution().map(AuthenticatedActor::factoryId).orElse(null);
    }
}
