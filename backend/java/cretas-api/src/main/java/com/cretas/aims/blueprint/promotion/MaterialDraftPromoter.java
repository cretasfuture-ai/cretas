package com.cretas.aims.blueprint.promotion;

import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.Blocker;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.entity.material.MaterialTypeDraft;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.material.MaterialCodeSegmentService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

/**
 * materials: {@code material_type_drafts} -> {@code raw_material_types} through
 * {@link RawMaterialTypeService}. The live code is allocated by the live service; the natural key of
 * a new material is its normalised name, which is also what the live writer refuses to duplicate.
 *
 * <p>The material's L1 category ({@code desired_primary_category_code} / {@code desired_category} /
 * {@code desired_packaging_category}) must be in {@code material_code_segments} before the material is
 * created. A DRAFT may name a category the blueprint only <em>planned</em>; the plan is decided under the
 * DRAFT row lock, shown in the preview and folded into its digest:</p>
 * <ul>
 *   <li>the table holds the code (not soft-deleted): it must be an active L1 whose label is the same
 *       after NFKC normalisation and whose packaging flag is the DRAFT's — then it is reused;</li>
 *   <li>otherwise {@code PROMOTION_CLASSIFICATION_CONFLICT} (with the existing label), as also for a
 *       soft-deleted row of the same code ({@code @Where} hides it from JPQL, so a native count) and for
 *       a label another L1 already uses (the segment service would refuse it);</li>
 *   <li>absent: created in the same transaction through {@link MaterialCodeSegmentService#create}
 *       (level 1, code, label, packaging flag, active), then the material.</li>
 * </ul>
 * The four category fields are read back from the table and compared like every other field.
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
class MaterialDraftPromoter implements DraftPromoter {

    static final String CAPABILITY_ID = "materials";
    static final String PERMISSION = "material:draft:promote";
    static final String CLASSIFICATION_CONFLICT = "PROMOTION_CLASSIFICATION_CONFLICT";
    /** Preview-only: what happens to the category (「将新建物料大类 原料（001）」 / 「沿用本厂已有物料大类 …」). */
    static final String CLASSIFICATION_PLAN = "classification.plan";
    static final String CLASSIFICATION_CODE = "classification.segmentCode";
    static final String CLASSIFICATION_LABEL = "classification.label";
    static final String CLASSIFICATION_PACKAGING = "classification.packagingCategory";
    static final String CLASSIFICATION_ACTIVE = "classification.isActive";

    private final RawMaterialTypeService materialTypeService;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialCodeSegmentService segmentService;

    @PersistenceContext
    private EntityManager entityManager;

    MaterialDraftPromoter(RawMaterialTypeService materialTypeService,
                          RawMaterialTypeRepository materialTypeRepository,
                          MaterialCodeSegmentService segmentService) {
        this.materialTypeService = materialTypeService;
        this.materialTypeRepository = materialTypeRepository;
        this.segmentService = segmentService;
    }

    @Override
    public String capabilityId() {
        return CAPABILITY_ID;
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    /** What the promotion does with the DRAFT's L1 category, decided from the table at read time. */
    record ClassificationPlan(String code, String label, boolean packaging, boolean create, Blocker conflict) {
        String statement() {
            if (conflict != null) return "物料大类 " + label + "（" + code + "）与本厂分类树冲突，不能转正";
            return (create ? "将新建物料大类 " : "沿用本厂已有物料大类 ") + label + "（" + code + "）";
        }
    }

    /** {@link DraftSnapshot#source()} of this promoter. */
    record MaterialSource(MaterialTypeDraft draft, ClassificationPlan plan, BigDecimal taxIncludedUnitPrice,
                          Map<String, Object> before, String currentSourceDigest) {
    }

    @Override
    public Optional<DraftSnapshot> read(String factoryId, String draftId, boolean forUpdate) {
        TypedQuery<MaterialTypeDraft> query = entityManager.createQuery(
                "select d from MaterialTypeDraft d where d.id = :id and d.factoryId = :factoryId "
                        + "and d.deletedAt is null", MaterialTypeDraft.class)
                .setParameter("id", draftId).setParameter("factoryId", factoryId);
        if (forUpdate) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        List<MaterialTypeDraft> rows = query.getResultList();
        if (rows.isEmpty()) return Optional.empty();
        MaterialTypeDraft draft = rows.get(0);
        entityManager.detach(draft);
        ClassificationPlan plan = plan(factoryId, draft);
        Map<String, Object> before = new LinkedHashMap<>();
        String currentSourceDigest = null;
        if (!clean(draft.getTargetMaterialTypeId()).isEmpty()) {
            Optional<RawMaterialType> target = materialTypeRepository.findByIdAndFactoryId(draft.getTargetMaterialTypeId(), factoryId);
            if (target.isPresent()) {
                RawMaterialType live = target.get();
                if (forUpdate) entityManager.lock(live, LockModeType.PESSIMISTIC_WRITE);
                entityManager.refresh(live);
                before.put("id", live.getId()); before.put("name", live.getName());
                before.put("code", live.getCode()); before.put("businessCode", "");
                before.put("category", live.getCategory());
                
                before.put("classificationId", live.getClassificationSegmentId() == null ? "" : String.valueOf(live.getClassificationSegmentId()));
                before.put("unit", live.getUnit()); before.put("storageType", live.getStorageType());
                before.put("shelfLifeDays", live.getShelfLifeDays()); before.put("isByproduct", Boolean.TRUE.equals(live.getIsByproduct()));
                before.put("createdBy", live.getCreatedBy()); before.put("updatedAt", live.getUpdatedAt());
                before.put("taxIncludedUnitPrice", live.getTaxIncludedUnitPrice()); before.put("taxRate", live.getTaxRate());
                before.put("unitPrice", live.getUnitPrice());
                before.put("taxTreatment", live.getTaxTreatment()); before.put("taxExemptionReason", live.getTaxExemptionReason());
                before.put("associatedCustomerId", live.getAssociatedCustomerId()); before.put("packQtyPerProduct", live.getPackQtyPerProduct());
                currentSourceDigest = com.cretas.aims.service.materialdraft.MaterialTypeDraftCommand.sourceSnapshotDigest(live);
            }
        }
        return Optional.of(new DraftSnapshot(draft.getId(), draft.getFactoryId(), draft.getBlueprintId(),
                draft.getBlueprintVersion(), draft.getSourceDigest(), draft.getDesiredDigest(),
                draft.getLockVersion(), draft.getStatus(), draft.getNaturalKey(),
                draft.getTargetMaterialTypeId(), updateAfter(draft, plan, null, before), new MaterialSource(draft, plan, null, before, currentSourceDigest)));
    }

    @Override
    public DraftSnapshot withPromotionInput(DraftSnapshot draft, Object input) {
        BigDecimal price = input instanceof BigDecimal value ? value : null;
        MaterialSource source = (MaterialSource) draft.source();
        Map<String, Object> after = updateAfter(source.draft(), source.plan(), price, source.before());
        return new DraftSnapshot(draft.id(), draft.factoryId(), draft.blueprintId(), draft.blueprintVersion(),
                draft.sourceDigest(), draft.desiredDigest(), draft.lockVersion(), draft.status(), draft.naturalKey(),
                draft.targetLiveId(), after, new MaterialSource(source.draft(), source.plan(), price, source.before(), source.currentSourceDigest()));
    }

    private ClassificationPlan plan(String factoryId, MaterialTypeDraft draft) {
        // desiredPrimaryCategoryCode carries the resolved L1 node ID; the formal material DTO
        // receives desiredClassificationSegmentCode (the selected L3/L1 node) as classificationId.
        String code = clean(draft.getDesiredPrimaryCategoryCode());
        String label = clean(draft.getDesiredCategory());
        boolean packaging = Boolean.TRUE.equals(draft.getDesiredPackagingCategory());
        if (code.isEmpty() && clean(draft.getDesiredClassificationSegmentCode()).isEmpty()
                && Set.of("原料", "辅料", "包材").contains(label)
                && packaging == "包材".equals(label)) {
            return new ClassificationPlan("", label, packaging, false, null);
        }

        Long id;
        try { id = Long.valueOf(code); } catch (NumberFormatException ex) { id = null; }
        if (id == null || label.isEmpty()) {
            return conflict(code, label, packaging, null,
                    "这份物料草稿的分类节点 ID 或名称无效（ID“" + code + "”，名称“" + label + "”）",
                    "重新预览并写入这份物料草稿");
        }
        if (packaging && !isPackagingLabel(label)) {
            return conflict(code, label, packaging, null,
                    "当前正式物料契约只通过 category=包材（或 PACKAGING）表达包材角色，所选分类名称无法保留该语义",
                    "选择名称为“包材”的一级分类，或先在正式物料分类中完成角色归类");
        }
        Optional<MaterialCodeSegment> found = entityManager.createQuery(
                        "select s from MaterialCodeSegment s where s.factoryId = :factoryId and s.id = :id",
                        MaterialCodeSegment.class).setParameter("factoryId", factoryId).setParameter("id", id)
                .getResultStream().findFirst();
        if (found.isPresent()) {
            MaterialCodeSegment existing = found.get();
            String existingLabel = clean(existing.getSegmentLabel());
            List<String> differences = new ArrayList<>();
            if (existing.getLevel() == null || existing.getLevel() != 1) differences.add("它不是大类（L1）");
            if (!Objects.equals(com.cretas.aims.entity.material.MaterialMainCategory.of(label), com.cretas.aims.entity.material.MaterialMainCategory.of(existingLabel))) differences.add("名称是“" + existingLabel + "”");
            if (!Boolean.TRUE.equals(existing.getIsActive())) differences.add("它已停用");
            if (!differences.isEmpty()) {
                return conflict(code, label, packaging, existing,
                        "本厂分类树里已有编码 " + code + " 的大类“" + existingLabel + "”，与这份草稿的“" + label + "”"
                                + (packaging ? "（按包材管理）" : "（不按包材管理）") + "不一致："
                                + String.join("、", differences) + "，这次转正没有写入任何数据",
                        "确认应当沿用哪一个：改本厂分类树里的这一类，或按本厂分类重新写物料草稿");
            }
            return new ClassificationPlan(code, existingLabel, packaging, false, null);
        }
        return conflict(code, label, packaging, null,
                "当前分类体系只允许引用已存在的分类节点 ID，不能在物料转正时按旧编码新建分类",
                "先在本厂物料分类中创建并启用分类，再重新预览物料草稿");
    }

    private static ClassificationPlan conflict(String code, String label, boolean packaging,
                                               MaterialCodeSegment existing, String message, String nextStep) {
        return new ClassificationPlan(code, label, packaging, false, new Blocker(CLASSIFICATION_CONFLICT, message,
                nextStep, existing == null || existing.getId() == null ? null : existing.getId().toString(),
                existing == null ? null : clean(existing.getSegmentLabel())));
    }

    /**
     * The live fields written and read back, plus the category plan. The classification segment code is
     * an input that drives the live code allocation; raw_material_types does not store it as such, so it
     * is not a readback field (it is covered by the draft's desired digest, which the preview digest
     * includes). The category's four fields are read back from {@code material_code_segments}.
     */
    private static Map<String, Object> after(MaterialTypeDraft draft, ClassificationPlan plan, BigDecimal taxIncludedUnitPrice) {
        boolean packaging = Boolean.TRUE.equals(draft.getDesiredPackagingCategory());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", draft.getDesiredName());
        // The live writer stores the L1's own label as the category.
        after.put("category", plan.packaging() ? "包材" : plan.label());
        after.put("classificationId", clean(draft.getDesiredClassificationSegmentCode()));
        after.put("unit", draft.getDesiredUnit());
        // The live writer stores no storage type for a packaging category.
        after.put("sourceStorageMode", packaging ? null : draft.getDesiredStorageMode());
        after.put("storageType", packaging || draft.getDesiredStorageMode() == null ? null
                : MaterialStorageModeMapping.require(draft.getDesiredStorageMode()).code());
        after.put("shelfLifeDays", draft.getDesiredShelfLifeDays());
        after.put("isByproduct", Boolean.TRUE.equals(draft.getDesiredIsByproduct()));
        if (packaging) after.put("taxIncludedUnitPrice", taxIncludedUnitPrice);
        after.put(CLASSIFICATION_CODE, plan.code());
        after.put(CLASSIFICATION_LABEL, plan.label());
        after.put(CLASSIFICATION_PACKAGING, plan.packaging());
        after.put(CLASSIFICATION_ACTIVE, true);
        after.put(CLASSIFICATION_PLAN, plan.statement());
        return after;
    }

    private static Map<String, Object> updateAfter(MaterialTypeDraft draft, ClassificationPlan plan,
                                                    BigDecimal price, Map<String, Object> before) {
        Map<String, Object> result = after(draft, plan, price);
        if (!clean(draft.getTargetMaterialTypeId()).isEmpty() && Boolean.TRUE.equals(draft.getDesiredPackagingCategory())) {
            BigDecimal gross = price != null ? price : (BigDecimal) before.get("taxIncludedUnitPrice");
            BigDecimal net = (BigDecimal) before.get("unitPrice");
            if (price != null) {
                TaxRate rate = (TaxRate) before.get("taxRate");
                net = before.get("taxTreatment") == TaxTreatment.EXEMPT ? price
                        : rate == null ? null : rate.preTaxPrice(price);
                // The live service calculates four decimals; unit_price persists NUMERIC(10,2).
                if (net != null) net = net.setScale(2, RoundingMode.HALF_UP);
            }
            result.put("taxIncludedUnitPrice", gross); result.put("unitPrice", net);
            for (String key : List.of("taxRate", "taxTreatment", "taxExemptionReason", "associatedCustomerId", "packQtyPerProduct")) {
                result.put(key, before.get(key));
            }
        }
        return result;
    }

    @Override
    public Set<String> previewOnlyFields() {
        return Set.of(CLASSIFICATION_PLAN);
    }

    @Override
    public Optional<LiveRow> findLiveConflict(String factoryId, DraftSnapshot draft) {
        String desiredName = String.valueOf(draft.after().get("name"));
        return materialTypeRepository.findByFactoryId(factoryId).stream()
                .filter(row -> clean(row.getName()).equalsIgnoreCase(desiredName))
                .filter(row -> !row.getId().equals(draft.targetLiveId()))
                .findFirst()
                .map(row -> new LiveRow(row.getId(), row.getName()));
    }

    @Override
    public List<Blocker> capabilityBlockers(DraftSnapshot draft) {
        MaterialSource source = (MaterialSource) draft.source();
        List<Blocker> blockers = new ArrayList<>();
        if (!clean(draft.targetLiveId()).isEmpty()) {
            boolean packaging = Boolean.TRUE.equals(source.draft().getDesiredPackagingCategory());
            if (packaging && !source.before().isEmpty()) {
                Object treatment = source.before().get("taxTreatment");
                Object rate = source.before().get("taxRate");
                if ((treatment != TaxTreatment.TAXABLE && treatment != TaxTreatment.EXEMPT)
                        || (treatment == TaxTreatment.TAXABLE && rate == null)
                        || (treatment == TaxTreatment.EXEMPT && (rate != null || clean((String) source.before().get("taxExemptionReason")).isEmpty()))) {
                    blockers.add(Blocker.of("PROMOTION_PACKAGING_TAX_METADATA_INVALID", "正式包材的计税方式、税率或免税依据不完整，不能按已确认价格转正", "先在正式包材页面修正税务资料，再重新生成草稿"));
                }
                BigDecimal gross = (BigDecimal) draft.after().get("taxIncludedUnitPrice");
                BigDecimal net = (BigDecimal) draft.after().get("unitPrice");
                if (gross == null || gross.signum() <= 0) {
                    blockers.add(Blocker.of("PROMOTION_PRICE_REQUIRED", "正式包材接口要求大于零的采购参考价，所以不能转正", "输入大于零的含税采购参考价并重新预览"));
                } else if (gross.stripTrailingZeros().scale() > 4 || (net != null && net.abs().compareTo(new BigDecimal("99999999.99")) > 0)) {
                    blockers.add(Blocker.of("PROMOTION_PRICE_PRECISION_UNSUPPORTED", "采购参考价超出正式接口支持的精度或未税价范围，所以不能转正", "含税价最多4位小数，未税价最多8位整数；修改后重新预览"));
                }
            }
            if (!packaging && source.taxIncludedUnitPrice() != null) {
                blockers.add(Blocker.of("PROMOTION_UPDATE_PRICE_NOT_SUPPORTED", "原料草稿修改转正不支持修改价格，所以不能转正", "去掉价格输入，或到正式原料页面编辑参考价"));
            }
            Map<String, Object> before = source.before();
            if (before.isEmpty()) {
                blockers.add(Blocker.of("PROMOTION_TARGET_NOT_FOUND", "本厂没有这份草稿指定的有效正式原料，不能修改转正", "刷新正式原料并重新生成草稿"));
            } else {
                if (!Objects.equals(source.currentSourceDigest(), source.draft().getSourceSnapshotDigest())) {
                    blockers.add(Blocker.of("PROMOTION_SOURCE_STALE", "正式原料已在草稿生成后改变，这次不能覆盖修改", "基于最新正式原料重新生成修改草稿"));
                }
                if (!Objects.equals(before.get("unit"), draft.after().get("unit"))) {
                    blockers.add(Blocker.of("PROMOTION_UNIT_CHANGE_UNSUPPORTED", "草稿修改转正暂不支持更换库存基本单位，不能转正", "到正式原料页面完成单位转换后重新生成草稿"));
                }
                String materialCode = clean((String) before.get("code"));
                String currentClassificationId = clean((String) before.get("classificationId"));
                String requestedClassificationId = clean(source.draft().getDesiredClassificationSegmentCode());
                if (source.plan().create() || !Objects.equals(before.get("category"), draft.after().get("category"))
                        || !Objects.equals(currentClassificationId, requestedClassificationId)
                        || materialCode.isEmpty()) {
                    blockers.add(Blocker.of("PROMOTION_CLASSIFICATION_CHANGE_UNSUPPORTED", "草稿修改转正暂不支持重新分配分类或物料编码，不能转正", "到正式原料页面处理分类后重新生成草稿"));
                }
                if ((before.get("shelfLifeDays") != null && draft.after().get("shelfLifeDays") == null)
                        || (before.get("storageType") != null && draft.after().get("storageType") == null)) {
                    blockers.add(Blocker.of("PROMOTION_CLEAR_NOT_SUPPORTED", "正式原料更新接口不支持清空已有保质期或储存方式，所以这份草稿不能转正", "保留现有值或填写新的值后重新生成草稿"));
                }
            }
        }
        if (source.plan().conflict() != null) blockers.add(source.plan().conflict());
        if (clean(draft.targetLiveId()).isEmpty() && Boolean.TRUE.equals(source.draft().getDesiredPackagingCategory())
                && (source.taxIncludedUnitPrice() == null || source.taxIncludedUnitPrice().signum() <= 0)) {
            // The live writer requires a positive tax-included reference price for a packaging
            // category, and a material DRAFT carries no price at all.
            blockers.add(Blocker.of("PROMOTION_PRICE_REQUIRED",
                    "包材大类的物料必须有含税采购参考价，这份物料草稿里没有价格，不能直接转正",
                    "填写大于 0 的含税采购参考价后重新预览"));
        }
        return blockers;
    }

    /** Exactly what {@link #create} writes besides the material: the planned category, when the plan creates it. */
    @Override
    public List<String> sideWrites(DraftSnapshot draft) {
        if (!clean(draft.targetLiveId()).isEmpty()) return List.of();
        ClassificationPlan plan = ((MaterialSource) draft.source()).plan();
        return plan.create() ? List.of("新建物料大类 " + plan.label() + "（" + plan.code() + "）") : List.of();
    }

    @Override public boolean supportsUpdate() { return true; }

    @Override public Map<String, Object> before(DraftSnapshot draft) { return ((MaterialSource) draft.source()).before(); }

    @Override public Long expectedCreatedBy(DraftSnapshot draft, ActorRef actor) {
        return clean(draft.targetLiveId()).isEmpty() ? actor.factoryUserId() : (Long) before(draft).get("createdBy");
    }

    @Override public Map<String, Object> preservedFields(DraftSnapshot draft) {
        if (clean(draft.targetLiveId()).isEmpty()) return Map.of();
        Map<String,Object> fields=new LinkedHashMap<>();
        for (String key : List.of("code","businessCode","taxRate","taxTreatment","taxExemptionReason","associatedCustomerId","packQtyPerProduct")) fields.put(key,before(draft).get(key));
        if (((MaterialSource) draft.source()).taxIncludedUnitPrice() == null) {
            fields.put("taxIncludedUnitPrice",before(draft).get("taxIncludedUnitPrice"));
            fields.put("unitPrice",before(draft).get("unitPrice"));
        }
        return fields;
    }

    @Override public String update(String factoryId, DraftSnapshot draft, ActorRef actor) {
        if (!capabilityBlockers(draft).isEmpty()) throw new IllegalStateException("Blocked material update reached live writer");
        MaterialTypeDraft row = ((MaterialSource) draft.source()).draft();
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setName(row.getDesiredName()); dto.setUnit(row.getDesiredUnit());
        dto.setStorageType((String) draft.after().get("storageType")); dto.setShelfLifeDays(row.getDesiredShelfLifeDays());
        dto.setIsByproduct(Boolean.TRUE.equals(row.getDesiredIsByproduct()));
        dto.setAssociatedCustomerId((String) before(draft).get("associatedCustomerId"));
        if (Boolean.TRUE.equals(row.getDesiredPackagingCategory())) dto.setTaxIncludedUnitPrice(((MaterialSource) draft.source()).taxIncludedUnitPrice());
        return materialTypeService.updateMaterialType(factoryId, draft.targetLiveId(), dto).getId();
    }

    @Override
    public String create(String factoryId, DraftSnapshot draft, ActorRef actor) {
        MaterialSource source = (MaterialSource) draft.source();
        MaterialTypeDraft row = source.draft();
        ClassificationPlan plan = source.plan();
        if (plan.conflict() != null) {
            // The service refuses a preview with blockers before it gets here; this is the invariant.
            throw new IllegalStateException("classification conflict reached the live write: " + plan.conflict().code());
        }
        String segment = row.getDesiredClassificationSegmentCode();
        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
        dto.setName(row.getDesiredName());
        try { dto.setClassificationId(clean(segment).isEmpty() ? null : Long.valueOf(segment)); }
        catch (NumberFormatException ex) { throw new IllegalStateException("Invalid taxonomy node id: " + segment); }
        dto.setCategory(plan.packaging() ? "包材" : plan.label());
        dto.setUnit(row.getDesiredUnit());
        dto.setStorageType(plan.packaging() || row.getDesiredStorageMode() == null ? null
                : MaterialStorageModeMapping.require(row.getDesiredStorageMode()).code());
        dto.setShelfLifeDays(row.getDesiredShelfLifeDays());
        dto.setIsByproduct(Boolean.TRUE.equals(row.getDesiredIsByproduct()));
        if (plan.packaging()) dto.setTaxIncludedUnitPrice(source.taxIncludedUnitPrice());
        // Platform actors intentionally leave the legacy factory-user FK null.
        dto.setCreatedBy(actor.factoryUserId());
        String id = materialTypeService.createMaterialType(factoryId, dto).getId();
        RawMaterialType created = materialTypeRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new IllegalStateException("正式物料创建后无法在目标工厂读回"));
        created.setActor(actor);
        materialTypeRepository.saveAndFlush(created);
        return id;
    }

    @Override
    public LiveReadback readBack(String factoryId, String liveTargetId) {
        entityManager.flush();
        Optional<RawMaterialType> found = materialTypeRepository.findByIdAndFactoryId(liveTargetId, factoryId);
        if (found.isEmpty()) return null;
        RawMaterialType row = found.get();
        entityManager.refresh(row);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", row.getName());
        fields.put("category", row.getCategory());
        fields.put("classificationId", row.getClassificationSegmentId() == null ? "" : String.valueOf(row.getClassificationSegmentId()));
        fields.put("unit", row.getUnit());
        fields.put("storageType", row.getStorageType());
        fields.put("shelfLifeDays", row.getShelfLifeDays());
        fields.put("isByproduct", Boolean.TRUE.equals(row.getIsByproduct()));
        MaterialCodeSegment category = null;
        if (row.getClassificationSegmentId() != null) {
            MaterialCodeSegment leaf = entityManager.find(MaterialCodeSegment.class, row.getClassificationSegmentId());
            MaterialCodeSegment parent = leaf == null || leaf.getParentId() == null ? null : entityManager.find(MaterialCodeSegment.class, leaf.getParentId());
            category = parent == null || parent.getParentId() == null ? null : entityManager.find(MaterialCodeSegment.class, parent.getParentId());
            if (leaf == null || parent == null || category == null || !factoryId.equals(leaf.getFactoryId())
                    || !factoryId.equals(parent.getFactoryId()) || !factoryId.equals(category.getFactoryId())) return null;
            entityManager.refresh(category);
        }
        boolean packaging = "PACKAGING".equals(com.cretas.aims.entity.material.MaterialMainCategory.of(row.getCategory()));
        if (packaging) fields.put("taxIncludedUnitPrice", row.getTaxIncludedUnitPrice());
        fields.put(CLASSIFICATION_CODE, category == null ? "" : String.valueOf(category.getId()));
        fields.put(CLASSIFICATION_LABEL, category == null ? row.getCategory() : category.getSegmentLabel());
        fields.put(CLASSIFICATION_PACKAGING, packaging);
        fields.put(CLASSIFICATION_ACTIVE, category == null || Boolean.TRUE.equals(category.getIsActive()));
        Map<String,Object> proof=new LinkedHashMap<>();
        proof.put("code",row.getCode()); proof.put("businessCode",""); proof.put("taxRate",row.getTaxRate());
        proof.put("taxIncludedUnitPrice",row.getTaxIncludedUnitPrice()); proof.put("unitPrice",row.getUnitPrice());
        proof.put("taxTreatment",row.getTaxTreatment()); proof.put("taxExemptionReason",row.getTaxExemptionReason());
        proof.put("associatedCustomerId",row.getAssociatedCustomerId()); proof.put("packQtyPerProduct",row.getPackQtyPerProduct());
        return new LiveReadback(row.getId(), fields, row.getCreatedBy(),proof);
    }

    @Override
    public LiveReadback enrichReadback(DraftSnapshot draft, LiveReadback readback) {
        if (readback == null) return null;
        Map<String, Object> fields = new LinkedHashMap<>(readback.fields());
        fields.put("sourceStorageMode", draft.after().get("sourceStorageMode"));
        if (!clean(draft.targetLiveId()).isEmpty() && Boolean.TRUE.equals(((MaterialSource) draft.source()).draft().getDesiredPackagingCategory())) {
            for (String key : List.of("unitPrice","taxRate","taxTreatment","taxExemptionReason","associatedCustomerId","packQtyPerProduct")) fields.put(key,readback.proofFields().get(key));
        }
        return new LiveReadback(readback.id(), fields, readback.createdBy(),
                clean(draft.targetLiveId()).isEmpty() ? Map.of() : readback.proofFields());
    }

    @Override
    public boolean recordsCreatedBy() {
        return true;
    }

    /** The segment service's label identity (NFKC, lower case, no whitespace). */
    private static String identity(String value) {
        return Normalizer.normalize(clean(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isPackagingLabel(String value) {
        return "包材".equals(clean(value)) || "PACKAGING".equalsIgnoreCase(clean(value))
                || "PACKAGE".equalsIgnoreCase(clean(value));
    }
}
