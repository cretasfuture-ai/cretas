package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.entity.product.ProductTypeDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.product.ProductTypeDraftRepository;
import com.cretas.aims.service.productdraft.ProductTypeDraftCommand;
import com.cretas.aims.service.productdraft.ProductTypeDraftModels.DraftRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@code products}: product types → {@link ProductTypeDraftCommand} (materialize takes the server-vouched permission set).
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class ProductsDraftEntry extends TypedCapabilityDraftEntry<DraftRequest> {

    private final ProductTypeDraftCommand command;
    private final ProductTypeDraftRepository drafts;

    public ProductsDraftEntry(ObjectMapper mapper, ProductTypeDraftCommand command, ProductTypeDraftRepository drafts) {
        super(mapper, DraftRequest.class);
        this.command = command;
        this.drafts = drafts;
    }

    @Override
    public String capabilityId() {
        return ProductTypeDraftCommand.CAPABILITY_ID;
    }

    @Override
    public String requiredPermission() {
        return ProductTypeDraftCommand.REQUIRED_PERMISSION;
    }

    @Override
    protected Object preview(DraftRequest request) {
        return command.preview(request);
    }

    @Override
    protected Object materialize(DraftRequest request, DraftOperator operator) {
        // The command authorizes on this set and takes the operator from request.operatorId(),
        // which the controller has already overwritten with the signed-in user.
        Object typed = command.materialize(request, new com.cretas.aims.service.productdraft.ProductTypeDraftModels.AuthenticatedOperator(operator.actor(), operator.factoryId(), operator.permissions()));
        // Mockito compatibility for legacy callers; real commands always return a receipt.
        return typed != null ? typed : command.materialize(request, operator.permissions());
    }

    @Override
    public Object readBack(String factoryId, String draftId) {
        // ProductTypeDraftCommand has no readBack.
        throw new BusinessException(404, "这类建厂草稿暂不提供回读")
                .withCode("BLUEPRINT_DRAFT_READBACK_UNSUPPORTED");
    }

    @Override
    public Optional<List<WrittenDraft>> written(WrittenScope scope) {
        ProductTypeDraft probe = new ProductTypeDraft();
        probe.setFactoryId(scope.factoryId());
        probe.setBlueprintId(scope.blueprintId());
        probe.setBlueprintVersion(scope.blueprintVersion());
        probe.setSourceDigest(scope.sourceDigest());
        return Optional.of(drafts.findAll(Example.of(probe), WrittenDrafts.NEWEST_FIRST).stream()
                .map(row -> new WrittenDraft(row.getId(), row.getNaturalKey(), "产品 " + row.getDesiredName(), row.getStatus(),
                        row.getDesiredDigest(), row.getCreatedAt()))
                .toList());
    }

    /** The command's recovery plan takes no digest; {@code expectedDesiredDigest} is ignored. */
    @Override
    public Object recoveryPlan(String factoryId, String draftId, String expectedDesiredDigest) {
        return command.recoveryPlan(factoryId, draftId);
    }
}
