package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationCenterReceipt;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleActivationReceipt;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleExecutionStatus;
import com.cretas.aims.entity.blueprintactivation.FactoryActivationExecution;
import com.cretas.aims.entity.blueprintactivation.FactoryActivationExecutionModule;
import com.cretas.aims.repository.blueprintactivation.FactoryActivationExecutionModuleRepository;
import com.cretas.aims.repository.blueprintactivation.FactoryActivationExecutionRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable {@link FactoryActivationExecutionStore} backed by two typed tables.
 *
 * <p>{@link #claim} is the whole point of this class. It inserts the reservation row in its own
 * {@code REQUIRES_NEW} transaction so the uniqueness constraint decides the race immediately and
 * visibly, rather than at the end of the caller's transaction when the module commands have
 * already run. A duplicate-key violation is the expected losing outcome, not an error to
 * propagate.</p>
 *
 * <p>This is storage only. It registers no activation command and exposes no endpoint, so its
 * existence does not make activation reachable in production.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class JpaFactoryActivationExecutionStore implements FactoryActivationExecutionStore {
    static final String CLAIMED = "CLAIMED";

    private final FactoryActivationExecutionRepository executions;
    private final FactoryActivationExecutionModuleRepository modules;
    private final TransactionTemplate claimTransaction;

    public JpaFactoryActivationExecutionStore(
            FactoryActivationExecutionRepository executions,
            FactoryActivationExecutionModuleRepository modules,
            PlatformTransactionManager transactionManager) {
        this.executions = executions;
        this.modules = modules;
        this.claimTransaction = new TransactionTemplate(transactionManager);
        this.claimTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActivationCenterReceipt> find(String factoryId, String idempotencyKey) {
        return executions.findByFactoryIdAndIdempotencyKey(factoryId, idempotencyKey)
                // A CLAIMED row is a reservation, not a result. Returning it as a receipt would
                // report an execution that never finished as if it had.
                .filter(row -> !CLAIMED.equals(row.getStatus()))
                .map(this::toReceipt);
    }

    @Override
    public boolean claim(String factoryId, String idempotencyKey, String requestDigest,
                         long executedBy) {
        return claim(factoryId, idempotencyKey, requestDigest,
                new ActorRef(AuthenticatedActorType.FACTORY_USER, executedBy));
    }

    @Override
    public boolean claim(String factoryId, String idempotencyKey, String requestDigest,
                         ActorRef executedActor) {
        return claim(factoryId, idempotencyKey, requestDigest, null, null, executedActor);
    }

    @Override
    public boolean claim(String factoryId, String idempotencyKey, String requestDigest,
                         ActorRef requestedActor, ActorRef approvedActor, ActorRef executedActor) {
        java.util.Objects.requireNonNull(executedActor, "executedActor");
        // The catch has to sit OUTSIDE the transaction boundary. Catching the duplicate-key
        // violation inside the transaction still leaves it marked rollback-only, so the commit
        // then throws UnexpectedRollbackException and the losing caller fails with an infrastructure
        // error instead of a clean "no". Hence an explicit REQUIRES_NEW template rather than an
        // annotation on this method: the inner transaction rolls back (there is nothing to keep),
        // and the failure is handed to us here, after it has ended.
        try {
            claimTransaction.executeWithoutResult(status ->
                    executions.saveAndFlush(FactoryActivationExecution.builder()
                            .id(UUID.randomUUID().toString())
                            .factoryId(factoryId)
                            .idempotencyKey(idempotencyKey)
                            .requestDigest(requestDigest)
                            .status(CLAIMED)
                            .executedBy(executedActor.factoryUserId())
                            .executedActorType(executedActor.type())
                            .executedActorId(executedActor.id())
                            .requestedBy(requestedActor == null ? null : requestedActor.factoryUserId())
                            .requestedActorType(requestedActor == null ? null : requestedActor.type())
                            .requestedActorId(requestedActor == null ? null : requestedActor.id())
                            .approvedBy(approvedActor == null ? null : approvedActor.factoryUserId())
                            .approvedActorType(approvedActor == null ? null : approvedActor.type())
                            .approvedActorId(approvedActor == null ? null : approvedActor.id())
                            .businessWriteCount(0)
                            .successfulCount(0)
                            .failedCount(0)
                            .blockedCount(0)
                            .build()));
            return true;
        } catch (DataIntegrityViolationException violation) {
            // Only the idempotency key losing its race means "someone else holds this". Any other
            // integrity violation — a column overflow, a broken foreign key — would otherwise be
            // reported as a lost race, and the caller would go looking for a concurrent execution
            // that does not exist while the real fault stayed invisible.
            if (!isIdempotencyRace(violation)) {
                throw violation;
            }
            return false;
        }
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public ActivationCenterReceipt save(ActivationCenterReceipt receipt) {
        FactoryActivationExecution row = executions
                .findByFactoryIdAndIdempotencyKey(receipt.factoryId(), receipt.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Activation execution was never claimed: " + receipt.idempotencyKey()));
        if (!CLAIMED.equals(row.getStatus())) {
            throw new IllegalStateException(
                    "Activation execution already finalised: " + receipt.idempotencyKey());
        }
        if (!row.getRequestDigest().equals(receipt.requestDigest())) {
            throw new IllegalStateException(
                    "Activation receipt does not match the claimed payload: "
                            + receipt.idempotencyKey());
        }
        requireSameClaimedActor("requester", actor(row.getRequestedActorType(), row.getRequestedActorId()), receipt.requestedActor());
        requireSameClaimedActor("approver", actor(row.getApprovedActorType(), row.getApprovedActorId()), receipt.approvedActor());
        requireSameClaimedActor("executor", actor(row.getExecutedActorType(), row.getExecutedActorId()), receipt.executedActor());
        row.setPlanId(receipt.planId());
        row.setSchemaVersion(receipt.schemaVersion());
        row.setStatus(receipt.status());
        if (receipt.requestedActor() == null) row.setRequestedBy(receipt.requestedBy());
        else row.setRequestedActor(receipt.requestedActor());
        if (receipt.approvedActor() == null) row.setApprovedBy(receipt.approvedBy());
        else row.setApprovedActor(receipt.approvedActor());
        if (receipt.executedActor() != null && !receipt.executedActor().equals(
                actor(row.getExecutedActorType(), row.getExecutedActorId()))) {
            throw new IllegalStateException("Activation receipt actor does not match claimed identity");
        }
        row.setSuccessfulCount(receipt.successfulCount());
        row.setFailedCount(receipt.failedCount());
        row.setBlockedCount(receipt.blockedCount());
        row.setBusinessWriteCount(receipt.originalBusinessWriteCount());
        row.setRecoverySummary(receipt.recoverySummary());
        FactoryActivationExecution saved = executions.saveAndFlush(row);

        List<FactoryActivationExecutionModule> moduleRows = new ArrayList<>();
        List<ModuleActivationReceipt> values = receipt.modules();
        for (int ordinal = 0; ordinal < values.size(); ordinal++) {
            ModuleActivationReceipt module = values.get(ordinal);
            moduleRows.add(FactoryActivationExecutionModule.builder()
                    .id(UUID.randomUUID().toString())
                    .executionId(saved.getId())
                    .factoryId(saved.getFactoryId())
                    .ordinal(ordinal)
                    .capabilityId(module.capabilityId())
                    .status(module.status().name())
                    .commandReceiptId(blankToNull(module.commandReceiptId()))
                    .targetRuntimeId(blankToNull(module.targetRuntimeId()))
                    .activeRevision(module.activeRevision())
                    .activeDigest(blankToNull(module.activeDigest()))
                    .businessWriteCount(module.businessWriteCount())
                    .failureCode(blankToNull(module.failureCode()))
                    .failureMessage(blankToNull(module.failureMessage()))
                    .recovery(blankToNull(module.recovery()))
                    .build());
        }
        modules.saveAllAndFlush(moduleRows);
        return receipt;
    }

    private ActivationCenterReceipt toReceipt(FactoryActivationExecution row) {
        List<ModuleActivationReceipt> moduleReceipts = modules
                .findByExecutionIdAndFactoryIdOrderByOrdinal(row.getId(), row.getFactoryId())
                .stream()
                .map(module -> new ModuleActivationReceipt(module.getCapabilityId(),
                        ModuleExecutionStatus.valueOf(module.getStatus()),
                        module.getCommandReceiptId(), module.getTargetRuntimeId(),
                        module.getActiveRevision() == null ? 0L : module.getActiveRevision(),
                        module.getActiveDigest(), module.getBusinessWriteCount(),
                        module.getFailureCode(), module.getFailureMessage(), module.getRecovery()))
                .toList();
        return new ActivationCenterReceipt(FactoryBlueprintActivationModels.RECEIPT_SCHEMA,
                row.getFactoryId(), row.getPlanId(), row.getRequestDigest(),
                row.getIdempotencyKey(), row.getStatus(),
                actorId(row.getRequestedActorId(), row.getRequestedBy()),
                actorId(row.getApprovedActorId(), row.getApprovedBy()),
                actorId(row.getExecutedActorId(), row.getExecutedBy()),
                moduleReceipts,
                row.getSuccessfulCount() == null ? 0 : row.getSuccessfulCount(),
                row.getFailedCount() == null ? 0 : row.getFailedCount(),
                row.getBlockedCount() == null ? 0 : row.getBlockedCount(),
                row.getBusinessWriteCount() == null ? 0 : row.getBusinessWriteCount(),
                0, false, row.getRecoverySummary(),
                "NOT_PUBLISHED", "NOT_PUBLISHED", "NOT_PUBLISHED", "NOT_PUBLISHED",
                actor(row.getRequestedActorType(), row.getRequestedActorId()),
                actor(row.getApprovedActorType(), row.getApprovedActorId()),
                actor(row.getExecutedActorType(), row.getExecutedActorId()));
    }

    private static ActorRef actor(AuthenticatedActorType type, Long id) {
        return type == null || id == null ? null : new ActorRef(type, id);
    }

    private static void requireSameClaimedActor(String role, ActorRef claimed, ActorRef supplied) {
        if (claimed != null && !claimed.equals(supplied)) {
            throw new IllegalStateException("Activation receipt " + role + " does not match claimed identity");
        }
    }

    private static long actorId(Long typedId, Long legacyId) {
        return typedId != null ? typedId : legacyId == null ? 0L : legacyId;
    }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT)
                    .contains("uk_factory_activation_execution_idempotency")) {
                return true;
            }
        }
        return false;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
