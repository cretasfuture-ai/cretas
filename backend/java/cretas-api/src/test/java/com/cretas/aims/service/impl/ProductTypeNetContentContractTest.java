package com.cretas.aims.service.impl;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKU 净含量契约 (`applyNetContentContract`) 的行为。
 *
 * <h2>为什么补这个</h2>
 *
 * <p>ACTIVE 台账 `BUG-F006-R2-SKU-UNIT-001` 明写「Java 契约仍须协调者另行审计」。
 * 审计结果: `applyNetContentContract` 有一整套真实约束(成对必填 / 单位归一 /
 * 质量型克重桥 / 容量型不折克), 而全仓 `src/test` 下 grep `netContent`
 * <b>只命中 `BomRecipeDraftLifecycleJpaTest`</b>(那是 BomRecipe 的字段, 不是这条契约)
 * —— 也就是说这条契约<b>一条断言都没有</b>。</p>
 *
 * <h2>它守的核心承诺</h2>
 *
 * <p>方法自己的 javadoc 写着「质量型同时维护 gramsPerUnit 兼容桥；<b>容量型绝不伪装为克重</b>」。
 * 后半句是这条契约唯一真正危险的地方: 若 `ml`/`L` 被折算成克, 下游按克重结算的成本/报工
 * 会拿到一个凭空造出来的重量。`toGrams` 对 `ml`/`L` 返回 `null` 就是在守它 ——
 * 本文件把它钉住。</p>
 *
 * <h2>⚠️ 写这个文件时踩到的仪器陷阱(留给下一个人)</h2>
 *
 * <p>`ProductTypeDTO` 用 Lombok `@Builder`, 而 `netContentQuantityPresent` /
 * `netContentUnitPresent` 这两个标志是在<b>setter 里</b>置位的。
 * `@Builder` 直接赋字段、<b>绕开 setter</b> ⇒ 用
 * `ProductTypeDTO.builder().netContentQuantity(x).netContentUnit(y).build()`
 * 造出来的 DTO, 两个 Present 标志都是 `false`, `structuredPresent` 为假,
 * 于是<b>根本不会进结构化分支</b>, 而是掉进旧 `gramsPerUnit` 兼容分支。
 * 照隔壁 `ProductTypeTaxConversionTest` 的 builder 写法写这条测试, 用例会静默测错分支
 * 并且<b>照样绿</b>。所以本文件一律用 `new ProductTypeDTO()` + setter。</p>
 */
