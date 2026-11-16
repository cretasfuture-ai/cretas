package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试里的「普通入口」(/execute) = 生产上的接线 (意图蒸馏③ PR-3): 门面 → {@link StreamTurnCollector} →
 * {@link SseStreamingService} 流式管线, 管线每轮的共用步骤由同一个编排器提供 ({@code turnSteps})。
 *
 * <p>PR-3 删了编排器的识别分支, 原来直接调 {@code orchestrator.execute(无意图码)} 的入口用例改成经这里跑:
 * 流式管线用的依赖<b>与 Spring 一样是同一批 bean</b> —— 这里从测试已配好的编排器上按字段名逐个取来
 * (识别、工具分发、权限门、写闸、格式化、会话服务、遮价……), 编排器没有的 (任务状态、识别历史等) 由
 * {@code extras} 按类型补上。显式意图码的请求照生产走编排器的显式意图路径。
 */
final class OrdinaryEntryPipeline {

    private OrdinaryEntryPipeline() {
    }

    /** 与 {@code IntentExecutorServiceImpl.execute} 同一个路由: 带意图码 → 编排器显式意图路径, 其余 → 流式管线。 */
    static IntentExecuteResponse execute(IntentExecutionOrchestrator orchestrator, String factoryId,
                                         IntentExecuteRequest request, Long userId, String userRole, Object... extras) {
        StreamTurnCollector collector = collector(orchestrator, extras);
        return collector.route(request).viaStream()
                ? collector.collect(factoryId, request, userId, userRole)
                : orchestrator.execute(factoryId, request, userId, userRole);
    }

    static StreamTurnCollector collector(IntentExecutionOrchestrator orchestrator, Object... extras) {
        return new StreamTurnCollector(pipeline(orchestrator, extras), orchestrator);
    }

