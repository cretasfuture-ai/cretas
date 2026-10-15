package com.cretas.aims.blueprint.promotion;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver;
import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.blueprint.draftentry.BlueprintSourceDigest;
import com.cretas.aims.blueprint.promotion.DraftPromoter.DraftSnapshot;
import com.cretas.aims.blueprint.promotion.DraftPromoter.LiveReadback;
import com.cretas.aims.blueprint.promotion.DraftPromoter.LiveRow;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.Blocker;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.ExecuteRequest;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PreviewRequest;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PromotionList;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PromotionPreview;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PromotionReceipt;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PromotionRecord;
import com.cretas.aims.entity.promotion.MasterDataDraftPromotion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.promotion.MasterDataDraftPromotionRepository;
import com.cretas.aims.security.ActorRef;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Promotes a master-data DRAFT row (products / materials / processes) into its live table — CREATE
 * and material UPDATE, one authenticated operator, no change to the DRAFT row.
 *
 * <p>Order inside every entry point: capability must be open, then the operator is resolved on the
 * server (platform admins, other factories and members without the permission are refused here),
 * and only then is any blueprint, DRAFT or promotion row read.</p>
 *
 * <p>Execution, in one transaction: idempotency lookup (replay) -> DRAFT row lock -> idempotency
 * re-check -> already-promoted check -> preview recomputed under the lock, refused unless it equals
 * the confirmed digest -> CLAIMED row (the database decides the idempotency race) -> live write
 * through the module's live service with the server operator as {@code createdBy} -> live row read
 * back from the database and compared field by field with the preview -> PROMOTED. Any failure rolls
 * the whole transaction back: no live row and no PROMOTED row survive it.</p>
 *
 * <p>Product/process UPDATE and undoing a promotion remain unsupported. The
 * intended undo is to deactivate the live row and create a corrective DRAFT — never a physical
 * delete.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class MasterDataDraftPromotionService {

    /** Width of one preview window; a preview stays executable for the rest of its window plus one more. */
    static final long WINDOW_MINUTES = 15;
    private static final String DIGEST_PATTERN = "[0-9a-f]{64}";

    private final Map<String, DraftPromoter> promoters;
    private final BlueprintDraftOperatorResolver operators;
    private final CanonicalFactoryBlueprintService blueprints;
    private final MasterDataDraftPromotionRepository records;
    private final Clock clock;

    @Autowired
    public MasterDataDraftPromotionService(List<DraftPromoter> promoters, BlueprintDraftOperatorResolver operators,
                                           CanonicalFactoryBlueprintService blueprints,
                                           MasterDataDraftPromotionRepository records) {
        this(promoters, operators, blueprints, records, Clock.systemDefaultZone());
    }

    MasterDataDraftPromotionService(List<DraftPromoter> promoters, BlueprintDraftOperatorResolver operators,
                                    CanonicalFactoryBlueprintService blueprints,
                                    MasterDataDraftPromotionRepository records, Clock clock) {
        Map<String, DraftPromoter> indexed = new LinkedHashMap<>();
        for (DraftPromoter promoter : promoters) {
            if (indexed.putIfAbsent(promoter.capabilityId(), promoter) != null) {
                throw new IllegalStateException("Duplicate draft promoter: " + promoter.capabilityId());
            }
        }
        this.promoters = Map.copyOf(indexed);
        this.operators = operators;
        this.blueprints = blueprints;
        this.records = records;
        this.clock = clock;
    }

    /** Capabilities whose promotion entry is open. */
    public Set<String> openCapabilities() {
        return new TreeSet<>(promoters.keySet());
    }

    @Transactional(readOnly = true)
    public PromotionPreview preview(HttpServletRequest http, String factoryId, String blueprintId,
                                    String capabilityId, String draftId) {
        return preview(http, factoryId, blueprintId, capabilityId, draftId, null);
    }

    @Transactional(readOnly = true)
    public PromotionPreview preview(HttpServletRequest http, String factoryId, String blueprintId,
                                    String capabilityId, String draftId, PreviewRequest request) {
        DraftPromoter promoter = require(capabilityId);
        operators.resolveDraft(http, factoryId, promoter.permission());
        Lineage lineage = currentLineage(factoryId, blueprintId, capabilityId);
        DraftSnapshot draft = ownedDraft(promoter, lineage, draftId, false);
        draft = promoter.withPromotionInput(draft, request == null ? null
                : promotionInput(capabilityId, request.taxIncludedUnitPrice(), request.packagingRoles()));
        Evaluation evaluation = evaluate(promoter, factoryId, draft);
        long window = window();
        return evaluation.toPreview(digest(evaluation, window), expiresAt(window));
    }

    @Transactional
    public PromotionReceipt execute(HttpServletRequest http, String factoryId, String blueprintId,
                                    String capabilityId, String draftId, ExecuteRequest body) {
        DraftPromoter promoter = require(capabilityId);
        DraftOperator operator = operators.resolveDraft(http, factoryId, promoter.permission());
        String confirmed = body == null ? "" : clean(body.confirmedPromotionDigest());
        String idempotencyKey = body == null ? "" : clean(body.idempotencyKey());
        if (!confirmed.matches(DIGEST_PATTERN)) {
            throw fail(400, "请先预览，再确认预览返回的转正摘要", "PROMOTION_CONFIRMED_DIGEST_REQUIRED",
                    "重新预览后用返回的 promotionPreviewDigest 确认");
        }
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 128) {
            throw fail(400, "转正幂等键不能为空且不能超过 128 个字符", "PROMOTION_IDEMPOTENCY_KEY_INVALID",
                    "为这一次确认生成一个稳定的幂等键");
        }

        Optional<MasterDataDraftPromotion> prior = records.findByFactoryIdAndIdempotencyKey(factoryId, idempotencyKey);
        if (prior.isPresent()) return replay(promoter, prior.get(), capabilityId, draftId, confirmed,
                body == null ? null : promotionInput(capabilityId, body.taxIncludedUnitPrice(), body.packagingRoles()));

        Lineage lineage = currentLineage(factoryId, blueprintId, capabilityId);
        DraftSnapshot draft = ownedDraft(promoter, lineage, draftId, true);
        draft = promoter.withPromotionInput(draft, body == null ? null
                : promotionInput(capabilityId, body.taxIncludedUnitPrice(), body.packagingRoles()));

        // Re-checked under the DRAFT row lock: a concurrent execution of the same key that committed
        // while this one waited for the lock is a replay, not a second promotion.
        prior = records.findByFactoryIdAndIdempotencyKey(factoryId, idempotencyKey);
        if (prior.isPresent()) return replay(promoter, prior.get(), capabilityId, draftId, confirmed,
                body == null ? null : promotionInput(capabilityId, body.taxIncludedUnitPrice(), body.packagingRoles()));

        List<MasterDataDraftPromotion> done = promotedRows(factoryId, capabilityId, draft.id());
        if (!done.isEmpty()) {
            MasterDataDraftPromotion existing = done.get(0);
            throw fail(409, "这份草稿已经转正过（正式数据 ID：" + existing.getLiveTargetId() + "），不能重复转正",
                    "PROMOTION_ALREADY_DONE", "查看这份草稿的转正记录；需要改正式数据请走正式数据的编辑流程");
        }

        Evaluation evaluation = evaluate(promoter, factoryId, draft);
        if (!evaluation.blockers().isEmpty()) {
            Blocker first = evaluation.blockers().get(0);
            throw fail(409, first.message(), first.code(), first.nextStep());
        }
        long window = window();
        boolean confirmedIsCurrent = confirmed.equals(digest(evaluation, window))
                || confirmed.equals(digest(evaluation, window - 1));
        if (!confirmedIsCurrent) {
            throw fail(409, "预览之后草稿或正式数据已经变化，或预览已过期，这次确认没有写入任何数据",
                    "PROMOTION_PREVIEW_STALE", "重新预览，核对将写入的内容后再确认");
        }

        MasterDataDraftPromotion row = MasterDataDraftPromotion.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .capabilityId(capabilityId)
                .draftId(draft.id())
                .blueprintId(lineage.blueprintId())
                .blueprintVersion(lineage.blueprintVersion())
                .sourceDigest(lineage.sourceDigest())
                .draftDesiredDigest(draft.desiredDigest())
                .draftLockVersion(draft.lockVersion())
                .action(evaluation.action())
                .naturalKey(draft.naturalKey())
                .promotionPreviewDigest(confirmed)
                .idempotencyKey(idempotencyKey)
                .status(MasterDataDraftPromotion.STATUS_CLAIMED)
                .build();
        row.setActor(operator.actor());
        try {
            row = records.saveAndFlush(row);
        } catch (DataIntegrityViolationException race) {
            // PostgreSQL marks the transaction rollback-only after a unique violation; the caller
            // retries with the same key and reads the winner in a fresh transaction.
            throw fail(409, "同一个转正请求正在被并发处理", "PROMOTION_IDEMPOTENCY_RACE",
                    "用同一个幂等键重试，读取先完成的那一次的回执");
        }

        String liveTargetId = writeLive(promoter, factoryId, draft, operator.actor());
        List<String> sideWrites = promoter.sideWrites(draft);
        LiveReadback readback = promoter.enrichReadback(draft, promoter.readBack(factoryId, liveTargetId));
        requireExactReadback(promoter, evaluation.after(), liveTargetId, readback,
                promoter.expectedCreatedBy(draft, operator.actor()), promoter.preservedFields(draft));

        row.setStatus(MasterDataDraftPromotion.STATUS_PROMOTED);
        row.setLiveTargetId(liveTargetId);
        row.setLiveReadbackDigest(readbackDigest(capabilityId, readback));
        row = records.saveAndFlush(row);
        return receipt(row, false, 1 + sideWrites.size(), readback.fields(), sideWrites);
    }

    @Transactional(readOnly = true)
    public PromotionRecord find(HttpServletRequest http, String factoryId, String blueprintId,
                                String capabilityId, String draftId) {
        DraftPromoter promoter = require(capabilityId);
        operators.resolveDraft(http, factoryId, promoter.permission());
        return records.findByFactoryIdAndCapabilityIdAndDraftIdOrderByCreatedAtDescIdDesc(
                        factoryId, capabilityId, draftId).stream()
                .filter(row -> blueprintId.equals(row.getBlueprintId()))
                .findFirst()
                .map(MasterDataDraftPromotionService::record)
                .orElseThrow(() -> fail(404, "这份草稿还没有转正记录", "PROMOTION_NOT_FOUND",
                        "先预览并确认转正"));
    }

    @Transactional(readOnly = true)
    public PromotionList list(HttpServletRequest http, String factoryId, String blueprintId) {
        DraftOperator operator = operators.resolveDraftForAny(http, factoryId,
                promoters.values().stream().map(DraftPromoter::permission).toList());
        Lineage lineage = currentLineage(factoryId, blueprintId, null);
        Set<String> visible = new TreeSet<>();
        promoters.values().stream().filter(p -> operator.permissions().contains(p.permission()))
                .forEach(p -> visible.add(p.capabilityId()));
        List<PromotionRecord> rows = records
                .findByFactoryIdAndBlueprintIdAndBlueprintVersionAndSourceDigestOrderByCreatedAtDescIdDesc(
                        factoryId, lineage.blueprintId(), lineage.blueprintVersion(), lineage.sourceDigest())
                .stream()
                .filter(row -> visible.contains(row.getCapabilityId()))
                .map(MasterDataDraftPromotionService::record)
                .toList();
        return new PromotionList(MasterDataDraftPromotionModels.SCHEMA_VERSION, lineage.blueprintId(),
                lineage.blueprintVersion().intValue(), lineage.sourceDigest(), rows);
    }

    // ---------------------------------------------------------------------------------------------

    private DraftPromoter require(String capabilityId) {
        DraftPromoter promoter = capabilityId == null ? null : promoters.get(capabilityId);
        if (promoter == null) {
            throw fail(404, "这项能力的草稿转正还没有开放（目前只开放产品、物料、工序）", "PROMOTION_NOT_OPEN",
                    "其它能力的草稿请继续走原有流程");
        }
        return promoter;
    }

    private record Lineage(String factoryId, String blueprintId, Long blueprintVersion, String sourceDigest) {
        boolean owns(DraftSnapshot draft) {
            return factoryId.equals(draft.factoryId()) && blueprintId.equals(draft.blueprintId())
                    && Objects.equals(blueprintVersion, draft.blueprintVersion())
                    && sourceDigest.equals(draft.sourceDigest());
        }
    }

    private Lineage currentLineage(String factoryId, String blueprintId, String capabilityId) {
        BlueprintResponse blueprint = blueprints.current(factoryId);
        if (blueprint == null || blueprint.getId() == null || !blueprint.getId().equals(blueprintId)) {
            throw notCurrent("只能转正本厂当前蓝图下的草稿，请刷新蓝图后重试");
        }
        if (capabilityId != null && !com.cretas.aims.blueprint.draftentry.BlueprintDraftLifecycle.allows(
                blueprint.getLifecycleState(), capabilityId)) {
            throw notCurrent("蓝图当前状态是「" + blueprint.getLifecycleState()
                    + "」，只有草稿或已校验的蓝图下的草稿可以转正");
        }
        if (blueprint.getSourcePayload() == null || blueprint.getVersion() == null) {
            throw notCurrent("当前蓝图还没有保存过的配置内容，没有可以转正的草稿");
        }
        return new Lineage(factoryId, blueprint.getId(), blueprint.getVersion().longValue(),
                BlueprintSourceDigest.of(blueprint));
    }

    private DraftSnapshot ownedDraft(DraftPromoter promoter, Lineage lineage, String draftId, boolean forUpdate) {
        DraftSnapshot draft = promoter.read(lineage.factoryId(), clean(draftId), forUpdate)
                .orElseThrow(() -> notCurrent("本厂当前蓝图下没有这份草稿"));
        if (!lineage.owns(draft)) {
            throw notCurrent("这份草稿不是由本厂当前蓝图生成的，蓝图内容改过之后旧草稿不能转正");
        }
        if (!"DRAFT".equals(draft.status())) {
            throw notCurrent("这份草稿不处于可转正的草稿状态");
        }
        return draft;
    }

    private record Evaluation(String capabilityId, DraftSnapshot draft, String action, Map<String, Object> before, Map<String, Object> after,
                              boolean liveConflict, List<Blocker> blockers) {
        PromotionPreview toPreview(String digest, LocalDateTime expiresAt) {
            return new PromotionPreview(MasterDataDraftPromotionModels.SCHEMA_VERSION,
                    blockers.isEmpty() ? MasterDataDraftPromotionModels.READY : MasterDataDraftPromotionModels.BLOCKED,
                    action, capabilityId, draft.id(), draft.naturalKey(),
                    before.isEmpty() ? null : before, after, blockers, digest, expiresAt);
        }
    }

    private Evaluation evaluate(DraftPromoter promoter, String factoryId, DraftSnapshot draft) {
        List<Blocker> blockers = new ArrayList<>();
        boolean update = !clean(draft.targetLiveId()).isEmpty();
        if (update && !promoter.supportsUpdate()) {
            blockers.add(Blocker.of("PROMOTION_UPDATE_NOT_SUPPORTED",
                    "这份草稿是修改已有正式数据（ID：" + draft.targetLiveId() + "），首期只支持新建",
                    "到正式数据页面直接编辑该条数据"));
        }
        Optional<LiveRow> conflict = promoter.findLiveConflict(factoryId, draft);
        conflict.ifPresent(live -> blockers.add(new Blocker("PROMOTION_LIVE_CONFLICT",
                "正式数据里已经有同一条（ID：" + live.id() + "，名称：" + live.name() + "），不能再新建一条",
                "确认是否就是这一条；需要改动请到正式数据页面编辑", live.id(), live.name())));
        List<MasterDataDraftPromotion> done = promotedRows(factoryId, promoter.capabilityId(), draft.id());
        if (!done.isEmpty()) {
            blockers.add(Blocker.of("PROMOTION_ALREADY_DONE",
                    "这份草稿已经转正过（正式数据 ID：" + done.get(0).getLiveTargetId() + "）",
                    "查看这份草稿的转正记录"));
        }
        blockers.addAll(promoter.capabilityBlockers(draft));
        blockers.sort(Comparator.comparing(Blocker::code));
        return new Evaluation(promoter.capabilityId(), draft, update && promoter.supportsUpdate() ? "UPDATE" : "CREATE",
                update ? promoter.before(draft) : Map.of(), draft.after(), conflict.isPresent(),
                List.copyOf(blockers));
    }

    private List<MasterDataDraftPromotion> promotedRows(String factoryId, String capabilityId, String draftId) {
        return records.findByFactoryIdAndCapabilityIdAndDraftIdAndStatus(
                factoryId, capabilityId, draftId, MasterDataDraftPromotion.STATUS_PROMOTED);
    }

    /**
     * sha256 over: schema, capability, draft id, draft desired digest, draft lock version, action,
     * natural key, whether the live table already holds the key, the fields to be written, and the
     * preview window. A change to any of them makes a confirmed digest stale.
     */
    private String digest(Evaluation evaluation, long window) {
        if ("UPDATE".equals(evaluation.action())) {
            DraftSnapshot draft = evaluation.draft();
            return PromotionDigest.sha256(MasterDataDraftPromotionModels.SCHEMA_VERSION, evaluation.capabilityId(),
                    draft.id(), draft.desiredDigest(), String.valueOf(draft.lockVersion()), "UPDATE",
                    draft.targetLiveId(), draft.naturalKey(), String.valueOf(evaluation.liveConflict()),
                    PromotionDigest.canonicalFields(evaluation.before()), PromotionDigest.canonicalFields(evaluation.after()),
                    "window:" + window);
        }
        return promotionPreviewDigest(evaluation.capabilityId(), evaluation.draft(), evaluation.after(),
                evaluation.liveConflict(), window);
    }

    /** Rebuild the confirmed preview digest from the same detached promoter projection. */
    static String promotionPreviewDigest(String capabilityId, DraftSnapshot draft,
                                         Map<String, Object> after, boolean liveConflict, long window) {
        return PromotionDigest.sha256(MasterDataDraftPromotionModels.SCHEMA_VERSION, capabilityId,
                draft.id(), draft.desiredDigest(), String.valueOf(draft.lockVersion()),
                MasterDataDraftPromotionModels.ACTION_CREATE, draft.naturalKey(),
                String.valueOf(liveConflict), PromotionDigest.canonicalFields(after),
                "window:" + window);
    }

    /** The receipt's own creation time is the authority for its accepted preview windows. */
    static long previewWindow(LocalDateTime createdAt) {
        if (createdAt == null) return Long.MIN_VALUE;
        return Math.floorDiv(createdAt.atZone(ZoneId.systemDefault()).toEpochSecond(), WINDOW_MINUTES * 60);
    }

    static Set<Long> previewWindows(LocalDateTime createdAt) {
        if (createdAt == null) return Set.of();
        return Set.of(previewWindow(createdAt));
    }

    private long window() {
        return Math.floorDiv(clock.instant().getEpochSecond(), WINDOW_MINUTES * 60);
    }

    /** The preview of window {@code w} is accepted in windows {@code w} and {@code w + 1}. */
    private LocalDateTime expiresAt(long window) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond((window + 2) * WINDOW_MINUTES * 60), clock.getZone());
    }

    private String writeLive(DraftPromoter promoter, String factoryId, DraftSnapshot draft, ActorRef actor) {
        try {
            String liveTargetId = clean(draft.targetLiveId()).isEmpty()
                    ? promoter.create(factoryId, draft, actor) : promoter.update(factoryId, draft, actor);
            // Flush through a repository proxy so a constraint violation of the live insert (e.g. a
            // concurrent insert of the same key that committed after the check) surfaces here, as a
            // translated DataIntegrityViolationException, instead of later as a raw 500.
            records.flush();
            return liveTargetId;
        } catch (BusinessException rejected) {
            int status = rejected.getCode() == null ? 400 : rejected.getCode();
            if (status < 400 || status >= 500) throw rejected;
            BusinessException translated = new BusinessException(status, rejected.getMessage())
                    .withCode(rejected.getErrorCode() != null ? rejected.getErrorCode() : "PROMOTION_LIVE_WRITE_REJECTED");
            if (rejected.getActionHint() != null) translated.withHint(rejected.getActionHint());
            if (rejected.getHintTarget() != null) translated.withHintTarget(rejected.getHintTarget());
            throw translated;
        } catch (DataIntegrityViolationException violation) {
            // Only a unique violation means "someone created the same row"; NOT NULL / FK / CHECK
            // failures are defects and must not be dressed up as a business conflict.
            if (!isUniqueViolation(violation)) throw violation;
            throw fail(409, "写入正式数据时发现同一条已被别人新建，这次转正没有写入任何数据",
                    "PROMOTION_LIVE_CONFLICT", "重新预览，确认已存在的那一条");
        }
    }

    private static boolean isUniqueViolation(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private void requireExactReadback(DraftPromoter promoter, Map<String, Object> after, String liveTargetId,
                                      LiveReadback readback, Long expectedCreatedBy, Map<String, Object> preserved) {
        List<String> differing = new ArrayList<>();
        if (readback == null || !Objects.equals(liveTargetId, readback.id())) {
            differing.add("id");
        } else {
            Set<String> keys = new TreeSet<>(after.keySet());
            keys.removeAll(promoter.previewOnlyFields());
            keys.addAll(readback.fields().keySet());
            for (String key : keys) {
                if (!after.containsKey(key) || !readback.fields().containsKey(key)
                        || !PromotionDigest.canonical(after.get(key))
                        .equals(PromotionDigest.canonical(readback.fields().get(key)))) {
                    differing.add(key);
                }
            }
            if (promoter.recordsCreatedBy() && !Objects.equals(readback.createdBy(), expectedCreatedBy)) {
                differing.add("createdBy");
            }
            for (var entry : preserved.entrySet()) {
                if (!readback.proofFields().containsKey(entry.getKey())
                        || !PromotionDigest.canonical(entry.getValue()).equals(PromotionDigest.canonical(readback.proofFields().get(entry.getKey())))) {
                    differing.add("preserved." + entry.getKey());
                }
            }
        }
        if (!differing.isEmpty()) {
            throw fail(500, "正式数据写入后回读与预览不一致（字段：" + String.join("、", differing)
                            + "），本次转正已整体撤回，没有留下任何变更",
                    "PROMOTION_READBACK_MISMATCH", "重新预览；若仍不一致，请把这条草稿和字段名发给管理员排查");
        }
    }

    static String readbackDigest(String capabilityId, LiveReadback readback) {
        if (!readback.proofFields().isEmpty()) {
            return PromotionDigest.sha256(MasterDataDraftPromotionModels.SCHEMA_VERSION, "readback", capabilityId,
                    readback.id(), PromotionDigest.canonicalFields(readback.fields()),
                    readback.createdBy() == null ? null : readback.createdBy().toString(),
                    PromotionDigest.canonicalFields(readback.proofFields()));
        }
        return PromotionDigest.sha256(MasterDataDraftPromotionModels.SCHEMA_VERSION, "readback", capabilityId,
                readback.id(), PromotionDigest.canonicalFields(readback.fields()),
                readback.createdBy() == null ? null : readback.createdBy().toString());
    }

    private static Object promotionInput(String capabilityId, java.math.BigDecimal price,
                                         Map<String, String> packagingRoles) {
        return "bom".equals(capabilityId) ? packagingRoles : price;
    }

    private PromotionReceipt replay(DraftPromoter promoter, MasterDataDraftPromotion row, String capabilityId,
                                    String draftId, String confirmed, Object input) {
        if (!row.getCapabilityId().equals(capabilityId) || !row.getDraftId().equals(clean(draftId))) {
            throw fail(409, "这个幂等键已经用于另一次转正确认", "PROMOTION_IDEMPOTENCY_KEY_CONFLICT",
                    "重新预览，并为新的确认生成新的幂等键");
        }
        // Same key, same draft, a different (newer) preview: the client keys promotions per draft, so
        // this is "this draft was already promoted" (e.g. from another tab) — say that, with the live
        // id, instead of a key conflict whose next step the user cannot take.
        if (!row.getPromotionPreviewDigest().equals(confirmed)) {
            if (MasterDataDraftPromotion.STATUS_PROMOTED.equals(row.getStatus())) {
                throw fail(409, "这份草稿已经转正过（正式数据 ID：" + row.getLiveTargetId() + "），不能重复转正",
                        "PROMOTION_ALREADY_DONE", "查看这份草稿的转正记录；需要改正式数据请走正式数据的编辑流程");
            }
            throw fail(409, "这个幂等键已经用于另一次转正确认", "PROMOTION_IDEMPOTENCY_KEY_CONFLICT",
                    "重新预览，并为新的确认生成新的幂等键");
        }
        if (!MasterDataDraftPromotion.STATUS_PROMOTED.equals(row.getStatus())) {
            throw fail(409, "这个幂等键对应的转正没有完成（状态：" + row.getStatus() + "）",
                    "PROMOTION_NOT_REPLAYABLE", "重新预览，并用新的幂等键确认");
        }
        promoter.validateReplayInput(row.getFactoryId(), row.getLiveTargetId(), input);
        DraftSnapshot sourceDraft = promoter.read(row.getFactoryId(), row.getDraftId(), false).orElse(null);
        LiveReadback readback = promoter.readBack(row.getFactoryId(), row.getLiveTargetId());
        readback = sourceDraft == null ? readback : promoter.enrichReadback(sourceDraft, readback);
        if (readback != null && readback.fields().containsKey("taxIncludedUnitPrice")
                && !("UPDATE".equals(row.getAction()) && input == null)
                && !Objects.equals(PromotionDigest.canonical(input),
                PromotionDigest.canonical(readback.fields().get("taxIncludedUnitPrice")))) {
            throw fail(409, "预览确认中的价格已经变化，这次重复提交没有写入任何数据",
                    "PROMOTION_PREVIEW_STALE", "重新预览，核对含税采购参考价后再确认");
        }
        if ("materials".equals(capabilityId) && "UPDATE".equals(row.getAction()) && sourceDraft == null) {
            throw fail(409, "原转正草稿已不可验证，这次重复确认没有写入任何数据",
                    "PROMOTION_PREVIEW_STALE", "查看正式资料；需要再次修改请重新生成草稿");
        }
        if ("materials".equals(capabilityId) && "UPDATE".equals(row.getAction()) && readback != null
                && Boolean.TRUE.equals(sourceDraft.after().get("classification.packagingCategory"))
                && !readbackDigest(capabilityId, readback).equals(row.getLiveReadbackDigest())) {
            throw fail(409, "正式包材在转正后已改变，这次重复确认没有写入任何数据",
                    "PROMOTION_PREVIEW_STALE", "查看正式包材当前资料；需要再次修改请重新生成草稿");
        }
        return receipt(row, true, 0, readback == null ? null : readback.fields(), List.of());
    }

    private static PromotionReceipt receipt(MasterDataDraftPromotion row, boolean replayed, int writes,
                                            Map<String, Object> readback, List<String> sideWrites) {
        return new PromotionReceipt(MasterDataDraftPromotionModels.SCHEMA_VERSION, row.getId(), row.getStatus(),
                row.getCapabilityId(), row.getDraftId(), row.getLiveTargetId(), replayed, writes,
                readback, row.getLiveReadbackDigest(), sideWrites);
    }

    private static PromotionRecord record(MasterDataDraftPromotion row) {
        return new PromotionRecord(row.getId(), row.getCapabilityId(), row.getDraftId(), row.getStatus(),
                row.getAction(), row.getNaturalKey(), row.getLiveTargetId(), row.getPromotionPreviewDigest(),
                row.getLiveReadbackDigest(), row.getFailureCode(), row.getOperatorId(), row.getBlueprintId(),
                row.getBlueprintVersion(), row.getSourceDigest(), row.getCreatedAt(), row.getUpdatedAt(),
                row.getActorType(), row.getActorId());
    }

    private static BusinessException notCurrent(String message) {
        return fail(409, message, "PROMOTION_DRAFT_NOT_CURRENT", "刷新蓝图与草稿列表后重试");
    }

    private static BusinessException fail(int status, String message, String code, String nextStep) {
        return new BusinessException(status, message).withCode(code).withHint(nextStep);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
