package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ConversationMemoryService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 服务端翻页的唯一一份规则 (INTENT-DISTILL D1)。
 *
 * <p>以前只在 {@link SseStreamingService} 里: 普通入口 {@code /execute} 的「下一页」落到
 * {@code PaginationNextTool}, 那个工具只回一句「正在加载」, 没有数据; 普通入口也从不记分页元数据。
 * 现在两个入口都从这里取: 页码只由程序根据动作 (下一页 / 上一页) 与上一页的服务端元数据算出,
 * 不由模型输出; 条件取上一轮实际执行的参数 ({@link ReadTurnParameters} 的 readQuery)。
 */
final class ReadPaginationSupport {

    static final String METADATA_KEY = "pagination";
    static final String REPLAY_KEY = "paginationReplay";
    static final Set<String> PAGINATION_INTENTS = Set.of(
            "PAGINATION_NEXT", "NAVIGATION_NEXT_PAGE", "PAGINATION_PREVIOUS", "NAVIGATION_PREVIOUS_PAGE");
    private static final Set<String> PREVIOUS_INTENTS = Set.of("PAGINATION_PREVIOUS", "NAVIGATION_PREVIOUS_PAGE");
    private static final Set<String> NON_FILTER_KEYS = Set.of("page", "pageBase", "size", REPLAY_KEY);

    private ReadPaginationSupport() {
    }

    static boolean isPaginationIntent(String intentCode) {
        return intentCode != null && PAGINATION_INTENTS.contains(intentCode.toUpperCase(java.util.Locale.ROOT));
    }

    static boolean isPreviousIntent(String intentCode) {
        return intentCode != null && PREVIOUS_INTENTS.contains(intentCode.toUpperCase(java.util.Locale.ROOT));
    }

    /** 翻页计划: 要么一个拒绝 (不调度任何工具), 要么一次用服务端页码重放上一轮只读查询。 */
    record Plan(IntentExecuteResponse refusal, AIIntentConfig intent, ToolExecutor tool,
                IntentExecuteRequest replay, IntentMatchResult match) {
        static Plan refuse(IntentExecuteResponse refusal) {
            return new Plan(refusal, null, null, null, null);
        }

        boolean refused() {
            return refusal != null;
        }
    }

