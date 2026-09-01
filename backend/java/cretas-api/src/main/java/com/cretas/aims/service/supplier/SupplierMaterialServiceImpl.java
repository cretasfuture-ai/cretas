package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialDTO;
import com.cretas.aims.dto.supplier.SupplierMaterialRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SupplierMaterialServiceImpl implements SupplierMaterialService {
    /**
     * 供应关系与采购规格的记账币种 —— 全系统当前唯一取值。
     *
     * <p>🔒 2026-09-01 owner 裁定 (缺陷 21):「目前普遍默认币种肯定就是人民币,
     * 不可能出现其他币种」。⛔ 不删 {@code currency} 数据库列, 只把写入端钉住,
     * 将来要多币种时改这一处 + 把 UI 放回来即可。
     */
    static final String SUPPLIER_MATERIAL_CURRENCY = "CNY";

    private static final String PRICE_SOURCE_RELATION = "SUPPLIER_RELATION";
    private static final String PRICE_SOURCE_MATERIAL = "MATERIAL_REFERENCE";
    private static final String PRICE_SOURCE_UNCONFIGURED = "UNCONFIGURED";
    private final SupplierMaterialRepository repository;
    private final SupplierRepository supplierRepository;
    private final RawMaterialTypeRepository materialRepository;
    private final UnitContractService unitContractService;

    @Override @Transactional(readOnly = true)
    public List<SupplierMaterialDTO> listBySupplier(String factoryId, String supplierId) {
        ownedSupplier(factoryId, supplierId, false);
        return repository.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(factoryId, supplierId)
                .stream().map(this::toDto).toList();
    }

    @Override @Transactional(readOnly = true)
    public List<SupplierMaterialDTO> listByMaterial(String factoryId, String materialTypeId) {
        ownedMaterial(factoryId, materialTypeId);
        return repository.findByFactoryIdAndMaterialTypeIdOrderByActiveDescPreferredDescCreatedAtDesc(factoryId, materialTypeId)
                .stream().map(this::toDto).toList();
    }

    @Override @Transactional
    public SupplierMaterialDTO create(String factoryId, String supplierId, SupplierMaterialRequest request) {
        ownedSupplier(factoryId, supplierId, true);
        RawMaterialType material = ownedMaterial(factoryId, request.getMaterialTypeId());
        if (repository.findByFactoryIdAndSupplierIdAndMaterialTypeId(factoryId, supplierId, request.getMaterialTypeId()).isPresent()) {
            throw new BusinessException(409, "该供应商与物料的供应关系已存在").withHintTarget("materialTypeId");
        }
        requireCreationPrice(request);
        SupplierMaterial relation = new SupplierMaterial();
        relation.setFactoryId(factoryId); relation.setSupplierId(supplierId);
        relation.setMaterialTypeId(material.getId()); relation.setActive(request.getActive() == null || request.getActive());
        apply(relation, request, material);
        enforcePreferred(factoryId, relation);
        return toDto(repository.saveAndFlush(relation));
    }

    @Override @Transactional
    public SupplierMaterialDTO update(String factoryId, String supplierId, String relationId, SupplierMaterialRequest request) {
        ownedSupplier(factoryId, supplierId, true);
        SupplierMaterial relation = ownedRelation(factoryId, supplierId, relationId);
        if (request.getVersion() != null && !request.getVersion().equals(relation.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(SupplierMaterial.class, relationId);
        }
        if (request.getMaterialTypeId() != null && !request.getMaterialTypeId().equals(relation.getMaterialTypeId())) {
            throw new BusinessException(400, "已建立的供应关系不能更换物料，请停用后新建").withHintTarget("materialTypeId");
        }
        RawMaterialType material = ownedMaterial(factoryId, relation.getMaterialTypeId());
        apply(relation, request, material);
        enforcePreferred(factoryId, relation);
        return toDto(repository.saveAndFlush(relation));
    }

    @Override @Transactional
    public SupplierMaterialDTO deactivate(String factoryId, String supplierId, String relationId, Long version) {
        SupplierMaterial relation = ownedRelation(factoryId, supplierId, relationId);
        if (version != null && !version.equals(relation.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(SupplierMaterial.class, relationId);
        }
        if (!Boolean.TRUE.equals(relation.getActive())) return toDto(relation);
        relation.setActive(false); relation.setPreferred(false);
        return toDto(repository.save(relation));
    }

    private void enforcePreferred(String factoryId, SupplierMaterial target) {
        if (!Boolean.TRUE.equals(target.getActive())) target.setPreferred(false);
        if (!Boolean.TRUE.equals(target.getPreferred())) return;
        List<SupplierMaterial> active = repository.findByFactoryIdAndMaterialTypeIdAndActiveTrue(factoryId, target.getMaterialTypeId());
        active.stream().filter(other -> !java.util.Objects.equals(other.getId(), target.getId()))
                .filter(other -> Boolean.TRUE.equals(other.getPreferred()))
                .forEach(other -> other.setPreferred(false));
        repository.saveAll(active);
    }

    /**
     * 缺陷 17 后端闸 —— <b>新建</b>供应原料关系必须带默认采购价。
     *
     * <h2>为什么前端那条不够</h2>
     *
     * <p>web-admin 已经在 {@code SupplierDetailDrawer} 里拦了空价格, 但那是 <b>UX 闸不是执行边界</b>:
     * 直接调接口照样能建出没有价格的关系 —— LIUSHANMEN 现存那 4 条空价格关系
     * (DEMO235005→WL026/WL065/WL130、上海鹃城→YL063) 就是这么来的。
     *
     * <h2>⛔ 只拦 create, 绝不碰 update</h2>
     *
     * <p>{@link #apply} 把 {@code null} 当作<b>「本次不修改」</b>而不是「清空」。这个语义正是
     * 「不动价格就能改别的字段」之所以天然安全的原因 —— 那 4 条存量关系要保持可编辑
     * (owner 裁定: 逼人为了改启用状态先编一个价格出来, 比留空更糟, 因为编出来的价格
     * 会一直安静地错下去)。
     * ⇒ 所以这道闸<b>只装在 create 上</b>, 且⛔ <b>不能</b>改成把
     * {@code defaultPurchasePrice} 做成 {@code @NotNull} 的必填字段 —— 那会同时打在 update 上,
     * 从另一侧把 owner 那条裁定破坏掉。
     *
     * <h2>判据是 &gt; 0, 与既有校验同口径</h2>
     *
     * <p>{@link #apply} 里对已给出的价格就是 {@code <= 0} 即 400
     * ({@code SUPPLIER_MATERIAL_PRICE_INVALID})。这里只补「create 时不许不给」这一种情况,
     * 具体的数值合法性仍然由那一处判 —— ⛔ 不在两处各写一份大小判断。
     *
     * <p>创建路径实测<b>只有一条</b>: {@code SupplierMaterialController:32}。
     * 关系仓储的 {@code save/saveAll} 在 main 树里除本类外<b>零命中</b>
     * (MaterialPackagingHierarchy / PurchaseService / PurchaseSpec 三处都只读),
     * 供应商 Excel 导入也建不了关系 ({@code SupplierImportTemplateRow} 只有 9 个档案字段)。
     * ⇒ 没有任何合法内部路径会被这道闸误伤。
     */
    private void requireCreationPrice(SupplierMaterialRequest request) {
        // 19② 之后界面填的是【含税】, 所以「给没给价格」要认两个字段中的任意一个。
        // ⚠️ 这不是放松必填性(仍然必填), 只是承认权威字段换了 —— 只认未税会让
        //    「照界面填了含税」的新建请求被这道闸拒掉, 而那正是界面引导用户做的事。
        if (request.getTaxIncludedDefaultPurchasePrice() == null
                && request.getDefaultPurchasePrice() == null) {
            throw new BusinessException(400, "新建供应原料关系必须填写默认采购价")
                    .withCode("SUPPLIER_MATERIAL_PRICE_REQUIRED")
                    .withHint("请填写该物料在此供应商处的默认采购价；价格与采购单位绑定")
                    .withHintTarget("defaultPurchasePrice");
        }
    }

    private void apply(SupplierMaterial target, SupplierMaterialRequest request, RawMaterialType material) {
        if (request.getSupplierMaterialCode() != null) target.setSupplierMaterialCode(SupplierProfileValidator.trimToNull(request.getSupplierMaterialCode()));
        // 19②: 含税是人填的权威值, 未税由物料税率派生。⛔ 既有的未税列语义不变,
        //       所以四个下游消费点一行都不用改。
        if (request.getTaxIncludedDefaultPurchasePrice() != null) {
            if (request.getTaxIncludedDefaultPurchasePrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(400, "含税默认采购价必须大于0")
                        .withCode("SUPPLIER_MATERIAL_PRICE_INVALID")
                        .withHintTarget("taxIncludedDefaultPurchasePrice");
            }
            target.setTaxIncludedDefaultPurchasePrice(request.getTaxIncludedDefaultPurchasePrice());
            BigDecimal derived = SupplierTaxInclusivePricing.deriveUntaxed(
                    request.getTaxIncludedDefaultPurchasePrice(), material);
            if (derived != null) {
                target.setDefaultPurchasePrice(derived);
            }
            // derived == null ⇒ 物料没有税率, 算不出未税。⛔ 此时**不动**既有未税值,
            //    也不拿含税值顶替 —— 那等于断言税率为 0。
        } else if (request.getDefaultPurchasePrice() != null) {
            // 兼容既有 API 调用方直接给未税值。⚠️ 界面已不再让人填未税(19② owner 裁定),
            //    这条路留给迁移期与既有集成, ⛔ 不要在前端复活它。
            if (request.getDefaultPurchasePrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(400, "默认采购价必须大于0")
                        .withCode("SUPPLIER_MATERIAL_PRICE_INVALID")
                        .withHintTarget("defaultPurchasePrice");
            }
            target.setDefaultPurchasePrice(request.getDefaultPurchasePrice());
        }
        // 🔴 两个都为 null ⇒ 这次请求没给价格 ⇒ 什么都不做。
        //    这正是「未税有值 / 含税为空」那 2 行存量能在改别的字段时活下来的原因:
        //    ⛔ 绝不能在这里写 setDefaultPurchasePrice(null) 或写入派生出来的 null。
        // 🔒 2026-09-01 owner 裁定 (缺陷 21):「目前普遍默认币种肯定就是人民币, 不可能出现其他币种」。
        //    ⇒ 前端已收起币种输入, 后端在这里固定 CNY —— 收起 UI 而不钉后端, 等于把一个
        //    用户改不了的字段留成可被构造请求改写的。⛔ 不删数据库列, 留将来多币种的余地。
        //    ⚠️ 与 SupplierMaterialPurchaseSpecServiceImpl 那一处是同一条口径, 两处必须同时改。
        target.setCurrency(SUPPLIER_MATERIAL_CURRENCY);
        String requestedUnit = request.getPurchaseUnit() != null
                ? request.getPurchaseUnit()
                : target.getPurchaseUnit() != null ? target.getPurchaseUnit() : material.getUnit();
        var normalizedUnit = unitContractService.normalize(material.getFactoryId(), requestedUnit);
        if (!normalizedUnit.recognized()) {
            throw new BusinessException(400, "未登记的采购计量单位: " + requestedUnit)
                    .withCode("SUPPLIER_MATERIAL_UNIT_UNRECOGNIZED")
                    .withHintTarget("purchaseUnit");
        }
        if (!unitContractService.supportsUsage(material.getFactoryId(), normalizedUnit.code(),
                UnitUsageScope.PURCHASE_QUANTITY)) {
            throw new BusinessException(400, "该计量单位不允许用于采购数量: " + normalizedUnit.code())
                    .withCode("SUPPLIER_MATERIAL_UNIT_SCOPE_INVALID")
                    .withHintTarget("purchaseUnit");
        }
        UnitNormalizationResult materialUnit = unitContractService.normalize(material.getFactoryId(), material.getUnit());
        if (!materialUnit.recognized()
                || !unitContractService.supportsUsage(material.getFactoryId(), materialUnit.code(),
                UnitUsageScope.INVENTORY_QUANTITY)) {
            throw new BusinessException(400, "物料库存基本单位配置无效")
                    .withCode("SUPPLIER_MATERIAL_BASE_UNIT_INVALID")
                    .withHintTarget("materialTypeId");
        }
        if (!isDirectPurchaseUnitCompatible(normalizedUnit.unit(), materialUnit.unit())) {
            throw new BusinessException(400,
                    "采购单位与物料库存基本单位不兼容；包装采购请配置明确的采购包装规格")
                    .withCode("SUPPLIER_MATERIAL_UNIT_REQUIRES_SPEC")
                    .withHintTarget("purchaseUnit");
        }
        if (request.getPurchaseUnit() != null
                && target.getPurchaseUnit() != null
                && !Objects.equals(target.getPurchaseUnit(), normalizedUnit.code())
                && target.getDefaultPurchasePrice() != null
                && request.getDefaultPurchasePrice() == null) {
            throw new BusinessException(400, "修改采购单位时必须重新确认对应单位的默认采购价")
                    .withCode("SUPPLIER_MATERIAL_PRICE_UNIT_RECONFIRM_REQUIRED")
                    .withHintTarget("defaultPurchasePrice");
        }
        target.setPurchaseUnit(normalizedUnit.code());
        if (request.getMinOrderQuantity() != null) target.setMinOrderQuantity(request.getMinOrderQuantity());
        if (request.getLeadTimeDays() != null) target.setLeadTimeDays(request.getLeadTimeDays());
        if (request.getPreferred() != null) target.setPreferred(request.getPreferred());
        else if (target.getPreferred() == null) target.setPreferred(false);
        if (request.getActive() != null) target.setActive(request.getActive());
    }

    private Supplier ownedSupplier(String factoryId, String supplierId, boolean requireActive) {
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应商不存在或不属于当前工厂"));
        if (requireActive && !Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new BusinessException(409, "供应商已暂停合作，不能新增或修改供应关系");
        }
        return supplier;
    }
    private RawMaterialType ownedMaterial(String factoryId, String id) {
        RawMaterialType material = materialRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "物料不存在"));
        if (!factoryId.equals(material.getFactoryId())) throw new BusinessException(403, "物料不属于当前工厂");
        return material;
    }
    private SupplierMaterial ownedRelation(String factoryId, String supplierId, String id) {
        SupplierMaterial relation = repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应关系不存在"));
        if (!supplierId.equals(relation.getSupplierId())) throw new BusinessException(403, "供应关系不属于该供应商");
        return relation;
    }
    private SupplierMaterialDTO toDto(SupplierMaterial relation) {
        Supplier supplier = supplierRepository.findByIdAndFactoryId(relation.getSupplierId(), relation.getFactoryId()).orElse(null);
        RawMaterialType material = materialRepository.findById(relation.getMaterialTypeId()).orElse(null);
        PriceResolution price = resolveEffectivePrice(relation, material);
        return SupplierMaterialDTO.builder().id(relation.getId()).factoryId(relation.getFactoryId())
                .supplierId(relation.getSupplierId()).supplierName(supplier == null ? null : supplier.getName())
                .materialTypeId(relation.getMaterialTypeId()).materialCode(material == null ? null : material.getCode())
                .materialName(material == null ? null : material.getName()).baseUnit(material == null ? null : material.getUnit())
                .materialReferencePrice(material == null ? null : positiveOrNull(material.getUnitPrice()))
                .materialReferencePriceUnit(material == null ? null : material.getUnit())
                .supplierMaterialCode(relation.getSupplierMaterialCode()).defaultPurchasePrice(relation.getDefaultPurchasePrice())
                .taxIncludedDefaultPurchasePrice(relation.getTaxIncludedDefaultPurchasePrice())
                .currency(relation.getCurrency()).purchaseUnit(relation.getPurchaseUnit())
                .effectivePurchasePrice(price.price()).effectivePriceUnit(price.unit()).priceSource(price.source())
                .minOrderQuantity(relation.getMinOrderQuantity())
                .leadTimeDays(relation.getLeadTimeDays()).preferred(relation.getPreferred()).active(relation.getActive())
                .version(relation.getVersion()).build();
    }

    private boolean isDirectPurchaseUnitCompatible(CanonicalUnit purchaseUnit, CanonicalUnit materialUnit) {
        if (purchaseUnit == null || materialUnit == null) return false;
        if (purchaseUnit.code().equals(materialUnit.code())) return true;
        return purchaseUnit.dimension() == materialUnit.dimension()
                && isIntrinsicDimension(purchaseUnit.dimension())
                && purchaseUnit.factorToBase() != null
                && materialUnit.factorToBase() != null;
    }

    private boolean isIntrinsicDimension(UnitDimension dimension) {
        return dimension == UnitDimension.MASS
                || dimension == UnitDimension.VOLUME
                || dimension == UnitDimension.LENGTH;
    }

    private PriceResolution resolveEffectivePrice(SupplierMaterial relation, RawMaterialType material) {
        BigDecimal relationPrice = positiveOrNull(relation.getDefaultPurchasePrice());
        if (relationPrice != null) {
            return new PriceResolution(relationPrice, relation.getPurchaseUnit(), PRICE_SOURCE_RELATION);
        }
        BigDecimal materialPrice = material == null ? null : positiveOrNull(material.getUnitPrice());
        if (materialPrice == null || material.getUnit() == null || relation.getPurchaseUnit() == null) {
            return new PriceResolution(null, relation.getPurchaseUnit(), PRICE_SOURCE_UNCONFIGURED);
        }
        UnitNormalizationResult purchaseUnit = unitContractService.normalize(
                relation.getFactoryId(), relation.getPurchaseUnit());
        UnitNormalizationResult materialUnit = unitContractService.normalize(
                relation.getFactoryId(), material.getUnit());
        if (!purchaseUnit.recognized() || !materialUnit.recognized()
                || !isDirectPurchaseUnitCompatible(purchaseUnit.unit(), materialUnit.unit())) {
            return new PriceResolution(null, relation.getPurchaseUnit(), PRICE_SOURCE_UNCONFIGURED);
        }
        if (purchaseUnit.code().equals(materialUnit.code())) {
            return new PriceResolution(materialPrice, purchaseUnit.code(), PRICE_SOURCE_MATERIAL);
        }
        BigDecimal purchaseToMaterialFactor = purchaseUnit.unit().factorToBase()
                .divide(materialUnit.unit().factorToBase(), 12, RoundingMode.HALF_UP);
        BigDecimal convertedPrice = materialPrice.multiply(purchaseToMaterialFactor)
                .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        return new PriceResolution(convertedPrice, purchaseUnit.code(), PRICE_SOURCE_MATERIAL);
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    }

    private record PriceResolution(BigDecimal price, String unit, String source) {}
}
