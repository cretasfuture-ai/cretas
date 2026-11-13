package com.cretas.aims.service.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges {@link CachePurgeEvent} Spring application events to {@link CachePurgeService}.
 *
 * <p>Uses {@code AFTER_COMMIT} phase — purge fires only after the publishing transaction
 * successfully commits. This is intentional: if the underlying config save rolls back, we
 * must NOT have invalidated cache (would force unnecessary re-computation for unchanged data).
 *
 * <p>The {@code fallbackExecution=true} ensures the listener runs even when there's no
 * transaction context (e.g. when published from a {@code @PostConstruct} or test code that
 * doesn't open a transaction).
 *
 * <p>{@code NOT_SUPPORTED} must stay: at AFTER_COMMIT the publisher's just-committed transaction is
 * still bound to the thread, so {@code purge}'s {@code @Transactional} (REQUIRED) would join it and the
 * {@code @Modifying} DELETE would throw {@code TransactionRequiredException} ("Executing an update/delete
 * query"), which the catch below turns into a warn: the config is saved, the stale cache survives.
 * Suspending lets {@code purge} open and commit its own transaction. Guarded by
 * {@code CachePurgeEventListenerAfterCommitContractTest}.
 *
 * <p>Wiring for sister chats:
 * <pre>
 * &#64;Autowired ApplicationEventPublisher events;
 *
 * &#64;Transactional
 * public AIIntentConfig saveIntentConfig(AIIntentConfig cfg) {
 *     AIIntentConfig saved = repo.save(cfg);
 *     events.publishEvent(CachePurgeEvent.routing(saved.getFactoryId(), saved.getIntentCode(),
 *             "intent-config-save"));
 *     return saved;
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CachePurgeEventListener {

    private final CachePurgeService cachePurgeService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onCachePurgeRequested(CachePurgeEvent event) {
        try {
            int deleted = cachePurgeService.purge(event);
            log.debug("[CachePurgeEventListener] purged {} rows for {}", deleted, event);
        } catch (Exception e) {
            // never fail the publisher's commit due to a cache invalidation hiccup — log and
            // continue. Cache will self-heal via TTL or next manual purge.
            log.warn("[CachePurgeEventListener] purge failed for {}: {}", event, e.getMessage());
        }
    }
}
