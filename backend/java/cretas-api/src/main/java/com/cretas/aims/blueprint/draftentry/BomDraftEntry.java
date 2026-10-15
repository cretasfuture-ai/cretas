package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.Assembly;
import com.cretas.aims.blueprint.bomassembly.BomAssemblyModels.AssemblyBlocker;
import com.cretas.aims.blueprint.bomassembly.BomCompileRequestAssembler;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.service.bomdraft.BomRecipeDraftCommand;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftIssue;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels.DraftPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code bom}: recipe pinned to a Workflow revision → {@link BomRecipeDraftCommand}, with the command's
 * request built on the server by {@link BomCompileRequestAssembler}.
 *
 * <p>Trust boundary: the body ({@link BomDraftRequest}) carries the blueprint lineage and the user's choices
 * (output, change reason, one row per Workflow raw-material port with category / quantity / yield / optional).
 * The product, the Workflow revision pin, the materials, the Workflow node and edge ids, the units and every
 * snapshot digest come from the live tables — a body cannot send one (unknown fields are refused). The command
 * still re-validates the assembled request in full.</p>
 *
 * <p>Blueprint check: as for the Workflow entry, the body's blueprint id (compared with the URL by the
 * controller), version and {@code sourceDigest} must be the factory's current DRAFT/VALIDATED blueprint.</p>
 *
 * <p>What preview answers: the panel's contract — top-level {@code status}, {@code issues} and
 * {@code previewDigest}. Issue targets are in the user's vocabulary: the command speaks of {@code items[i]},
 * the user filled {@code lines[i]}.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class BomDraftEntry extends TypedCapabilityDraftEntry<BomDraftRequest> {

    private static final Set<String> CURRENT_BLUEPRINT_STATES = Set.of("DRAFT", "VALIDATED");

    /**
     * What preview answers. {@code preview} is the command's own preview (absent when the assembler could not
     * build a request); {@code issues} is what the panel lists.
     */
    public record BomDraftPreviewView(String status, String previewDigest, List<DraftIssue> issues,
                                      DraftPreview preview) {
        public BomDraftPreviewView {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private final BomRecipeDraftCommand command;
    private final BomRecipeDraftRepository drafts;
    private final BomCompileRequestAssembler assembler;
    private final CanonicalFactoryBlueprintService blueprints;

    public BomDraftEntry(ObjectMapper mapper, BomRecipeDraftCommand command, BomRecipeDraftRepository drafts,
                         BomCompileRequestAssembler assembler, CanonicalFactoryBlueprintService blueprints) {
        super(mapper, BomDraftRequest.class);
        this.command = command;
        this.drafts = drafts;
        this.assembler = assembler;
        this.blueprints = blueprints;
    }

    @Override
    public String capabilityId() {
        return BomRecipeDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return BomRecipeDraftCommand.REQUIRED_PERMISSION;
    }

    @Override
    protected Object preview(BomDraftRequest request) {
        requireCurrentBlueprint(request);
        Assembly assembly = assemble(request);
        if (assembly.blocked()) {
            return new BomDraftPreviewView("BLOCKED", null, assembly.blockers().stream()
                    .map(b -> new DraftIssue(b.code(), b.target(), b.message(), b.nextStep())).toList(), null);
        }
        DraftPreview preview = command.preview(assembly.request());
        return new BomDraftPreviewView(preview.status(), preview.previewDigest(),
                preview.issues().stream().map(BomDraftEntry::inUsersVocabulary).toList(), preview);
    }

    @Override
    protected Object materialize(BomDraftRequest request, DraftOperator operator) {
        requireCurrentBlueprint(request);
        Assembly assembly = assemble(request);
        if (assembly.blocked()) {
            AssemblyBlocker first = assembly.blockers().getFirst();
            throw new BusinessException(409, assembly.blockers().stream().map(AssemblyBlocker::message)
                    .collect(Collectors.joining("；")))
                    .withCode(first.code()).withHint(first.nextStep()).withHintTarget(first.target());
        }
        return command.materialize(assembly.request(),
                new AuthenticatedOperator(operator.actor(), operator.factoryId(), operator.permissions()));
    }

    private Assembly assemble(BomDraftRequest request) {
        return assembler.assemble(request.factoryId(), request.blueprintId(), request.blueprintVersion(),
                request.sourceDigest(), request.choices(), request.confirmedPreviewDigest(), request.idempotencyKey(),
                request.operatorId());
    }

    /** {@code items[2].standardQuantity} → {@code lines[2].standardQuantity}: the row the user filled. */
    private static DraftIssue inUsersVocabulary(DraftIssue issue) {
        String target = issue.target() == null ? "" : issue.target();
        return target.startsWith("items[")
                ? new DraftIssue(issue.code(), "lines[" + target.substring("items[".length()), issue.message(), issue.recovery())
                : issue;
    }

    @Override
    public Object readBack(String factoryId, String draftId) {
        return command.readBack(factoryId, draftId);
    }

    @Override
    public Optional<List<WrittenDraft>> written(WrittenScope scope) {
        BomRecipeDraft probe = new BomRecipeDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), row.getTargetProductRefId(), "配方 " + row.getItemCount() + " 项物料", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is not used. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }

    /** The body must name the factory's current DRAFT/VALIDATED blueprint, version and source digest. */
    private void requireCurrentBlueprint(BomDraftRequest request) {
        BlueprintResponse blueprint = blueprints.current(request.factoryId());
        if (blueprint == null || !Objects.equals(request.blueprintId(), blueprint.getId())
                || blueprint.getVersion() == null || request.blueprintVersion() != blueprint.getVersion()
                || !CURRENT_BLUEPRINT_STATES.contains(blueprint.getLifecycleState())
                || blueprint.getSourcePayload() == null) {
            throw new BusinessException(409, "请求里的蓝图不是本厂当前处于草稿或已校验状态的蓝图版本，请刷新蓝图后重新预填")
                    .withCode("BOM_DRAFT_BLUEPRINT_NOT_CURRENT").withHintTarget("blueprintVersion").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
        }
        if (!Objects.equals(request.sourceDigest(), BlueprintSourceDigest.of(blueprint))) {
            throw new BusinessException(409, "请求的蓝图血缘(sourceDigest)与当前蓝图不一致，蓝图内容已变化，请重新预填")
                    .withCode("BOM_DRAFT_SOURCE_DIGEST_MISMATCH").withHintTarget("sourceDigest").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
        }
    }
}
