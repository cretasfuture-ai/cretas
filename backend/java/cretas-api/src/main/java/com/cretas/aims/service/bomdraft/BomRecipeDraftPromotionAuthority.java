package com.cretas.aims.service.bomdraft;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies that a formal BOM is the durable, tenant-owned result of its own BOM draft promotion.
 * Implementations must re-read managed state before returning a proof.
 */
public interface BomRecipeDraftPromotionAuthority {
    Optional<VerifiedPromotionTarget> verifiedFormalRecipeId(BomRecipeDraft draft);

    boolean matchesCurrent(VerifiedPromotionTarget proof, BomRecipe candidate);

    record VerifiedPromotionTarget(String id, Map<String, Object> headerProof,
                                   String receiptProofDigest) {
        public VerifiedPromotionTarget(String id, Map<String, Object> headerProof) {
            this(id, headerProof, null);
        }

        public VerifiedPromotionTarget {
            headerProof = headerProof == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(headerProof));
        }

        public boolean matches(BomRecipe recipe) {
            return recipe != null && id.equals(recipe.getId())
                    && headerProof.equals(headerSnapshot(recipe));
        }
    }

    static Map<String, Object> headerSnapshot(BomRecipe recipe) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("factoryId", recipe.getFactoryId());
        fields.put("productTypeId", recipe.getProductTypeId());
        fields.put("outputQuantity", decimal(recipe.getOutputQuantityPerUnit()));
        fields.put("outputUnit", recipe.getOutputUnit());
        fields.put("netContentQuantity", decimal(recipe.getNetContentQuantity()));
        fields.put("netContentUnit", recipe.getNetContentUnit());
        fields.put("workflowId", recipe.getWorkflowId());
        fields.put("workflowRevisionId", recipe.getWorkflowRevisionId());
        fields.put("workflowRevisionHash", recipe.getWorkflowRevisionHash());
        fields.put("version", recipe.getVersion());
        fields.put("status", recipe.getStatus() == null ? null : recipe.getStatus().name());
        fields.put("isCurrent", recipe.getIsCurrent());
        fields.put("updatedAt", recipe.getUpdatedAt());
        return java.util.Collections.unmodifiableMap(fields);
    }

    private static Object decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }
}
