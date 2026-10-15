package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.roletrainingdraft.RoleTrainingDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.roletrainingdraft.RoleTrainingDraftRepository;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftCommand;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** {@code accounts}: positions, training and access review → {@link RoleTrainingDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class AccountsDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final RoleTrainingDraftCommand command;
    private final RoleTrainingDraftRepository drafts;
    private final CanonicalFactoryBlueprintService blueprints;

    public AccountsDraftEntry(ObjectMapper mapper, RoleTrainingDraftCommand command,
                              RoleTrainingDraftRepository drafts,
                              CanonicalFactoryBlueprintService blueprints) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
        this.blueprints = blueprints;
    }

    @Override
    public String capabilityId() {
        return RoleTrainingDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return RoleTrainingDraftCommand.REQUIRED_PERMISSION;
    }

    @Override
    protected Object preview(DraftRequest request) {
        requireCurrentSource(request);
        return command.preview(request);
    }

    @Override
    protected Object materialize(DraftRequest request, DraftOperator operator) {
        requireCurrentSource(request);
        return command.materialize(request,
                new AuthenticatedOperator(operator.actor(), operator.factoryId(), operator.permissions()));
    }

    private void requireCurrentSource(DraftRequest request) {
        BlueprintResponse current = blueprints.current(request.factoryId());
        if (current == null || !Objects.equals(request.factoryId(), current.getFactoryId())
                || !Objects.equals(request.blueprintId(), current.getId())
                || current.getVersion() == null
                || request.blueprintVersion() != current.getVersion().longValue()
                || !("DRAFT".equals(current.getLifecycleState())
                     || "VALIDATED".equals(current.getLifecycleState()))
                || !sourceMatches(request.sourceDigest(), current)) {
            throw new BusinessException(409, "岗位草稿来源与当前蓝图不一致，请刷新蓝图后重新预览")
                    .withCode("ACCOUNTS_BLUEPRINT_SOURCE_MISMATCH").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
        }
    }

    private static boolean sourceMatches(String requested, BlueprintResponse current) {
        try {
            return Objects.equals(requested, BlueprintSourceDigest.of(current));
        } catch (IllegalArgumentException missingPayload) {
            return false;
        }
    }

    @Override
    public Object readBack(String factoryId, String draftId) {
        return command.readBack(factoryId, draftId);
    }

    @Override
    public Optional<List<WrittenDraft>> written(WrittenScope scope) {
        RoleTrainingDraft probe = new RoleTrainingDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getPositionCount() + " 个岗位", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId, expectedDesiredDigest);
    }
}
