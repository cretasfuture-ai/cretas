package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;
import com.cretas.aims.dto.material.MaterialPackagingSpecDTO;
import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.material.MaterialPackagingSpec;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.material.MaterialPackagingSpecRepository;
import com.cretas.aims.service.impl.MaterialPackagingHierarchyServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户 2026-07-30:「在成品SKU中设定完换算规格后, 生成采购订单时不再自动填入供应商中设定好的采购单价」。
 *
 * <p>{@code d5c3b2de88} (2026-08-05) 修的是**供应商侧**采购规格那一半。本测试守的是
 * **物料侧包装换算** (原料档案 → 包装换算) 那一半 —— 供应商没配采购规格时采购单表单会回落到
 * 这条路径, 而它原来的 DTO 一个价格字段都没有, 于是同一个症状原样复现。
 *
 * <p>两组断言:
 * <ol>
 *   <li>推导本身 (纯函数) 对不对;</li>
 *   <li>🔴 **它有没有接到那条真实读取路径上** —— 单测最容易漏的就是这一层。</li>
 * </ol>
 */
class PurchasePriceDerivationContractTest {

    // ---------- 第一组: 推导本身 ----------

    @Test
    void 关系价单位与包装单位相同时原样带过_不再乘一次系数() {
        var d = PurchasePriceDerivation.derive(
                new BigDecimal("20"), "case", "case", "kg", new BigDecimal("10"));
        assertThat(d).isNotNull();
        // 乘了系数就会变成 200 —— 那是同一笔钱被算了两遍。
        assertThat(d.price()).isEqualByComparingTo("20");
        assertThat(d.source()).isEqualTo(PurchasePriceDerivation.SOURCE_SAME_UNIT);
    }

    @Test
    void 关系价按基本单位计时乘以本规格的换算系数() {
        var d = PurchasePriceDerivation.derive(
                new BigDecimal("20"), "kg", "case", "kg", new BigDecimal("10"));
        assertThat(d).isNotNull();
        assertThat(d.price()).isEqualByComparingTo("200");   // 20 元/kg × 10 kg/箱
        assertThat(d.unit()).isEqualTo("case");
        assertThat(d.source()).isEqualTo(PurchasePriceDerivation.SOURCE_CONVERTED);
    }

    @Test
    void 单位大小写与空格不影响匹配() {
        assertThat(PurchasePriceDerivation.derive(
                new BigDecimal("20"), " KG ", "case", "kg", new BigDecimal("10"))).isNotNull();
    }

    @Test
    void 跨不过去的单位一律返回null_不猜也不返回0() {
        // 关系价按「只」计, 而规格是 箱/kg —— 没有任何一行写着「1 箱 = N 只」, 不许猜。
        assertThat(PurchasePriceDerivation.derive(
                new BigDecimal("20"), "pcs", "case", "kg", new BigDecimal("10"))).isNull();
    }

    @Test
    void 没有关系价或系数非法时返回null() {
        assertThat(PurchasePriceDerivation.derive(null, "kg", "case", "kg", BigDecimal.TEN)).isNull();
        assertThat(PurchasePriceDerivation.derive(BigDecimal.ZERO, "kg", "case", "kg", BigDecimal.TEN)).isNull();
        assertThat(PurchasePriceDerivation.derive(
                new BigDecimal("20"), "kg", "case", "kg", BigDecimal.ZERO)).isNull();
        assertThat(PurchasePriceDerivation.derive(
                new BigDecimal("20"), "kg", "case", "kg", null)).isNull();
    }

    // ---------- 第二组: 接上没有 ----------

    private final MaterialPackagingHierarchyRepository hierarchyRepository =
            mock(MaterialPackagingHierarchyRepository.class);
    private final MaterialPackagingSpecRepository packagingSpecRepository =
            mock(MaterialPackagingSpecRepository.class);
    private final RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
    private final UnitContractService unitContractService = mock(UnitContractService.class);
    private final SupplierMaterialRepository supplierMaterialRepository =
            mock(SupplierMaterialRepository.class);
    private final MaterialPackagingHierarchyServiceImpl service =
            new MaterialPackagingHierarchyServiceImpl(
                    hierarchyRepository, packagingSpecRepository, materialRepository,
                    unitContractService, supplierMaterialRepository);

    private static final String F = "F006";
    private static final String M = "RMT_TEST";
    private static final String REL = "REL-1";

