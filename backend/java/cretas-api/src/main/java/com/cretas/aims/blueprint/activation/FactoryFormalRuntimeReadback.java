package com.cretas.aims.blueprint.activation;

import java.util.Objects;
import java.util.function.Supplier;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Read committed formal state outside a request's stale OpenEntityManagerInView context. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class FactoryFormalRuntimeReadback {
    private final TransactionTemplate transaction;
    private final EntityManagerFactory entityManagerFactory;

    public FactoryFormalRuntimeReadback(PlatformTransactionManager manager, EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(true);
    }

    public <T> T read(Supplier<T> operation) {
        // REQUIRES_NEW suspends an active transaction. Without one, JpaTransactionManager
        // can reuse the request-bound OSIV holder, including entities loaded before apply.
        Object requestHolder = !TransactionSynchronizationManager.isActualTransactionActive()
                ? TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory) : null;
        try {
            return transaction.execute(status -> operation.get());
        } finally {
            if (requestHolder != null) {
                TransactionSynchronizationManager.bindResource(entityManagerFactory, requestHolder);
            }
        }
    }
}
