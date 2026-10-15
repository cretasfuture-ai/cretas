package com.cretas.aims.blueprint.promotion;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.promotion.MasterDataDraftPromotion;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.promotion.MasterDataDraftPromotionRepository;
import com.cretas.aims.service.bomdraft.BomRecipeDraftPromotionAuthority;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Server-owned proof that a formal DRAFT is the exact result of this BOM promotion. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
class BomPromotionDependencyAuthority implements BomRecipeDraftPromotionAuthority {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private final MasterDataDraftPromotionRepository promotions;
    private final BomRecipeRepository recipes;
    private final BomRecipeItemRepository recipeItems;
    private final ObjectProvider<BomDraftPromoter> promoters;

    BomPromotionDependencyAuthority(MasterDataDraftPromotionRepository promotions,
                                     BomRecipeRepository recipes,
                                     ObjectProvider<BomDraftPromoter> promoters) {
        this(promotions, recipes, null, promoters);
    }

    @org.springframework.beans.factory.annotation.Autowired
    BomPromotionDependencyAuthority(MasterDataDraftPromotionRepository promotions,
                                     BomRecipeRepository recipes,
                                     BomRecipeItemRepository recipeItems,
                                     ObjectProvider<BomDraftPromoter> promoters) {
        this.promotions = promotions;
        this.recipes = recipes;
        this.recipeItems = recipeItems;
        this.promoters = promoters;
    }

    @Override
    public Optional<VerifiedPromotionTarget> verifiedFormalRecipeId(BomRecipeDraft draft) {
        if (draft == null || draft.getId() == null || draft.getFactoryId() == null
                || draft.getBlueprintId() == null || draft.getSourceDigest() == null
                || draft.getDesiredDigest() == null || draft.getLockVersion() == null) return Optional.empty();

        List<MasterDataDraftPromotion> rows = promotions
                .findByFactoryIdAndCapabilityIdAndDraftIdAndStatus(
                        draft.getFactoryId(), BomDraftPromoter.CAPABILITY_ID, draft.getId(),
                        MasterDataDraftPromotion.STATUS_PROMOTED);
        if (rows.size() != 1) return Optional.empty();
        MasterDataDraftPromotion row = rows.getFirst();
        if (!Objects.equals(row.getFactoryId(), draft.getFactoryId())
                || !Objects.equals(row.getCapabilityId(), BomDraftPromoter.CAPABILITY_ID)
                || !Objects.equals(row.getDraftId(), draft.getId())
                || !Objects.equals(row.getStatus(), MasterDataDraftPromotion.STATUS_PROMOTED)
                || !Objects.equals(row.getBlueprintId(), draft.getBlueprintId())
                || !Objects.equals(row.getBlueprintVersion(), draft.getBlueprintVersion())
                || !Objects.equals(row.getSourceDigest(), draft.getSourceDigest())
                || !Objects.equals(row.getDraftDesiredDigest(), draft.getDesiredDigest())
                || !Objects.equals(row.getDraftLockVersion(), draft.getLockVersion())
                || row.getLiveTargetId() == null || row.getLiveReadbackDigest() == null) return Optional.empty();

        BomRecipe recipe = recipes.findById(row.getLiveTargetId())
                .filter(candidate -> draft.getFactoryId().equals(candidate.getFactoryId()))
                .filter(candidate -> candidate.getStatus() == BomRecipe.Status.DRAFT)
                .orElse(null);
        BomDraftPromoter promoter = promoters.getIfAvailable();
        if (recipe == null || promoter == null) return Optional.empty();
        DraftPromoter.LiveReadback readback = promoter.readBackSnapshot(draft.getFactoryId(), recipe.getId());
        recipe = recipes.findById(row.getLiveTargetId())
                .filter(candidate -> draft.getFactoryId().equals(candidate.getFactoryId()))
                .filter(candidate -> candidate.getStatus() == BomRecipe.Status.DRAFT)
                .orElse(null);
        if (recipe == null) return Optional.empty();
        if (readback == null || readback.proofFields().isEmpty()
                || !readback.proofFields().equals(BomRecipeDraftPromotionAuthority.headerSnapshot(recipe))
                || !row.getLiveReadbackDigest().equals(
                MasterDataDraftPromotionService.readbackDigest(BomDraftPromoter.CAPABILITY_ID, readback))) {
            return Optional.empty();
        }
        String confirmedReceipt = row.getPromotionPreviewDigest();
        if (confirmedReceipt != null && !confirmedReceipt.isBlank()
                && !matchesConfirmedReceipt(draft, recipe, promoter, row, confirmedReceipt)) {
            return Optional.empty();
        }
        String receiptProof = recipeItems == null ? null
                : packagingProofDigest(recipe.getId(), draft.getFactoryId(), promoter);
        if (recipeItems != null && receiptProof == null) return Optional.empty();
        return confirmedReceipt == null || confirmedReceipt.isBlank()
                ? Optional.of(new VerifiedPromotionTarget(recipe.getId(), readback.proofFields()))
                : Optional.of(new VerifiedPromotionTarget(recipe.getId(), readback.proofFields(), receiptProof));
    }

