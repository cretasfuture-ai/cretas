package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.alertpolicydraft.AlertPolicyDraft;
import com.cretas.aims.repository.alertpolicydraft.AlertPolicyDraftRepository;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftCommand;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.alertpolicydraft.AlertPolicyDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code alerts}: alert policies (pinned to the module config) → {@link AlertPolicyDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class AlertsDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final AlertPolicyDraftCommand command;
    private final AlertPolicyDraftRepository drafts;

    public AlertsDraftEntry(ObjectMapper mapper, AlertPolicyDraftCommand command, AlertPolicyDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return AlertPolicyDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return AlertPolicyDraftCommand.REQUIRED_PERMISSION;
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
        AlertPolicyDraft probe = new AlertPolicyDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getPolicyCount() + " 条告警策略", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId, expectedDesiredDigest);
    }
}
