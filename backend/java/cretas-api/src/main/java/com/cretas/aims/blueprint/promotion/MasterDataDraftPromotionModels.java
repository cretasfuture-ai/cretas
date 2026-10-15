package com.cretas.aims.blueprint.promotion;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.cretas.aims.security.AuthenticatedActorType;

/** Wire models of the master-data DRAFT promotion entry (products / materials / processes). */
public final class MasterDataDraftPromotionModels {

    public static final String SCHEMA_VERSION = "master-data-draft-promotion/v1";
    public static final String ACTION_CREATE = "CREATE";
    public static final String READY = "READY";
    public static final String BLOCKED = "BLOCKED";

    private MasterDataDraftPromotionModels() {
    }

    /**
     * Why a preview cannot be executed. {@code existingLiveId} / {@code existingLiveName} name the live
     * row that already holds the natural key, when that is the reason.
     */
    public record Blocker(String code, String message, String nextStep,
                          String existingLiveId, String existingLiveName) {
        static Blocker of(String code, String message, String nextStep) {
            return new Blocker(code, message, nextStep, null, null);
        }
    }

    /**
     * Read-only preview. {@code before} contains the current live material for UPDATE and is null for CREATE.
     * {@code after} is exactly the set of live fields the execution writes and reads
     * back. {@code expiresAt} is enforced: the digest carries the preview window, and execution accepts
     * the current and the previous window only.
     */
    public record PromotionPreview(String schemaVersion, String status, String action, String capabilityId,
                                   String draftId, String naturalKey, Object before, Map<String, Object> after,
                                   List<Blocker> blockers, String promotionPreviewDigest,
                                   LocalDateTime expiresAt) {
    }

    public record PreviewRequest(BigDecimal taxIncludedUnitPrice, Map<String, String> packagingRoles) {
        public PreviewRequest(BigDecimal taxIncludedUnitPrice) {
            this(taxIncludedUnitPrice, null);
        }
    }

    public record ExecuteRequest(String confirmedPromotionDigest, String idempotencyKey,
                                 BigDecimal taxIncludedUnitPrice, Map<String, String> packagingRoles) {
        public ExecuteRequest(String confirmedPromotionDigest, String idempotencyKey) {
            this(confirmedPromotionDigest, idempotencyKey, null, null);
        }
        public ExecuteRequest(String confirmedPromotionDigest, String idempotencyKey, BigDecimal taxIncludedUnitPrice) {
            this(confirmedPromotionDigest, idempotencyKey, taxIncludedUnitPrice, null);
        }
    }

    /**
     * {@code readback} is read from the live table, never echoed from the request or the preview.
     * {@code sideWrites} names the extra live rows this call wrote before the main one (materials: 「新建物料大类
     * 原料（001）」); {@code liveWriteCount} counts them too. A replay wrote nothing, so both are empty / 0 —
     * the promotion record has no column for them, so a replay cannot restate what the original call wrote.
     */
    public record PromotionReceipt(String schemaVersion, String promotionId, String status, String capabilityId,
                                   String draftId, String liveTargetId, boolean replayed, int liveWriteCount,
                                   Map<String, Object> readback, String liveReadbackDigest, List<String> sideWrites) {
        public PromotionReceipt {
            sideWrites = List.copyOf(sideWrites);
        }
    }

    public record PromotionRecord(String promotionId, String capabilityId, String draftId, String status,
                                  String action, String naturalKey, String liveTargetId,
                                  String promotionPreviewDigest, String liveReadbackDigest,
                                  String failureCode, Long operatorId, String blueprintId,
                                  Long blueprintVersion, String sourceDigest, LocalDateTime createdAt,
                                  LocalDateTime updatedAt, AuthenticatedActorType actorType, Long actorId) {
    }

    public record PromotionList(String schemaVersion, String blueprintId, Integer blueprintVersion,
                                String sourceDigest, List<PromotionRecord> promotions) {
    }
}
