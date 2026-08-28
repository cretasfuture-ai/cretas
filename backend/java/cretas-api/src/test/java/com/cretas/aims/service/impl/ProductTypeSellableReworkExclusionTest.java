package com.cretas.aims.service.impl;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.product.ProductRetirementGuard;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductTypeSellableReworkExclusionTest {

    @Test
    void 已发布画布标记的回料SKU不进入销售目录而普通半成品仍保留() {
        ProductTypeRepository productRepository = mock(ProductTypeRepository.class);
        ProductProcessWorkflowRepository workflowRepository = mock(ProductProcessWorkflowRepository.class);
        ProductTypeServiceImpl service = new ProductTypeServiceImpl(
                productRepository,
                new ObjectMapper(),
                mock(CustomerRepository.class),
                mock(WorkflowUnitReviewService.class),
                mock(ProductSpecificationConversionSyncService.class),
                mock(ProductPackagingSpecService.class),
                mock(ProductRetirementGuard.class));
        ReflectionTestUtils.setField(service, "productProcessWorkflowRepository", workflowRepository);

        ProductType finished = product("FG-1", "熟肉饼", ProductCategory.FINISHED_PRODUCT);
        ProductType regularSemi = product("SEMI-1", "普通半成品", ProductCategory.SEMI_FINISHED);
        ProductType rework = product("REWORK-1", "任意名称", ProductCategory.SEMI_FINISHED);
        when(productRepository.findByFactoryIdAndIsActiveTrue("LIUSHANMEN"))
                .thenReturn(List.of(finished, regularSemi, rework));

        ProductProcessWorkflow published = new ProductProcessWorkflow();
        published.setFactoryId("LIUSHANMEN");
        published.setProductTypeId("FG-1");
        published.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        published.setNodesJson("""
                [{"id":"rework-port","data":{"skuId":"REWORK-1","isRework":true}}]
                """);
        ProductProcessWorkflow draft = new ProductProcessWorkflow();
        draft.setFactoryId("LIUSHANMEN");
        draft.setProductTypeId("FG-1");
        draft.setStatus(ProductProcessWorkflow.Status.DRAFT);
        draft.setNodesJson("""
                [{"id":"draft-only","data":{"skuId":"SEMI-1","isRework":true}}]
                """);
        when(workflowRepository.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc("LIUSHANMEN"))
                .thenReturn(List.of(published, draft));

        List<ProductTypeDTO> result = service.getSellableProductTypes("LIUSHANMEN");

        assertThat(result).extracting(ProductTypeDTO::getId)
                .containsExactly("FG-1", "SEMI-1")
                .doesNotContain("REWORK-1");
    }

    private ProductType product(String id, String name, String category) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId("LIUSHANMEN");
        product.setCode(id);
        product.setName(name);
        product.setUnit("kg");
        product.setProductCategory(category);
        product.setIsActive(true);
        return product;
    }
}
