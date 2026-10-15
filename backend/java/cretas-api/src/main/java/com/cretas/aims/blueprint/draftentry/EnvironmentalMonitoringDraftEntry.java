package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraft;
import com.cretas.aims.repository.environmentalmonitoringdraft.EnvironmentalMonitoringTemplateDraftRepository;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftCommand;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.environmentalmonitoringdraft.EnvironmentalMonitoringDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code environmental-monitoring}: environmental monitoring points → {@link EnvironmentalMonitoringDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class EnvironmentalMonitoringDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final EnvironmentalMonitoringDraftCommand command;
    private final EnvironmentalMonitoringTemplateDraftRepository drafts;

    public EnvironmentalMonitoringDraftEntry(ObjectMapper mapper, EnvironmentalMonitoringDraftCommand command, EnvironmentalMonitoringTemplateDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return EnvironmentalMonitoringDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return EnvironmentalMonitoringDraftCommand.REQUIRED_PERMISSION;
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
        EnvironmentalMonitoringTemplateDraft probe = new EnvironmentalMonitoringTemplateDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getPointCount() + " 个环境监测点", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId, expectedDesiredDigest);
    }
}
