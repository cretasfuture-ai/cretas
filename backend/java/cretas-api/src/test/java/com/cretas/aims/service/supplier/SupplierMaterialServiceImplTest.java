package com.cretas.aims.service.supplier;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierMaterialServiceImplTest {
    @Mock SupplierMaterialRepository repository;
    @Mock SupplierRepository supplierRepository;
    @Mock RawMaterialTypeRepository materialRepository;
    @Mock UnitContractService unitContractService;
    SupplierMaterialServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierMaterialServiceImpl(repository, supplierRepository, materialRepository, unitContractService);
    }

    @Test
    void createsFactoryOwnedRelationWithCanonicalPurchaseUnitAndRelationPrice() {
        stubOwnedMasterData(material("kg", new BigDecimal("8.00")));
        stubUnits();

        SupplierMaterialRequest request = request("KG");
        request.setDefaultPurchasePrice(new BigDecimal("10.00"));
        var result = service.create("F006", "sup", request);

        assertThat(result.getPurchaseUnit()).isEqualTo("kg");
        assertThat(result.getMaterialName()).isEqualTo("原料A");
        assertThat(result.getMaterialReferencePrice()).isEqualByComparingTo("8.00");
        assertThat(result.getMaterialReferencePriceUnit()).isEqualTo("kg");
        assertThat(result.getEffectivePurchasePrice()).isEqualByComparingTo("10.00");
        assertThat(result.getEffectivePriceUnit()).isEqualTo("kg");
        assertThat(result.getPriceSource()).isEqualTo("SUPPLIER_RELATION");
    }

    @Test
    void createsMetreBasedMaterialRelationWhenPurchaseIsByMetre() {
        stubOwnedMasterData(material("m", new BigDecimal("11.30")));
        stubUnits();

        SupplierMaterialRequest request = request("米");
        request.setDefaultPurchasePrice(new BigDecimal("11.30"));
        var result = service.create("F006", "sup", request);

        assertThat(result.getPurchaseUnit()).isEqualTo("m");
        assertThat(result.getEffectivePurchasePrice()).isEqualByComparingTo("11.30");
        assertThat(result.getEffectivePriceUnit()).isEqualTo("m");
        verify(repository).saveAndFlush(any(SupplierMaterial.class));
    }

    // ⚠️ 下面两条走 update 而不是 create —— 缺陷 17 之后 create 必须带价格,
    //    但它们守的行为("关系没有价格时怎么算有效价")跟 create 无关, 而且**依然是活的**:
    //    生产库里那 4 条空价格关系还在, update 也仍然允许价格保持为空。
    //    ⛔ 不能给它们塞个价格让它们变绿 —— 那样守的就不是"没有价格"这件事了。

    @Test
    void fallsBackToMaterialReferencePriceWithSafeMassConversion() {
        stubOwnedMasterData(material("kg", new BigDecimal("10.00")));
        stubUnits();
        existingRelationWithoutPrice();

        var result = service.update("F006", "sup", "rel", request("g"));

        assertThat(result.getEffectivePurchasePrice()).isEqualByComparingTo("0.01");
        assertThat(result.getEffectivePriceUnit()).isEqualTo("g");
        assertThat(result.getPriceSource()).isEqualTo("MATERIAL_REFERENCE");
    }

    @Test
    void leavesEffectivePriceUnconfiguredWhenMaterialReferenceIsMissing() {
        stubOwnedMasterData(material("kg", null));
        stubUnits();
        existingRelationWithoutPrice();

        var result = service.update("F006", "sup", "rel", request("kg"));

        assertThat(result.getEffectivePurchasePrice()).isNull();
        assertThat(result.getPriceSource()).isEqualTo("UNCONFIGURED");
    }

    @Test
    void requiresDefaultPurchasePriceWhenCreatingRelation() {
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();

        assertThatThrownBy(() -> service.create("F006", "sup", request("kg")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须填写默认采购价");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void allowsUpdatingPricelessLegacyRelationWithoutSupplyingAPrice() {
        // 🔒 owner 裁定的那一条: LIUSHANMEN 那 4 条空价格关系必须保持可编辑。
        //    逼人为了改启用状态先编一个价格出来, 比留空更糟 —— 编出来的价格会一直安静地错下去。
        //    ⛔ 所以这道闸只能装在 create 上; 若有人把 defaultPurchasePrice 改成必填字段,
        //       本用例会红。
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();
        existingRelationWithoutPrice();

        SupplierMaterialRequest request = request("kg");
        request.setActive(false);                 // 只想停用它
        var result = service.update("F006", "sup", "rel", request);

        assertThat(result).isNotNull();
        verify(repository).saveAndFlush(any(SupplierMaterial.class));
    }

    @Test
    void rejectsPackagingUnitWithoutExplicitPurchaseSpec() {
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();

        assertThatThrownBy(() -> service.create("F006", "sup", pricedRequest("case")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("采购包装规格");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsCrossDimensionPurchaseUnitWithoutPartialWrite() {
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();

        assertThatThrownBy(() -> service.create("F006", "sup", pricedRequest("m")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不兼容");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnitOutsidePurchaseUsageScope() {
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();
        when(unitContractService.supportsUsage(eq("F006"), eq("minute"), any())).thenReturn(false);

        assertThatThrownBy(() -> service.create("F006", "sup", pricedRequest("minute")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许用于采购数量");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsZeroDefaultPurchasePriceInServiceBoundary() {
        stubOwnedMasterData(material("kg", new BigDecimal("10")));
        stubUnits();
        SupplierMaterialRequest request = request("kg");
        request.setDefaultPurchasePrice(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.create("F006", "sup", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须大于0");
        verify(repository, never()).saveAndFlush(any());
    }

    private void stubOwnedMasterData(RawMaterialType material) {
        Supplier supplier = new Supplier();
        supplier.setId("sup"); supplier.setFactoryId("F006"); supplier.setIsActive(true); supplier.setName("供应商A");
        when(supplierRepository.findByIdAndFactoryId("sup", "F006")).thenReturn(Optional.of(supplier));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material));
        // lenient: 这条"重复关系"查询只有 create 走; 走 update 的用例用不到它。
        lenient().when(repository.findByFactoryIdAndSupplierIdAndMaterialTypeId("F006", "sup", "mat"))
                .thenReturn(Optional.empty());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubUnits() {
        lenient().when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = raw.equalsIgnoreCase("KG") ? "kg" : raw.equals("米") ? "m" : raw;
            CanonicalUnit unit = switch (code) {
                case "kg" -> unit("kg", UnitDimension.MASS, "g", "1000");
                case "g" -> unit("g", UnitDimension.MASS, "g", "1");
                case "m" -> unit("m", UnitDimension.LENGTH, "m", "1");
                case "case" -> unit("case", UnitDimension.PACKAGE, "case", "1");
                case "minute" -> unit("minute", UnitDimension.TIME, "minute", "1");
                default -> null;
            };
            return unit == null
                    ? new UnitNormalizationResult(raw, raw, null)
                    : new UnitNormalizationResult(raw, code, unit);
        });
        lenient().when(unitContractService.supportsUsage(eq("F006"), anyString(), any())).thenReturn(true);
    }

    private CanonicalUnit unit(String code, UnitDimension dimension, String base, String factor) {
        return new CanonicalUnit(code, dimension, base, new BigDecimal(factor), code, 3);
    }

    private SupplierMaterialRequest request(String unit) {
        SupplierMaterialRequest request = new SupplierMaterialRequest();
        request.setMaterialTypeId("mat");
        request.setPurchaseUnit(unit);
        request.setActive(true);
        return request;
    }

    /**
     * 带价格的新建请求 —— 缺陷 17 之后 create 必须带默认采购价。
     *
     * <p>⚠️ 那几条<b>单位</b>校验用例要的是"走到单位那一步再被拒", 所以必须先满足价格闸,
     * 否则它们会被价格闸提前拦下 —— 断言仍然是绿的(照样抛 BusinessException),
     * 但守的已经不是单位校验了。⛔ 这种"绿得不对"的改法比红更危险。
     */
    private SupplierMaterialRequest pricedRequest(String unit) {
        SupplierMaterialRequest request = request(unit);
        request.setDefaultPurchasePrice(new BigDecimal("10.00"));
        return request;
    }

    /**
     * 一条<b>已存在且没有价格</b>的关系 —— LIUSHANMEN 现存那 4 条就是这个形状。
     *
     * <p>缺陷 17 之后 create 不再造得出这种记录, 但它<b>依然存在于生产库里</b>,
     * 而且 update 仍然允许价格保持为空(null = 不修改, owner 裁定要保住的那条性质)。
     * ⇒ "关系没价格时回落到物料参考价"这条行为必须继续被守着,
     * ⛔ 不能因为构造路径关了就把这两条用例删掉或给它们塞个价格 —— 那会把被守的行为改掉。
     */
    private SupplierMaterial existingRelationWithoutPrice() {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setId("rel");
        relation.setFactoryId("F006");
        relation.setSupplierId("sup");
        relation.setMaterialTypeId("mat");
        relation.setActive(true);
        relation.setDefaultPurchasePrice(null);
        when(repository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        return relation;
    }

    private RawMaterialType material(String unit, BigDecimal referencePrice) {
        RawMaterialType material = new RawMaterialType();
        material.setId("mat"); material.setFactoryId("F006");
        material.setCode("RMT-1"); material.setName("原料A"); material.setUnit(unit);
        material.setUnitPrice(referencePrice);
        return material;
    }
}
