package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.TypeItem;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.blueprint.interview.FactoryBuildApplyValidator;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftCommand;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.DraftRequest;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.RequestedMaterial;
import com.cretas.aims.service.materialdraft.MaterialTypeDraftModels.StorageMode;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code materials} proposal: one {@link MaterialTypeDraftCommand} request per
 * {@code suggestedData.materialTypes} entry, keyed by the material code the interview gave.
 *
 * <p>New vs update follows the command, which identifies a material by its <b>name</b>
 * ({@code LOWER(TRIM(name))}), not by code: with a blank {@code targetMaterialTypeId} it refuses a
 * name that already exists. So the proposal looks the name up with
 * {@link RawMaterialTypeRepository#findByFactoryIdAndNormalizedName} (the same expression): one
 * hit is targeted. With several hits no target is accepted (each one collides with the others'
 * name), so the user is asked for another name instead.</p>
 *
 * <p>Classification: the command takes an active L1 (3 digits) or L3 (10 digits) segment code of
 * this factory, or a <b>planned</b> L1 — a code of the current blueprint's {@code codeSegments} that is
 * not in the table yet, created when the first material under it is promoted. The choosable L1 are
 * therefore the table's active L1 ∪ the blueprint's {@code codeSegments}, deduplicated by code (the
 * table wins on a shared code; a different blueprint label is noted in the evidence). The interview's
 * facts are the explicit {@code category} label, else the material's structural position in the process
 * recipes (aux → 辅料, pack → 包材, via {@link FactoryBuildApplyValidator#deriveMaterialRoles}).
 * ⛔ The legacy apply's "anything else is 原料" fallback is not used: absence from the aux/pack lists is
 * not evidence of being raw. Unresolved => asked. Whether a planned category gets packaging semantics is
 * not a blueprint fact (not even for a category named 包材): it is always asked, never pre-filled — the
 * framework emits every listed input as null, so a pre-fill would not survive anyway.</p>
 *
 * <p>Storage mode, shelf life and the by-product flag are not interview facts and are always asked,
 * except storage mode under a packaging L1, which the command requires to be empty.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class MaterialsDraftProposer implements CapabilityDraftProposer {

    /** {@code MATERIAL_DRAFT_NAME_TOO_LONG}. */
    static final int MAX_NAME = 200;
    static final String PLANNED_SUFFIX_TEXT = "蓝图，转正时新建";
    /** Appended to a planned category's option label. */
    static final String PLANNED_SUFFIX = "（" + PLANNED_SUFFIX_TEXT + "）";
    static final List<Option> STORAGE_OPTIONS = Option.ofEnum(StorageMode.class, Map.of(
            StorageMode.FROZEN, "冷冻", StorageMode.REFRIGERATED, "冷藏", StorageMode.AMBIENT, "常温"));
    private static final String STORAGE_VALUES = String.join("、",
            STORAGE_OPTIONS.stream().map(o -> o.value() + "（" + o.label() + "）").toList()) + "之一";

    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialCodeSegmentRepository segmentRepository;
    private final FactoryBuildApplyValidator interviewRules;

    public MaterialsDraftProposer(RawMaterialTypeRepository materialTypeRepository,
                                  MaterialCodeSegmentRepository segmentRepository,
                                  FactoryBuildApplyValidator interviewRules) {
        this.materialTypeRepository = materialTypeRepository;
        this.segmentRepository = segmentRepository;
        this.interviewRules = interviewRules;
    }

    @Override
    public String capabilityId() {
        return MaterialTypeDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        ApplyAIInitRequest.SuggestedData suggested = source.blueprint().getSourcePayload().getSuggestedData();
        List<TypeItem> materials = suggested == null || suggested.getMaterialTypes() == null
                ? List.of() : suggested.getMaterialTypes();

        Set<String> seen = new HashSet<>();
        for (TypeItem material : materials) {
            String code = text(material == null ? null : material.getCode());
            if (!code.isEmpty() && !seen.add(code)) {
                return Proposed.blocked("BLUEPRINT_DUPLICATE_MATERIAL_CODE",
                        "建厂蓝图里物料编号“" + code + "”出现了不止一次，无法确定每条草稿对应哪个物料",
                        "回到建厂访谈，修正重复的物料编号");
            }
        }
        if (materials.stream().allMatch(m -> m == null)) {
            // No materials => no items; the controller reports BLUEPRINT_PROPOSAL_EMPTY.
            return new Proposed(List.of());
        }

        Set<String> roleConflicts = new HashSet<>();
        Map<String, String> roles = interviewRules.deriveMaterialRoles(suggested, roleConflicts);
        Categories categories = categories(
                segmentRepository.findByFactoryIdOrderBySortOrderAscIdAsc(source.factoryId()),
                suggested.getCodeSegments());
        if (categories.offered().isEmpty()) {
            // Every material's classification would be asked with nothing to choose from.
            return Proposed.blocked("BLUEPRINT_MATERIAL_CLASSIFICATION_MISSING",
                    "本厂还没有启用的物料大类，建厂蓝图里也没有可用的计划大类，物料草稿的分类无从选择",
                    "先在物料分类里建立并启用大类（如原料、辅料、包材），或回到建厂访谈补上物料大类，再回来预填");
        }

        List<ProposedItem> items = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            TypeItem material = materials.get(i);
            if (material == null) continue;
            items.add(item(source, i, material, roles, roleConflicts, categories));
        }
        return new Proposed(items);
    }

    /**
     * One choosable L1: from the factory's segment table ({@code planned=false}) or from the blueprint's
     * {@code codeSegments} and not in the table yet ({@code planned=true}, created on promotion).
     */
    record Category(String code, String label, boolean planned, boolean packaging, String evidencePath) {
        Option option() {
            return new Option(code, code.startsWith("category:") ? label + "（不选可选分类）" : label + " / 分类ID " + code);
        }
    }

    /**
     * @param offered  formal active L1 first (table order), then the blueprint's planned ones (payload order)
     * @param notes    blueprint category facts that were <em>not</em> offered as given, and why — attached to
     *                 every item's evidence so the user sees why a blueprint category is missing or renamed
     */
    record Categories(List<Category> offered, List<Evidence> notes) {
        boolean anyPlanned() {
            return offered.stream().anyMatch(Category::planned);
        }
    }

    /**
     * Union of the table's active L1 and the blueprint's {@code codeSegments}, deduplicated by code. The
     * command decides a code that exists in the table in any state by the table, so a blueprint entry is
     * offered only when its code is absent from the table, its code is 3 digits, its label is non-blank and
     * unambiguous, and its label is not already another L1's (promotion could not create it).
     */
    static Categories categories(List<MaterialCodeSegment> nodes, List<ApplyAIInitRequest.CodeSegmentItem> planned) {
        List<Category> offered = new ArrayList<>();
        // Current main makes taxonomy optional. These explicit choices carry only the basic
        // material category; they do not pretend that a taxonomy node was created or selected.
        for (String category : List.of("原料", "辅料", "包材")) {
            offered.add(new Category("category:" + category, category, false,
                    "包材".equals(category), "formalMaterial.category"));
        }
        Map<Long, MaterialCodeSegment> byId = new LinkedHashMap<>();
        for (MaterialCodeSegment node : nodes) if (node.getId() != null) byId.put(node.getId(), node);
        for (MaterialCodeSegment node : nodes) {
            if (!Short.valueOf((short)3).equals(node.getLevel()) || !Boolean.TRUE.equals(node.getIsActive())) continue;
            MaterialCodeSegment parent = byId.get(node.getParentId());
            MaterialCodeSegment top = parent == null ? null : byId.get(parent.getParentId());
            if (parent == null || top == null || !Short.valueOf((short)2).equals(parent.getLevel())
                    || !Short.valueOf((short)1).equals(top.getLevel()) || top.getParentId() != null
                    || !Boolean.TRUE.equals(parent.getIsActive()) || !Boolean.TRUE.equals(top.getIsActive())) continue;
            String category = text(top.getSegmentLabel());
            if (com.cretas.aims.entity.material.MaterialMainCategory.of(category) == null) continue;
            offered.add(new Category(String.valueOf(node.getId()), category, false,
                    "PACKAGING".equals(com.cretas.aims.entity.material.MaterialMainCategory.of(category)),
                    "factory.materialCodeSegments[id=" + node.getId() + "]"));
        }
        return new Categories(offered, List.of());
    }

    private ProposedItem item(Source source, int index, TypeItem material, Map<String, String> roles,
                              Set<String> roleConflicts, Categories categories) {
        List<Option> classificationOptions = categories.offered().stream().map(Category::option).toList();
        String classificationConstraint = "明确选择基本类型（不绑定可选分类），或本厂父链完整且启用的三级分类 ID";
        var blueprint = source.blueprint();
        String from = "sourcePayload.suggestedData.materialTypes[" + index + "].";
        String code = text(material.getCode());
        String name = text(material.getName());
        String unit = text(material.getUnit());
        String title = name.isEmpty() ? (code.isEmpty() ? "第 " + (index + 1) + " 个物料" : code) : name;
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();

        if (!code.isEmpty()) evidence.add(new Evidence(from + "code", code));
        if (name.isEmpty()) {
            inputs.add(RequiredInput.text("desired.name", "“" + title + "”的物料名称",
                    "建厂访谈没有给这个物料名称",
                    "不能为空，不超过 " + MAX_NAME + " 字；与本厂已有物料同名会被当作更新那个物料", MAX_NAME));
        } else {
            evidence.add(new Evidence(from + "name", name));
        }
        if (unit.isEmpty()) {
            inputs.add(RequiredInput.text("desired.unit", "“" + title + "”的库存单位",
                    "建厂访谈没有给这个物料的单位", "本厂单位目录里能用于库存数量的单位（质量、体积、计数或已授权包装单位）",
                    null));
        } else {
            evidence.add(new Evidence(from + "unit", unit));
        }

        List<RawMaterialType> existing = name.isEmpty() ? List.of()
                : materialTypeRepository.findByFactoryId(source.factoryId()).stream().filter(row -> row.getName() != null && row.getName().trim().equalsIgnoreCase(name)).toList();
        Category currentCategory = existing.size() == 1 ? categories.offered().stream()
                .filter(c -> !c.planned() && c.code().equals(existing.get(0).getClassificationSegmentId() == null ? "category:" + existing.get(0).getCategory() : String.valueOf(existing.get(0).getClassificationSegmentId())))
                .findFirst().orElse(null) : null;

        // ---- classification
        String label;
        String labelSource;
        if (currentCategory != null) {
            label = currentCategory.label();
            labelSource = "factory.rawMaterialTypes[name=" + name + "].category（更新沿用正式分类）";
        } else if (!text(material.getCategory()).isEmpty()) {
            label = text(material.getCategory());
            labelSource = from + "category";
        } else if (!name.isEmpty() && roleConflicts.contains(name)) {
            label = null;
            labelSource = null;
        } else {
            label = name.isEmpty() ? null : roles.get(name);
            labelSource = label == null ? null : "sourcePayload.suggestedData.workProcesses[*]."
                    + ("包材".equals(label) ? "packMaterials" : "auxMaterials") + "[name=" + name + "]";
        }
        Category l1 = currentCategory;
        String classificationReason;
        if (currentCategory != null) {
            classificationReason = null;
        } else if (label == null) {
            classificationReason = !name.isEmpty() && roleConflicts.contains(name)
                    ? "这个物料在工序里既被列为辅料又被列为包材，无法判断分类"
                    : "建厂访谈没有给这个物料的分类，它也不在任何工序的辅料/包材清单里";
        } else {
            List<Category> matches = categories.offered().stream()
                    .filter(c -> c.code().equals("category:" + label)).toList();
            if (matches.size() == 1) {
                l1 = matches.get(0);
                classificationReason = null;
            } else {
                classificationReason = matches.isEmpty()
                        ? "访谈给的分类“" + label + "”在本厂启用的物料大类" + (categories.anyPlanned() ? "和蓝图计划的大类" : "")
                                + "里找不到"
                        : "有多个可选大类都叫“" + label + "”";
            }
        }
        // Packaging semantics are known only for a category from the table; a planned one is asked.
        boolean packagingKnown = l1 != null && !l1.planned();
        boolean packaging = packagingKnown && l1.packaging();
        if (l1 == null) {
            inputs.add(RequiredInput.oneOf("desired.classificationSegmentCode", "“" + title + "”属于哪个物料分类",
                    classificationReason, classificationConstraint, classificationOptions));
            if (categories.anyPlanned()) {
                inputs.add(RequiredInput.bool("desired.classificationPackagingCategory",
                        "如果选的是蓝图里计划新建的大类：这个大类是否按包材管理",
                        "标着“" + PLANNED_SUFFIX_TEXT + "”的大类本厂还没有，转正时才新建；蓝图没有说它是否按包材管理"
                                + "（按包材管理的物料必须维护含税采购参考价，且不配置储存方式）",
                        "选蓝图计划新建的大类时必填（是 / 否）；选本厂已有大类时留空").allowNull());
            }
        } else {
            evidence.add(new Evidence(labelSource, label));
            if (l1.planned()) {
                evidence.add(new Evidence(l1.evidencePath() + ".code", l1.code()));
                inputs.add(RequiredInput.bool("desired.classificationPackagingCategory",
                        "大类“" + l1.label() + "”是否按包材管理",
                        "大类“" + l1.label() + "”（" + l1.code() + "）来自建厂蓝图，本厂还没有，转正时才新建；"
                                + "蓝图没有说它是否按包材管理（按包材管理的物料必须维护含税采购参考价，且不配置储存方式）",
                        "是 / 否；同一份蓝图下归入这个大类的物料必须回答一致"));
            } else {
                evidence.add(new Evidence("factory.materialCodeSegments[level=1,label=" + label + "].segmentCode",
                        l1.code()));
            }
        }
        evidence.addAll(categories.notes());

        // ---- storage / shelf life / by-product: never interview facts
        if (!packaging) {
            RequiredInput storage = RequiredInput.oneOf("desired.storageMode", "“" + title + "”怎么储存",
                    "建厂访谈没有问物料的储存方式",
                    packagingKnown ? "必填：" + STORAGE_VALUES
                            : "非包材必填：" + STORAGE_VALUES + "；若分类按包材管理则必须留空",
                    STORAGE_OPTIONS);
            // Packaging semantics still open: the category may turn out to be a packaging one, which
            // requires the storage mode empty.
            inputs.add(packagingKnown ? storage : storage.allowNull());
        }
        inputs.add(RequiredInput.integer("desired.shelfLifeDays", "“" + title + "”保质期多少天",
                "建厂访谈没有问物料的保质期", "至少 1 的整数天；未知或不适用时明确留空", 1, null).allowNull());
        inputs.add(RequiredInput.bool("desired.byproduct", "“" + title + "”是不是加工副产物",
                "建厂访谈没有把物料标成副产物或非副产物", "是 / 否"));

        // ---- new vs update (the command's identity is the normalized name)
        String targetId = null;
        String requestName = name;
        if (!name.isEmpty()) {
            if (existing.size() == 1) {
                targetId = existing.get(0).getId();
                evidence.add(new Evidence("factory.rawMaterialTypes[name=" + name + "].id", targetId));
            } else if (existing.size() > 1) {
                // Targeting any one of them is refused (the others keep the name: MATERIAL_DRAFT_NAME_CONFLICT)
                // and a blank target is refused (MATERIAL_DRAFT_TARGET_REQUIRED). The only answer the draft
                // itself can carry is another name.
                inputs.add(RequiredInput.text("desired.name", "“" + title + "”换个什么名称",
                        "本厂已有 " + existing.size() + " 条同名物料“" + name + "”，更新哪一条都会与另一条重名，新建也会重名",
                        "本厂没有的名称，不超过 " + MAX_NAME + " 字；或先由主数据负责人合并同名物料后重新预填", MAX_NAME));
                requestName = "";
            }
        }

        RequestedMaterial desired = new RequestedMaterial(nullIfEmpty(requestName), l1 == null ? null : l1.code(),
                nullIfEmpty(unit), null, null, false, null);
        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), targetId, desired, null, null, 0L);
        String itemKey = code.isEmpty() ? "#" + (index + 1) : code;
        return new ProposedItem(itemKey, (targetId == null ? "新建物料：" : "更新物料：") + title, request, inputs, evidence);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** The segment service's label identity (NFKC, lower case, no whitespace). */
    private static String identity(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }
}
