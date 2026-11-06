package com.cretas.aims.ai.tool.impl.dataop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.producttype.ProductTypeSuggestionDTO;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.ProductCreateCommandReceiptService;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private ProductCreateCommandReceiptService commandReceiptService;

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
        ReflectionTestUtils.setField(tool, "commandReceiptService", commandReceiptService);
        ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
        lenient().when(commandReceiptService.createProductAndAttach(anyString(), any(ProductTypeDTO.class)))
                .thenAnswer(invocation -> productTypeService.createProductType(
                        invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(commandReceiptService.copyPreparedAndAdvance(
                anyString(), anyString(), anyString(), any(ProductWorkProcessService.PreparedChain.class)))
                .thenAnswer(invocation -> productWorkProcessService.copyPreparedChain(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3)));
    }

    @Test
    void similarNonEmptyChainIsCopiedAndCountIsReported() throws Exception {
        configureSource(List.of(process("切片", "wp-slice"), process("腌制", "wp-marinate")));
        when(productWorkProcessService.copyPreparedChain(eq(FACTORY_ID), eq(SOURCE_ID),
                eq(TARGET_ID), any(ProductWorkProcessService.PreparedChain.class))).thenReturn(2);
        configureCreatedProduct();

        JsonNode result = execute();

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.at("/data/copiedProcessCount").asInt()).isEqualTo(2);
        assertThat(result.at("/data/inheritedFromProduct").asText()).isEqualTo(SOURCE_NAME);
        verify(productWorkProcessService).copyPreparedChain(eq(FACTORY_ID), eq(SOURCE_ID),
                eq(TARGET_ID), any(ProductWorkProcessService.PreparedChain.class));
        verify(productTypeService).createProductType(eq(FACTORY_ID), any(ProductTypeDTO.class));
    }

    @Test
    void directNativePreviewDoesNotExposeServerBinding() throws Exception {
        JsonNode preview = objectMapper.readTree(tool.preview(
                com.cretas.aims.ai.dto.ToolCall.of(
                        "product-create-native-preview", "product_create",
                        objectMapper.writeValueAsString(Map.of("productName", TARGET_NAME))),
                Map.of("factoryId", FACTORY_ID, "userId", 42L)));

        assertThat(preview.at("/data/" + ProductCreateTool.SERVER_BINDING_KEY).isMissingNode())
                .isTrue();
    }

    @Test
    void previewCaptureScopeIsRemovedWhenCaptureFails() {
        try (ProductCreateTool.PreviewBindingCapture capture =
                     ProductCreateTool.openPreviewBindingCapture(FACTORY_ID)) {
            assertThatThrownBy(capture::requireJson)
                    .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        }

        ProductCreateTool.PreviewBindingCapture reopened =
                ProductCreateTool.openPreviewBindingCapture(FACTORY_ID);
        reopened.close();
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
                .when(productWorkProcessService).copyPreparedChain(eq(FACTORY_ID), eq(SOURCE_ID),
                        eq(TARGET_ID), any(ProductWorkProcessService.PreparedChain.class));
        when(commandReceiptService.readCurrent()).thenReturn(Optional.of(receipt(
                ProductCreateCommandReceiptService.PRODUCT_CREATED)));
        configureCreatedProduct();

        JsonNode result = execute();

        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("error").asText())
                .contains(TARGET_ID, TARGET_NAME, SOURCE_NAME, "已创建", "未完成继承")
                .doesNotContain("database secret", "无相似", "已继承");
        verify(productTypeService).createProductType(eq(FACTORY_ID), any(ProductTypeDTO.class));
        verify(productWorkProcessService).copyPreparedChain(eq(FACTORY_ID), eq(SOURCE_ID),
                eq(TARGET_ID), any(ProductWorkProcessService.PreparedChain.class));
    }

    @Test
    void copyCommitAcknowledgementLossUsesDurableStageAndContinuesReadback() throws Exception {
        configureSource(List.of(process("切片", "wp-slice"), process("腌制", "wp-marinate")));
        configureCreatedProduct();
        when(commandReceiptService.copyPreparedAndAdvance(
                anyString(), anyString(), anyString(), any(ProductWorkProcessService.PreparedChain.class)))
                .thenThrow(new IllegalStateException("ack lost"));
        when(commandReceiptService.readCurrent()).thenReturn(Optional.of(receipt(
                ProductCreateCommandReceiptService.COPY_COMMITTED)));

        JsonNode result = execute();

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.at("/data/copiedProcessCount").asInt()).isEqualTo(2);
        verify(commandReceiptService).verifyPreparedReadbackAndAdvance(
                eq(FACTORY_ID), eq(TARGET_ID), any(ProductWorkProcessService.PreparedChain.class));
    }

    @Test
    void sourceChainDriftIsRejectedBeforeProductWrite() throws Exception {
        configureSource(List.of(process("切片", "wp-slice")));
        doThrow(new com.cretas.aims.exception.BusinessException(409,
                "源产品工序链或主工序版本已变化"))
                .when(productWorkProcessService).validatePreparedChain(
                        eq(FACTORY_ID), eq(SOURCE_ID), any(ProductWorkProcessService.PreparedChain.class));

        JsonNode result = execute();

        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("error").asText()).contains("已变化");
        verify(productTypeService, never()).createProductType(eq(FACTORY_ID), any(ProductTypeDTO.class));
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
        when(productTypeRepository.findByIdAndFactoryId(SOURCE_ID, FACTORY_ID))
                .thenReturn(Optional.of(source));
        when(processSecretGuard.permitsProduct(anyString(), anyString(), any(), anyString()))
                .thenReturn(true);
        when(productWorkProcessService.prepareChain(FACTORY_ID, SOURCE_ID)).thenReturn(prepared(chain));
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

    private ProductWorkProcessService.PreparedChain prepared(List<ProductWorkProcessDTO> chain) {
        return new ProductWorkProcessService.PreparedChain(
                SOURCE_ID,
                chain.stream().map(row -> new ProductWorkProcessService.PreparedProcess(
                        row.getWorkProcessId(), row.getProcessOrder(), row.getUnitOverride(),
                        row.getEstimatedMinutesOverride(), row.getReportingRequired(),
                        row.getAllowSemiFinishedInjection(), row.getAllowMultipleUpstreamSources(),
                        row.getAllowFinishedGoodsSource(), row.getDefaultCostCategory(),
                        row.getPackagingTemplate(), row.getAuxAllocMethod(), row.getStandardYieldRate(),
                        row.getAuxUnitPrice(), row.getAuxBasis())).toList(),
                List.of(), "digest");
    }

    private ProductCreateCommandReceiptService.ReceiptSnapshot receipt(String stage) {
        return new ProductCreateCommandReceiptService.ReceiptSnapshot(
                "receipt-1", ToolExecutionIdempotencyRecord.State.IN_PROGRESS,
                ToolExecutionStatus.SUCCEEDED, ProductCreateCommandReceiptService.ENTITY_TYPE,
                TARGET_ID, stage, null);
    }

    private JsonNode execute() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("productName", TARGET_NAME);
        params.put("productCategory", "FINISHED_PRODUCT");
        String bindingJson;
        try (ProductCreateTool.PreviewBindingCapture capture =
                     ProductCreateTool.openPreviewBindingCapture(FACTORY_ID)) {
            objectMapper.readTree(tool.preview(
                    com.cretas.aims.ai.dto.ToolCall.of(
                            "product-create-preview-contract", "product_create",
                            objectMapper.writeValueAsString(params)),
                    Map.of("factoryId", FACTORY_ID, "userId", 42L)));
            bindingJson = capture.requireJson();
        }
        params.put(ProductCreateTool.SERVER_BINDING_KEY, bindingJson);
        try (ProductCreateCommandReceiptService.CommandScope ignored =
                     ProductCreateCommandReceiptService.openScope(
                             FACTORY_ID, "FACTORY", PrincipalType.USER, "42",
                             ProductCreateCommandReceiptService.TOOL_NAME,
                             ProductCreateCommandReceiptService.DESCRIPTOR_VERSION,
                             "idem-hash", "command-hash", "confirmation-hash")) {
            return objectMapper.readTree(tool.execute(
                    com.cretas.aims.ai.dto.ToolCall.of(
                            "product-create-contract",
                            "product_create",
                            objectMapper.writeValueAsString(params)),
                    Map.of("factoryId", FACTORY_ID, "userId", 42L)));
        }
    }
}
