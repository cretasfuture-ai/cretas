package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.tracerecalldraft.TraceRecallPlanDraft;
import com.cretas.aims.repository.tracerecalldraft.TraceRecallPlanDraftRepository;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftCommand;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.tracerecalldraft.TraceRecallPlanDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code trace-recall}: trace and recall plan → {@link TraceRecallPlanDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class TraceRecallDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final TraceRecallPlanDraftCommand command;
    private final TraceRecallPlanDraftRepository drafts;

    public TraceRecallDraftEntry(ObjectMapper mapper, TraceRecallPlanDraftCommand command, TraceRecallPlanDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return TraceRecallPlanDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return TraceRecallPlanDraftCommand.REQUIRED_PERMISSION;
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
        TraceRecallPlanDraft probe = new TraceRecallPlanDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getSegmentCount() + " 段追溯链路", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command names its recovery entry {@code recovery(factoryId, draftId, expectedDesiredDigest)}. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recovery(factoryId, draftId, expectedDesiredDigest);
    }
}
