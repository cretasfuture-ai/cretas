package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.emergencydefensedraft.EmergencyDefensePlanDraft;
import com.cretas.aims.repository.emergencydefensedraft.EmergencyDefensePlanDraftRepository;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftCommand;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.emergencydefensedraft.EmergencyDefensePlanDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code emergency-defense}: emergency and food defense plans → {@link EmergencyDefensePlanDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class EmergencyDefenseDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final EmergencyDefensePlanDraftCommand command;
    private final EmergencyDefensePlanDraftRepository drafts;

    public EmergencyDefenseDraftEntry(ObjectMapper mapper, EmergencyDefensePlanDraftCommand command, EmergencyDefensePlanDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return EmergencyDefensePlanDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return EmergencyDefensePlanDraftCommand.REQUIRED_PERMISSION;
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
        EmergencyDefensePlanDraft probe = new EmergencyDefensePlanDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getPlanCount() + " 份应急与食品防护计划", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId, expectedDesiredDigest);
    }
}