    private void givenMaterialWithOneCaseSpec() {
        MaterialPackagingHierarchy h = new MaterialPackagingHierarchy();
        h.setId("H1");
        h.setFactoryId(F);
        h.setMaterialTypeId(M);
        when(hierarchyRepository.findByMaterialTypeId(M)).thenReturn(Optional.of(h));

        MaterialPackagingSpec spec = new MaterialPackagingSpec();
        spec.setId("SPEC-1");
        spec.setName("整箱");
        spec.setPackageUnit("case");
        spec.setBaseUnit("kg");
        spec.setConversionFactor(new BigDecimal("10"));
        spec.setDefaultSpec(true);
        spec.setActive(true);
        when(packagingSpecRepository
                .findByFactoryIdAndMaterialTypeIdOrderBySortOrderAscCreatedAtAsc(F, M))
                .thenReturn(List.of(spec));
    }

    private void givenRelationPricedPerKg() {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setId(REL);
        relation.setFactoryId(F);
        relation.setMaterialTypeId(M);
        relation.setPurchaseUnit("kg");
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        when(supplierMaterialRepository.findById(REL)).thenReturn(Optional.of(relation));
    }

    private static MaterialPackagingSpecDTO onlySpec(MaterialPackagingHierarchyDTO dto) {
        List<MaterialPackagingSpecDTO> specs = dto.getPackagingSpecs();
        assertThat(specs).hasSize(1);
        return specs.get(0);
    }

    /** 阳性对照: 不传供应关系时这条路径本来就读得到规格 —— 否则下面的断言分不清「没带价」和「没读到」。 */
    @Test
    void 阳性对照_不传供应关系时仍然读得到包装规格_只是没有价() {
        givenMaterialWithOneCaseSpec();

        MaterialPackagingSpecDTO spec = onlySpec(service.getByMaterialTypeId(F, M).orElseThrow());

        assertThat(spec.packageUnit()).isEqualTo("case");
        assertThat(spec.derivedPrice()).isNull();
    }

    /** 🔴 本次修复的核心: 带上供应关系, 物料侧包装换算也要带出采购价。 */
    @Test
    void 带上供应关系时物料侧包装换算也带出采购价() {
        givenMaterialWithOneCaseSpec();
        givenRelationPricedPerKg();

        MaterialPackagingSpecDTO spec = onlySpec(service.getByMaterialTypeId(F, M, REL).orElseThrow());

        assertThat(spec.derivedPrice()).isEqualByComparingTo("200");
        assertThat(spec.derivedPriceUnit()).isEqualTo("case");
        assertThat(spec.derivedPriceSource())
                .isEqualTo(PurchasePriceDerivation.SOURCE_CONVERTED);
    }

    @Test
    void 供应关系不属于本工厂时不带价_也不报错() {
        givenMaterialWithOneCaseSpec();
        SupplierMaterial other = new SupplierMaterial();
        other.setId(REL);
        other.setFactoryId("OTHER_FACTORY");
        other.setMaterialTypeId(M);
        other.setPurchaseUnit("kg");
        other.setDefaultPurchasePrice(new BigDecimal("20"));
        when(supplierMaterialRepository.findById(REL)).thenReturn(Optional.of(other));

        assertThat(onlySpec(service.getByMaterialTypeId(F, M, REL).orElseThrow())
                .derivedPrice()).isNull();
    }

    @Test
    void 供应关系指向别的物料时不带价() {
        givenMaterialWithOneCaseSpec();
        SupplierMaterial other = new SupplierMaterial();
        other.setId(REL);
        other.setFactoryId(F);
        other.setMaterialTypeId("RMT_OTHER");
        other.setPurchaseUnit("kg");
        other.setDefaultPurchasePrice(new BigDecimal("20"));
        when(supplierMaterialRepository.findById(REL)).thenReturn(Optional.of(other));

        assertThat(onlySpec(service.getByMaterialTypeId(F, M, REL).orElseThrow())
                .derivedPrice()).isNull();
    }

    @Test
    void 供应关系查不到时不带价_也不让表单打不开() {
        givenMaterialWithOneCaseSpec();
        when(supplierMaterialRepository.findById(REL)).thenReturn(Optional.empty());

        assertThat(onlySpec(service.getByMaterialTypeId(F, M, REL).orElseThrow())
                .derivedPrice()).isNull();
    }
}
