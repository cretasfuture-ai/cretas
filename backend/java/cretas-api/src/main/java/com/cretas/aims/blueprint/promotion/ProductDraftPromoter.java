package com.cretas.aims.blueprint.promotion;

import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.Blocker;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.product.ProductTypeDraft;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.security.ActorRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** products: {@code product_type_drafts} -> {@code product_types} through {@link ProductTypeService}. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
class ProductDraftPromoter implements DraftPromoter {

    static final String CAPABILITY_ID = "products";
    static final String PERMISSION = "product:draft:promote";

    private final ProductTypeService productTypeService;
    private final ProductTypeRepository productTypeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    ProductDraftPromoter(ProductTypeService productTypeService, ProductTypeRepository productTypeRepository) {
        this.productTypeService = productTypeService;
        this.productTypeRepository = productTypeRepository;
    }

    @Override
    public String capabilityId() {
        return CAPABILITY_ID;
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public Optional<DraftSnapshot> read(String factoryId, String draftId, boolean forUpdate) {
        TypedQuery<ProductTypeDraft> query = entityManager.createQuery(
                "select d from ProductTypeDraft d where d.id = :id and d.factoryId = :factoryId "
                        + "and d.deletedAt is null", ProductTypeDraft.class)
                .setParameter("id", draftId).setParameter("factoryId", factoryId);
        if (forUpdate) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        List<ProductTypeDraft> rows = query.getResultList();
        if (rows.isEmpty()) return Optional.empty();
        ProductTypeDraft draft = rows.get(0);
        // Detached: nothing this promotion does can flush a change into the DRAFT row.
        entityManager.detach(draft);
        return Optional.of(new DraftSnapshot(draft.getId(), draft.getFactoryId(), draft.getBlueprintId(),
                draft.getBlueprintVersion(), draft.getSourceDigest(), draft.getDesiredDigest(),
                draft.getLockVersion(), draft.getStatus(), draft.getNaturalKey(),
                draft.getTargetProductTypeId(), after(draft), draft));
    }

    private static Map<String, Object> after(ProductTypeDraft draft) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", draft.getNaturalKey());
        after.put("name", draft.getDesiredName());
        after.put("unit", draft.getDesiredUnit());
        after.put("productCategory", draft.getDesiredProductCategory());
        return after;
    }

    @Override
    public Optional<LiveRow> findLiveConflict(String factoryId, DraftSnapshot draft) {
        Optional<ProductType> sameCode = productTypeRepository.findByFactoryIdAndCode(factoryId, draft.naturalKey());
        if (sameCode.isPresent()) {
            return sameCode.map(row -> new LiveRow(row.getId(), row.getName()));
        }
        // The live writer also refuses a duplicate name (LOWER(TRIM(name)), RAW_MATERIAL excluded);
        // showing it in the preview is better than a READY preview that the write then rejects.
        String wanted = normalizedName(String.valueOf(draft.after().get("name")));
        return productTypeRepository.findByFactoryId(factoryId).stream()
                .filter(row -> !"RAW_MATERIAL".equalsIgnoreCase(trim(row.getProductCategory())))
                .filter(row -> normalizedName(row.getName()).equals(wanted))
                .findFirst()
                .map(row -> new LiveRow(row.getId(), row.getName()));
    }

    @Override
    public List<Blocker> capabilityBlockers(DraftSnapshot draft) {
        String category = trim(String.valueOf(draft.after().get("productCategory")));
        if (!com.cretas.aims.service.productdraft.ProductTypeDraftCommand.FACTORY_PRODUCT_CATEGORIES.contains(category)) {
            return List.of(Blocker.of("PROMOTION_CATEGORY_INVALID",
                    "这份产品草稿的类别「" + category + "」不是系统的产品类别，转正后下游（如 Workflow）无法识别",
                    "重新预填并写一份类别正确的产品草稿，再转正"));
        }
        return List.of();
    }

    @Override
    public String create(String factoryId, DraftSnapshot draft, ActorRef actor) {
        ProductTypeDraft source = (ProductTypeDraft) draft.source();
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setCode(source.getNaturalKey());
        dto.setName(source.getDesiredName());
        dto.setUnit(source.getDesiredUnit());
        dto.setProductCategory(source.getDesiredProductCategory());
        return productTypeService.createProductTypeAsActor(factoryId, dto, actor).getId();
    }

    @Override
    public LiveReadback readBack(String factoryId, String liveTargetId) {
        entityManager.flush();
        Optional<ProductType> found = productTypeRepository.findByIdAndFactoryId(liveTargetId, factoryId);
        if (found.isEmpty()) return null;
        ProductType row = found.get();
        entityManager.refresh(row);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", row.getCode());
        fields.put("name", row.getName());
        fields.put("unit", row.getUnit());
        fields.put("productCategory", row.getProductCategory());
        return new LiveReadback(row.getId(), fields, row.getCreatedBy());
    }

    @Override
    public boolean recordsCreatedBy() {
        return true;
    }

    private static String normalizedName(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
