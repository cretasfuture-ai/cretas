package com.cretas.aims.blueprint.promotion;

import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.Blocker;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One capability's half of a promotion: how to read its DRAFT row, what a live collision looks like,
 * which live service creates the row, and how to read the live row back.
 *
 * <p>Implementations never modify the DRAFT row: they read it (optionally under a row lock) and
 * detach it. The live write always goes through the module's own live service, with the
 * server-resolved operator as {@code createdBy} wherever the live table records one.</p>
 */
interface DraftPromoter {

    String capabilityId();

    /** Three-segment permission checked by {@code BlueprintDraftOperatorResolver}. */
    String permission();

    /**
     * The DRAFT row of {@code factoryId}, or empty. {@code forUpdate} takes a row lock that is held
     * until the surrounding transaction ends; it does not bump the row's lock version.
     */
    Optional<DraftSnapshot> read(String factoryId, String draftId, boolean forUpdate);

    /** A live row that already holds this draft's natural key (or the live uniqueness it implies). */
    Optional<LiveRow> findLiveConflict(String factoryId, DraftSnapshot draft);

    /** Capability-specific reasons the draft cannot be created as-is (e.g. packaging without a price). */
    List<Blocker> capabilityBlockers(DraftSnapshot draft);

    /** Updating live master data is opt-in per capability. */
    default boolean supportsUpdate() { return false; }

    /** Immutable live state reviewed by the operator and included in an UPDATE digest. */
    default Map<String, Object> before(DraftSnapshot draft) { return Map.of(); }

    default String update(String factoryId, DraftSnapshot draft, ActorRef actor) {
        throw new IllegalStateException("This capability does not support UPDATE promotion");
    }

    default Long expectedCreatedBy(DraftSnapshot draft, ActorRef actor) { return actor.factoryUserId(); }

    /** Fields an UPDATE must preserve even though they are not editable draft fields. */
    default Map<String, Object> preservedFields(DraftSnapshot draft) { return Map.of(); }

    /** Adds request-only confirmation inputs to the detached snapshot used by preview and execution. */
    default DraftSnapshot withPromotionInput(DraftSnapshot draft, Object input) {
        return draft;
    }

    /** Replays must reject changed request-only confirmation facts without writing again. */
    default void validateReplayInput(String factoryId, String liveTargetId, Object input) {
    }

    /** Creates the live row through the live service and returns its id. */
    String create(String factoryId, DraftSnapshot draft, ActorRef actor);

    /** Source compatibility for existing unit doubles; production promotion uses the typed overload. */
    default String create(String factoryId, DraftSnapshot draft, long operatorId) {
        return create(factoryId, draft, new ActorRef(AuthenticatedActorType.FACTORY_USER, operatorId));
    }

    /**
     * The extra live rows {@link #create} writes before the main one (materials: a planned L1 category),
     * one human-readable line each, for the receipt. Derived from the same snapshot {@link #create} acts
     * on, so it is exact once {@link #create} has returned.
     */
    default List<String> sideWrites(DraftSnapshot draft) {
        return List.of();
    }

    /**
     * Keys of {@link DraftSnapshot#after()} that state the plan to the user ("将新建物料大类 …") rather than
     * a live field: they are part of the preview digest (so a changed plan makes a confirmation stale) but
     * have no live column to read back, so the readback comparison skips them.
     */
    default Set<String> previewOnlyFields() {
        return Set.of();
    }

    /** Reads the live row back from the database (not from the persistence context). */
    LiveReadback readBack(String factoryId, String liveTargetId);

    /** Adds immutable source-only fields to the live readback without pretending they are live columns. */
    default LiveReadback enrichReadback(DraftSnapshot draft, LiveReadback readback) {
        return readback;
    }

    /** Whether the live table records who created the row (work_processes does not). */
    boolean recordsCreatedBy();

    record DraftSnapshot(String id, String factoryId, String blueprintId, Long blueprintVersion,
                         String sourceDigest, String desiredDigest, Long lockVersion, String status,
                         String naturalKey, String targetLiveId, Map<String, Object> after, Object source) {
    }

    record LiveRow(String id, String name) {
    }
    /** {@code createdBy} is {@code null} when the live table has no such column. */
    record LiveReadback(String id, Map<String, Object> fields, Long createdBy,
                        Map<String, Object> proofFields) {
        public LiveReadback(String id, Map<String, Object> fields, Long createdBy) {
            this(id, fields, createdBy, Map.of());
        }
        public LiveReadback {
            proofFields = proofFields == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(proofFields));
        }
    }
}
