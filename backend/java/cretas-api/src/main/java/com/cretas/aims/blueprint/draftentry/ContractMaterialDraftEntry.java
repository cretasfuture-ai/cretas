package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.contractmaterialdraft.ContractMaterialPolicyDraft;
import com.cretas.aims.repository.contractmaterialdraft.ContractMaterialPolicyDraftRepository;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftCommand;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.contractmaterialdraft.ContractMaterialPolicyDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code contract-material}: customer-owned material policies → {@link ContractMaterialPolicyDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class ContractMaterialDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final ContractMaterialPolicyDraftCommand command;
    private final ContractMaterialPolicyDraftRepository drafts;

    public ContractMaterialDraftEntry(ObjectMapper mapper, ContractMaterialPolicyDraftCommand command, ContractMaterialPolicyDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return ContractMaterialPolicyDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return ContractMaterialPolicyDraftCommand.REQUIRED_PERMISSION;
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
        ContractMaterialPolicyDraft probe = new ContractMaterialPolicyDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getPolicyCount() + " 条客供料策略", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId, expectedDesiredDigest);
    }
}
