package com.cretas.aims.service.impl;

import com.cretas.aims.dto.config.BindQualityCheckItemRequest;
import com.cretas.aims.entity.config.QualityCheckItem;
import com.cretas.aims.entity.config.QualityCheckItemBinding;
import com.cretas.aims.entity.enums.QualityCheckCategory;
import com.cretas.aims.exception.ValidationException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.QualityCheckItemBindingRepository;
import com.cretas.aims.repository.QualityCheckItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualityCheckItemBindingTenantContractTest {

    private static final String FACTORY = "F006";
    private static final String ITEM_ID = "item-a";
    private static final String PRODUCT_ID = "product-a";

    @Mock private QualityCheckItemRepository qualityCheckItemRepository;
    @Mock private QualityCheckItemBindingRepository bindingRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @InjectMocks private QualityCheckItemServiceImpl service;

    @BeforeEach
    void setUp() {
        QualityCheckItem item = QualityCheckItem.builder()
                .id(ITEM_ID)
                .factoryId(FACTORY)
                .itemCode("TEMP-1")
                .itemName("温度")
                .category(QualityCheckCategory.PHYSICAL)
                .valueType("NUMERIC")
                .enabled(true)
                .build();
        lenient().when(qualityCheckItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        lenient().when(bindingRepository.existsByProductTypeIdAndQualityCheckItemIdAndDeletedAtIsNull(PRODUCT_ID, ITEM_ID))
                .thenReturn(false);
        lenient().when(bindingRepository.countByQualityCheckItemIdAndEnabledTrueAndDeletedAtIsNull(ITEM_ID)).thenReturn(0L);
        lenient().when(bindingRepository.save(any(QualityCheckItemBinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void sameFactoryProductBindsAndPersistsFactoryScope() {
        when(productTypeRepository.existsByIdAndFactoryId(PRODUCT_ID, FACTORY)).thenReturn(true);

        var result = service.bindToProduct(FACTORY, request(PRODUCT_ID));

        assertThat(result.getFactoryId()).isEqualTo(FACTORY);
        assertThat(result.getProductTypeId()).isEqualTo(PRODUCT_ID);
        verify(productTypeRepository).existsByIdAndFactoryId(PRODUCT_ID, FACTORY);
        verify(productTypeRepository, never()).existsById(anyString());
        verify(bindingRepository).save(argThat(binding -> FACTORY.equals(binding.getFactoryId())));
    }

    @Test
    void foreignFactoryProductIsRejectedBeforeAnyBindingWrite() {
        when(productTypeRepository.existsByIdAndFactoryId(PRODUCT_ID, FACTORY)).thenReturn(false);

        assertThatThrownBy(() -> service.bindToProduct(FACTORY, request(PRODUCT_ID)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(PRODUCT_ID);

        verify(productTypeRepository).existsByIdAndFactoryId(PRODUCT_ID, FACTORY);
        verify(bindingRepository, never()).save(any(QualityCheckItemBinding.class));
    }

    @Test
    void softDeletedProductIsRejectedByTenantAwareLookupBeforeAnyBindingWrite() {
        // The repository method excludes a soft-deleted product; the service must treat it as absent.
        when(productTypeRepository.existsByIdAndFactoryId(PRODUCT_ID, FACTORY)).thenReturn(false);

        assertThatThrownBy(() -> service.bindToProduct(FACTORY, request(PRODUCT_ID)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Product type not found");

        verify(productTypeRepository).existsByIdAndFactoryId(PRODUCT_ID, FACTORY);
        verify(bindingRepository, never()).save(any(QualityCheckItemBinding.class));
    }

    @Test
    void blankStringOverrideInheritsWhileExplicitFalseAndZeroRemainEffective() {
        QualityCheckItem booleanItem = QualityCheckItem.builder()
                .id(ITEM_ID)
                .factoryId(FACTORY)
                .itemCode("BOOLEAN-1")
                .itemName("包装完整")
                .category(QualityCheckCategory.PHYSICAL)
                .valueType("BOOLEAN")
                .standardValue("true")
                .minValue(BigDecimal.ONE)
                .samplingRatio(BigDecimal.TEN)
                .isRequired(true)
                .enabled(true)
                .build();
        when(qualityCheckItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(booleanItem));
        when(productTypeRepository.existsByIdAndFactoryId(PRODUCT_ID, FACTORY)).thenReturn(true);

        BindQualityCheckItemRequest request = BindQualityCheckItemRequest.builder()
                .productTypeId(PRODUCT_ID)
                .qualityCheckItemId(ITEM_ID)
                .overrideStandardValue("  ")
                .overrideMinValue(BigDecimal.ZERO)
                .overrideSamplingRatio(BigDecimal.ZERO)
                .overrideIsRequired(false)
                .enabled(true)
                .build();

        var result = service.bindToProduct(FACTORY, request);

        assertThat(result.getEffectiveStandardValue()).isEqualTo("true");
        assertThat(result.getEffectiveMinValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getEffectiveSamplingRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getEffectiveIsRequired()).isFalse();

        for (String explicitStandard : new String[]{"false", "0"}) {
            request.setOverrideStandardValue(explicitStandard);
            assertThat(service.bindToProduct(FACTORY, request).getEffectiveStandardValue())
                    .isEqualTo(explicitStandard);
        }
    }

    private static BindQualityCheckItemRequest request(String productTypeId) {
        return BindQualityCheckItemRequest.builder()
                .productTypeId(productTypeId)
                .qualityCheckItemId(ITEM_ID)
                .enabled(true)
                .build();
    }
}
