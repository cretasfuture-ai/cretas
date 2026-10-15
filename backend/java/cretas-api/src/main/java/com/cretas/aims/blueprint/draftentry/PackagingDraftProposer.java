package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.TypeItem;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.material.MaterialTypeDraft;
import com.cretas.aims.entity.product.ProductTypeDraft;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import com.cretas.aims.repository.product.ProductTypeDraftRepository;
import com.cretas.aims.service.packagingdraft.PackagingDraftCommand;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.DraftRequest;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.PackagingItem;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.PackagingRole;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.TargetKind;
import com.cretas.aims.service.packagingdraft.PackagingDraftModels.TargetRef;
import com.cretas.aims.service.unit.UnitContractService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code packaging} proposal: one primary packaging level per product whose interview record has a
 * case spec ({@code productTypes[].level1Unit} + {@code boxConversionCoefficient}: "1 箱 = 20 斤").
 *
 * <p>What the fact maps to is the domain's existing rule, not a choice made here:
 * {@code ProductPackagingSpecServiceImpl.trySynchronizeLegacyDefault} turns exactly this pair into
 * the product's one spec named 默认箱规, default, sort 0, package unit = level1Unit, base unit =
 * product unit. In the command's hierarchy a level that directly contains the product's base unit
 * is the primary container ({@code PACKAGING_PRIMARY_BASE_UNIT_MISMATCH} otherwise), so the role
 * follows too. Net content and label references were never asked and are optional in the command;
 * they stay empty (no net content, label NONE) rather than being invented.</p>
 *
 * <p>The target must pin the product DRAFT of this blueprint lineage (factory, blueprint, version,
 * source digest, status DRAFT, natural key = product code), latest first. A product with a case spec
 * but no such DRAFT blocks the whole proposal: packaging depends on products and the pin is never
 * invented. {@code conversionRates} (material → product yields) have no field in the packaging
 * command and are not carried.</p>
 *
 * <p>Products without a case spec get no item: "not sold by the case" is a legal state, not a gap.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class PackagingDraftProposer implements CapabilityDraftProposer {

    static final String SPEC_NAME = "默认箱规";
    /** The command's limits: unit length 20, factor &gt; 0 with at most 8 decimals ({@code PACKAGING_FACTOR_INVALID}). */
    static final int MAX_UNIT_LENGTH = 20;
    static final BigDecimal MIN_FACTOR = new BigDecimal("0.00000001");

    private final ProductTypeDraftRepository productDrafts;
    private final RawMaterialTypeRepository materials;
    private final PackagingDraftCommand packagingAuthority;
    private final UnitContractService units;
    private final MaterialTypeDraftRepository materialDrafts;

    /** Legacy narrow-test constructor; Spring uses the authority-aware constructor. */
    public PackagingDraftProposer(ProductTypeDraftRepository productDrafts, UnitContractService units) {
        this(productDrafts, null, null, units, null);
    }

    public PackagingDraftProposer(ProductTypeDraftRepository productDrafts, RawMaterialTypeRepository materials,
                                  PackagingDraftCommand packagingAuthority, UnitContractService units) {
        this(productDrafts, materials, packagingAuthority, units, null);
    }

    @Autowired
    public PackagingDraftProposer(ProductTypeDraftRepository productDrafts, RawMaterialTypeRepository materials,
                                  PackagingDraftCommand packagingAuthority, UnitContractService units,
                                  MaterialTypeDraftRepository materialDrafts) {
        this.productDrafts = productDrafts;
        this.materials = materials;
        this.packagingAuthority = packagingAuthority;
        this.units = units;
        this.materialDrafts = materialDrafts;
    }

    @Override
    public String capabilityId() {
        return PackagingDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        var blueprint = source.blueprint();
        ApplyAIInitRequest.SuggestedData suggested = blueprint.getSourcePayload().getSuggestedData();
        List<TypeItem> productTypes = suggested == null || suggested.getProductTypes() == null
                ? List.of() : suggested.getProductTypes();
        boolean hasConversionRates = suggested != null && suggested.getConversionRates() != null
                && !suggested.getConversionRates().isEmpty();

        // product index in productTypes -> fact, only products that declared a case spec
        Map<Integer, TypeItem> cased = new LinkedHashMap<>();
        for (int i = 0; i < productTypes.size(); i++) {
            TypeItem product = productTypes.get(i);
            if (product != null && (!blank(product.getLevel1Unit()) || product.getBoxConversionCoefficient() != null)) {
                cased.put(i, product);
            }
        }
        List<PackMaterialFact> packMaterials = packMaterialFacts(suggested);
        if (cased.isEmpty() && packMaterials.isEmpty()) {
            return Proposed.blocked("BLUEPRINT_PACKAGING_SPEC_MISSING",
                    "蓝图里没有任何产品登记箱规（一箱装多少）"
                            + (hasConversionRates ? "；访谈里的出成率（转换率）是原料到成品的比例，不是包装规格，包装草稿不承载它" : ""),
                    "回到建厂访谈补充产品的箱规，或在包装页逐项填写");
        }

        Map<Integer, ProductTypeDraft> pins = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Integer, TypeItem> entry : cased.entrySet()) {
            String code = trim(entry.getValue().getCode());
            ProductTypeDraft pin = code.isEmpty() ? null : latestProductDraft(source, code);
            if (pin == null) {
                missing.add(code.isEmpty() ? "第 " + (entry.getKey() + 1) + " 个产品（没有编号）" : code);
            } else {
                pins.put(entry.getKey(), pin);
            }
        }
        if (!missing.isEmpty()) {
            return Proposed.blocked("BLUEPRINT_DRAFT_UPSTREAM_MISSING",
                    "这些产品还没有本蓝图的产品草稿，包装必须钉住产品草稿：" + String.join("、", missing),
                    "先写产品草稿（products）");
        }

        List<PackagingItem> items = new ArrayList<>();
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        Set<String> targetIdentities = new LinkedHashSet<>();
        for (Map.Entry<Integer, TypeItem> entry : cased.entrySet()) {
            PackagingItem packagingItem = item(items.size(), entry.getKey(), entry.getValue(), pins.get(entry.getKey()), source,
                    inputs, evidence);
            items.add(packagingItem);
            targetIdentities.add(targetIdentity(packagingItem.target()));
        }
        if (!packMaterials.isEmpty()) {
            Proposed blocked = appendMaterialPackaging(source, suggested, packMaterials, items, inputs, evidence,
                    targetIdentities);
            if (blocked != null) return blocked;
        }
        if (hasConversionRates) {
            evidence.add(new Evidence("sourcePayload.suggestedData.conversionRates",
                    suggested.getConversionRates().size() + " 条（原料到成品的出成率，包装草稿没有承载字段，未带入）"));
        }

        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), items, null, null, 0L);
        String label = cased.isEmpty() ? "包材包装规格" : packMaterials.isEmpty() ? "产品包装规格" : "产品与包材包装规格";
        return Proposed.single(capabilityId(), label, request, inputs, evidence);
    }

    private Proposed appendMaterialPackaging(Source source, ApplyAIInitRequest.SuggestedData suggested,
                                              List<PackMaterialFact> facts, List<PackagingItem> items,
                                              List<RequiredInput> inputs, List<Evidence> evidence,
                                              Set<String> targetIdentities) {
        if (materials == null || packagingAuthority == null) {
            return Proposed.blocked("BLUEPRINT_PACKAGING_MATERIAL_AUTHORITY_UNAVAILABLE",
                    "包材 packaging authority 未接入，已停止生成包装 pin", "刷新当前工厂包材主数据后重试");
        }
        Map<String, TypeItem> byNameOrCode = new LinkedHashMap<>();
        List<TypeItem> sourceMaterials = suggested.getMaterialTypes() == null ? List.of() : suggested.getMaterialTypes();
        for (TypeItem material : sourceMaterials) {
            if (material == null) continue;
            if (!trim(material.getCode()).isEmpty()) byNameOrCode.putIfAbsent(trim(material.getCode()), material);
            if (!trim(material.getName()).isEmpty()) byNameOrCode.putIfAbsent(trim(material.getName()), material);
        }
        for (int index = 0; index < facts.size(); index++) {
            PackMaterialFact fact = facts.get(index);
            TypeItem sourceMaterial = byNameOrCode.get(fact.name());
            if (sourceMaterial == null) {
                return Proposed.blocked("BLUEPRINT_PACKAGING_MATERIAL_UPSTREAM_MISSING",
                        "访谈列出的包材“" + fact.name() + "”没有对应物料主数据，不能虚构包装目标",
                        "先确认并写入该包材的物料草稿，再回来填写包装规格");
            }
            MaterialTarget target = resolveMaterialTarget(source, sourceMaterial);
            if (target == null) {
                return Proposed.blocked("BLUEPRINT_PACKAGING_MATERIAL_UPSTREAM_MISSING",
                        "包材“" + fact.name() + "”还没有当前工厂有效物料，包装必须钉住真实 MATERIAL target",
                        "先写该包材的物料草稿并转正，或刷新当前工厂物料目录");
            }
            String identity = targetIdentity(target.kind(), target.id());
            if (!targetIdentities.add(identity)) {
                evidence.add(new Evidence(fact.path(), fact.name() + "（与已有包装目标重复，已合并）"));
                continue;
            }
            var authority = packagingAuthority.currentTargetAuthority(source.factoryId(),
                    source.blueprint().getId(), source.blueprint().getVersion(), source.sourceDigest(),
                    target.kind(), target.id()).orElse(null);
            if (authority == null) {
                return Proposed.blocked("BLUEPRINT_PACKAGING_MATERIAL_AUTHORITY_UNAVAILABLE",
                        "包材“" + fact.name() + "”的 packaging authority 无法解析，已停止生成包装 pin",
                        "刷新当前工厂包材主数据后重试");
            }
            int itemIndex = items.size();
            String at = "items[" + itemIndex + "].";
            String shown = "“" + fact.name() + "”";
            String materialPath = "sourcePayload.suggestedData.materialTypes[" + sourceMaterials.indexOf(sourceMaterial) + "].";
            evidence.add(new Evidence(fact.path(), fact.name()));
            evidence.add(new Evidence(materialPath + "code", trim(sourceMaterial.getCode())));
            evidence.add(new Evidence(materialPath + "unit", trim(target.baseUnit())));
            if (fact.quantity() != null) {
                evidence.add(new Evidence(fact.path() + ".quantity", fact.quantity().stripTrailingZeros().toPlainString()
                        + (trim(fact.unit()).isEmpty() ? "" : " " + trim(fact.unit()))
                        + "（工序用量事实，不自动当作净含量或包装换算）"));
            }
            inputs.add(RequiredInput.text(at + "packageUnit", shown + "按什么包装单位管理",
                    "访谈确认了包材名称，但没有确认包装层级单位",
                    "当前工厂包装或计数单位，不能等于内装单位，不超过 " + MAX_UNIT_LENGTH + " 字", MAX_UNIT_LENGTH));
            String containedUnit = trim(target.baseUnit());
            if (containedUnit.isEmpty()) {
                inputs.add(RequiredInput.text(at + "containedUnit", shown + "内装单位",
                        "物料主数据没有有效库存单位", "当前工厂有效物料单位", MAX_UNIT_LENGTH));
                containedUnit = null;
            }
            inputs.add(RequiredInput.decimal(at + "unitsPerPackage", shown + "一个包装里装多少",
                    "原始包材事实没有提供包装换算，不能猜测",
                    "大于 0（最小 " + MIN_FACTOR.toPlainString() + "）且最多 8 位小数", MIN_FACTOR, null));
            items.add(new PackagingItem(new TargetRef(target.kind(), target.id(), authority.snapshotDigest()),
                    PackagingRole.PRIMARY_CONTAINER, "包材包装规格", null, containedUnit, null, null, null,
                    true, itemIndex, null, "依据建厂访谈 " + fact.path() + "：包材“" + fact.name()
                            + "；包装单位与装量待确认"));
        }
        return null;
    }

    private RawMaterialType resolveMaterial(String factoryId, TypeItem source) {
        String code = trim(source.getCode());
        if (!code.isEmpty()) {
            RawMaterialType row = materials.findByFactoryIdAndCode(factoryId, code).orElse(null);
            if (row != null && Boolean.TRUE.equals(row.getIsActive())) return row;
        }
        String name = trim(source.getName());
        if (name.isEmpty()) return null;
        List<RawMaterialType> rows = materials.findByFactoryId(factoryId).stream()
                .filter(row -> row.getName() != null && row.getName().trim().equalsIgnoreCase(name))
                .toList();
        return rows.size() == 1 && Boolean.TRUE.equals(rows.get(0).getIsActive()) ? rows.get(0) : null;
    }

    private MaterialTarget resolveMaterialTarget(Source source, TypeItem material) {
        RawMaterialType live = resolveMaterial(source.factoryId(), material);
        if (live != null) {
            var authority = packagingAuthority.currentTargetAuthority(source.factoryId(), source.blueprint().getId(),
                    source.blueprint().getVersion(), source.sourceDigest(), TargetKind.MATERIAL, live.getId()).orElse(null);
            return authority == null ? null : new MaterialTarget(TargetKind.MATERIAL, live.getId(), authority.snapshotDigest(),
                    live.getUnit());
        }
        List<MaterialTypeDraft> drafts = sameLineageMaterialDrafts(source, material);
        if (drafts.size() != 1) return null;
        MaterialTypeDraft draft = drafts.get(0);
        var authority = packagingAuthority.currentTargetAuthority(source.factoryId(), source.blueprint().getId(),
                source.blueprint().getVersion(), source.sourceDigest(), TargetKind.MATERIAL_DRAFT, draft.getId()).orElse(null);
        return authority == null ? null : new MaterialTarget(TargetKind.MATERIAL_DRAFT, draft.getId(), authority.snapshotDigest(),
                draft.getDesiredUnit());
    }

    private List<MaterialTypeDraft> sameLineageMaterialDrafts(Source source, TypeItem material) {
        if (materialDrafts == null || trim(material.getName()).isEmpty()) return List.of();
        MaterialTypeDraft probe = new MaterialTypeDraft();
        probe.setFactoryId(source.factoryId());
        probe.setBlueprintId(source.blueprint().getId());
        probe.setBlueprintVersion(source.blueprint().getVersion().longValue());
        probe.setSourceDigest(source.sourceDigest());
        probe.setNaturalKey("NEW:" + normalizeIdentity(material.getName()));
        probe.setDesiredName(trim(material.getName()));
        probe.setStatus("DRAFT");
        return materialDrafts.findAll(Example.of(probe), Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    private static String targetIdentity(TargetRef target) {
        return targetIdentity(target.kind(), target.targetId());
    }

    private static String targetIdentity(TargetKind kind, String id) {
        return kind.name() + ":" + id;
    }

    private static String normalizeIdentity(String value) {
        return Normalizer.normalize(trim(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record MaterialTarget(TargetKind kind, String id, String snapshotDigest, String baseUnit) {}

    private List<PackMaterialFact> packMaterialFacts(ApplyAIInitRequest.SuggestedData suggested) {
        if (suggested == null || suggested.getWorkProcesses() == null) return List.of();
        List<PackMaterialFact> facts = new ArrayList<>();
        for (int processIndex = 0; processIndex < suggested.getWorkProcesses().size(); processIndex++) {
            ApplyAIInitRequest.WorkProcessItem process = suggested.getWorkProcesses().get(processIndex);
            if (process == null || process.getPackMaterials() == null) continue;
            for (int materialIndex = 0; materialIndex < process.getPackMaterials().size(); materialIndex++) {
                ApplyAIInitRequest.RecipeItem item = process.getPackMaterials().get(materialIndex);
                if (item == null || trim(item.getName()).isEmpty()) continue;
                facts.add(new PackMaterialFact(trim(item.getName()), item.getQuantity(), item.getUnit(),
                        "sourcePayload.suggestedData.workProcesses[" + processIndex + "].packMaterials[" + materialIndex + "]"));
            }
        }
        return facts.stream().collect(java.util.stream.Collectors.toMap(PackMaterialFact::name, fact -> fact,
                (left, ignored) -> left, LinkedHashMap::new)).values().stream().toList();
    }

    private record PackMaterialFact(String name, BigDecimal quantity, String unit, String path) {}

    private PackagingItem item(int index, int productIndex, TypeItem product, ProductTypeDraft pin, Source source,
                               List<RequiredInput> inputs, List<Evidence> evidence) {
        String at = "items[" + index + "].";
        String from = "sourcePayload.suggestedData.productTypes[" + productIndex + "].";
        String name = trim(product.getName());
        String shown = "“" + (name.isEmpty() || name.length() > 100 ? trim(product.getCode()) : name) + "”";
        evidence.add(new Evidence(from + "code", trim(product.getCode())));
        evidence.add(new Evidence("productDrafts." + trim(product.getCode()), pin.getId()));

        String packageUnit = trim(product.getLevel1Unit());
        if (packageUnit.isEmpty()) {
            inputs.add(RequiredInput.text(at + "packageUnit", shown + "按什么包装卖（箱、袋…）",
                    "访谈给了一箱装多少，却没说“箱”是什么单位",
                    "本厂单位目录里的包装或计数单位，不超过 " + MAX_UNIT_LENGTH + " 字，不能与内装单位相同", MAX_UNIT_LENGTH));
            packageUnit = null;
        } else {
            evidence.add(new Evidence(from + "level1Unit", packageUnit));
        }

        // The coefficient counts the interview's product unit; it only carries over when that unit
        // is the product DRAFT's base unit, which the command checks the primary level against.
        String containedUnit = trim(product.getUnit());
        boolean sameBase = !containedUnit.isEmpty()
                && units.areEquivalent(source.factoryId(), containedUnit, pin.getDesiredUnit());
        BigDecimal factor = product.getBoxConversionCoefficient();
        if (!sameBase) {
            // The primary level must contain the product DRAFT's base unit (PACKAGING_PRIMARY_BASE_UNIT_MISMATCH):
            // exactly one legal answer, offered as the only option rather than typed in.
            inputs.add(RequiredInput.oneOf(at + "containedUnit", shown + "一个包装里装的是什么单位",
                    "产品草稿的基本单位是“" + pin.getDesiredUnit() + "”，访谈里的单位是“" + containedUnit + "”，两者不同",
                    "必须是产品草稿的基本单位“" + pin.getDesiredUnit() + "”",
                    List.of(new Option(pin.getDesiredUnit(), pin.getDesiredUnit()))));
            containedUnit = null;
        } else {
            evidence.add(new Evidence(from + "unit", containedUnit));
        }
        if (factor == null || factor.signum() <= 0 || !sameBase) {
            inputs.add(RequiredInput.decimal(at + "unitsPerPackage", shown + "一个包装里装多少",
                    factor == null ? "访谈给了包装单位，却没说一个装多少"
                            : !sameBase ? "访谈里的数量按“" + trim(product.getUnit()) + "”计，与产品草稿的基本单位不同，要重新确认"
                            : "访谈给的数量不是正数",
                    "大于 0（最小 " + MIN_FACTOR.toPlainString() + "）的精确数，最多 8 位小数", MIN_FACTOR, null));
            factor = null;
        } else {
            evidence.add(new Evidence(from + "boxConversionCoefficient", factor.stripTrailingZeros().toPlainString()));
        }

        String spec = packageUnit == null || containedUnit == null || factor == null ? ""
                : " 1" + packageUnit + "=" + factor.stripTrailingZeros().toPlainString() + containedUnit;
        return new PackagingItem(
                new TargetRef(TargetKind.PRODUCT_DRAFT, pin.getId(), productDraftSnapshot(pin)),
                PackagingRole.PRIMARY_CONTAINER,
                SPEC_NAME,
                packageUnit,
                containedUnit,
                factor,
                null,
                null,
                true,
                0,
                null,
                "依据建厂蓝图 v" + source.blueprint().getVersion() + "：访谈登记" + shown + "箱规" + spec);
    }

    /** Latest DRAFT row of this product in this blueprint lineage, or null. */
    private ProductTypeDraft latestProductDraft(Source source, String code) {
        ProductTypeDraft probe = new ProductTypeDraft();
        probe.setFactoryId(source.factoryId());
        probe.setBlueprintId(source.blueprint().getId());
        probe.setBlueprintVersion(source.blueprint().getVersion().longValue());
        probe.setSourceDigest(source.sourceDigest());
        probe.setStatus("DRAFT");
        probe.setNaturalKey(code);
        List<ProductTypeDraft> rows = productDrafts.findAll(Example.of(probe),
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("createdAt")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * What {@link PackagingDraftCommand} compares {@code target.targetSnapshotDigest} with for a
     * {@code PRODUCT_DRAFT} target (its {@code resolveProductDraft} "authority" digest). The command
     * keeps this private, so it is restated here; ProposersLineATest runs the real command's
     * preview and fails on {@code PACKAGING_TARGET_SNAPSHOT_CONFLICT} if the two ever drift.
     */
    static String productDraftSnapshot(ProductTypeDraft row) {
        return digest("PRODUCT_DRAFT", row.getId(), row.getDesiredDigest(), row.getDesiredUnit(),
                row.getDesiredName(), row.getStatus(), String.valueOf(row.getLockVersion()),
                String.valueOf(row.getUpdatedAt()));
    }

    private static String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (bytes.length >>> 24));
                md.update((byte) (bytes.length >>> 16));
                md.update((byte) (bytes.length >>> 8));
                md.update((byte) bytes.length);
                md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
