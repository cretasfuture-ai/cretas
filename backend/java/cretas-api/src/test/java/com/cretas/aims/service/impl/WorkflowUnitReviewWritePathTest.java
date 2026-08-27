package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.cretas.aims.utils.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowUnitReviewWritePathTest {

    @Test
    void productPrimaryUnitChangeMarksFactoryWorkflows() {
        ProductTypeRepository repository = mock(ProductTypeRepository.class);
        WorkflowUnitReviewService reviewService = mock(WorkflowUnitReviewService.class);
        ProductTypeServiceImpl service = new ProductTypeServiceImpl(
                repository, new ObjectMapper(), mock(CustomerRepository.class), reviewService,
                mock(com.cretas.aims.service.unit.ProductSpecificationConversionSyncService.class));
        ProductType product = new ProductType();
        product.setId("P1");
        product.setFactoryId("F1");
        product.setUnit("pcs");
        // 生产侧已收紧 (assertUnitChangeDoesNotBypassMigration,
        // ProductTypeServiceImpl.java:479-484): 普通编辑不再允许直接改基本单位
        // (会抛 SKU_UNIT_MIGRATION_REQUIRED, 逼走受控迁移), 除非产品分类是
        // SEMI_FINISHED —— 这是唯一仍走本测试要验证的「普通编辑改单位 → 标记
        // workflow 待复核」路径的分类, 因此测试夹具显式声明该分类。
        product.setProductCategory(com.cretas.aims.entity.enums.ProductCategory.SEMI_FINISHED);
        when(repository.findById("P1")).thenReturn(Optional.of(product));
        when(repository.save(product)).thenReturn(product);
        ProductTypeDTO request = new ProductTypeDTO();
        request.setUnit("g");

        service.updateProductType("F1", "P1", request);

        verify(reviewService).markPublishedWorkflowsForReview("F1");
    }

    @Test
    void rawMaterialPrimaryUnitChangeMarksFactoryWorkflows() {
        RawMaterialTypeRepository repository = mock(RawMaterialTypeRepository.class);
        WorkflowUnitReviewService reviewService = mock(WorkflowUnitReviewService.class);
        RawMaterialTypeServiceImpl service = new RawMaterialTypeServiceImpl(
                repository,
                mock(MaterialBatchRepository.class),
                mock(ConversionRepository.class),
                mock(MaterialPackagingHierarchyRepository.class),
                mock(MaterialCodeSegmentRepository.class),
                mock(ExcelUtil.class),
                reviewService);
        RawMaterialType material = new RawMaterialType();
        material.setId("R1");
        material.setFactoryId("F1");
        material.setUnit("kg");
        // 生产侧已收紧 (RawMaterialTypeServiceImpl.java:248-252): 更新时若最终
        // category 为空即报「请选择物料基本类型」, 不再静默留空 —— 与
        // ProductTypeServiceImpl 那条「SKU 大类必填」是同一批加固。夹具需显式
        // 提供一个有效分类, 让用例真正走到「单位变更 → 标记 workflow 待复核」这一步。
        material.setCategory(com.cretas.aims.entity.enums.ProductCategory.RAW_MATERIAL);
        when(repository.findById("R1")).thenReturn(Optional.of(material));
        when(repository.save(material)).thenReturn(material);
        RawMaterialTypeDTO request = new RawMaterialTypeDTO();
        request.setUnit("g");

        service.updateMaterialType("F1", "R1", request);

        verify(reviewService).markPublishedWorkflowsForReview("F1");
    }
}
