package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.impl.material.MaterialStockSummaryTool;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolGatewayPriceBoundaryContractTest {

    @Mock UserRepository userRepository;
    @Mock FactoryRepository factoryRepository;
    @Mock ToolRuntimeRegistry runtimeRegistry;
    @Mock ToolConfirmationLease confirmationLease;
    @Mock ToolExecutionLedgerService ledgerService;
    @Mock MaterialBatchService materialBatchService;
    @Mock FindingService findingService;
    @Mock FindingTextRenderer findingTextRenderer;

    private ToolPrincipalPolicy principalPolicy;
    private PermissionServiceImpl permissionService;
    private User activeUser;
    private Factory activeFactory;

    private ObjectMapper objectMapper;
    private DefaultToolExecutionGateway gateway;
    private MaterialStockSummaryTool concreteTool;
    private MaterialBatchDTO source;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionServiceImpl();
        activeUser = new User();
        activeUser.setId(42L);
        activeUser.setFactoryId("F006");
        activeUser.setIsActive(true);
        activeUser.setRoleCode("warehouse_manager");
        activeFactory = new Factory();
        activeFactory.setId("F006");
        activeFactory.setIsActive(true);
        activeFactory.setType(FactoryType.FACTORY);
        principalPolicy = new ToolPrincipalPolicy(userRepository, factoryRepository, permissionService);
        lenient().when(userRepository.findById(42L)).thenReturn(Optional.of(activeUser));
        lenient().when(factoryRepository.findById("F006")).thenReturn(Optional.of(activeFactory));
        objectMapper = new ObjectMapper()
                .registerModule(new PriceSensitiveJacksonConfig().priceSensitiveJacksonModule());
        concreteTool = new MaterialStockSummaryTool();
        ReflectionTestUtils.setField(concreteTool, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(concreteTool, "materialBatchService", materialBatchService);
        ReflectionTestUtils.setField(concreteTool, "findingService", findingService);
        ReflectionTestUtils.setField(concreteTool, "findingTextRenderer", findingTextRenderer);
        source = MaterialBatchDTO.builder()
                .id("batch-1")
                .materialName("鲈鱼")
                .receiptQuantity(new BigDecimal("12"))
                .totalValue(new BigDecimal("99.00"))
                .unitPrice(new BigDecimal("8.25"))
                .totalPrice(new BigDecimal("99.00"))
                .build();
        lenient().when(materialBatchService.getMaterialBatchList(any(), any()))
                .thenReturn(PageResponse.of(java.util.List.of(source), 1, 100, 1L));
        lenient().when(findingService.detectInline("F006", "inventory"))
                .thenReturn(new FindingService.Result(java.util.List.of(),
                        java.util.List.of("LOW_STOCK"), 1,
                        Map.of("LOW_STOCK", 1), java.util.List.of()));
        lenient().when(findingTextRenderer.renderInline(any())).thenReturn("");
        gateway = new DefaultToolExecutionGateway(
                principalPolicy, runtimeRegistry, confirmationLease, ledgerService, objectMapper);
        lenient().when(ledgerService.beginAudit(any(), any())).thenReturn("audit-1");
        lenient().when(runtimeRegistry.resolve(any(), any())).thenReturn(Optional.of(new ToolRuntimeRegistry.ResolvedTool(
                descriptor(), concreteTool)));
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void clearPriceContext() {
        PriceSensitiveContext.clear();
    }

    @Test
    void deniedConcreteInventoryExecutionMasksPricesAndPreservesQuantityAndSource() {
        activeUser.setRoleCode("warehouse_manager");

        ToolExecutionResult result = gateway.execute(command());

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertPriceFieldsHidden(result);
        assertThat(result.payload().at("/data/totalBatches").longValue()).isEqualTo(1L);
        assertThat(result.payload().at("/data/batches/0/receiptQuantity").decimalValue())
                .isEqualByComparingTo("12");
        assertSourceUnchanged();
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isFalse();
    }

    @Test
    void realCanonicalBossKeepsConcretePricesAndIgnoresForgedManagerClaims() {
        activeUser.setRoleCode("factory_super_admin");

        ToolExecutionResult result = gateway.execute(command());

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(result.payload().at("/data/batches/0/totalValue").decimalValue())
                .isEqualByComparingTo("99.00");
        assertThat(result.payload().at("/data/batches/0/unitPrice").decimalValue())
                .isEqualByComparingTo("8.25");
        assertThat(result.payload().at("/data/batches/0/totalPrice").decimalValue())
                .isEqualByComparingTo("99.00");
        assertThat(result.payload().at("/data/batches/0/receiptQuantity").decimalValue())
                .isEqualByComparingTo("12");
        assertSourceUnchanged();
    }

    @Test
    void deniedThenAllowedConcreteExecutionOnSameThreadRestoresSourceAndVisibility() {
        activeUser.setRoleCode("warehouse_manager");
        ToolExecutionResult denied = gateway.execute(command());
        assertPriceFieldsHidden(denied);

        activeUser.setRoleCode("factory_super_admin");
        ToolExecutionResult allowed = gateway.execute(command());
        assertThat(allowed.payload().at("/data/batches/0/totalValue").decimalValue())
                .isEqualByComparingTo("99.00");
        assertThat(allowed.payload().at("/data/batches/0/totalPrice").decimalValue())
                .isEqualByComparingTo("99.00");
        assertSourceUnchanged();
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isFalse();
    }

    @Test
    void unknownActorIsDeniedBeforeConcreteInventoryService() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        ToolExecutionResult result = gateway.execute(command());

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.DENIED);
        verify(materialBatchService, never()).getMaterialBatchList(any(), any());
    }

    @Test
    void concreteServiceFailureReturnsFailedAndRestoresPriorContext() {
        activeUser.setRoleCode("warehouse_manager");
        when(materialBatchService.getMaterialBatchList(any(), any()))
                .thenThrow(new IllegalStateException("inventory unavailable"));

        PriceSensitiveContext.hide();
        ToolExecutionResult result = gateway.execute(command());
        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isTrue();
        PriceSensitiveContext.clear();
    }

    @Test
    void deniedPreviewMasksPricesAndRestoresThreadContext() {
        activeUser.setRoleCode("warehouse_manager");
        PreviewPriceTool previewTool = new PreviewPriceTool(objectMapper, source);
        when(runtimeRegistry.resolve(any(), any())).thenReturn(Optional.of(new ToolRuntimeRegistry.ResolvedTool(
                previewDescriptor(), previewTool)));

        ToolExecutionResult result = gateway.execute(new ToolExecutionCommand(
                "preview-1", "correlation-1", "trace-1", "preview_price", "1.0.0",
                JsonNodeFactory.instance.objectNode(), assertedPrincipal(),
                ToolExecutionSource.AI_CHAT, ToolExecutionMode.PREVIEW,
                Optional.empty(), Optional.empty(), Optional.empty(), Instant.now().plusSeconds(60)));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(result.payload().at("/data/batch/totalValue").isNull()).isTrue();
        assertThat(result.payload().at("/data/batch/receiptQuantity").decimalValue())
                .isEqualByComparingTo("12");
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isFalse();
    }

    @Test
    void allowedPreviewKeepsPricesAndPreviewFailureRestoresContext() {
        activeUser.setRoleCode("factory_super_admin");
        PreviewPriceTool previewTool = new PreviewPriceTool(objectMapper, source);
        when(runtimeRegistry.resolve(any(), any())).thenReturn(Optional.of(new ToolRuntimeRegistry.ResolvedTool(
                previewDescriptor(), previewTool)));

        ToolExecutionResult allowed = gateway.execute(previewCommand("preview-allowed"));
        assertThat(allowed.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(allowed.payload().at("/data/batch/totalValue").decimalValue())
                .isEqualByComparingTo("99.00");

        PreviewPriceTool failingTool = new PreviewPriceTool(objectMapper, source, true);
        when(runtimeRegistry.resolve(any(), any())).thenReturn(Optional.of(new ToolRuntimeRegistry.ResolvedTool(
                previewDescriptor(), failingTool)));
        ToolExecutionResult failed = gateway.execute(previewCommand("preview-failed"));
        assertThat(failed.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view")).isFalse();
    }

    private void assertPriceFieldsHidden(ToolExecutionResult result) {
        assertThat(result.payload().at("/data/batches/0/totalValue").isNull()).isTrue();
        assertThat(result.payload().at("/data/batches/0/unitPrice").isNull()).isTrue();
        assertThat(result.payload().at("/data/batches/0/totalPrice").isNull()).isTrue();
    }

    private void assertSourceUnchanged() {
        assertThat(source.getTotalValue()).isEqualByComparingTo("99.00");
        assertThat(source.getUnitPrice()).isEqualByComparingTo("8.25");
        assertThat(source.getTotalPrice()).isEqualByComparingTo("99.00");
        assertThat(source.getReceiptQuantity()).isEqualByComparingTo("12");
    }

    private ExecutionPrincipal assertedPrincipal() {
        return new ExecutionPrincipal("F006", "FACTORY", "42", PrincipalType.USER,
                Set.of("warehouse_manager"), Set.of("inventory:read"), Set.of());
    }

    private ToolExecutionCommand command() {
        return new ToolExecutionCommand(
                "request-1", "correlation-1", "trace-1", "material_stock_summary", "1.0.0",
                JsonNodeFactory.instance.objectNode(),
                assertedPrincipal(), ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE,
                Optional.empty(), Optional.empty(), Optional.empty(), Instant.now().plusSeconds(60));
    }

    private ToolExecutionCommand previewCommand(String requestId) {
        return new ToolExecutionCommand(
                requestId, "correlation-1", "trace-1", "preview_price", "1.0.0",
                JsonNodeFactory.instance.objectNode(), assertedPrincipal(), ToolExecutionSource.AI_CHAT,
                ToolExecutionMode.PREVIEW, Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.now().plusSeconds(60));
    }

    private ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "material_stock_summary", com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ,
                com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.LOW,
                Set.of("warehouse:read", "warehouse:read_write", "inventory:read", "inventory:read_write"),
                Set.of(), Set.of(com.cretas.aims.entity.enums.FactoryType.FACTORY), Set.of("material"),
                "1.0.0", false, ConfirmationPolicy.NOT_REQUIRED, ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.NOT_REQUIRED, DataClassification.INTERNAL,
                Set.of(ToolExecutionSource.AI_CHAT), ToolEgressPolicy.denyAll(), DescriptorProvenance.EXPLICIT);
    }

    private ToolDescriptor previewDescriptor() {
        return new ToolDescriptor(
                "preview_price", com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ,
                com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.LOW,
                Set.of("inventory:read"), Set.of(),
                Set.of(com.cretas.aims.entity.enums.FactoryType.FACTORY), Set.of("material"),
                "1.0.0", true, ConfirmationPolicy.NOT_REQUIRED, ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.NOT_REQUIRED, DataClassification.INTERNAL,
                Set.of(ToolExecutionSource.AI_CHAT), ToolEgressPolicy.denyAll(), DescriptorProvenance.EXPLICIT);
    }

    private static final class PreviewPriceTool extends AbstractTool {
        private final ObjectMapper mapper;
        private final MaterialBatchDTO batch;
        private final boolean failPreview;

        private PreviewPriceTool(ObjectMapper mapper, MaterialBatchDTO batch) {
            this(mapper, batch, false);
        }

        private PreviewPriceTool(ObjectMapper mapper, MaterialBatchDTO batch, boolean failPreview) {
            this.mapper = mapper;
            this.batch = batch;
            this.failPreview = failPreview;
        }

        @Override
        public String getToolName() {
            return "preview_price";
        }

        @Override
        public String getDescription() {
            return "price preview";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
            return preview(toolCall, context);
        }

        @Override
        public String preview(ToolCall toolCall, Map<String, Object> context) throws Exception {
            if (failPreview) {
                throw new IllegalStateException("preview unavailable");
            }
            return mapper.writeValueAsString(Map.of(
                    "success", true,
                    "data", Map.of("batch", batch)));
        }

        @Override
        public boolean supportsPreview() {
            return true;
        }

        @Override
        public Set<String> getRequiredPermissions() {
            return Set.of("inventory:read");
        }
    }
}
