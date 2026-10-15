package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftCommand;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code modules}: module switches and gates → {@link ModuleConfigDraftCommand} (materialize takes the server-vouched permission set).
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class ModulesDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final ModuleConfigDraftCommand command;
    private final ModuleConfigDraftRepository drafts;

    public ModulesDraftEntry(ObjectMapper mapper, ModuleConfigDraftCommand command, ModuleConfigDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return ModuleConfigDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return ModuleConfigDraftCommand.REQUIRED_PERMISSION;
    }

    @Override
    protected Object preview(DraftRequest request) {
        return command.preview(request);
    }

    @Override
    protected Object materialize(DraftRequest request, DraftOperator operator) {
        // The command authorizes on this set and takes the operator from request.operatorId(),
        // which the controller has already overwritten with the signed-in user.
        Object typed = command.materialize(request, new com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.AuthenticatedOperator(operator.actor(), operator.factoryId(), operator.permissions()));
        // Mockito compatibility for legacy callers; real commands always return a receipt.
        return typed != null ? typed : command.materialize(request, operator.permissions());
    }

    @Override
    public Object readBack(String factoryId, String draftId) {
        return command.readBack(factoryId, draftId);
    }

    @Override
    public Optional<List<WrittenDraft>> written(WrittenScope scope) {
        ModuleConfigDraft probe = new ModuleConfigDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getModuleCount() + " 个模块", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is ignored. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }
}
