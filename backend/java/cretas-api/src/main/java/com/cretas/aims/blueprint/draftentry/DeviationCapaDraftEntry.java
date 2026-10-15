package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.deviationcapadraft.DeviationCapaTemplateDraft;
import com.cretas.aims.repository.deviationcapadraft.DeviationCapaTemplateDraftRepository;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftCommand;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.deviationcapadraft.DeviationCapaTemplateDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code deviation-capa}: deviation and CAPA template → {@link DeviationCapaTemplateDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class DeviationCapaDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final DeviationCapaTemplateDraftCommand command;
    private final DeviationCapaTemplateDraftRepository drafts;

    public DeviationCapaDraftEntry(ObjectMapper mapper, DeviationCapaTemplateDraftCommand command, DeviationCapaTemplateDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return DeviationCapaTemplateDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return DeviationCapaTemplateDraftCommand.REQUIRED_PERMISSION;
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
        DeviationCapaTemplateDraft probe = new DeviationCapaTemplateDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getRuleCount() + " 条偏差处置规则", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command names its recovery entry {@code recovery(factoryId, draftId, expectedDesiredDigest)}. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recovery(factoryId, draftId, expectedDesiredDigest);
    }
}
