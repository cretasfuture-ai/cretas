package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import com.cretas.aims.entity.conversation.FactoryConfirmedFact;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryConfirmedFactRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers what a factory has already confirmed in the conversational build, so a later interview
 * reuses it and asks only about differences (S2-01), and so a different answer never silently
 * replaces a confirmed fact: it becomes a pending conflict the administrator settles item by item,
 * with the whole history kept (S2-02).
 *
 * <p>Every read and write is scoped by factory id. Nothing here is guessed: only answers the user
 * actually gave (accepted structured answers) are recorded, never extraction candidates.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactoryConfirmedFactService {
    public static final String SOURCE_USER_ANSWER = "USER_ANSWER";
    public static final String CHOICE_KEEP_CONFIRMED = "KEEP_CONFIRMED";
    public static final String CHOICE_USE_NEW = "USE_NEW";
    public static final String REUSE_REASON_CODE = "REUSED_CONFIRMED_FACT";

    private final FactoryConfirmedFactRepository facts;

    /** One item whose new answer differs from what the factory had confirmed. */
    public record Conflict(String field, String label, String confirmedValue, String newValue,
            String confirmedSource, String newSource, String pendingId) { }

    /**
     * Records answers the user just gave. Same as the confirmed value: nothing written (it stays the
     * same fact). No confirmed value yet: becomes the confirmed fact. Different: a pending conflict.
     */
    @Transactional
    public List<Conflict> recordAnswers(String factoryId, String sessionId, String actor,
            Map<String, String> accepted, Map<String, String> labels) {
        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, String> entry : accepted.entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            if (field == null || value == null || value.isBlank()) continue;
            Optional<FactoryConfirmedFact> confirmed =
                    facts.findByFactoryIdAndFieldKeyAndStatus(factoryId, field, FactoryConfirmedFact.CONFIRMED);
            String label = labels == null ? field : labels.getOrDefault(field, field);
            if (confirmed.isEmpty()) {
                int next = facts.findFirstByFactoryIdAndFieldKeyOrderByVersionNoDescCreatedAtDesc(factoryId, field)
                        .map(row -> row.getVersionNo() + 1).orElse(1);
                facts.save(row(factoryId, field, label, value, FactoryConfirmedFact.CONFIRMED, next,
                        sessionId, null, actor));
                continue;
            }
            FactoryConfirmedFact current = confirmed.get();
            Optional<FactoryConfirmedFact> pending =
                    facts.findByFactoryIdAndFieldKeyAndStatus(factoryId, field, FactoryConfirmedFact.CONFLICT_PENDING);
            if (Objects.equals(current.getValueText(), value)) {
                // 答案改回了已确认的值: 之前挂着的冲突作废, 不留悬案。
                pending.ifPresent(row -> {
                    row.setStatus(FactoryConfirmedFact.REJECTED);
                    row.setDecidedBy(actor);
                    row.setDecidedAt(LocalDateTime.now());
                    facts.save(row);
                });
                continue;
            }
            FactoryConfirmedFact open = pending.orElse(null);
            if (open != null && Objects.equals(open.getValueText(), value)) {
                conflicts.add(toConflict(current, open));
                continue;
            }
            if (open != null) {
                open.setStatus(FactoryConfirmedFact.REJECTED);
                open.setDecidedBy(actor);
                open.setDecidedAt(LocalDateTime.now());
                facts.saveAndFlush(open);
            }
            int next = facts.findFirstByFactoryIdAndFieldKeyOrderByVersionNoDescCreatedAtDesc(factoryId, field)
                    .map(row -> row.getVersionNo() + 1).orElse(1);
            FactoryConfirmedFact created = facts.save(row(factoryId, field, label, value,
                    FactoryConfirmedFact.CONFLICT_PENDING, next, sessionId, current.getId(), actor));
            conflicts.add(toConflict(current, created));
        }
        return conflicts;
    }

    /**
     * Fills the not-yet-answered items of a new session from what the factory already confirmed,
     * so only differences are asked. Returns the reused field -> value (empty if none).
     */
    @Transactional(readOnly = true)
    public Map<String, String> reusableFor(String factoryId, FactoryAssemblyConversationSession session) {
        Map<String, String> reuse = new LinkedHashMap<>();
        Map<String, String> collected = session.getCollectedParameters();
        Map<String, FactoryConfirmedFact> confirmed = new LinkedHashMap<>();
        for (FactoryConfirmedFact row : facts.findByFactoryIdAndStatus(factoryId, FactoryConfirmedFact.CONFIRMED)) {
            confirmed.put(row.getFieldKey(), row);
        }
        for (FactoryAssemblyConversationSession.RequiredParameter parameter : session.getRequiredParameters()) {
            String name = parameter.getName();
            String have = collected.get(name);
            if (have != null && !have.isBlank()) continue;
            FactoryConfirmedFact fact = confirmed.get(name);
            if (fact != null) reuse.put(name, fact.getValueText());
        }
        return reuse;
    }

    /** Administrator picks the source for one conflicting item. */
    @Transactional
    public FactoryConfirmedFact resolve(String factoryId, String field, String choice, String actor) {
        FactoryConfirmedFact pending = facts
                .findByFactoryIdAndFieldKeyAndStatus(factoryId, field, FactoryConfirmedFact.CONFLICT_PENDING)
                .orElseThrow(() -> new BusinessException(404, "这一项没有待选择的冲突: " + field)
                        .withCode("FACT_CONFLICT_NOT_FOUND"));
        FactoryConfirmedFact current = facts
                .findByFactoryIdAndFieldKeyAndStatus(factoryId, field, FactoryConfirmedFact.CONFIRMED)
                .orElseThrow(() -> new BusinessException(409, "这一项已没有已确认事实，无法选择来源")
                        .withCode("FACT_CONFIRMED_MISSING")
                        .withHint("请刷新「已确认资料」页面后重新作答"));
        LocalDateTime now = LocalDateTime.now();
        if (CHOICE_USE_NEW.equals(choice)) {
            current.setStatus(FactoryConfirmedFact.SUPERSEDED);
            current.setDecidedBy(actor);
            current.setDecidedAt(now);
            // 先让出「每项只有一条已确认」的唯一位置, 再提升新值。
            facts.saveAndFlush(current);
            pending.setStatus(FactoryConfirmedFact.CONFIRMED);
            pending.setSupersedesId(current.getId());
            pending.setDecidedBy(actor);
            pending.setDecidedAt(now);
            return facts.save(pending);
        }
        if (CHOICE_KEEP_CONFIRMED.equals(choice)) {
            pending.setStatus(FactoryConfirmedFact.REJECTED);
            pending.setDecidedBy(actor);
            pending.setDecidedAt(now);
            facts.save(pending);
            return current;
        }
        throw new BusinessException(400, "来源选择只能是 " + CHOICE_KEEP_CONFIRMED + " 或 " + CHOICE_USE_NEW)
                .withCode("FACT_CONFLICT_CHOICE_INVALID");
    }

    @Transactional(readOnly = true)
    public List<FactoryConfirmedFact> history(String factoryId) {
        return facts.findByFactoryIdOrderByFieldKeyAscVersionNoDescCreatedAtDesc(factoryId);
    }

    @Transactional(readOnly = true)
    public List<Conflict> openConflicts(String factoryId) {
        List<Conflict> conflicts = new ArrayList<>();
        for (FactoryConfirmedFact pending : facts.findByFactoryIdAndStatus(
                factoryId, FactoryConfirmedFact.CONFLICT_PENDING)) {
            facts.findByFactoryIdAndFieldKeyAndStatus(factoryId, pending.getFieldKey(), FactoryConfirmedFact.CONFIRMED)
                    .ifPresent(current -> conflicts.add(toConflict(current, pending)));
        }
        return conflicts;
    }

    private static Conflict toConflict(FactoryConfirmedFact current, FactoryConfirmedFact pending) {
        return new Conflict(pending.getFieldKey(), pending.getFieldLabel(), current.getValueText(),
                pending.getValueText(), current.getSourceRef(), pending.getSourceRef(), pending.getId());
    }

    private static FactoryConfirmedFact row(String factoryId, String field, String label, String value,
            String status, int version, String sessionId, String supersedes, String actor) {
        FactoryConfirmedFact fact = new FactoryConfirmedFact();
        fact.setFactoryId(factoryId);
        fact.setFieldKey(field);
        fact.setFieldLabel(label);
        fact.setValueText(value);
        fact.setStatus(status);
        fact.setVersionNo(version);
        fact.setSourceKind(SOURCE_USER_ANSWER);
        fact.setSourceRef("conversation:" + sessionId);
        fact.setSupersedesId(supersedes);
        if (FactoryConfirmedFact.CONFIRMED.equals(status)) {
            fact.setDecidedBy(actor);
            fact.setDecidedAt(LocalDateTime.now());
        }
        return fact;
    }
}