    private boolean matchesConfirmedReceipt(BomRecipeDraft draft, BomRecipe recipe, BomDraftPromoter promoter,
                                            MasterDataDraftPromotion row, String receiptProof) {
        if (recipeItems == null || row.getCreatedAt() == null) return false;
        if (!MasterDataDraftPromotion.ACTION_CREATE.equals(row.getAction())) return false;
        Optional<DraftPromoter.DraftSnapshot> snapshot = promoter.read(draft.getFactoryId(), draft.getId(), false);
        if (snapshot.isEmpty()) return false;
        if (!Objects.equals(row.getNaturalKey(), snapshot.get().naturalKey())) return false;
        Optional<Map<String, String>> roles = currentPackagingRoles(recipe.getId(), draft.getFactoryId(), promoter);
        if (roles.isEmpty()) return false;
        DraftPromoter.DraftSnapshot projected = promoter.withPromotionInput(snapshot.get(), roles.get());
        for (long window : MasterDataDraftPromotionService.previewWindows(row.getCreatedAt())) {
            String current = MasterDataDraftPromotionService.promotionPreviewDigest(
                    BomDraftPromoter.CAPABILITY_ID, projected, projected.after(), false, window);
            String previous = MasterDataDraftPromotionService.promotionPreviewDigest(
                    BomDraftPromoter.CAPABILITY_ID, projected, projected.after(), false, window - 1);
            String beforePrevious = MasterDataDraftPromotionService.promotionPreviewDigest(
                    BomDraftPromoter.CAPABILITY_ID, projected, projected.after(), false, window - 2);
            if (receiptProof.equals(current) || receiptProof.equals(previous)
                    || receiptProof.equals(beforePrevious)) return true;
        }
        return false;
    }

    /** Formal BOM packaging roles are the only request-only facts absent from historical readback. */
    private Optional<Map<String, String>> currentPackagingRoles(String recipeId, String factoryId,
                                                                BomDraftPromoter promoter) {
        List<BomRecipeItem> items = promoter.freshOwnedItems(factoryId, recipeId);
        if (items == null) return Optional.empty();
        Map<String, String> roles = new TreeMap<>();
        for (BomRecipeItem item : items) {
            if (!factoryId.equals(item.getFactoryId())) return Optional.empty();
            if (!"PACKAGING".equals(item.getMaterialCategory())) {
                if (item.getPackagingRole() != null && !item.getPackagingRole().isBlank()) return Optional.empty();
                continue;
            }
            String port = item.getWorkflowInputPortId();
            String role = item.getPackagingRole();
            if (port == null || port.isBlank() || role == null || role.isBlank()
                    || !Set.of("PRIMARY_CONTAINER", "SEAL", "OUTER_CASE").contains(role)
                    || roles.putIfAbsent(port, role) != null) return Optional.empty();
        }
        return Optional.of(Map.copyOf(roles));
    }

    private String packagingProofDigest(String recipeId, String factoryId, BomDraftPromoter promoter) {
        if (recipeItems == null || promoter == null) return null;
        List<BomRecipeItem> items = promoter.freshOwnedItems(factoryId, recipeId);
        if (items == null) return null;
        List<String> values = new java.util.ArrayList<>();
        values.add(recipeId);
        values.add(factoryId);
        for (BomRecipeItem item : items) {
            if (!factoryId.equals(item.getFactoryId())) return null;
            values.add(String.valueOf(item.getId()));
            values.add(String.valueOf(item.getMaterialTypeId()));
            values.add(String.valueOf(item.getMaterialCategory()));
            values.add(String.valueOf(item.getSortOrder()));
            values.add(String.valueOf(item.getStandardQuantity()));
            values.add(String.valueOf(item.getYieldRate()));
            values.add(String.valueOf(item.getActualQuantity()));
            values.add(String.valueOf(item.getUnit()));
            values.add(String.valueOf(item.getIsOptional()));
            values.add(String.valueOf(item.getWorkflowMaterialNodeId()));
            values.add(String.valueOf(item.getWorkflowInputPortId()));
            values.add(String.valueOf(item.getWorkflowEdgeId()));
            values.add(String.valueOf(item.getPackagingRole()));
            values.add(String.valueOf(item.getNaturalQuantity()));
            values.add(String.valueOf(item.getNaturalUnit()));
            values.add(String.valueOf(item.getPackagingSpecId()));
            values.add(String.valueOf(item.getPackagingSpecNameSnapshot()));
            values.add(String.valueOf(item.getPackagingPackageUnitSnapshot()));
            values.add(String.valueOf(item.getPackagingBaseUnitSnapshot()));
            values.add(String.valueOf(item.getPackagingConversionFactorSnapshot()));
        }
        values.add(0, "bom-receipt-proof");
        values.add(0, MasterDataDraftPromotionModels.SCHEMA_VERSION);
        return PromotionDigest.sha256(values.toArray(String[]::new));
    }

    @Override
    public boolean matchesCurrent(VerifiedPromotionTarget proof, BomRecipe candidate) {
        if (proof == null || candidate == null || entityManager == null
                || !proof.id().equals(candidate.getId()) || !entityManager.contains(candidate)) return false;
        try {
            entityManager.refresh(candidate);
            if (!proof.matches(candidate)) return false;
            if (proof.receiptProofDigest() == null) return true;
            BomDraftPromoter promoter = promoters.getIfAvailable();
            return promoter != null && Objects.equals(proof.receiptProofDigest(),
                    packagingProofDigest(candidate.getId(), candidate.getFactoryId(), promoter));
        } catch (jakarta.persistence.EntityNotFoundException removed) {
            return false;
        }
    }
}
