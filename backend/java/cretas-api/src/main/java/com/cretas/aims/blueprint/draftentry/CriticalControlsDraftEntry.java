package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.criticalcontroldraft.CriticalControlDraft;
import com.cretas.aims.repository.criticalcontroldraft.CriticalControlDraftRepository;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftCommand;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.criticalcontroldraft.CriticalControlDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code critical-controls}: CCP limits and monitoring pinned to a hazard analysis → {@link CriticalControlDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class CriticalControlsDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final CriticalControlDraftCommand command;
    private final CriticalControlDraftRepository drafts;

    public CriticalControlsDraftEntry(ObjectMapper mapper, CriticalControlDraftCommand command, CriticalControlDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return CriticalControlDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return CriticalControlDraftCommand.REQUIRED_PERMISSION;
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
        CriticalControlDraft probe = new CriticalControlDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getItemCount() + " 个关键控制点", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is not used. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }
}
