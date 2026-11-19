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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKU 自动编码契约: 取 max+1 (含软删行已占用的号)、并发串行化、冲突提示不露字段名。
 * 软删行是否真的被查出来由真 JPA 的 ProductTypeGeneratedCodeSoftDeleteContractTest 守;
 * 本类守 Service 在「仓库返回含软删行编码」时的取号与并发/冲突行为。
 */
@DisplayName("SKU 自动编码 max+1 / 并发串行 / 冲突提示")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductTypeGeneratedCodeContractTest {

    private static final String FACTORY_ID = "F006";

    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private CustomerRepository customerRepository;

    private ProductTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductTypeServiceImpl(productTypeRepository, new ObjectMapper(), customerRepository,
                org.mockito.Mockito.mock(com.cretas.aims.service.workflow.WorkflowUnitReviewService.class),
                org.mockito.Mockito.mock(com.cretas.aims.service.unit.ProductSpecificationConversionSyncService.class));
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ProductTypeDTO autoCodeDto() {
        ProductTypeDTO dto = new ProductTypeDTO();
        dto.setName("自动编码测试品");
        dto.setUnit("盒");
        dto.setCreatedBy(1L);
        dto.setProductCategory(ProductCategory.FINISHED_PRODUCT);
        dto.setNetContentQuantity(new BigDecimal("500"));
        dto.setNetContentUnit("g");
        return dto;
    }

    @Test
    @DisplayName("已有编码含软删行占用的号 → 取其最大序号 +1, 不复用被占的 0039")
    void generatesMaxPlusOneIncludingCodesOccupiedBySoftDeletedRows() {
        // 活行最大 0038, 软删行占 0039..0048 (prod F006 实测形态); 仓库(原生查询)返回全部
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix(anyString(), anyString()))
                .thenReturn(List.of("CPF0060001", "CPF0060038", "CPF0060039", "CPF0060043", "CPF0060048"));

        service.createProductType(FACTORY_ID, autoCodeDto());

        ArgumentCaptor<ProductType> saved = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository).save(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("CPF0060049");
    }

    @Test
    @DisplayName("预览与落库同口径: 同样的已有编码, 预览出的号 == 落库的号")
    void previewMatchesSavedCode() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix(anyString(), anyString()))
                .thenReturn(List.of("CPF0060038", "CPF0060048"));

        String preview = service.previewGeneratedCode(FACTORY_ID, "FINISHED_PRODUCT", null, null);
        service.createProductType(FACTORY_ID, autoCodeDto());

        ArgumentCaptor<ProductType> saved = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository).save(saved.capture());
        assertThat(preview).isEqualTo("CPF0060049");
        assertThat(saved.getValue().getCode()).isEqualTo(preview);
    }

    @Test
    @DisplayName("并发: 读 max 之前必须先拿同厂取号 advisory lock (同厂自动编码创建串行)")
    void takesGeneratedCodeLockBeforeReadingExistingCodes() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix(anyString(), anyString()))
                .thenReturn(List.of());

        service.createProductType(FACTORY_ID, autoCodeDto());

        InOrder order = inOrder(productTypeRepository);
        order.verify(productTypeRepository).lockGeneratedCodeSequence("product_code:" + FACTORY_ID);
        order.verify(productTypeRepository).findCodesByFactoryIdAndGeneratedPrefix(anyString(), anyString());
        order.verify(productTypeRepository).save(any(ProductType.class));
    }

    @Test
    @DisplayName("手输编码不拿锁 (阴性对照: 只有自动编码才串行)")
    void manualCodeDoesNotTakeLock() {
        ProductTypeDTO dto = autoCodeDto();
        dto.setCode("MY-CODE-1");
        when(productTypeRepository.existsByFactoryIdAndCode(FACTORY_ID, "MY-CODE-1")).thenReturn(false);

        service.createProductType(FACTORY_ID, dto);

        verify(productTypeRepository, org.mockito.Mockito.never()).lockGeneratedCodeSequence(anyString());
    }

    @Test
    @DisplayName("落库仍撞唯一约束 → 友好 409, 用户可见文案不含 factory_id / 约束名")
    void collisionBecomesFriendly409WithoutInternalFieldNames() {
        when(productTypeRepository.findCodesByFactoryIdAndGeneratedPrefix(anyString(), anyString()))
                .thenReturn(List.of("CPF0060038"));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uk_product_types_factory_code\" "
                                + "Detail: Key (factory_id, code)=(F006, CPF0060039) already exists."))
                .when(productTypeRepository).flush();

        assertThatThrownBy(() -> service.createProductType(FACTORY_ID, autoCodeDto()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).doesNotContain("factory_id").doesNotContain("uk_product_types");
                    assertThat(String.valueOf(e.getActionHint())).doesNotContain("factory_id");
                });
    }
}