    static Plan plan(boolean previous, ConversationMemoryService memory, ToolRegistry toolRegistry,
                     WriteGuardService writeGuardService,
                     Function<String, Optional<AIIntentConfig>> intentByCode,
                     String factoryId, Long userId, IntentExecuteRequest request) {
        if (memory == null || request == null || request.getSessionId() == null
                || request.getSessionId().isBlank() || userId == null || factoryId == null) {
            return Plan.refuse(refusal(previous, "当前会话没有可继续的成功列表查询。"));
        }
        ConversationContext owner = memory.getContext(request.getSessionId());
        if (owner == null || !factoryId.equals(owner.getFactoryId()) || !userId.equals(owner.getUserId())) {
            return Plan.refuse(refusal(previous, "当前会话不属于当前用户或工厂，无法翻页。"));
        }
        Map<String, Object> metadata = latestPaginationMetadata(owner);
        if (metadata == null) {
            return Plan.refuse(refusal(previous, "当前会话没有可继续的成功列表查询。"));
        }
        String intentCode = stringValue(metadata.get("intentCode"));
        if (intentCode == null) {
            return Plan.refuse(refusal(previous, "上次查询没有可复用的服务端意图。"));
        }
        Optional<AIIntentConfig> intentOpt = intentByCode.apply(intentCode);
        if (intentOpt.isEmpty()) {
            return Plan.refuse(refusal(previous, "上次查询已不可用，无法安全翻页。"));
        }
        AIIntentConfig intent = intentOpt.get();
        Optional<ToolExecutor> toolOpt = intent.getToolName() == null
                ? Optional.empty() : toolRegistry.getExecutor(intent.getToolName());
        if (toolOpt.isEmpty() || toolOpt.get().getAccessMode() != ToolExecutor.AccessMode.READ
                || (writeGuardService != null && writeGuardService.isWriteTool(toolOpt.get()))
                || (writeGuardService != null && writeGuardService.isWriteIntent(intent))) {
            return Plan.refuse(refusal(previous, "上次查询不是受支持的只读列表，无法翻页。"));
        }
        Integer pageBase = pageBase(toolOpt.get());

        Map<String, Object> replayContext = new LinkedHashMap<>();
        if (metadata.get("filters") instanceof Map<?, ?> filterMap) {
            for (Map.Entry<?, ?> entry : filterMap.entrySet()) {
                String key = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                if (key != null && entry.getValue() != null && !Set.of("page", "pageBase", REPLAY_KEY).contains(key)) {
                    replayContext.put(key, entry.getValue());
                }
            }
        }
        Integer currentPage = strictPageValue(metadata.get("page"));
        if (currentPage == null) {
            return Plan.refuse(refusal(previous, "上次查询页码基准未知，无法安全翻页。"));
        }
        boolean hasMorePresent = metadata.containsKey("hasMore");
        Boolean hasMore = hasMorePresent ? strictBooleanValue(metadata.get("hasMore")) : null;
        if (hasMorePresent && hasMore == null) {
            return Plan.refuse(refusal(previous, "分页元数据中的 hasMore 无效，无法安全翻页。"));
        }
        boolean totalPagesPresent = metadata.containsKey("totalPages");
        Long totalPages = totalPagesPresent ? strictTotalPagesValue(metadata.get("totalPages")) : null;
        if (totalPagesPresent && totalPages == null) {
            return Plan.refuse(refusal(previous, "分页元数据中的总页数无效，无法安全翻页。"));
        }
        if (pageBase == null) {
            if (previous) {
                return Plan.refuse(refusal(previous, "上次查询页码基准未知，无法安全翻页。"));
            }
            if (!hasMorePresent || !Boolean.TRUE.equals(hasMore)) {
                return Plan.refuse(refusal(previous,
                        hasMorePresent ? "已经是最后一页，没有更多数据。" : "分页元数据缺少 hasMore，无法安全翻页。"));
            }
        } else if (totalPages != null) {
            long lastPage = pageBase == 0 ? totalPages - 1L : totalPages;
            if (totalPages <= 0 || currentPage < pageBase || (long) currentPage > lastPage) {
                return Plan.refuse(refusal(previous, "当前页码超出服务端分页范围，无法安全翻页。"));
            }
            boolean atTail = currentPage == lastPage;
            if (hasMore != null && hasMore != !atTail) {
                return Plan.refuse(refusal(previous, "分页元数据与总页数不一致，无法安全翻页。"));
            }
            if (!previous && atTail) {
                return Plan.refuse(refusal(previous, "已经是最后一页，没有更多数据。"));
            }
        } else {
            if (currentPage < pageBase) {
                return Plan.refuse(refusal(previous, "当前页码超出服务端分页范围，无法安全翻页。"));
            }
            if (!previous) {
                if (!hasMorePresent) {
                    return Plan.refuse(refusal(previous, "分页元数据缺少 hasMore 和总页数，无法安全翻页。"));
                }
                if (Boolean.FALSE.equals(hasMore)) {
                    return Plan.refuse(refusal(previous, "已经是最后一页，没有更多数据。"));
                }
            }
        }
        if (!previous && currentPage == Integer.MAX_VALUE) {
            return Plan.refuse(refusal(previous, "下一页页码超出可支持范围，无法安全翻页。"));
        }
        int targetPage = previous ? currentPage - 1 : currentPage + 1;
        if (previous && targetPage < pageBase) {
            return Plan.refuse(refusal(previous, "已经是第一页，没有上一页。"));
        }
        replayContext.put("page", targetPage);
        Object size = metadata.get("size");
        if (size != null) replayContext.put("size", size);
        replayContext.put(REPLAY_KEY, true);
        String queryInput = stringValue(metadata.get("queryInput"));
        if (queryInput == null) queryInput = "列表查询";
        IntentExecuteRequest replay = IntentExecuteRequest.builder()
                .userInput(queryInput)
                .intentCode(intentCode)
                .context(replayContext)
                .sessionId(request.getSessionId())
                .mode(request.getMode())
                .build();
        IntentMatchResult match = IntentMatchResult.builder()
                .bestMatch(intent)
                .confidence(1.0d)
                .matchMethod(IntentMatchResult.MatchMethod.EXACT)
                .requiresConfirmation(false)
                .build();
        return new Plan(null, intent, toolOpt.get(), replay, match);
    }

