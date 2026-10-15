package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraft;
import com.cretas.aims.repository.equipmentplan.EquipmentCalibrationPlanDraftRepository;
import com.cretas.aims.service.equipmentplandraft.EquipmentCalibrationPlanDraftCommand;
import com.cretas.aims.service.equipmentplandraft.EquipmentCalibrationPlanDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.equipmentplandraft.EquipmentCalibrationPlanDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code equipment-calibration}: equipment calibration and maintenance plans → {@link EquipmentCalibrationPlanDraftCommand}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class EquipmentCalibrationDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final EquipmentCalibrationPlanDraftCommand command;
    private final EquipmentCalibrationPlanDraftRepository drafts;

    public EquipmentCalibrationDraftEntry(ObjectMapper mapper, EquipmentCalibrationPlanDraftCommand command, EquipmentCalibrationPlanDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return EquipmentCalibrationPlanDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return EquipmentCalibrationPlanDraftCommand.REQUIRED_PERMISSION;
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
        EquipmentCalibrationPlanDraft probe = new EquipmentCalibrationPlanDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), null, row.getPlanCount() + " 个设备计划", row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is not used. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }
}
