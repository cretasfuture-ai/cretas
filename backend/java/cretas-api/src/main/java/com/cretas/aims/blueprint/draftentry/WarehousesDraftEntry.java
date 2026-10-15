package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.warehousecontrol.WarehouseControlDraft;
import com.cretas.aims.repository.warehousecontrol.WarehouseControlDraftRepository;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftCommand;
import com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code warehouses}: warehouses and cold-chain controls → {@link WarehouseControlDraftCommand} (materialize takes the server-vouched permission set).
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class WarehousesDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final WarehouseControlDraftCommand command;
    private final WarehouseControlDraftRepository drafts;

    public WarehousesDraftEntry(ObjectMapper mapper, WarehouseControlDraftCommand command, WarehouseControlDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return WarehouseControlDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return WarehouseControlDraftCommand.REQUIRED_PERMISSION;
    }

    @Override
    protected Object preview(DraftRequest request) {
        return command.preview(request);
    }

    @Override
    protected Object materialize(DraftRequest request, DraftOperator operator) {
        // The command authorizes on this set and takes the operator from request.operatorId(),
        // which the controller has already overwritten with the signed-in user.
        Object typed = command.materialize(request, new com.cretas.aims.service.warehousecontroldraft.WarehouseControlDraftModels.AuthenticatedOperator(operator.actor(), operator.factoryId(), operator.permissions()));
        // Mockito compatibility for legacy callers; real commands always return a receipt.
        return typed != null ? typed : command.materialize(request, operator.permissions());
    }

    @Override
    public Object readBack(String factoryId, String draftId) {
        return command.readBack(factoryId, draftId);
    }

    @Override
    public Optional<List<WrittenDraft>> written(WrittenScope scope) {
        WarehouseControlDraft probe = new WarehouseControlDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getWarehouseCount() + " 个仓库", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is ignored. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }
}