    static IntentExecuteResponse refusal(boolean previous, String message) {
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(previous ? "PAGINATION_PREVIOUS" : "PAGINATION_NEXT")
                .status("PAGINATION_REFUSED")
                .message(message)
                .formattedText(message)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 成功的只读列表结果 → 分页元数据 (挂在助手消息上)。条件优先用本次实际执行的参数
     * ({@code readQueryParams}); 没有时退回结果里的 queryConditions / 请求 context (旧行为)。
     */
    static Map<String, Object> buildMetadata(IntentExecuteRequest request, String intentCode, String toolName,
                                             ToolExecutor executor, Object rawResultData,
                                             Map<?, ?> readQuery) {
        Map<String, Object> pageData = findPageMap(rawResultData);
        if (pageData == null) return null;
        Object pageValue = pageData.containsKey("currentPage") ? pageData.get("currentPage") : pageData.get("page");
        if (pageValue == null) return null;
        Integer pageNumber = strictPageValue(pageValue);
        if (pageNumber == null) return null;
        Map<String, Object> readQueryParams = readQuery != null && readQuery.get("params") instanceof Map<?, ?> p
                ? copyMap(p) : null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intentCode", intentCode);
        metadata.put("toolName", toolName);
        // 翻页重放的是原查询那句话 (不是「下一页」), 由 readQuery 记下的本次执行原话给出
        metadata.put("queryInput", readQuery != null && readQuery.get("queryInput") instanceof String q && !q.isBlank()
                ? q : request.getUserInput());
        metadata.put("page", pageNumber);
        Integer pageBase = pageBase(executor);
        if (pageBase != null && (pageBase == 0 || pageBase == 1)) metadata.put("pageBase", pageBase);
        Object size = firstValue(pageData, "size", "pageSize", "limit");
        if (size == null && request.getContext() != null) {
            size = firstValue(request.getContext(), "size", "pageSize", "limit");
        }
        if (size != null) metadata.put("size", size);
        Object hasMore = pageData.get("hasMore");
        if (hasMore != null) metadata.put("hasMore", hasMore);
        Object totalPages = pageData.get("totalPages");
        if (totalPages != null) metadata.put("totalPages", totalPages);

        Map<String, Object> filters = new LinkedHashMap<>();
        if (readQueryParams != null && !readQueryParams.isEmpty()) {
            readQueryParams.forEach((key, value) -> {
                if (key != null && value != null && !NON_FILTER_KEYS.contains(key)) filters.put(key, value);
            });
            if (!filters.isEmpty()) metadata.put("filters", filters);
            return metadata;
        }
        Object conditions = firstValue(pageData, "queryConditions", "filters", "filter");
        if (conditions instanceof Map<?, ?> conditionMap) {
            metadata.put("filters", copyMap(conditionMap));
        } else if (request.getContext() != null) {
            for (Map.Entry<String, Object> entry : request.getContext().entrySet()) {
                if (entry.getValue() != null && !NON_FILTER_KEYS.contains(entry.getKey())) {
                    filters.put(entry.getKey(), entry.getValue());
                }
            }
            if (!filters.isEmpty()) metadata.put("filters", filters);
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> latestPaginationMetadata(ConversationContext context) {
        List<ConversationMessage> messages = context.getRecentMessages();
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage message = messages.get(i);
            if (message.getRole() != ConversationMessage.Role.ASSISTANT || message.getMetadata() == null) continue;
            Object raw = message.getMetadata().get(METADATA_KEY);
            if (raw instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return null;
    }

    static Integer pageBase(ToolExecutor executor) {
        if (executor == null) return null;
        Map<String, Object> schema;
        try {
            schema = executor.getParametersSchema();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties)) return null;
        Object rawPage = properties.get("page");
        if (!(rawPage instanceof Map<?, ?> pageSchema)) return null;
        Integer minimum = strictPageValue(pageSchema.get("minimum"));
        if (minimum == null || (minimum != 0 && minimum != 1)) return null;
        if (pageSchema.containsKey("default")) {
            Integer defaultPage = strictPageValue(pageSchema.get("default"));
            if (defaultPage == null || defaultPage < minimum) return null;
        }
        return minimum;
    }

    static Integer strictPageValue(Object value) {
        if (value instanceof Number number) {
            try {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (decimal.signum() < 0
                        || decimal.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return null;
                return decimal.intValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof String text) {
            try {
                int parsed = Integer.parseInt(text.trim());
                return parsed < 0 ? null : parsed;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean strictBooleanValue(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private static Long strictTotalPagesValue(Object value) {
        if (value instanceof Number number) {
            try {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (decimal.signum() < 0 || decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                    return null;
                }
                return decimal.longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text.trim());
                return parsed < 0 ? null : parsed;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Object> findPageMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> candidate = copyMap(map);
        if (candidate.containsKey("currentPage") || candidate.containsKey("page")) return candidate;
        Object nested = candidate.get("data");
        return nested instanceof Map<?, ?> ? findPageMap(nested) : null;
    }

    private static Object firstValue(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
