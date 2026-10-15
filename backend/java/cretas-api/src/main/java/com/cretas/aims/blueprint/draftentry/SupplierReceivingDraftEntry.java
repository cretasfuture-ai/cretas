package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraft;
import com.cretas.aims.repository.supplierreceiving.SupplierReceivingPolicyDraftRepository;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftCommand;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code supplier-receiving}: supplier receiving policies → {@link SupplierReceivingPolicyDraftCommand} (materialize takes its own {@link AuthenticatedOperator}).
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class SupplierReceivingDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final SupplierReceivingPolicyDraftCommand command;
    private final SupplierReceivingPolicyDraftRepository drafts;

    public SupplierReceivingDraftEntry(ObjectMapper mapper, SupplierReceivingPolicyDraftCommand command, SupplierReceivingPolicyDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return SupplierReceivingPolicyDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return SupplierReceivingPolicyDraftCommand.REQUIRED_PERMISSION;
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
        SupplierReceivingPolicyDraft probe = new SupplierReceivingPolicyDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getPolicyCount() + " 条收货策略", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is ignored. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }
}
