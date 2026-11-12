package com.cretas.aims.service.execution;

import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 服务端持有的任务状态 (INTENT-DISTILL-20261111 第二批, 审阅第 9 节建议 3)。
 *
 * <p><b>不新建状态表</b>: 任务就是现有 {@code conversation_sessions} 里的参数收集会话行。
 * 被打断时这一行转 {@link SessionStatus#SUSPENDED} (挂起时刻、参数来源记在 task_state_json),
 * 本人本厂的 SUSPENDED 行按挂起时刻排成任务栈。已填参数就在原来的 collected_parameters_json 里, 恢复时原样找回。
 *
 * <ul>
 *   <li>栈深上限 {@link #MAX_DEPTH}: 再挂起一个, 最早的那个转 CANCELLED。</li>
 *   <li>过期: 挂起超过 {@link #SUSPENDED_MAX_AGE} 的转 TIMEOUT (读栈时顺手收掉), 与只读续句窗口一致。</li>
 *   <li>恢复只把会话改回 ACTIVE、接着问缺的那一项, <b>不执行任何东西</b>; 补齐后走显式意图,
 *       写操作照常只出确认卡 (预览凭据 + 改前值比对)。被挂起的任务一定还在收集参数, 还没有预览凭据 ——
 *       预览只在参数收齐之后铸, 所以栈里没有「待确认写操作」可复用, 恢复后必然重新预览。</li>
 *   <li>开关 {@code cretas.ai.task-state.enabled} 默认关: 关着时被打断 = 取消 (与改动前逐字相同),
 *       不恢复、不发摘要。回滚 = 关开关。</li>
 * </ul>
 */
@Slf4j
@Service
public class ConversationTaskStateService {

    static final int MAX_DEPTH = 3;
    public static final Duration SUSPENDED_MAX_AGE = Duration.ofMinutes(30);

    private final ConversationSessionRepository sessions;

    @Value("${cretas.ai.task-state.enabled:false}")
    private boolean enabled;

    @Autowired(required = false)
    private ConversationMemoryService conversationMemoryService;

    @Autowired(required = false)
    private FactorySecrecyConfig secrecy;

    /** 测试可替换。 */
    Supplier<LocalDateTime> clock = LocalDateTime::now;

    @Autowired
    public ConversationTaskStateService(ConversationSessionRepository sessions) {
        this.sessions = sessions;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 这一轮开始时, 请求点名的会话是不是一个正在问参数的任务 (本人本厂、ACTIVE、没过期)。
     * 是 → 这一轮不做恢复 (终审 S1: 同一句话既打断又恢复 = 原地吞话)。查询失败按「是」处理 (宁可不恢复)。
     */
    @Transactional(readOnly = true)
    public boolean hasOpenTask(String factoryId, Long userId, String sessionId) {
        if (factoryId == null || userId == null || sessionId == null || sessionId.isBlank()) return false;
        try {
            return sessions.findBySessionIdAndFactoryIdAndUserId(sessionId, factoryId, userId)
                    .filter(ConversationSession::isParameterCollectionMode)
                    .filter(s -> s.getStatus() == SessionStatus.ACTIVE && !s.isExpired())
                    .isPresent();
        } catch (RuntimeException e) {
            log.warn("[TaskState] 查正在问的任务失败, 本轮不恢复: {}", e.getMessage());
            return true;
        }
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setConversationMemoryService(ConversationMemoryService memory) {
        this.conversationMemoryService = memory;
    }

    /** 恢复的结果: 会话 (已回到 ACTIVE) 与要对用户说的话。 */
    public record Resumed(ConversationSession session, String message) {
    }

    /**
     * 正在问参数时, 这句话是在回答它、打断它, 还是「继续 + 这个任务本身」(REPEAT: 只把这一问再问一遍,
     * 不挂起、也不另起一份同意图的收集) —— 两个入口都只调这里 ({@link TaskContinuationRule})。
     */
    public TaskContinuationRule.Action classify(ConversationSession active, String input,
                                                Predicate<String> isKnownCommand,
                                                Function<String, Optional<String>> phraseIntent,
                                                Function<String, List<String>> intentNames) {
        List<RequiredParameter> pending = active.getRequiredParameters().stream()
                .filter(p -> !p.isCollected()).toList();
        TaskContinuationRule.Action action = TaskContinuationRule.forActive(input, pending, isKnownCommand);
        if (action == TaskContinuationRule.Action.INTERRUPT && enabled
                && TaskContinuationRule.resumeTarget(input, List.of(active), phraseIntent, intentNames).isPresent()) {
            return TaskContinuationRule.Action.REPEAT;
        }
        return action;
    }

    /** REPEAT 时回给用户的话: 与恢复时同一句 (回到 X, 已记下…, 还需要…)。不改任何状态。 */
    public static String repeatMessage(ConversationSession active, Function<String, List<String>> intentNames) {
        return resumeMessage(active, intentNames);
    }

    /** 意图配置 → 名称在前、配置的关键词在后 (恢复说法按这些整句比对)。 */
    public static List<String> namesOf(com.cretas.aims.entity.config.AIIntentConfig config) {
        if (config == null) return List.of();
        List<String> names = new ArrayList<>();
        if (config.getIntentName() != null && !config.getIntentName().isBlank()) names.add(config.getIntentName());
        try {
            for (String keyword : config.getKeywordsList()) {
                if (keyword != null && !keyword.isBlank()) names.add(keyword.strip());
            }
        } catch (RuntimeException ignored) {
            // 关键词解析不了就只用名称
        }
        return names;
    }

    /**
     * 被打断: 开关开 → 挂起进栈; 关 → 取消 (旧行为)。
     *
     * <p>会话行带乐观锁版本号 (终审 S3): 传进来的 {@code session} 若已被同一会话的并发请求改过
     * (例如另一请求刚补了参数或已挂起 / 恢复), 保存抛 ObjectOptimisticLockingFailureException,
     * 不会用旧值覆盖。调用方接住后按「这句话当新话识别」处理。
     */
    @Transactional
    public void interrupt(String factoryId, Long userId, ConversationSession session) {
        LocalDateTime now = clock.get();
        if (!enabled || !session.suspend(now)) {
            session.cancel();
            sessions.save(session);
            return;
        }
        sessions.save(session);
        log.info("[TaskState] 挂起任务: session={}, intent={}", session.getSessionId(), session.getKnownIntentCode());
        List<ConversationSession> stack = stack(factoryId, userId);
        for (int i = MAX_DEPTH; i < stack.size(); i++) {
            ConversationSession dropped = stack.get(i);
            dropped.cancel();
            sessions.save(dropped);
            log.info("[TaskState] 栈满, 丢弃最早的挂起任务: session={}", dropped.getSessionId());
        }
    }

    /** 本人本厂被挂起且没过期的任务, 栈顶在前。过期的顺手转 TIMEOUT。 */
    @Transactional
    public List<ConversationSession> stack(String factoryId, Long userId) {
        if (factoryId == null || userId == null) return List.of();
        LocalDateTime cutoff = clock.get().minus(SUSPENDED_MAX_AGE);
        List<ConversationSession> live = new ArrayList<>();
        for (ConversationSession s : sessions.findSuspendedByFactoryAndUser(factoryId, userId)) {
            // 防御: 查询已按 factory + user 过滤, 这里再核一次, 跨租户行不可能进栈
            if (!factoryId.equals(s.getFactoryId()) || !userId.equals(s.getUserId())) continue;
            LocalDateTime at = s.getSuspendedAt();
            if (at == null || at.isBefore(cutoff)) {
                s.timeout();
                sessions.save(s);
                continue;
            }
            live.add(s);
        }
        live.sort(Comparator.comparing(ConversationSession::getSuspendedAt).reversed());
        return live;
    }

    /**
     * 没有正在问的任务时, 这句话要不要回到被挂起的任务。要 → 那个任务回到 ACTIVE, 返回下一问。
     *
     * @param phraseIntent 已审核短语表 (原话 → 意图码)
     * @param intentName   意图码 → 给人看的名字
     */
    @Transactional
    public Optional<Resumed> tryResume(String factoryId, Long userId, String input,
                                       Function<String, Optional<String>> phraseIntent,
                                       Function<String, List<String>> intentNames) {
        if (!enabled) return Optional.empty();
        List<ConversationSession> stack = stack(factoryId, userId);
        Optional<ConversationSession> target = TaskContinuationRule.resumeTarget(input, stack, phraseIntent, intentNames);
        if (target.isEmpty()) return Optional.empty();
        ConversationSession session = target.get();
        // 同一时刻只留一个在问的任务 (与 startParameterCollection 同口径); 挂起的不受影响
        sessions.cancelActiveSessionsForUser(factoryId, userId, clock.get());
        if (!session.resumeFromSuspension(clock.get())) return Optional.empty();
        String message = resumeMessage(session, intentNames);
        session.addAssistantMessage(message);
        sessions.save(session);
        log.info("[TaskState] 恢复任务: session={}, intent={}", session.getSessionId(), session.getKnownIntentCode());
        return Optional.of(new Resumed(session, message));
    }

    /**
     * 给识别层的脱敏摘要 (白名单见 {@link TaskStateSummary})。开关关、身份不明或没有任务 → 空 map。
     *
     * @param sessionId 请求带的会话 id (可空): 用来找正在问的任务和最近一次只读查询
     */
    @Transactional
    public Map<String, Object> summary(String factoryId, Long userId, String sessionId) {
        if (!enabled || factoryId == null || userId == null) return Map.of();
        try {
            TaskStateSummary.Current current = null;
            if (sessionId != null && !sessionId.isBlank()) {
                Optional<ConversationSession> active = sessions
                        .findBySessionIdAndFactoryIdAndUserId(sessionId, factoryId, userId)
                        .filter(ConversationSession::isParameterCollectionMode)
                        .filter(s -> s.getStatus() == SessionStatus.ACTIVE && !s.isExpired());
                if (active.isPresent()) {
                    current = new TaskStateSummary.Current(active.get().getKnownIntentCode(),
                            active.get().getCollectedParameters());
                } else if (conversationMemoryService != null) {
                    ConversationContext context = conversationMemoryService.getContext(sessionId);
                    Optional<ReadTurnParameters.Snapshot> read =
                            ReadTurnParameters.latest(context, factoryId, userId, clock.get());
                    if (read.isPresent()) {
                        current = new TaskStateSummary.Current(read.get().intentCode(), read.get().params());
                    }
                }
            }
            boolean withholdNumbers = secrecy == null || secrecy.isOn(factoryId, SecrecyItem.PROCESS_SECRET);
            return TaskStateSummary.build(current, stack(factoryId, userId), withholdNumbers);
        } catch (RuntimeException e) {
            log.warn("[TaskState] 摘要生成失败, 本轮不带任务状态: {}", e.getMessage());
            return Map.of();
        }
    }

    static String resumeMessage(ConversationSession session, Function<String, List<String>> intentNames) {
        String code = session.getKnownIntentCode();
        String name = null;
        try {
            List<String> names = intentNames == null ? null : intentNames.apply(code);
            name = names == null || names.isEmpty() ? null : names.get(0);
        } catch (RuntimeException ignored) {
            // 名字只是给人看的, 拿不到就用意图码
        }
        StringBuilder sb = new StringBuilder("好的，回到刚才的「").append(name == null || name.isBlank() ? code : name)
                .append("」。");
        List<String> filled = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        java.util.Set<String> required = new java.util.HashSet<>();
        session.getRequiredParameters().forEach(p -> required.add(p.getName()));
        session.getCollectedParameters().forEach((k, v) -> {
            if (!required.contains(k)) filled.add(k + "：" + v);
        });
        for (RequiredParameter p : session.getRequiredParameters()) {
            String label = p.getLabel() == null || p.getLabel().isBlank() ? p.getName() : p.getLabel();
            if (p.isCollected()) {
                filled.add(label + "：" + p.getValue());
            } else {
                missing.add(label);
            }
        }
        if (!filled.isEmpty()) sb.append("已记下 ").append(String.join("，", filled)).append("。");
        if (!missing.isEmpty()) sb.append("还需要：").append(String.join("、", missing)).append("。");
        sb.append("填好后会先给您确认，不会直接提交。");
        return sb.toString();
    }
}