@DisplayName("SKU 净含量契约 (applyNetContentContract)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductTypeNetContentContractTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private CustomerRepository customerRepository;

    private ProductTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductTypeServiceImpl(productTypeRepository, new ObjectMapper(), customerRepository,
                org.mockito.Mockito.mock(com.cretas.aims.service.workflow.WorkflowUnitReviewService.class),
                org.mockito.Mockito.mock(com.cretas.aims.service.unit.ProductSpecificationConversionSyncService.class));
        when(productTypeRepository.existsByFactoryIdAndCode(any(), any())).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** ⛔ 必须用 setter —— builder 不置位 Present 标志, 见类 javadoc。 */
    private ProductTypeDTO structured(String name, String code, BigDecimal qty, String unit) {
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setName(name);
        dto.setCode(code);
        dto.setUnit("盒");
        dto.setCreatedBy(1L);
        dto.setProductCategory(ProductCategory.FINISHED_PRODUCT);   // 创建期必填, 否则走不到净含量那段
        dto.setNetContentQuantity(qty);
        dto.setNetContentUnit(unit);
        return dto;
    }

    /**
     * 建一个 SKU 并取回<b>本次</b>落库的实体。
     *
     * <p>⚠️ 这里刻意用 {@code atLeastOnce()} + 取最后一次, 而不是 {@code verify(repo).save(...)}:
     * 后者断言「save 恰好一次」, 于是同一个用例里连着建几个 SKU(单位归一那两条正是如此)
     * 会撞 {@code TooManyActualInvocations} —— 那是<b>仪器</b>红, 不是被测契约红,
     * 很容易被误读成「归一逻辑坏了」。</p>
     */
    private ProductType created(ProductTypeDTO dto) {
        service.createProductType(FACTORY_ID, dto);
        ArgumentCaptor<ProductType> captor = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository, atLeastOnce()).save(captor.capture());
        java.util.List<ProductType> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    @Nested
    @DisplayName("核心承诺: 容量型绝不伪装为克重")
    class VolumeNeverMasqueradesAsWeight {

        @Test
        @DisplayName("ml: 存净含量, 但 gramsPerUnit 保持 null")
        void millilitreDoesNotProduceGrams() {
            ProductType saved = created(structured("骨汤", "PT-ML", new BigDecimal("500"), "ml"));

            assertThat(saved.getNetContentUnit()).isEqualTo("ml");
            assertThat(saved.getNetContentQuantity()).isEqualByComparingTo("500");
            assertThat(saved.getGramsPerUnit())
                    .as("容量型折成克重 = 凭空造一个重量给下游成本/报工")
                    .isNull();
        }

        @Test
        @DisplayName("L: 同样不折克重")
        void litreDoesNotProduceGrams() {
            ProductType saved = created(structured("高汤", "PT-L", new BigDecimal("1.5"), "升"));

            assertThat(saved.getNetContentUnit()).isEqualTo("L");
            assertThat(saved.getGramsPerUnit()).isNull();
        }

        /**
         * 阳性对照: 同一条路径上, <b>质量型确实会</b>折出克重。
         * 没有这一条, 上面两条「为 null」可能只是因为整条赋值根本没执行。
         */
        @Test
        @DisplayName("阳性对照 — kg 会折出克重 (×1000), 证明这条路真的会写 gramsPerUnit")
        void kilogramDoesProduceGrams() {
            ProductType saved = created(structured("猪蹄", "PT-KG", new BigDecimal("2"), "kg"));

            assertThat(saved.getNetContentUnit()).isEqualTo("kg");
            assertThat(saved.getGramsPerUnit()).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("g 原值折克重")
        void gramProducesSameGrams() {
            ProductType saved = created(structured("猪舌", "PT-G", new BigDecimal("250"), "克"));

            assertThat(saved.getNetContentUnit()).isEqualTo("g");
            assertThat(saved.getGramsPerUnit()).isEqualByComparingTo("250");
        }
    }

    @Nested
    @DisplayName("单位归一")
    class UnitCanonicalisation {

        @Test
        @DisplayName("中文别名归一: 克→g / 公斤·千克→kg / 毫升→ml / 升→L")
        void chineseAliasesAreCanonicalised() {
            assertThat(created(structured("A", "PT-A", BigDecimal.ONE, "克")).getNetContentUnit()).isEqualTo("g");
            assertThat(created(structured("B", "PT-B", BigDecimal.ONE, "公斤")).getNetContentUnit()).isEqualTo("kg");
            assertThat(created(structured("C", "PT-C", BigDecimal.ONE, "千克")).getNetContentUnit()).isEqualTo("kg");
            assertThat(created(structured("D", "PT-D", BigDecimal.ONE, "毫升")).getNetContentUnit()).isEqualTo("ml");
            assertThat(created(structured("E", "PT-E", BigDecimal.ONE, "升")).getNetContentUnit()).isEqualTo("L");
        }

        /**
         * 大小写与空格不敏感 —— 但归一结果里 <b>L 是大写</b>, 其余是小写。
         * 这不是笔误: 下游若按 `"l"` 精确比对就会漏。把它钉死免得有人"顺手统一成小写"。
         */
        @Test
        @DisplayName("大小写/空格不敏感, 且 L 归一为大写")
        void inputCaseAndSpacingDoNotMatterButLStaysUpperCase() {
            assertThat(created(structured("F", "PT-F", BigDecimal.ONE, "  KG ")).getNetContentUnit()).isEqualTo("kg");
            assertThat(created(structured("G", "PT-G2", BigDecimal.ONE, "L")).getNetContentUnit()).isEqualTo("L");
            assertThat(created(structured("H", "PT-H", BigDecimal.ONE, "l")).getNetContentUnit()).isEqualTo("L");
        }

        @Test
        @DisplayName("值域外单位: 400, 不静默落库")
        void unsupportedUnitIsRejected() {
            assertThatThrownBy(() -> service.createProductType(
                    FACTORY_ID, structured("I", "PT-I", BigDecimal.ONE, "斤")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅允许 g/kg/ml/L");
        }
    }

    @Nested
    @DisplayName("成对必填与取值范围")
    class PairingAndRange {

        @Test
        @DisplayName("只给数量不给单位: 400 (一个没单位的数量对下游无意义)")
        void quantityWithoutUnitIsRejected() {
            ProductTypeDTO dto = new ProductTypeDTO();
            dto.setName("J"); dto.setCode("PT-J"); dto.setUnit("盒"); dto.setCreatedBy(1L); dto.setProductCategory(ProductCategory.FINISHED_PRODUCT);
            dto.setNetContentQuantity(new BigDecimal("500"));   // 只置位 quantityPresent

            assertThatThrownBy(() -> service.createProductType(FACTORY_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须同时提供");
        }

        @Test
        @DisplayName("只给单位不给数量: 400")
        void unitWithoutQuantityIsRejected() {
            ProductTypeDTO dto = new ProductTypeDTO();
            dto.setName("K"); dto.setCode("PT-K"); dto.setUnit("盒"); dto.setCreatedBy(1L); dto.setProductCategory(ProductCategory.FINISHED_PRODUCT);
            dto.setNetContentUnit("g");                          // 只置位 unitPresent

            assertThatThrownBy(() -> service.createProductType(FACTORY_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须同时提供");
        }

        @Test
        @DisplayName("数量为 0 或负: 400")
        void nonPositiveQuantityIsRejected() {
            assertThatThrownBy(() -> service.createProductType(
                    FACTORY_ID, structured("L1", "PT-L1", BigDecimal.ZERO, "g")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须大于 0");

            assertThatThrownBy(() -> service.createProductType(
                    FACTORY_ID, structured("L2", "PT-L2", new BigDecimal("-1"), "g")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须大于 0");
        }
    }

    @Nested
    @DisplayName("旧客户端兼容桥")
    class LegacyGramsPerUnitBridge {

        /**
         * 只传 `gramsPerUnit` 的老客户端, 要被结构化成 `g` —— 否则新老两条路会写出
         * 两种形状的同一件事(形态 D: 同一个东西两份)。
         */
        @Test
        @DisplayName("只传 gramsPerUnit: 结构化为 g, 三个字段一致")
        void legacyGramsOnlyIsStructuredAsGram() {
            ProductTypeDTO dto = new ProductTypeDTO();
            dto.setName("M"); dto.setCode("PT-M"); dto.setUnit("盒"); dto.setCreatedBy(1L); dto.setProductCategory(ProductCategory.FINISHED_PRODUCT);
            dto.setGramsPerUnit(new BigDecimal("300"));

            ProductType saved = created(dto);

            assertThat(saved.getGramsPerUnit()).isEqualByComparingTo("300");
            assertThat(saved.getNetContentQuantity()).isEqualByComparingTo("300");
            assertThat(saved.getNetContentUnit()).isEqualTo("g");
        }

        @Test
        @DisplayName("旧路径同样拒绝非正数")
        void legacyGramsRejectsNonPositive() {
            ProductTypeDTO dto = new ProductTypeDTO();
            dto.setName("N"); dto.setCode("PT-N"); dto.setUnit("盒"); dto.setCreatedBy(1L); dto.setProductCategory(ProductCategory.FINISHED_PRODUCT);
            dto.setGramsPerUnit(BigDecimal.ZERO);

            assertThatThrownBy(() -> service.createProductType(FACTORY_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须大于 0");
        }

        /**
         * 结构化字段在场时, 旧字段<b>不该</b>再被当作输入 ——
         * 否则「同时传 500ml 和 gramsPerUnit=500」会让容量型拿到克重, 正好绕过核心承诺。
         */
        @Test
        @DisplayName("结构化与旧字段同时给: 以结构化为准, 容量型仍不折克重")
        void structuredWinsOverLegacyAndVolumeStaysNull() {
            ProductTypeDTO dto = structured("O", "PT-O", new BigDecimal("500"), "ml");
            dto.setGramsPerUnit(new BigDecimal("500"));   // 老字段试图塞一个克重

            ProductType saved = created(dto);

            assertThat(saved.getNetContentUnit()).isEqualTo("ml");
            assertThat(saved.getGramsPerUnit())
                    .as("结构化容量型必须覆盖掉旧字段带来的克重")
                    .isNull();
        }
    }
}
