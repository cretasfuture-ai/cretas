package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraft;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftRepository;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftCommand;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code hazard-analysis}: HACCP hazard analysis pinned to a BOM draft → {@link HazardAnalysisDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class HazardAnalysisDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final HazardAnalysisDraftCommand command;
    private final HazardAnalysisDraftRepository drafts;

    public HazardAnalysisDraftEntry(ObjectMapper mapper, HazardAnalysisDraftCommand command, HazardAnalysisDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return HazardAnalysisDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return HazardAnalysisDraftCommand.REQUIRED_PERMISSION;
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
        HazardAnalysisDraft probe = new HazardAnalysisDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), row.getRouteProductCode(),
                        "危害分析 " + row.getRouteProductCode() + "（" + row.getItemCount() + " 项）", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is not used. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }
}
