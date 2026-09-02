package com.cretas.aims.service.impl;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-09-02 prod hotfix 回归测试。
 *
 * <p>Prod 事故: 07:47:51 LIUSHANMEN 工厂 user 1638 通过正常建产品类型接口创建
 * SEMI_FINISHED 产品 (PTSEMI-LSM-DEFECT「金检剔出不良品」), 请求体没有带
 * {@code acceptsRework} 字段。DB 侧 {@code product_types.accepts_rework} 是
 * {@code NOT NULL DEFAULT TRUE} (V20261101_05), 但 Hibernate 把这一列显式列进
 * INSERT (值为 null) —— 列出现在 INSERT 里, DB 侧列默认值就不会生效 —— 直接违反
 * NOT NULL 约束, 整个建产品类型请求 500。
 *
 * <p>根因分两层, 两层都要覆盖:
 * <ol>
 *   <li>{@link ProductType#acceptsRework} 没有 Java 侧默认值 (null)</li>
 *   <li>{@link ProductTypeServiceImpl#buildProductTypeEntity} 无条件把
 *       {@code dto.getAcceptsRework()} (可能是 null) 写回实体, 覆盖掉任何实体侧默认值</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ProductTypeAcceptsReworkDefaultTest {

    @Mock ProductTypeRepository productTypeRepository;
    @Mock CustomerRepository customerRepository;
    @Mock WorkflowUnitReviewService workflowUnitReviewService;
    @Mock ProductSpecificationConversionSyncService conversionSyncService;
    @Mock ProductPackagingSpecService packagingSpecService;

    private ProductTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductTypeServiceImpl(productTypeRepository, new ObjectMapper(), customerRepository,
                workflowUnitReviewService, conversionSyncService, packagingSpecService);
    }

    /** 实体级: 裸 new 出来的 ProductType, 没有任何 setter 调用, acceptsRework 必须是 TRUE 不是 null。 */
    @Test
    void freshEntityDefaultsAcceptsReworkToTrueNotNull() {
        ProductType productType = new ProductType();
        assertThat(productType.getAcceptsRework()).isNotNull().isTrue();
    }

    /**
     * 服务级 (复现 prod 事故的真实入口): 请求没有带 acceptsRework 字段 (dto 里是 null),
     * 走 createProductType -> buildProductTypeEntity -> repository.save() —— 捕获真正交给
     * save() 的实体, 断言它的 acceptsRework 不是 null (不会撞 NOT NULL), 且值维持 DB 语义
     * 的默认值 TRUE。
     */
    @Test
    void createProductTypeOmittingAcceptsReworkPersistsNonNullDefault() {
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .code("PTSEMI-LSM-DEFECT")
                .name("金检剔出不良品")
                .productCategory(ProductCategory.SEMI_FINISHED)
                .unit("kg")
                .build();
        // 阳性对照: 确认这条 DTO 确实没有带这个字段 (与 prod 请求体同形)
        assertThat(dto.getAcceptsRework()).isNull();

        service.createProductType("F006", dto);

        ArgumentCaptor<ProductType> captor = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository).save(captor.capture());
        assertThat(captor.getValue().getAcceptsRework())
                .as("acceptsRework 不能是 null —— null 会在真实 DB 上撞 NOT NULL 约束")
                .isNotNull()
                .isTrue();
    }

    /** 阴性对照: 请求显式传 acceptsRework=false (汉堡排类), 必须原样落地, 证明没有硬编码成 true。 */
    @Test
    void createProductTypeWithExplicitFalseIsPreserved() {
        when(productTypeRepository.save(any(ProductType.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeDTO dto = ProductTypeDTO.builder()
                .code("PT-BURGER-PATTY")
                .name("汉堡排")
                .productCategory(ProductCategory.FINISHED_PRODUCT)
                .unit("片")
                .acceptsRework(Boolean.FALSE)
                .build();

        service.createProductType("F006", dto);

        ArgumentCaptor<ProductType> captor = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository).save(captor.capture());
        assertThat(captor.getValue().getAcceptsRework()).isFalse();
    }
}
