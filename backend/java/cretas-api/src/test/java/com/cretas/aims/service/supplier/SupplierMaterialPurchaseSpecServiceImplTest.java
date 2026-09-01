package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecDTO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierMaterialPurchaseSpecServiceImplTest {
    @Mock SupplierMaterialPurchaseSpecRepository repository;
    @Mock SupplierMaterialRepository relationRepository;
    @Mock RawMaterialTypeRepository materialRepository;
    @Mock UnitContractService unitContractService;
    SupplierMaterialPurchaseSpecServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierMaterialPurchaseSpecServiceImpl(repository, relationRepository,
                materialRepository, unitContractService);
        lenient().when(unitContractService.supportsUsage(eq("F006"), anyString(), any())).thenReturn(true);
    }

    @Test
    void createsCanonicalPackageSpecAndKeepsOnlyOneDefault() {
        SupplierMaterial relation = relation(true);
        RawMaterialType material = material("kg");
        SupplierMaterialPurchaseSpec previous = new SupplierMaterialPurchaseSpec();
        previous.setId("old"); previous.setDefaultSpec(true); previous.setActive(true);
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = "箱".equals(raw) ? "case" : raw;
            return new UnitNormalizationResult(raw, code, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        when(repository.findByFactoryIdAndSupplierMaterialIdAndActiveTrueOrderByCreatedAtAsc("F006", "rel"))
                .thenReturn(List.of(previous));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierMaterialPurchaseSpecRequest request = request("箱", "kg", true);
        var result = service.create("F006", "sup", "rel", request);

        assertThat(result.getPurchasePackageUnit()).isEqualTo("case");
        assertThat(result.getInventoryBaseUnit()).isEqualTo("kg");
        assertThat(result.getFactor()).isEqualByComparingTo("10");
        assertThat(result.getQuotedPriceUnit()).isEqualTo("case");
        assertThat(previous.getDefaultSpec()).isFalse();
    }

    @Test
    void rejectsBaseUnitThatDiffersFromMaterialMasterData() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(true)));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material("kg")));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });

        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request("case", "g", false)))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void inactiveSupplierMaterialRelationCannotAddSpecs() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(false)));
        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request("case", "kg", false)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(materialRepository);
    }

    @Test
    void rejectsTimeUnitForPurchasePackage() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(true)));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material("kg")));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        when(unitContractService.supportsUsage("F006", "minute",
                com.cretas.aims.service.unit.UnitUsageScope.PURCHASE_QUANTITY)).thenReturn(false);

        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request("minute", "kg", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许用于当前采购规格字段");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsZeroQuotedPriceAtServiceBoundary() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(true)));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material("kg")));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        SupplierMaterialPurchaseSpecRequest request = request("case", "kg", false);
        request.setQuotedPrice(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("报价必须大于0");
        verify(repository, never()).saveAndFlush(any());
    }

    private SupplierMaterial relation(boolean active) {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setId("rel"); relation.setFactoryId("F006"); relation.setSupplierId("sup");
        relation.setMaterialTypeId("mat"); relation.setActive(active);
        return relation;
    }

    private RawMaterialType material(String unit) {
        RawMaterialType material = new RawMaterialType();
        material.setId("mat"); material.setFactoryId("F006"); material.setUnit(unit);
        return material;
    }

    private SupplierMaterialPurchaseSpecRequest request(String packageUnit, String baseUnit, boolean defaultSpec) {
        SupplierMaterialPurchaseSpecRequest request = new SupplierMaterialPurchaseSpecRequest();
        request.setName("10kg/箱"); request.setPurchasePackageUnit(packageUnit);
        request.setInventoryBaseUnit(baseUnit); request.setFactor(new BigDecimal("10"));
        request.setQuotedPrice(new BigDecimal("120")); request.setDefaultSpec(defaultSpec);
        request.setActive(true);
        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 客户 2026-07-30 表格第 38 行:「在成品SKU中设定完换算规格后, 生成采购订单的时候
    // 将不再自动填入供应商中设定好的采购单价。」
    //
    // 成因: 配了采购规格后, 采购行的计价单位变成规格的包装单位 (箱), 而供应关系上配的价
    // 是按库存基本单位 (kg) 的 —— 前端 applyRelationPrice 单位不等就把价清空。
    //
    // 这里只做**规格自己声明的那一次换算**: 规格行上就写着 1 箱 = conversionFactor kg。
    // 不引入通用单位引擎, 也不跨任何未声明的单位猜。
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void derivesSpecPriceFromRelationPriceUsingTheSpecOwnFactor() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("kg");                       // 供应关系价 = 20 元/kg
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(null, "case", "kg", new BigDecimal("20"))));  // 1 箱 = 20 kg

        SupplierMaterialPurchaseSpecDTO dto = service.list("F006", "sup", "rel").get(0);

        assertThat(dto.getQuotedPrice()).isNull();
        assertThat(dto.getDerivedPrice()).isEqualByComparingTo("400");   // 20 元/kg × 20 kg/箱
        assertThat(dto.getDerivedPriceUnit()).isEqualTo("case");
        assertThat(dto.getDerivedPriceSource()).isEqualTo("SUPPLIER_RELATION_CONVERTED");
    }

    @Test
    void keepsRelationPriceAsIsWhenTheSpecIsPricedInTheSameUnit() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("case");
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(null, "case", "kg", new BigDecimal("20"))));

        SupplierMaterialPurchaseSpecDTO dto = service.list("F006", "sup", "rel").get(0);

        assertThat(dto.getDerivedPrice()).isEqualByComparingTo("20");   // 同单位, 不得再乘一次系数
        assertThat(dto.getDerivedPriceSource()).isEqualTo("SUPPLIER_RELATION_SAME_UNIT");
    }

    @Test
    void neverGuessesAcrossAnUndeclaredUnit() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("g");                        // 既不是包装单位也不是该规格的基本单位
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(null, "case", "kg", new BigDecimal("20"))));

        SupplierMaterialPurchaseSpecDTO dto = service.list("F006", "sup", "rel").get(0);

        assertThat(dto.getDerivedPrice()).isNull();
        assertThat(dto.getDerivedPriceSource()).isNull();
    }

    @Test
    void specOwnQuoteAlwaysWinsAndSuppressesDerivation() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("kg");
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(new BigDecimal("380"), "case", "kg", new BigDecimal("20"))));

        SupplierMaterialPurchaseSpecDTO dto = service.list("F006", "sup", "rel").get(0);

        assertThat(dto.getQuotedPrice()).isEqualByComparingTo("380");
        assertThat(dto.getDerivedPrice()).isNull();   // 有自己的报价就不推导, 免得两个数打架
    }

    @Test
    void derivesNothingWhenTheFactorIsMissingOrZero() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("kg");
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(null, "case", "kg", null), spec(null, "case", "kg", BigDecimal.ZERO)));

        List<SupplierMaterialPurchaseSpecDTO> dtos = service.list("F006", "sup", "rel");

        assertThat(dtos).allSatisfy(dto -> assertThat(dto.getDerivedPrice()).isNull());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 🔴 同一个产品里两种 null 语义 —— 本组用例把现状**钉住**, 让它不能被悄悄改掉。
    //
    //   供应关系侧 SupplierMaterialServiceImpl.apply():
    //       if (request.getDefaultPurchasePrice() != null) target.setDefaultPurchasePrice(...)
    //       ⇒ null = 「本次不修改」
    //   采购规格侧 本类 apply():
    //       spec.setQuotedPrice(request.getQuotedPrice());        // 无条件
    //       ⇒ null = 「清空」
    //
    // ⚠️ 而且**同一个方法内部**就不一致: quotedPrice
    //    是清空语义, 而紧挨着的 defaultSpec / active 却是「null 就保持原值」。
    //    (缺陷 16 phase 1, 2026-09-02: minOrderQuantity/leadTimeDays 已从规格
    //     表单/DTO/实体中移除 —— 与供应关系侧字段重复且零读者, 不再是这条漂移的一部分;
    //     起订量/交期现在只在供应关系层录入一次。)
    //
    // 📌 这是**原始设计漂移不是回归**: git log -S 显示 quotedPrice 从 `Initial company
    //    baseline` 起就从未加过 null 判断, defaultSpec/active 也从一开始就是保持语义。
    //
    // 📌 爆炸半径(实测): 目前**只有 web-admin 一个写入方**, 且它每次都发完整的
    //    payload(specForm 全量) ⇒ 这条数据丢失路径**当前不可达**。RN 侧
    //    supplierApiClient 对规格**只有 GET**。⇒ 是潜伏形状, 不是正在发生的事故。
    //    ⛔ 但任何新写入方(集成/脚本/RN 补写)发一个合法的部分更新, 就会静默抹掉报价。
    //
    // ⇒ 该选哪种语义是**契约决定**, 不在本文件里改。这里只保证: 谁要改, 得先让这两条红。
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void updateWithoutQuotedPriceCurrentlyClearsIt() {
        SupplierMaterialPurchaseSpec existing = spec(new BigDecimal("120"), "case", "kg", new BigDecimal("10"));
        stubUpdate(existing);

        SupplierMaterialPurchaseSpecRequest request = request("case", "kg", false);
        request.setQuotedPrice(null);          // 合法的部分更新: DTO 上 quotedPrice 是可选的
        service.update("F006", "sup", "rel", "spec-1", request);

        assertThat(existing.getQuotedPrice())
                .as("采购规格侧: null = 清空(现状)。若这条变红, 说明有人把它改成了「保持原值」——"
                        + " 那是契约变更, 必须与供应关系侧一起决定, ⛔ 不能单边改一处")
                .isNull();
    }

    @Test
    void updateWithoutDefaultSpecFlagKeepsItInTheSameMethod() {
        // 阴性对照: 证明上面那条不是「整个方法都清空」, 而是**同一方法内两种语义并存**。
        SupplierMaterialPurchaseSpec existing = spec(new BigDecimal("120"), "case", "kg", new BigDecimal("10"));
        existing.setDefaultSpec(true);
        stubUpdate(existing);

        SupplierMaterialPurchaseSpecRequest request = request("case", "kg", false);
        request.setDefaultSpec(null);          // 同样是"没给"
        request.setActive(null);
        service.update("F006", "sup", "rel", "spec-1", request);

        assertThat(existing.getDefaultSpec())
                .as("defaultSpec 是「null 保持原值」—— 与紧挨着的 quotedPrice 相反")
                .isTrue();
        assertThat(existing.getActive())
                .as("active 同样是保持语义")
                .isTrue();
    }

    /** update 路径的共用桩 —— 只桩外部 IO。 */
    private void stubUpdate(SupplierMaterialPurchaseSpec existing) {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(true)));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material("kg")));
        when(repository.findByIdAndFactoryId("spec-1", "F006")).thenReturn(Optional.of(existing));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        lenient().when(repository.findByFactoryIdAndSupplierMaterialIdAndActiveTrueOrderByCreatedAtAsc("F006", "rel"))
                .thenReturn(List.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private SupplierMaterialPurchaseSpec spec(BigDecimal quotedPrice, String packageUnit,
                                              String baseUnit, BigDecimal factor) {
        SupplierMaterialPurchaseSpec spec = new SupplierMaterialPurchaseSpec();
        spec.setId("spec-1"); spec.setFactoryId("F006"); spec.setSupplierMaterialId("rel");
        spec.setMaterialTypeId("mat"); spec.setName("整箱");
        spec.setPurchasePackageUnit(packageUnit); spec.setInventoryBaseUnit(baseUnit);
        spec.setConversionFactor(factor); spec.setQuotedPrice(quotedPrice);
        spec.setActive(true);
        return spec;
    }
}
