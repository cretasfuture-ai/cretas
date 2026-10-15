package com.cretas.aims.repository.promotion;

import com.cretas.aims.entity.promotion.MasterDataDraftPromotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MasterDataDraftPromotionRepository extends JpaRepository<MasterDataDraftPromotion, String> {

    Optional<MasterDataDraftPromotion> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);

    List<MasterDataDraftPromotion> findByFactoryIdAndCapabilityIdAndDraftIdAndStatus(
            String factoryId, String capabilityId, String draftId, String status);

    List<MasterDataDraftPromotion> findByFactoryIdAndCapabilityIdAndDraftIdOrderByCreatedAtDescIdDesc(
            String factoryId, String capabilityId, String draftId);

    List<MasterDataDraftPromotion>
            findByFactoryIdAndBlueprintIdAndBlueprintVersionAndSourceDigestOrderByCreatedAtDescIdDesc(
            String factoryId, String blueprintId, Long blueprintVersion, String sourceDigest);

    long countByFactoryId(String factoryId);
}