    private static final Map<IntentExecutionOrchestrator, SseStreamingService> BUILT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 用例中途在编排器上换了依赖 (识别 / 会话 / 工具分发…) 就重建管线, 与 Spring 注入同一批 bean 的前提一致。 */
    private static final Map<IntentExecutionOrchestrator, java.util.List<Object>> BUILT_FROM =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static java.util.List<Object> sources(IntentExecutionOrchestrator o) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (String name : new String[]{"aiIntentService", "semanticCacheService", "conversationService",
                "conversationMemoryService", "toolDispatchService", "toolRegistry", "dynamicToolSelectionService",
                "dashScopeClient", "intentPermissionGate", "slotFillingService", "resultFormatterService"}) {
            out.add(get(o, name));
        }
        return out;
    }

    private static boolean sameIdentities(java.util.List<Object> a, java.util.List<Object> b) {
        if (b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static final Map<IntentExecutionOrchestrator, java.util.List<Object>> PENDING_EXTRAS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 先登记只有管线才有的依赖 (任务状态、识别历史解析器…), 建管线时按类型接上 ——
     * 替代 PR-3 前测试往编排器上设的同名字段 (那些字段随识别分支一起删了)。
     */
    static void bind(IntentExecutionOrchestrator orchestrator, Object... extras) {
        PENDING_EXTRAS.computeIfAbsent(orchestrator, k -> new java.util.ArrayList<>()).addAll(java.util.Arrays.asList(extras));
        SseStreamingService existing = BUILT.get(orchestrator);
        if (existing != null) bindExtras(existing, extras);
    }

    /** 同一个编排器只建一条管线 (多轮用例共享同一个会话服务等状态)。 */
    static SseStreamingService pipeline(IntentExecutionOrchestrator orchestrator, Object... extras) {
        SseStreamingService existing = BUILT.get(orchestrator);
        java.util.List<Object> sources = sources(orchestrator);
        if (existing != null && sameIdentities(sources, BUILT_FROM.get(orchestrator))) {
            bindExtras(existing, extras);
            return existing;
        }
        BUILT_FROM.put(orchestrator, sources);
        SseStreamingService sse = new SseStreamingService(
                recognitionOverloads(get(orchestrator, "aiIntentService")),
                cacheMissWhenUnstubbed(get(orchestrator, "semanticCacheService")),
                get(orchestrator, "knowledgeBase"), get(orchestrator, "analysisRouterService"),
                get(orchestrator, "objectMapper") != null
                        ? ((com.fasterxml.jackson.databind.ObjectMapper) get(orchestrator, "objectMapper")).copy().findAndRegisterModules()
                        : new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                get(orchestrator, "toolRegistry"), get(orchestrator, "toolDispatchService"),
                get(orchestrator, "dynamicToolSelectionService"), get(orchestrator, "dashScopeClient"),
                get(orchestrator, "dashScopeConfig"));
        Map<String, Field> source = fields(orchestrator.getClass());
        for (Field target : fields(SseStreamingService.class).values()) {
            if (Modifier.isFinal(target.getModifiers()) || Modifier.isStatic(target.getModifiers())) continue;
            Field from = source.get(target.getName());
            if (from == null) continue;
            Object value = read(from, orchestrator);
            if (value != null && target.getType().isInstance(value)) {
                write(target, sse, value);
            }
        }
        ReflectionTestUtils.setField(sse, "turnSteps", orchestrator);
        Object memory = ReflectionTestUtils.getField(sse, "conversationMemoryService");
        if (memory != null && Mockito.mockingDetails(memory).isMock()) {
            ReflectionTestUtils.setField(sse, "conversationMemoryService",
                    creatingContexts((com.cretas.aims.service.ConversationMemoryService) memory));
        }
        if (ReflectionTestUtils.getField(sse, "factoryCapabilityPackRoutingPolicy") == null) {
            ReflectionTestUtils.setField(sse, "factoryCapabilityPackRoutingPolicy",
                    new com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy(
                            Mockito.mock(com.cretas.aims.repository.FactoryRepository.class),
                            new com.cretas.aims.ai.capability.FactoryCapabilityPackSelector(
                                    new com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry()), false));
        }
        FactoryAnalysisTestMetadata.bindNamedFactories(sse);
        bindExtras(sse, PENDING_EXTRAS.getOrDefault(orchestrator, java.util.List.of()).toArray());
        bindExtras(sse, extras);
        SseStreamingService spied = Mockito.spy(sse);
        BUILT.put(orchestrator, spied);
        return spied;
    }

    /**
     * 反方向: 只建了流式管线的测试 (App 入口) 也要有生产上的共用步骤 (对话记忆、补问、Drools、早期路由):
     * 用管线自己的依赖建一个编排器接到 {@code turnSteps} 上 (与 Spring 一样同一批 bean)。返回那个编排器。
     */
    static IntentExecutionOrchestrator attachTurnSteps(SseStreamingService sse) {
        IntentExecutionOrchestrator steps = new IntentExecutionOrchestrator(
                orMock(get(sse, "aiIntentService"), com.cretas.aims.service.AIIntentService.class),
                Mockito.mock(com.cretas.aims.service.IntentSemanticsParser.class),
                orMock(get(sse, "semanticCacheService"), com.cretas.aims.service.SemanticCacheService.class),
                Mockito.mock(com.cretas.aims.service.RuleEngineService.class),
                orMock(get(sse, "conversationService"), com.cretas.aims.service.ConversationService.class),
                orMock(get(sse, "conversationMemoryService"), com.cretas.aims.service.ConversationMemoryService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                orMock(get(sse, "dashScopeClient"), com.cretas.aims.ai.client.DashScopeClient.class),
                orMock(get(sse, "dashScopeConfig"), com.cretas.aims.config.DashScopeConfig.class),
                orMock(get(sse, "knowledgeBase"), com.cretas.aims.config.IntentKnowledgeBase.class),
                Mockito.mock(com.cretas.aims.repository.AIAnalysisResultRepository.class),
                orMock(get(sse, "toolRegistry"), com.cretas.aims.ai.tool.ToolRegistry.class),
                orMock(get(sse, "analysisRouterService"), com.cretas.aims.service.AnalysisRouterService.class),
                Mockito.mock(com.cretas.aims.service.ComplexityRouter.class),
                Mockito.mock(com.cretas.aims.service.AgentOrchestrator.class),
                Mockito.mock(com.cretas.aims.service.AgenticRAGRouterService.class),
                Mockito.mock(com.cretas.aims.service.ResultValidatorService.class),
                orMock(get(sse, "toolDispatchService"), ToolDispatchService.class),
                orMock(get(sse, "dynamicToolSelectionService"), DynamicToolSelectionService.class),
                orMock(get(sse, "queryPreprocessorService"), com.cretas.aims.service.QueryPreprocessorService.class));
        Map<String, Field> source = fields(sse.getClass());
        for (Field target : fields(IntentExecutionOrchestrator.class).values()) {
            if (Modifier.isFinal(target.getModifiers()) || Modifier.isStatic(target.getModifiers())) continue;
            Field from = source.get(target.getName());
            if (from == null) continue;
            Object value = read(from, sse);
            if (value != null && target.getType().isInstance(value)) {
                write(target, steps, value);
            }
        }
        if (ReflectionTestUtils.getField(steps, "writeGuardService") == null) {
            ReflectionTestUtils.setField(steps, "writeGuardService", new com.cretas.aims.ai.tool.WriteGuardService());
        }
        ReflectionTestUtils.setField(sse, "turnSteps", steps);
        return steps;
    }

    /**
     * 编排器用例里的会话记忆多是没桩 getOrCreateContext 的 mock (编排器旧识别分支用不到它); 流式管线每轮先建立本人会话,
     * 真实实现从不返回 null (没有就新建)。这里只在 mock 返回 null 时按请求的工厂 / 人 / 会话补一份新会话, 其余调用原样转给 mock。
     */
    private static com.cretas.aims.service.ConversationMemoryService creatingContexts(
            com.cretas.aims.service.ConversationMemoryService mock) {
        return (com.cretas.aims.service.ConversationMemoryService) java.lang.reflect.Proxy.newProxyInstance(
                OrdinaryEntryPipeline.class.getClassLoader(),
                new Class<?>[]{com.cretas.aims.service.ConversationMemoryService.class},
                (proxy, method, args) -> {
                    Object out;
                    try {
                        out = method.invoke(mock, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (out == null && "getOrCreateContext".equals(method.getName()) && args != null && args.length == 3) {
                        String sessionId = args[2] != null ? (String) args[2] : "pipeline-session";
                        return com.cretas.aims.dto.conversation.ConversationContext.builder()
                                .sessionId(sessionId).factoryId((String) args[0]).userId((Long) args[1])
                                .recentMessages(new java.util.ArrayList<>()).build();
                    }
                    return out;
                });
    }

    /**
     * 识别接口的重载链: 真实实现里带历史 / 任务状态的版本在历史为空时就是 8 参版 (接口 default 一路往下调)。
     * 编排器用例只桩了 8 参版 (编排器旧识别分支在没有上下文时调它), 管线调 9 参版 (带空历史): 这里按接口的默认链
     * 先问 8 参版, 桩没给结果再问原调用。其余方法原样转给 mock。
     */
    private static com.cretas.aims.service.AIIntentService recognitionOverloads(Object recognizer) {
        if (recognizer == null) return Mockito.mock(com.cretas.aims.service.AIIntentService.class);
        if (!Mockito.mockingDetails(recognizer).isMock()) return (com.cretas.aims.service.AIIntentService) recognizer;
        com.cretas.aims.service.AIIntentService mock = (com.cretas.aims.service.AIIntentService) recognizer;
        return (com.cretas.aims.service.AIIntentService) java.lang.reflect.Proxy.newProxyInstance(
                OrdinaryEntryPipeline.class.getClassLoader(), new Class<?>[]{com.cretas.aims.service.AIIntentService.class},
                (proxy, method, args) -> {
                    if ("recognizeIntentWithConfidence".equals(method.getName()) && args != null
                            && (args.length == 9 || args.length == 10)
                            && (args[8] == null || ((java.util.List<?>) args[8]).isEmpty())
                            && (args.length == 9 || args[9] == null || ((Map<?, ?>) args[9]).isEmpty())) {
                        Object eight = mock.recognizeIntentWithConfidence((String) args[0], (String) args[1], (Integer) args[2],
                                (Long) args[3], (String) args[4], (String) args[5], (String) args[6],
                                (java.util.Set<String>) args[7]);
                        if (eight != null) return eight;
                    }
                    try {
                        return method.invoke(mock, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** 语义缓存: 没装或桩没给结果 = 未命中 (真实实现从不返回 null)。 */
    private static com.cretas.aims.service.SemanticCacheService cacheMissWhenUnstubbed(Object cache) {
        com.cretas.aims.service.SemanticCacheService target = cache != null
                ? (com.cretas.aims.service.SemanticCacheService) cache
                : Mockito.mock(com.cretas.aims.service.SemanticCacheService.class);
        return (com.cretas.aims.service.SemanticCacheService) java.lang.reflect.Proxy.newProxyInstance(
                OrdinaryEntryPipeline.class.getClassLoader(), new Class<?>[]{com.cretas.aims.service.SemanticCacheService.class},
                (proxy, method, args) -> {
                    Object out;
                    try {
                        out = method.invoke(target, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (out == null && "queryCache".equals(method.getName())) {
                        return com.cretas.aims.dto.cache.SemanticCacheHit.miss(0L);
                    }
                    return out;
                });
    }

    private static <T> T orMock(Object value, Class<T> type) {
        return value != null ? type.cast(value) : Mockito.mock(type);
    }

    private static void bindExtras(SseStreamingService sse, Object... extras) {
        for (Object extra : extras) {
            if (extra == null) continue;
            for (Field target : fields(SseStreamingService.class).values()) {
                if (Modifier.isFinal(target.getModifiers()) || Modifier.isStatic(target.getModifiers())) continue;
                if (target.getType().isInstance(extra)) {
                    write(target, sse, extra);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String name) {
        Field f = fields(target.getClass()).get(name);
        return f == null ? null : (T) read(f, target);
    }

    private static Map<String, Field> fields(Class<?> type) {
        Map<String, Field> out = new HashMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                out.putIfAbsent(f.getName(), f);
            }
        }
        return out;
    }

    private static Object read(Field f, Object target) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Field f, Object target, Object value) {
        try {
            f.setAccessible(true);
            f.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
