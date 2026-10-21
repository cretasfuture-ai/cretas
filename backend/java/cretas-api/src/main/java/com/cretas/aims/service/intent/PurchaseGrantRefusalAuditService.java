package com.cretas.aims.service.intent;

import com.cretas.aims.entity.AIAuditLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Domain refusal event, independent of the failed grant execution transaction. */
@Service
public class PurchaseGrantRefusalAuditService {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> record(String requestedFactory, String attemptedGrantId, RuntimeException refusal) {
        if (!(refusal instanceof BusinessException)
                && !(refusal instanceof PurchaseTaskGrantService.GrantRejectedException)) {
            throw new IllegalArgumentException("An unknown outcome is not a confirmed grant refusal");
        }
        var actor = AuthenticatedActor.fromCurrentRequest()
                .filter(a -> a.type() == AuthenticatedActorType.FACTORY_USER)
                .filter(a -> a.id() != null && a.id() > 0 && a.factoryId() != null && !a.factoryId().isBlank());
        if (actor.isEmpty()) return Optional.empty();
        var current = actor.get();
        boolean sameFactory = Objects.equals(current.factoryId(), requestedFactory);
        String correlation = "grant-refusal:" + UUID.randomUUID();
        String grant = sameFactory && attemptedGrantId != null && attemptedGrantId.matches("preview-[1-9][0-9]{0,18}")
                ? attemptedGrantId : "WITHHELD";
        String reason = sameFactory ? "EXECUTION_REFUSED" : "REQUEST_SCOPE_MISMATCH";
        String detail = refusal.getMessage();
        if (detail == null) detail = "执行已拒绝";
        // Domain errors contain fixed guard messages; never persist the purchase command or proof.
        String message = reason + ": " + detail;
        if (message.length() > 500) message = message.substring(0, 500);
        var event = AIAuditLog.builder().factoryId(current.factoryId()).userId(current.id())
                .questionType("grant_refusal").question("operation=DEFERRED_EXECUTE;attemptedGrant=" + grant)
                .sessionId(correlation).isSuccess(false).errorMessage(message)
                .consumedQuota(false).quotaCost(0).cacheHit(false).build();
        entityManager.persist(event);
        entityManager.flush();
        return Optional.of(correlation);
    }
}
