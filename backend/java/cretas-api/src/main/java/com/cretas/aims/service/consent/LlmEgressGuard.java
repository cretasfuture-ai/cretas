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
     */
    public LlmEgressRedactor.Result utterance(String site, String... texts) {
        try {
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
     * 调用点没带工厂 (Excel 解析 / 纠错 Agent 这类深层方法) 时, 取本次请求登录 token 里的工厂;
     * 请求线程之外 (定时任务、异步线程) 取不到就当没有工厂上下文。
     */
    private Collection<String> protectedNames(String factoryId) {
        String factory = factoryId != null ? factoryId : currentFactory();
        if (processSecretSampleFilter == null || factory == null) {
            return Set.of();
        }
        // 一条消息要过好几处出口 (识别 / 消歧 / 抽参 / 回复), 每处都查两次库; 同一工厂 10 秒内复用。
        // 只缓存查到的结果: 查库失败照样抛出 (由调用方按「脱敏不了」拒绝), 不缓存。新设的保密名最多 10 秒后生效。
        return protectedNameCache.get(factory, processSecretSampleFilter::protectedProductNames);
    }

    static final java.time.Duration PROTECTED_NAME_TTL = java.time.Duration.ofSeconds(10);

    private final com.github.benmanes.caffeine.cache.Cache<String, Set<String>> protectedNameCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(PROTECTED_NAME_TTL)
                    .maximumSize(1_000)
                    .build();

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
        log.warn("LLM egress [{}]: redaction failed ({}), not sending", site, e.getClass().getSimpleName());
        return LlmEgressRedactor.Result.refusedFor("脱敏失败");
    }
}
