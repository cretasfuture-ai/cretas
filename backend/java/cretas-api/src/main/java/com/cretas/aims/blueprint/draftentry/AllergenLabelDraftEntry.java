package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.allergenlabeldraft.AllergenLabelDraft;
import com.cretas.aims.repository.allergenlabeldraft.AllergenLabelDraftRepository;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftCommand;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.allergenlabeldraft.AllergenLabelDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code allergen-label}: allergen declarations and changeovers → {@link AllergenLabelDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class AllergenLabelDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final AllergenLabelDraftCommand command;
    private final AllergenLabelDraftRepository drafts;

    public AllergenLabelDraftEntry(ObjectMapper mapper, AllergenLabelDraftCommand command, AllergenLabelDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return AllergenLabelDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return AllergenLabelDraftCommand.REQUIRED_PERMISSION;
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
        AllergenLabelDraft probe = new AllergenLabelDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getDeclarationCount() + " 条过敏原声明、" + row.getChangeoverCount() + " 条换线清场", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId, expectedDesiredDigest);
    }
}
