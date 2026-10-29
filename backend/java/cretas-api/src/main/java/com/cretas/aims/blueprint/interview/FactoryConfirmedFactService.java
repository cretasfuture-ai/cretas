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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers what a factory has already confirmed in the conversational build, so a later interview
 * reuses it and asks only about differences (S2-01), and so a different answer never silently
 * replaces a confirmed fact: it becomes a pending conflict the administrator settles item by item,
 * with the whole history kept (S2-02).
 *
 * <p>Every read and write is scoped by factory id. Nothing here is guessed: only a user decision
 * (structured answer, exact option, explicit confirmation) creates a CONFIRMED fact. A value the AI
 * extracted from free text, or carried over from an earlier version, is only PENDING_CONFIRM until
 * the user confirms it, and is never reused as if it were confirmed.</p>
 */
@Slf4j
@Service
public class FactoryConfirmedFactService {
    public static final String SOURCE_USER_ANSWER = "USER_ANSWER";
    public static final String CHOICE_KEEP_CONFIRMED = "KEEP_CONFIRMED";
    public static final String CHOICE_USE_NEW = "USE_NEW";
    public static final String REUSE_REASON_CODE = "REUSED_CONFIRMED_FACT";

    private final FactoryConfirmedFactRepository facts;
    /** One new transaction per carried-over item, so a duplicate insert only loses that item. Null in unit tests. */
    private final TransactionTemplate perItem;

    @Autowired
    public FactoryConfirmedFactService(FactoryConfirmedFactRepository facts, PlatformTransactionManager transactions) {
        this.facts = facts;
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.perItem = template;
    }

    public FactoryConfirmedFactService(FactoryConfirmedFactRepository facts) {
        this.facts = facts;
        this.perItem = null;
    }

    /** An item waiting for the user to confirm it. */
    public record PendingItem(String field, String label, String value, String sourceRef) { }

    /** One item whose new answer differs from what the factory had confirmed. */
    public record Conflict(String field, String label, String confirmedValue, String newValue,
            String confirmedSource, String newSource, String pendingId) { }

