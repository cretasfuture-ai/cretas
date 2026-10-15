package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.qualityreleasedraft.QualityReleaseTemplateDraft;
import com.cretas.aims.repository.qualityreleasedraft.QualityReleaseTemplateDraftRepository;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftCommand;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.qualityreleasedraft.QualityReleaseTemplateDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code quality-release}: inspection and release template → {@link QualityReleaseTemplateDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class QualityReleaseDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final QualityReleaseTemplateDraftCommand command;
    private final QualityReleaseTemplateDraftRepository drafts;

    public QualityReleaseDraftEntry(ObjectMapper mapper, QualityReleaseTemplateDraftCommand command, QualityReleaseTemplateDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return QualityReleaseTemplateDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return QualityReleaseTemplateDraftCommand.REQUIRED_PERMISSION;
    }

    @Override
    protected Object preview(DraftRequest request) {
        return command.preview(request);
    }

    @Override
    protected Object materialize(DraftRequest request, DraftOperator operator) {
        return command.materialize(request,
                new AuthenticatedOperator(operator.actor(), operator.factoryId(), operator.permissions()));
    }

    @Override
    public Object readBack(String factoryId, String draftId) {
        return command.readBack(factoryId, draftId);
    }

    @Override
    public Optional<List<WrittenDraft>> written(WrittenScope scope) {
        QualityReleaseTemplateDraft probe = new QualityReleaseTemplateDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getItemCount() + " 个检验放行项", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command names its recovery entry {@code recovery(factoryId, draftId, expectedDesiredDigest)}. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recovery(factoryId, draftId, expectedDesiredDigest);
    }
}
