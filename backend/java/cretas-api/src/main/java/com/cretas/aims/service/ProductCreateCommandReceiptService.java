package com.cretas.aims.service;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** Durable, locator-bound references for the governed product_create command. */
@Service
@RequiredArgsConstructor
public class ProductCreateCommandReceiptService {

    public static final String TOOL_NAME = "product_create";
    public static final String DESCRIPTOR_VERSION = "2.0.0";
    public static final String ENTITY_TYPE = "PRODUCT_TYPE";
    public static final String PRODUCT_CREATED = "PRODUCT_CREATED";
    public static final String COPY_COMMITTED = "COPY_COMMITTED";
    public static final String READBACK_VERIFIED = "READBACK_VERIFIED";

    private static final ThreadLocal<CommandScope> CURRENT_SCOPE = new ThreadLocal<>();

    private final ToolExecutionIdempotencyRepository repository;
    private final ProductTypeService productTypeService;
    private final ProductWorkProcessService productWorkProcessService;

    @PersistenceContext
    private EntityManager entityManager;

    public static CommandScope openScope(
            String tenantId,
            String businessType,
            PrincipalType principalType,
            String principalId,
            String toolName,
            String descriptorVersion,
            String idempotencyKeyHash,
            String commandDigest,
            String confirmationFingerprint) {
        if (CURRENT_SCOPE.get() != null) {
            throw new IllegalStateException("product_create receipt scope is nested");
        }
        CommandScope scope = new CommandScope(
                require(tenantId), require(businessType),
                java.util.Objects.requireNonNull(principalType, "principalType"),
                require(principalId), require(toolName), require(descriptorVersion),
                require(idempotencyKeyHash), require(commandDigest), require(confirmationFingerprint));
        if (!TOOL_NAME.equals(scope.toolName()) || !DESCRIPTOR_VERSION.equals(scope.descriptorVersion())) {
            throw new IllegalArgumentException("product_create receipt scope tool/version mismatch");
        }
        CURRENT_SCOPE.set(scope);
        return scope;
    }

    public static boolean hasScope() {
        return CURRENT_SCOPE.get() != null;
    }

    public static CommandScope currentScope() {
        return CURRENT_SCOPE.get();
    }