    /**
     * Records answers. userDecided = the user made the decision (structured answer, exact option);
     * false = the AI extracted it from free text.
     * <ul>
     *   <li>Same as the confirmed value: nothing written.</li>
     *   <li>Different from a confirmed value: a pending conflict, the confirmed fact is untouched.</li>
     *   <li>No confirmed value, user decided: becomes CONFIRMED (a waiting PENDING_CONFIRM with the same
     *       value is promoted, a different one is superseded).</li>
     *   <li>No confirmed value, extracted: only a PENDING_CONFIRM row, awaiting the user.</li>
     * </ul>
     */
    @Transactional
    public List<Conflict> recordAnswers(String factoryId, String sessionId, String actor,
            Map<String, String> accepted, Map<String, String> labels, boolean userDecided) {
        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, String> entry : accepted.entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            if (field == null || value == null || value.isBlank()) continue;
            Optional<FactoryConfirmedFact> confirmed =
                    facts.findByFactoryIdAndFieldKeyAndStatus(factoryId, field, FactoryConfirmedFact.CONFIRMED);
            String label = labels == null ? field : labels.getOrDefault(field, field);
            if (confirmed.isEmpty()) {
                recordWithoutConfirmed(factoryId, sessionId, actor, field, label, value, userDecided);
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

    private void recordWithoutConfirmed(String factoryId, String sessionId, String actor, String field,
            String label, String value, boolean userDecided) {
        Optional<FactoryConfirmedFact> waiting =
                facts.findByFactoryIdAndFieldKeyAndStatus(factoryId, field, FactoryConfirmedFact.PENDING_CONFIRM);
        if (waiting.isPresent() && Objects.equals(waiting.get().getValueText(), value)) {
            if (userDecided) {
                FactoryConfirmedFact row = waiting.get();
                row.setStatus(FactoryConfirmedFact.CONFIRMED);
                row.setDecidedBy(actor);
                row.setDecidedAt(LocalDateTime.now());
                facts.save(row);
            }
            return;
        }
        waiting.ifPresent(row -> {
            row.setStatus(FactoryConfirmedFact.SUPERSEDED);
            row.setDecidedBy(actor);
            row.setDecidedAt(LocalDateTime.now());
            facts.saveAndFlush(row);
        });
        int next = facts.findFirstByFactoryIdAndFieldKeyOrderByVersionNoDescCreatedAtDesc(factoryId, field)
                .map(row -> row.getVersionNo() + 1).orElse(1);
        facts.save(row(factoryId, field, label, value,
                userDecided ? FactoryConfirmedFact.CONFIRMED : FactoryConfirmedFact.PENDING_CONFIRM,
                next, sessionId, null, actor));
    }

    /** Items carried over or extracted that this session has not answered yet, awaiting confirmation. */
    @Transactional(readOnly = true)
    public List<PendingItem> pendingFor(String factoryId, FactoryAssemblyConversationSession session) {
        Map<String, String> collected = session.getCollectedParameters();
        List<PendingItem> items = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        session.getRequiredParameters().forEach(rp -> labels.put(rp.getName(), rp.getLabel()));
        for (FactoryConfirmedFact row : facts.findByFactoryIdAndStatus(factoryId, FactoryConfirmedFact.PENDING_CONFIRM)) {
            if (!labels.containsKey(row.getFieldKey())) continue;
            String have = collected.get(row.getFieldKey());
            if (have != null && !have.isBlank()) continue;
            items.add(new PendingItem(row.getFieldKey(), labels.get(row.getFieldKey()), row.getValueText(),
                    row.getSourceRef()));
        }
        return items;
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

    /** A fact a previous interview version confirmed, found in that session's own receipts. */
    public record PriorFact(String field, String label, String value, String sessionId, boolean userDecided) { }

    /**
     * Carries facts from earlier interview versions into the history, only for items that have no
     * history row yet (an existing row is never touched, so this cannot become a silent overwrite).
     * A value the user decided on stays CONFIRMED; a value the AI extracted from free text becomes
     * PENDING_CONFIRM. Per item and duplicate-tolerant: a concurrent restart that inserted the same
     * item first only costs this item, never the whole carry-over.
     */
    public int adoptPriorFacts(String factoryId, List<PriorFact> prior) {
        int adopted = 0;
        for (PriorFact fact : prior) {
            if (fact.field() == null || fact.value() == null || fact.value().isBlank()) continue;
            try {
                Boolean written = perItem == null
                        ? adoptOne(factoryId, fact) : perItem.execute(status -> adoptOne(factoryId, fact));
                if (Boolean.TRUE.equals(written)) adopted++;
            } catch (DataIntegrityViolationException duplicate) {
                log.info("carry-over of {} skipped: another restart already wrote it (factory={})",
                        fact.field(), factoryId);
            }
        }
        return adopted;
    }

    private boolean adoptOne(String factoryId, PriorFact fact) {
        if (facts.findFirstByFactoryIdAndFieldKeyOrderByVersionNoDescCreatedAtDesc(factoryId, fact.field())
                .isPresent()) return false;
        facts.saveAndFlush(row(factoryId, fact.field(), fact.label(), fact.value(),
                fact.userDecided() ? FactoryConfirmedFact.CONFIRMED : FactoryConfirmedFact.PENDING_CONFIRM, 1,
                fact.sessionId(), null, "SYSTEM:carried-from-prior-version"));
        return true;
    }

    /** The value currently confirmed for one item, if any. */
    @Transactional(readOnly = true)
    public Optional<String> confirmedValue(String factoryId, String field) {
        return facts.findByFactoryIdAndFieldKeyAndStatus(factoryId, field, FactoryConfirmedFact.CONFIRMED)
                .map(FactoryConfirmedFact::getValueText);
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
