package com.cretas.aims.ai.tool.impl.dataop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.producttype.ProductTypeSuggestionDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCreateToolContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String SOURCE_ID = "source-product";
    private static final String SOURCE_NAME = "相似产品";
    private static final String TARGET_ID = "target-product";
    private static final String TARGET_NAME = "新产品";

    @Mock
    private ProductTypeService productTypeService;
    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private ProductWorkProcessService productWorkProcessService;
    @Mock
    private BomRecipeService bomRecipeService;
    @Mock
    private ProcessSecretRecipeGuard processSecretGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductCreateTool tool;

    @BeforeEach
    void setUp() {
        tool = new ProductCreateTool();
        ReflectionTestUtils.setField(tool, "productTypeService", productTypeService);
        ReflectionTestUtils.setField(tool, "productTypeRepository", productTypeRepository);
        ReflectionTestUtils.setField(tool, "productWorkProcessService", productWorkProcessService);
        ReflectionTestUtils.setField(tool, "bomRecipeService", bomRecipeService);
        ReflectionTestUtils.setField(tool, "processSecretGuard", processSecretGuard);
        ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
    }

    @Test
    void similarNonEmptyChainIsCopiedAndCountIsReported() throws Exception {
        configureSource(List.of(process("切片", "wp-slice"), process("腌制", "wp-marinate")));
        when(productWorkProcessService.copyChain(FACTORY_ID, SOURCE_ID, TARGET_ID)).thenReturn(2);
        configureCreatedProduct();

        JsonNode result = execute();

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.at("/data/copiedProcessCount").asInt()).isEqualTo(2);
        assertThat(result.at("/data/inheritedFromProduct").asText()).isEqualTo(SOURCE_NAME);
        verify(productWorkProcessService).copyChain(FACTORY_ID, SOURCE_ID, TARGET_ID);
        verify(productTypeService).createProductType(eq(FACTORY_ID), any(ProductTypeDTO.class));
    }

    @Test
    void noSourceCreatesProductWithoutAttemptingCopy() throws Exception {
        configureCreatedProduct();

        JsonNode result = execute();

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.at("/data/inheritedFromProduct").isNull()).isTrue();
        assertThat(result.at("/data/copiedProcessCount").asInt()).isZero();
        verify(productWorkProcessService, never()).copyChain(anyString(), anyString(), anyString());
    }

    @Test
    void sourceWithEmptyChainCreatesProductWithoutAttemptingCopy() throws Exception {
        configureSource(List.of());
        configureCreatedProduct();

        JsonNode result = execute();

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.at("/data/inheritedFromProduct").asText()).isEqualTo(SOURCE_NAME);
        assertThat(result.at("/data/message").asText()).contains("没有可继承的工序链");
        verify(productWorkProcessService, never()).copyChain(anyString(), anyString(), anyString());
    }

    @Test
    void copyFailureIsAnExplicitUnknownOutcomeWithSourceFacts() throws Exception {
        configureSource(List.of(process("切片", "wp-slice")));
        doThrow(new IllegalStateException("database secret must not escape"))
                .when(productWorkProcessService).copyChain(FACTORY_ID, SOURCE_ID, TARGET_ID);
        configureCreatedProduct();

        JsonNode result = execute();

        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("error").asText())
                .contains(TARGET_ID, TARGET_NAME, SOURCE_NAME, "已创建", "未完成继承")
                .doesNotContain("database secret", "无相似", "已继承");
        verify(productTypeService).createProductType(eq(FACTORY_ID), any(ProductTypeDTO.class));
        verify(productWorkProcessService).copyChain(FACTORY_ID, SOURCE_ID, TARGET_ID);
    }

    private void configureSource(List<ProductWorkProcessDTO> chain) {
        ProductType source = new ProductType();
        source.setId(SOURCE_ID);
        source.setName(SOURCE_NAME);
        source.setFactoryId(FACTORY_ID);
        source.setProductCategory("FINISHED_PRODUCT");
        when(productTypeService.suggestDefaults(FACTORY_ID, TARGET_NAME, "FINISHED_PRODUCT"))
                .thenReturn(ProductTypeSuggestionDTO.builder().baseProductName(SOURCE_NAME).build());
        when(productTypeRepository.findByFactoryIdAndName(FACTORY_ID, SOURCE_NAME))
                .thenReturn(Optional.of(source));
        when(processSecretGuard.permitsProduct(anyString(), anyString(), any(), anyString()))
                .thenReturn(true);
        when(productWorkProcessService.listByProduct(FACTORY_ID, SOURCE_ID)).thenReturn(chain);
        when(bomRecipeService.getCurrentRecipe(FACTORY_ID, SOURCE_ID)).thenReturn(Optional.empty());
        when(bomRecipeService.getSeasoningByProduct(FACTORY_ID, SOURCE_ID)).thenReturn(Optional.empty());
    }

    private void configureCreatedProduct() {
        ProductTypeDTO created = ProductTypeDTO.builder()
                .id(TARGET_ID)
                .name(TARGET_NAME)
                .unit("盒")
                .build();
        when(productTypeService.createProductType(eq(FACTORY_ID), any(ProductTypeDTO.class)))
                .thenReturn(created);
    }

    private ProductWorkProcessDTO process(String name, String workProcessId) {
        return ProductWorkProcessDTO.builder()
                .processName(name)
                .workProcessId(workProcessId)
                .processOrder(1)
                .build();
    }

    private JsonNode execute() throws Exception {
        return objectMapper.readTree(tool.execute(
                com.cretas.aims.ai.dto.ToolCall.of(
                        "product-create-contract",
                        "product_create",
                        "{\"productName\":\"" + TARGET_NAME
                                + "\",\"productCategory\":\"FINISHED_PRODUCT\"}"),
                Map.of("factoryId", FACTORY_ID, "userId", 42L)));
    }
}