    public static void requireScope() {
        if (CURRENT_SCOPE.get() == null) {
            throw new IllegalStateException("product_create receipt scope is required");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProductCreated(String productId) {
        advanceOrAttach(productId, PRODUCT_CREATED, null);
    }

    /** Product creation and its initial durable reference share one transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductTypeDTO createProductAndAttach(String factoryId, ProductTypeDTO dto) {
        ProductTypeDTO created = productTypeService.createProductType(factoryId, dto);
        advanceOrAttach(created.getId(), PRODUCT_CREATED, null);
        return created;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCopyCommitted(String productId) {
        advanceOrAttach(productId, COPY_COMMITTED, PRODUCT_CREATED);
    }

    /** Chain copy and PRODUCT_CREATED -> COPY_COMMITTED CAS are one transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int copyPreparedAndAdvance(
            String factoryId,
            String sourceProductId,
            String targetProductId,
            ProductWorkProcessService.PreparedChain preparedChain) {
        int copied = productWorkProcessService.copyPreparedChain(
                factoryId, sourceProductId, targetProductId, preparedChain);
        advanceOrAttach(targetProductId, COPY_COMMITTED, PRODUCT_CREATED);
        return copied;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReadbackVerified(String productId) {
        advanceOrAttach(productId, READBACK_VERIFIED, COPY_COMMITTED);
    }

    /** Fresh readback verification and COPY_COMMITTED -> READBACK_VERIFIED CAS. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verifyPreparedReadbackAndAdvance(
            String factoryId,
            String targetProductId,
            ProductWorkProcessService.PreparedChain preparedChain) {
        productWorkProcessService.verifyPreparedChainReadback(factoryId, targetProductId, preparedChain);
        advanceOrAttach(targetProductId, READBACK_VERIFIED, COPY_COMMITTED);
    }

    /** Empty-chain readback and both monotonic receipt stages share one transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verifyEmptyReadbackAndAdvance(String factoryId, String targetProductId) {
        productWorkProcessService.verifyEmptyChainReadback(factoryId, targetProductId);
        advanceOrAttach(targetProductId, COPY_COMMITTED, PRODUCT_CREATED);
        advanceOrAttach(targetProductId, READBACK_VERIFIED, COPY_COMMITTED);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ReceiptSnapshot> readCurrent() {
        CommandScope scope = requireAndGetScope();
        // Bulk CAS updates intentionally bypass the persistence context.  Clear before
        // the owner-scoped read so a surrounding OSIV/outer transaction cannot replay
        // a stale managed receipt row as if it were the committed terminal state.
        if (entityManager != null) {
            entityManager.clear();
        }
        return repository.findByFullProductCreateLocator(
                        scope.tenantId(), scope.businessType(), scope.principalType(), scope.principalId(),
                        scope.toolName(), scope.descriptorVersion(), scope.idempotencyKeyHash(),
                        scope.commandDigest(), scope.confirmationFingerprint())
                .map(ReceiptSnapshot::from);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ReplayDecision classifyTerminal(IntentPreviewToken token) {
        requireScope();
        Optional<ReceiptSnapshot> snapshot = readCurrent();
        if (token == null) {
            return ReplayDecision.reject("产品创建确认凭证缺失", "PRODUCT_CREATE_RECEIPT_MISSING");
        }
        if (token.getStatus() == IntentPreviewToken.TokenStatus.PENDING) {
            if (token.isExpired()) {
                return ReplayDecision.reject("确认令牌已过期，拒绝执行", "INVALID_CONFIRMATION_PROOF");
            }
            return ReplayDecision.newExecution(snapshot.orElse(null));
        }
        if (snapshot.isEmpty()) {
            return ReplayDecision.reject("产品创建结果凭证缺失，拒绝重新执行", "PRODUCT_CREATE_RECEIPT_MISSING");
        }
        ReceiptSnapshot row = snapshot.get();
        if (row.state() == ToolExecutionIdempotencyRecord.State.SUCCEEDED
                && ToolExecutionStatus.SUCCEEDED == row.outcomeStatus()
                && READBACK_VERIFIED.equals(row.stage())
                && row.entityId() != null) {
            return ReplayDecision.replay(row);
        }
        if (row.state() == ToolExecutionIdempotencyRecord.State.IN_PROGRESS
                || row.state() == ToolExecutionIdempotencyRecord.State.IN_DOUBT) {
            return ReplayDecision.unknown(row);
        }
        // A terminal failed reservation is still an exact ledger match.  Let the
        // Gateway re-check the current policy/audit path; the controller must not
        // turn a pre-read into an authorization bypass or an automatic retry.
        if (row.state() == ToolExecutionIdempotencyRecord.State.FAILED) {
            return ReplayDecision.newExecution(row);
        }
        return ReplayDecision.reject("产品创建结果尚未达到可安全回放状态", "PRODUCT_CREATE_RECEIPT_INCOMPLETE");
    }

    private void advanceOrAttach(String productId, String nextStage, String expectedStage) {
        CommandScope scope = requireAndGetScope();
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required for receipt reference");
        }
        if (expectedStage == null) {
            int attached = repository.attachProductCreateReference(
                    scope.tenantId(), scope.businessType(), scope.principalType(), scope.principalId(),
                    scope.toolName(), scope.descriptorVersion(), scope.idempotencyKeyHash(),
                    scope.commandDigest(), scope.confirmationFingerprint(),
                    ToolExecutionIdempotencyRecord.State.IN_PROGRESS, ENTITY_TYPE, productId, nextStage);
            if (attached != 1) {
                throw receiptFailure("无法记录产品创建结果，拒绝继续执行");
            }
            return;
        }
        ReceiptSnapshot current = readCurrent().orElseThrow(
                () -> receiptFailure("产品创建结果引用缺失，无法推进执行阶段"));
        if (!ENTITY_TYPE.equals(current.entityType()) || !productId.equals(current.entityId())) {
            throw receiptFailure("产品创建结果引用与当前确认命令不匹配");
        }
        int advanced = repository.advanceProductCreateReference(
                current.recordId(), scope.tenantId(), scope.businessType(), scope.principalType(), scope.principalId(),
                scope.toolName(), scope.descriptorVersion(), scope.idempotencyKeyHash(),
                scope.commandDigest(), scope.confirmationFingerprint(),
                ToolExecutionIdempotencyRecord.State.IN_PROGRESS, ENTITY_TYPE, productId,
                expectedStage, nextStage);
        if (advanced != 1) {
            throw receiptFailure("产品创建结果阶段推进失败，需人工核对");
        }
    }

    private CommandScope requireAndGetScope() {
        requireScope();
        return CURRENT_SCOPE.get();
    }

    private static IllegalStateException receiptFailure(String message) {
        return new IllegalStateException(message);
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("receipt locator value is required");
        }
        return value;
    }

    public record CommandScope(
            String tenantId,
            String businessType,
            PrincipalType principalType,
            String principalId,
            String toolName,
            String descriptorVersion,
            String idempotencyKeyHash,
            String commandDigest,
            String confirmationFingerprint) implements AutoCloseable {
        @Override
        public void close() {
            if (CURRENT_SCOPE.get() == this) {
                CURRENT_SCOPE.remove();
            }
        }
    }

    public record ReceiptSnapshot(
            String recordId,
            ToolExecutionIdempotencyRecord.State state,
            ToolExecutionStatus outcomeStatus,
            String entityType,
            String entityId,
            String stage,
            LocalDateTime updatedAt) {
        static ReceiptSnapshot from(ToolExecutionIdempotencyRecord row) {
            return new ReceiptSnapshot(row.getId(), row.getState(), row.getOutcomeStatus(),
                    row.getResultEntityType(), row.getResultEntityId(),
                    row.getResultReferenceStage(), row.getUpdatedAt());
        }
    }

    public record ReplayDecision(Kind kind, ReceiptSnapshot receipt, String message, String code) {
        static ReplayDecision newExecution(ReceiptSnapshot receipt) {
            return new ReplayDecision(Kind.NEW_EXECUTION, receipt, null, null);
        }

        static ReplayDecision replay(ReceiptSnapshot receipt) {
            return new ReplayDecision(Kind.REPLAY, receipt, null, null);
        }

        static ReplayDecision unknown(ReceiptSnapshot receipt) {
            return new ReplayDecision(Kind.UNKNOWN, receipt,
                    "产品创建结果仍需人工核对，系统不会自动重试", "PRODUCT_CREATE_OUTCOME_UNKNOWN");
        }

        static ReplayDecision reject(String message, String code) {
            return new ReplayDecision(Kind.REJECT, null, message, code);
        }

        public enum Kind { NEW_EXECUTION, REPLAY, UNKNOWN, REJECT }
    }
}
