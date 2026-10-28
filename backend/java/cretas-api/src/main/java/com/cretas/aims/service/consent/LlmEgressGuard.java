package com.cretas.aims.service.consent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发给外部大模型之前的统一入口: 查本厂工艺保密产品名 → {@link LlmEgressRedactor} → 写一行日志。
 * 查保密产品名出错、脱敏器拒绝、或脱敏器自身出错, 都返回 {@link LlmEgressRedactor.Result#refused()} 的结果,
 * 调用方一律不发 (改用本机结果或直接提示)。
 *
 * <p>日志格式固定 {@code LLM egress [site]: redacted N name(s), dropped M field(s)} /
 * {@code LLM egress [site]: refused (原因), not sending}, 原因只有类别、不含原始值。
 */
@Slf4j
@Component
public class LlmEgressGuard {

    private final ObjectMapper objectMapper;
    private final ProcessSecretSampleFilter processSecretSampleFilter;

    @Autowired
    public LlmEgressGuard(ObjectMapper objectMapper, ProcessSecretSampleFilter processSecretSampleFilter) {
        this.objectMapper = objectMapper;
        this.processSecretSampleFilter = processSecretSampleFilter;
    }

    /** 没有 Spring 容器时 (单测直接 new 被测类) 的默认实例: 不查保密产品名, 其余照常脱敏。 */
    public static LlmEgressGuard standalone() {
        // 与 Spring Boot 注入的 ObjectMapper 一致: 认 java.time, 时间写成 ISO 字符串 (业务记录里常有 LocalDateTime)
        return new LlmEgressGuard(new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS), null);
    }

    /** 结构化结果 (查询记录 / 工具输出)。 */
    public LlmEgressRedactor.Result structured(String site, String factoryId, Object data) {
        try {
            LlmEgressRedactor.Result r = new LlmEgressRedactor(objectMapper).redact(data, protectedNames(factoryId));
            return logged(site, r);
        } catch (RuntimeException e) {
            return failed(site, e);
        }
    }

    /**
     * 带记录内容的提示词 / 对话历史: 号码、公司名、「岗位 + 人名」、已知交易对象名、保密产品名都换代号。
     *
     * @param knownParties 调用方从记录里取出的名字 (客户名等), 可为空
     */
    public LlmEgressRedactor.Result texts(String site, String factoryId,
                                          Map<String, LlmEgressRedactor.Party> knownParties, List<String> texts) {
        try {
            LlmEgressRedactor.Result r = new LlmEgressRedactor(objectMapper)
                    .redactTexts(texts, knownParties, protectedNames(factoryId), true);
            return logged(site, r);
        } catch (RuntimeException e) {
            return failed(site, e);
        }
    }

    public LlmEgressRedactor.Result texts(String site, String factoryId, String... texts) {
        return texts(site, factoryId, Map.of(), Arrays.asList(texts));
    }

    /**
     * 只发文本、但文本是从一份结构化记录拼出来的 (成本数据 / 表单提交值 / Excel 样本…):
     * 先从记录里认出人和交易对象名、不发字段 (地址 / 备注 / 电话) 的值, 再按这些名字脱敏文本。
     * 记录里不认识字段的值也在文本里换掉; 记录展开不了就拒绝 (不退回只按文本猜, 那会漏掉记录里的名字)。
     */
    public LlmEgressRedactor.Result textsWithRecord(String site, String factoryId, Object record,
                                                    Map<String, LlmEgressRedactor.Party> knownParties,
                                                    List<String> texts) {
        try {
            LlmEgressRedactor.Result r = new LlmEgressRedactor(objectMapper)
                    .redactTextsWithRecord(record, texts, knownParties, protectedNames(factoryId));
            return logged(site, r);
        } catch (RuntimeException e) {
            return failed(site, e);
        }
    }

    /** 结构化数据 + 文本共用一套代号 (名单里的人在原话里出现时是同一个代号)。 */
    public LlmEgressRedactor.Result combined(String site, String factoryId, Object data,
                                             Map<String, LlmEgressRedactor.Party> knownParties, List<String> texts) {
        try {
            LlmEgressRedactor.Result r = new LlmEgressRedactor(objectMapper)
                    .redact(data, texts, knownParties, protectedNames(factoryId), true);
            return logged(site, r);
        } catch (RuntimeException e) {
            return failed(site, e);
        }
    }

    /**
     * 只有用户原话、要按原话识别意图 / 抽参数: 只把手机号、座机、身份证号、银行卡号换成可还原的代号
     * (不查库, 不认人名 —— 识别链路上不加库查询, 也不让人名误报改坏原话)。
     *
     * <p><b>已知例外 (有意保留, Steve 2026-10-29 裁定):</b> 意图识别把用户原话连同其中的工艺保密产品名
     * <b>不脱敏</b>发给外部模型 —— 这里传的保密产品名单是空集, 也不因没有工厂上下文而拒绝。
     * 除意图识别以外的所有外发 ({@link #texts} / {@link #structured} / {@link #textsWithRecord} /
     * {@link #combined}) 一律把保密产品名换代号。⛔ 不要把别的出口改走这个方法来绕过脱敏;
     * Python 侧同一例外在 {@code backend/python/common/llm_client.py} 的 {@code _names_for_this_request};
     * 说明见 {@code docs/security/process-secret-ai-egress.md}。
     */
    public LlmEgressRedactor.Result utterance(String site, String... texts) {
        try {
            // 已知例外 (Steve 2026-10-29): 意图识别保留原名, 保密产品名单故意为空 —— 见上方 javadoc
            LlmEgressRedactor.Result r = new LlmEgressRedactor(objectMapper)
                    .redactTexts(Arrays.asList(texts), Map.of(), Set.of(), false);
            return logged(site, r);
        } catch (RuntimeException e) {
            return failed(site, e);
        }
    }

    /**
     * 对话记忆里的实体槽位: 供应商 / 客户 / 门店 / 员工的名字 (含展示值) 是已知交易对象, 在历史、摘要、原话里一律换代号。
     * 槽位类型用名字比对 ({@code EntitySlot.SlotType} 与记忆表里的字符串类型都适用)。
     */
    public static Map<String, LlmEgressRedactor.Party> partiesFromSlots(Map<?, ?> slots) {
        Map<String, LlmEgressRedactor.Party> out = new java.util.LinkedHashMap<>();
        if (slots == null) {
            return out;
        }
        for (Map.Entry<?, ?> e : slots.entrySet()) {
            LlmEgressRedactor.Party party = switch (String.valueOf(e.getKey())) {
                case "SUPPLIER" -> LlmEgressRedactor.Party.SUPPLIER;
                case "CUSTOMER", "STORE" -> LlmEgressRedactor.Party.CUSTOMER;
                case "EMPLOYEE" -> LlmEgressRedactor.Party.PERSON;
                default -> null;
            };
            if (party == null) {
                continue;
            }
            for (String name : slotNames(e.getValue())) {
                if (name != null && !name.isBlank()) {
                    out.put(name.trim(), party);
                }
            }
        }
        return out;
    }

    private static List<String> slotNames(Object slot) {
        if (slot instanceof com.cretas.aims.dto.conversation.EntitySlot s) {
            return Arrays.asList(s.getName(), s.getDisplayValue());
        }
        if (slot instanceof com.cretas.aims.entity.conversation.ConversationMemory.EntitySlotData s) {
            return Arrays.asList(s.getName(), s.getDisplayValue());
        }
        return List.of();
    }

    /**
     * 按哪家工厂的保密产品名脱敏: 调用点显式带的工厂优先; 没带 (Excel 解析 / 纠错 Agent 这类深层方法)
     * 才取本次执行的登录身份所属工厂 ({@link #currentFactory()})。
     *
     * <p>Steve 2026-10-29: 保密产品名不许出本厂。以前两头都取不到工厂时名单为空、原样发出; 平台账号替某厂分析时
     * token 里没有 (或不是) 那家工厂, 那家的保密产品名同样原样发出。现在 fail-closed:
     * <ul>
     *   <li>平台账号 ({@link com.cretas.aims.security.AuthenticatedActor#isPlatformAdmin()}) 必须由调用点显式带工厂,
     *       不按 token 推断;</li>
     *   <li>显式工厂和登录身份工厂都没有 (定时任务、没绑身份的异步线程) → 拒绝外发, 不再发未脱敏的原文。</li>
     * </ul>
     * 只在容器实例上生效; {@link #standalone()} (单测直接 new, 不查保密产品名) 行为不变。
     */
    private Collection<String> protectedNames(String factoryId) {
        if (processSecretSampleFilter == null) {
            return Set.of();
        }
        String factory = egressFactory(factoryId);
        if (factory == null) {
            throw new NoEgressFactoryException();
        }
        // 缓存在 ProcessSecretSampleFilter 里 (按「工厂 + 当前保密产品集合」作键, 改保密设置立即生效)
        return processSecretSampleFilter.protectedProductNames(factory);
    }

    /** 显式工厂优先; 没有时平台账号返回 null (不按 token 猜), 其余取本次执行的登录身份工厂。 */
    static String egressFactory(String explicitFactoryId) {
        if (explicitFactoryId != null && !explicitFactoryId.isBlank()) {
            return explicitFactoryId;
        }
        boolean platformActor = com.cretas.aims.security.AuthenticatedActor.fromCurrentExecution()
                .map(com.cretas.aims.security.AuthenticatedActor::isPlatformAdmin).orElse(false);
        if (platformActor) {
            return null;
        }
        String fromExecution = currentFactory();
        return fromExecution == null || fromExecution.isBlank() ? null : fromExecution;
    }

    /** 不知道按哪家工厂脱敏: 拒绝外发 (原因只写类别)。 */
    static final class NoEgressFactoryException extends RuntimeException {
        NoEgressFactoryException() {
            super("no factory context for egress", null, false, false);
        }
    }

    /**
     * 请求线程: 登录 token 里的工厂; 流式对话在 SSE 工作线程上跑, 没有 Servlet 请求, 取控制器绑上的可信身份
     * ({@link com.cretas.aims.security.AuthenticatedActor#trustedAsyncScope})。两者都没有才当没有工厂上下文。
     */
    static String currentFactory() {
        String fromRequest = com.cretas.aims.utils.SecurityUtils.currentFactoryId();
        if (fromRequest != null) {
            return fromRequest;
        }
        return com.cretas.aims.security.AuthenticatedActor.fromCurrentExecution()
                .map(com.cretas.aims.security.AuthenticatedActor::factoryId).orElse(null);
    }

    private static LlmEgressRedactor.Result logged(String site, LlmEgressRedactor.Result r) {
        if (r.refused()) {
            log.warn("LLM egress [{}]: refused ({}), not sending", site, r.refusal());
        } else {
            log.info("LLM egress [{}]: redacted {} name(s), dropped {} field(s)", site, r.tokenCount(), r.droppedFields());
        }
        return r;
    }

    private static LlmEgressRedactor.Result failed(String site, RuntimeException e) {
        if (e instanceof NoEgressFactoryException) {
            log.warn("LLM egress [{}]: refused (no factory context), not sending", site);
            return LlmEgressRedactor.Result.refusedFor("没有工厂上下文");
        }
        log.warn("LLM egress [{}]: redaction failed ({}), not sending", site, e.getClass().getSimpleName());
        return LlmEgressRedactor.Result.refusedFor("脱敏失败");
    }
}
