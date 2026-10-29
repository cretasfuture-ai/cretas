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

    /**
     * 单据类 AI 工具 (Steve 10-29: 按工具分): 只看一张单据 (一个批次 / 一个工序任务), 与对应页面同档 ——
     * 同厂无授权账号看本单总量和单批实测, 配比 / 成本仍按裁剪器隐藏; 平台 / 他厂照旧连数量都不给。
     * 显式、经审核的名单; 名单外 (含跨批统计、列表、新加的工具) 一律按汇总视图 (fail-closed)。
     * <ul>
     *   <li>{@code processing_batch_detail}: 单个生产批次详情 (含本批生产数据与本单成本), 必填 batchId</li>
     *   <li>{@code processing_batch_timeline}: 单个生产批次的操作时间线, 必填 batchId</li>
     *   <li>{@code process_task_summary}: 单个工序任务的摘要、参与人员与报工记录, 必填 taskId</li>
     * </ul>
     */
    public static final java.util.Set<String> DOCUMENT_VIEW_TOOLS = java.util.Set.of(
            "processing_batch_detail", "processing_batch_timeline", "process_task_summary");

    private final ProcessSecretPayloadScrubber scrubber;
    private final ObjectMapper objectMapper;
    /** AI 数据访问审计: 工具调用并进本请求那一条 (一个请求一条, 不是每次工具调用一条)。单测直接 new 时不记录。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit aiAccessAudit =
            com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.disabled();

    public ProcessSecretToolOutputAspect(ProcessSecretPayloadScrubber scrubber, ObjectMapper objectMapper) {
        this.scrubber = scrubber;
        this.objectMapper = objectMapper;
    }

    @Around("execution(String com.cretas.aims.ai.tool.ToolExecutor+.execute(..)) "
            + "|| execution(String com.cretas.aims.ai.tool.ToolExecutor+.preview(..))")
    public Object guardToolOutput(ProceedingJoinPoint pjp) throws Throwable {
        try (com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.Access ai = com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.beginOrNoop(aiAccessAudit, 
                     factoryOf(pjp.getArgs()), com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.Entry.AI_TOOL);
             ProcessSecretReadTracker.Scope ignored = ProcessSecretReadTracker.open()) {
            Object out = pjp.proceed();
            ai.involvesReads(ProcessSecretReadTracker.reads());
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
        // 单据类工具 (显式名单) 按单据视图; 其余 AI 回答按汇总视图: 单批实测出成也不给无权的人 (Steve 第三问 A: 汇总不给)
        boolean aggregateView = !DOCUMENT_VIEW_TOOLS.contains(toolName);
        boolean changed = scrubber.scrub(tree, actor, factoryId, ProcessSecretReadTracker.reads(),
                "AI tool " + toolName, aggregateView);
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
