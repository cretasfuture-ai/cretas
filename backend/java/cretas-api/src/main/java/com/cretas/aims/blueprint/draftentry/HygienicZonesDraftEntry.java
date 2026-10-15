package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.hygieniczone.HygienicZoneDraft;
import com.cretas.aims.repository.hygieniczone.HygienicZoneDraftRepository;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftCommand;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code hygienic-zones}: hygienic zones and flows → {@link HygienicZoneDraftCommand} (materialize takes its own {@link AuthenticatedOperator}).
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class HygienicZonesDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final HygienicZoneDraftCommand command;
    private final HygienicZoneDraftRepository drafts;

    public HygienicZonesDraftEntry(ObjectMapper mapper, HygienicZoneDraftCommand command, HygienicZoneDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return HygienicZoneDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return HygienicZoneDraftCommand.REQUIRED_PERMISSION;
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
        HygienicZoneDraft probe = new HygienicZoneDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getZoneCount() + " 个卫生分区", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is ignored. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }
}
