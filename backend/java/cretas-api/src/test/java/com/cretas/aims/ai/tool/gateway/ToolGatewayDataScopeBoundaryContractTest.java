package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.RehydratedPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.ai.tool.impl.material.MaterialStockSummaryTool;
import com.cretas.aims.aspect.DataScopeAspect;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.utils.SecurityUtils;
import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Collection;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ToolGatewayDataScopeBoundaryContractTest {
    @Mock ToolPrincipalPolicy principalPolicy;
    @Mock ToolRuntimeRegistry runtimeRegistry;
    @Mock ToolConfirmationLease confirmationLease;
    @Mock ToolExecutionLedgerService ledgerService;
    @Mock ToolExecutor executor;

    DefaultToolExecutionGateway gateway;
    RehydratedPrincipal current;
    ToolDescriptor descriptor;

    @BeforeEach
    void setUp() {
        gateway = new DefaultToolExecutionGateway(
                principalPolicy, runtimeRegistry, confirmationLease, ledgerService, new ObjectMapper());
        ExecutionPrincipal principal = new ExecutionPrincipal(
                "F006", "FACTORY", "42", PrincipalType.USER, Set.of("warehouse_manager"),
                Set.of("inventory:read"), Set.of());
        current = new RehydratedPrincipal(principal, Map.of(
                "factoryId", "F006", "tenantId", "F006", "businessType", "FACTORY", "userId", 42L));
        descriptor = new ToolDescriptor(
                "scope_probe", ToolExecutor.ActionType.READ, ToolExecutor.RiskLevel.LOW,
                Set.of("inventory:read"), Set.of(), Set.of(FactoryType.FACTORY), Set.of("material"),
                "1.0.0", true, ConfirmationPolicy.NOT_REQUIRED, ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.NOT_REQUIRED, DataClassification.INTERNAL,
                Set.of(ToolExecutionSource.AI_CHAT), ToolEgressPolicy.denyAll(), DescriptorProvenance.EXPLICIT);
        lenient().when(principalPolicy.rehydrate(any())).thenReturn(Optional.of(current));
        lenient().when(runtimeRegistry.resolve(any(), any())).thenReturn(Optional.of(new ResolvedTool(descriptor, executor,
                ToolRuntimeRegistry.ResolutionLane.APPROVED_POLICY)));
        lenient().when(ledgerService.beginAudit(any(), any())).thenReturn("audit-1");
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        assertCleanWorker();
    }

    @Test
    void executeBindsRehydratedUserForConcreteSynchronousExecutorAndRestoresPriorScope() throws Exception {
        try (AutoCloseable prior = (AutoCloseable) SecurityUtils.class
                .getMethod("trustedUserIdScope", Long.class).invoke(null, 7L)) {
            when(executor.execute(any(ToolCall.class), any())).thenAnswer(invocation -> {
                assertThat(SecurityUtils.currentUserId()).isEqualTo(42L);
                return "{\"success\":true}";
            });
            assertThat(gateway.execute(command(ToolExecutionMode.EXECUTE)).status())
                    .isEqualTo(ToolExecutionStatus.SUCCEEDED);
            assertThat(SecurityUtils.currentUserId()).isEqualTo(7L);
        }
        assertThat(SecurityUtils.currentUserId()).isNull();
    }

    @Test
    void previewUsesSameScopeAndRequestIdentityRemainsAuthoritative() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 99L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(executor.preview(any(ToolCall.class), any())).thenAnswer(invocation -> {
            assertThat(SecurityUtils.currentUserId()).isEqualTo(99L);
            return "{\"success\":true}";
        });
        assertThat(gateway.execute(command(ToolExecutionMode.PREVIEW)).status())
                .isEqualTo(ToolExecutionStatus.SUCCEEDED);
        verify(executor).preview(any(), any());
    }

    @Test
    void executorFailureRestoresContextAndNeverLeavesIdentityForPooledWorker() throws Exception {
        when(executor.execute(any(ToolCall.class), any())).thenThrow(new IllegalStateException("failed"));
        assertThat(gateway.execute(command(ToolExecutionMode.EXECUTE)).status())
                .isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(SecurityUtils.currentUserId()).isNull();
        AtomicReference<Long> child = new AtomicReference<>();
        Thread worker = new Thread(() -> child.set(SecurityUtils.currentUserId()));
        worker.start();
        worker.join();
        assertThat(child).hasValue(null);
    }

    @Test
    void realPolicyRuntimeConcreteStockAndSpringDataScopeProxyReturnOnlyTrustedActorRows() throws Exception {
        StockFixture f = new StockFixture();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            for (long actor : new long[] {101L, 102L, 150L, 101L}) {
                worker.submit(() -> {
                    assertCleanWorker();
                    ToolExecutionResult result = f.gateway.execute(f.command(actor));
                    assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
                    assertStock(result.payload(), actor == 150L ? List.of("A", "B")
                            : actor == 101L ? List.of("A") : List.of("B"), actor == 150L);
                    assertCleanWorker();
                }).get();
            }
            assertThat(f.queries).contains("F006:[101]", "F006:[102]", "F006:ALL");
            assertThat(f.rows).extracting(MaterialBatch::getId).containsExactly("A", "B", "F007-row");
            assertThat(f.rows).extracting(MaterialBatch::getUnitPrice)
                    .containsOnly(new BigDecimal("8.25"));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void concreteFailureAndDeniedActorsNeverLeaveStateOnReusedWorker() throws Exception {
        StockFixture f = new StockFixture();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                for (long actor : new long[] {999L, 103L, 104L}) {
                    assertThat(f.gateway.execute(f.command(actor)).status()).isEqualTo(ToolExecutionStatus.DENIED);
                    assertCleanWorker();
                }
                assertThat(f.queries).isEmpty();
                f.failQueries = true;
                assertThat(f.gateway.execute(f.command(101L)).status()).isEqualTo(ToolExecutionStatus.FAILED);
                assertCleanWorker();
                f.failQueries = false;
                assertStock(f.gateway.execute(f.command(102L)).payload(), List.of("B"), false);
                assertCleanWorker();
            }).get();
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void realRuntimeUnsupportedPreviewAndMissingBindingCannotFallBackToLegacy() {
        StockFixture f = new StockFixture();
        assertThat(f.gateway.execute(f.command(101L, ToolExecutionMode.PREVIEW)).status())
                .isEqualTo(ToolExecutionStatus.PREVIEW_UNSUPPORTED);
        assertThat(f.queries).isEmpty();
        when(f.tools.getExecutor("material_stock_summary")).thenReturn(Optional.empty());
        assertThat(f.gateway.execute(f.command(101L)).status()).isEqualTo(ToolExecutionStatus.DENIED);
        assertThat(f.queries).isEmpty();
    }

    public static void assertCleanWorker() {
        assertThat(SecurityUtils.currentUserId()).as("natural identity cleanup").isNull();
        assertThat(DataScopeContext.current()).as("natural row scope cleanup").isNull();
        assertThat(PriceSensitiveContext.shouldHide("procurement:price:view"))
                .as("natural price scope cleanup").isFalse();
    }

    public static void assertStock(JsonNode payload, List<String> expectedIds, boolean pricesVisible) {
        List<String> actual = new ArrayList<>();
        payload.at("/data/batches").forEach(batch -> {
            actual.add(batch.path("id").asText());
            assertThat(batch.path("factoryId").asText()).isEqualTo("F006");
            assertThat(batch.path("receiptQuantity").decimalValue()).isEqualByComparingTo("12");
            for (String price : List.of("unitPrice", "totalPrice", "totalValue")) {
                assertThat(batch.path(price).isNull()).as(price).isEqualTo(!pricesVisible);
            }
            if (pricesVisible) {
                assertThat(batch.path("unitPrice").decimalValue()).isEqualByComparingTo("8.25");
                assertThat(batch.path("totalPrice").decimalValue()).isEqualByComparingTo("99.00");
                assertThat(batch.path("totalValue").decimalValue()).isEqualByComparingTo("99.00");
            }
        });
        assertThat(actual).containsExactlyElementsOf(expectedIds);
        assertThat(payload.at("/data/totalBatches").longValue()).isEqualTo(expectedIds.size());
    }

    /** Offline fixture: actual policy, registries, tool, service and Spring aspect; only I/O is mocked. */
    public static final class StockFixture {
        public final UserRepository users = ioMock(UserRepository.class);
        public final FactoryRepository factories = ioMock(FactoryRepository.class);
        public final com.cretas.aims.ai.tool.ToolRegistry tools = ioMock(com.cretas.aims.ai.tool.ToolRegistry.class);
        public final FactoryToolConfigRepository configs = ioMock(FactoryToolConfigRepository.class);
        public final PlatformRolePermissionRepository l1 = ioMock(PlatformRolePermissionRepository.class);
        public final FactoryModuleConfigRepository l2 = ioMock(FactoryModuleConfigRepository.class);
        public final List<PlatformRolePermission> platformRows = new ArrayList<>();
        public final List<FactoryModuleConfig> factoryRows = new ArrayList<>();
        public final Map<Long, User> actors = new java.util.HashMap<>();
        public final List<MaterialBatch> rows = List.of(row("A", "F006", 101L),
                row("B", "F006", 102L), row("F007-row", "F007", 101L));
        public final List<String> queries = new ArrayList<>();
        public boolean failQueries;
        public final ObjectMapper mapper = new ObjectMapper()
                .registerModule(new PriceSensitiveJacksonConfig().priceSensitiveJacksonModule());
        public final PermissionServiceImpl canonical = new PermissionServiceImpl();
        public final ToolPrincipalPolicy policy;
        public final ToolRuntimeRegistry runtime;
        public final LegacyToolMigrationRegistry legacy;
        public final MaterialStockSummaryTool stock = new MaterialStockSummaryTool();
        public final DefaultToolExecutionGateway gateway;

        private static <T> T ioMock(Class<T> type) {
            return org.mockito.Mockito.mock(type, org.mockito.Mockito.withSettings()
                    .strictness(org.mockito.quality.Strictness.LENIENT));
        }

        public StockFixture() {
            actors.put(101L, actor(101L, "F006", "warehouse_worker", true));
            actors.put(102L, actor(102L, "F006", "warehouse_worker", true));
            actors.put(150L, actor(150L, "F006", "factory_super_admin", true));
            actors.put(103L, actor(103L, "F007", "warehouse_worker", true));
            actors.put(104L, actor(104L, "F006", "warehouse_worker", false));
            when(users.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(actors.get(i.getArgument(0))));
            when(factories.findById(anyString())).thenAnswer(i -> {
                Factory factory = new Factory();
                factory.setId(i.getArgument(0));
                factory.setIsActive(true);
                factory.setType(FactoryType.FACTORY);
                return Optional.of(factory);
            });
            when(l1.findByRoleCodeAndDeletedAtIsNull(anyString())).thenAnswer(i -> platformRows.stream()
                    .filter(r -> r.getRoleCode().equals(i.getArgument(0))).toList());
            when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(i ->
                    platformRows.stream().filter(r -> r.getRoleCode().equals(i.getArgument(0))
                            && r.getModuleCode().equals(i.getArgument(1))).findFirst());
            when(l2.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenAnswer(i -> factoryRows.stream()
                    .filter(r -> r.getFactoryId().equals(i.getArgument(0))).toList());
            ReflectionTestUtils.setField(canonical, "platformRepo", l1);
            ReflectionTestUtils.setField(canonical, "factoryConfigRepo", l2);
            policy = new ToolPrincipalPolicy(users, factories, canonical);
            DataScopeResolver resolver = ioMock(DataScopeResolver.class);
            when(resolver.resolve(anyLong())).thenAnswer(i -> {
                User user = actors.get(i.getArgument(0));
                return DataScopeContext.builder().userId(user.getId()).factoryId(user.getFactoryId())
                        .roleCode(user.getRoleCode()).column("created_by")
                        .scope(user.getId() == 150L ? DataScope.ALL : DataScope.SELF).build();
            });
            MaterialBatchRepository batches = ioMock(MaterialBatchRepository.class);
            when(batches.findByFactoryId(anyString(), any(org.springframework.data.domain.Pageable.class)))
                    .thenAnswer(i -> page(i.getArgument(0), null, i.getArgument(1)));
            when(batches.findByFactoryIdAndCreatedByIn(anyString(), anyCollection(), any()))
                    .thenAnswer(i -> page(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
            MaterialBatchMapper dtoMapper = ioMock(MaterialBatchMapper.class);
            when(dtoMapper.toDTO(any(MaterialBatch.class))).thenAnswer(i -> {
                MaterialBatch r = i.getArgument(0);
                return MaterialBatchDTO.builder().id(r.getId()).factoryId(r.getFactoryId())
                        .createdBy(r.getCreatedBy()).receiptQuantity(r.getReceiptQuantity())
                        .unitPrice(r.getUnitPrice()).totalPrice(new BigDecimal("99.00"))
                        .totalValue(new BigDecimal("99.00")).build();
            });
            MaterialBatchServiceImpl service = new MaterialBatchServiceImpl(batches,
                    ioMock(MaterialBatchAdjustmentRepository.class), ioMock(RawMaterialTypeRepository.class), dtoMapper,
                    ioMock(MaterialConsumptionRepository.class), ioMock(ProductionPlanBatchUsageRepository.class),
                    ioMock(com.cretas.aims.utils.ExcelUtil.class), ioMock(FuturePlanMatchingService.class));
            ReflectionTestUtils.setField(service, "dataScopeResolver", resolver);
            AspectJProxyFactory proxy = new AspectJProxyFactory(service);
            proxy.addAspect(new DataScopeAspect(resolver));
            MaterialBatchService scoped = proxy.getProxy();
            FindingService findings = ioMock(FindingService.class);
            when(findings.detectInline("F006", "inventory")).thenReturn(new FindingService.Result(
                    List.of(), List.of("LOW_STOCK"), 0, Map.of("LOW_STOCK", 0), List.of()));
            FindingTextRenderer text = ioMock(FindingTextRenderer.class);
            when(text.renderInline(any())).thenReturn("");
            ReflectionTestUtils.setField(stock, "objectMapper", mapper);
            ReflectionTestUtils.setField(stock, "materialBatchService", scoped);
            ReflectionTestUtils.setField(stock, "findingService", findings);
            ReflectionTestUtils.setField(stock, "findingTextRenderer", text);
            when(tools.getExecutor("material_stock_summary")).thenReturn(Optional.of(stock));
            runtime = new ToolRuntimeRegistry(tools, configs);
            legacy = new LegacyToolMigrationRegistry(tools, configs, true);
            ToolExecutionLedgerService ledger = ioMock(ToolExecutionLedgerService.class);
            when(ledger.beginAudit(any(), any())).thenReturn("offline-audit");
            gateway = new DefaultToolExecutionGateway(policy, runtime, ioMock(ToolConfirmationLease.class), ledger, mapper);
            ReflectionTestUtils.setField(gateway, "legacyToolMigrationRegistry", legacy);
        }

        private PageImpl<MaterialBatch> page(String factory, Collection<Long> creators,
                org.springframework.data.domain.Pageable pageable) {
            if (failQueries) throw new IllegalStateException("offline repository unavailable");
            queries.add(factory + ":" + (creators == null ? "ALL" : creators.toString()));
            List<MaterialBatch> selected = rows.stream().filter(r -> factory.equals(r.getFactoryId()))
                    .filter(r -> creators == null || creators.contains(r.getCreatedBy())).toList();
            int start = Math.min((int) pageable.getOffset(), selected.size());
            return new PageImpl<>(selected.subList(start, Math.min(start + pageable.getPageSize(), selected.size())),
                    pageable, selected.size());
        }

        public ToolExecutionCommand command(long actor) { return command(actor, ToolExecutionMode.EXECUTE); }
        public ToolExecutionCommand command(long actor, ToolExecutionMode mode) {
            return new ToolExecutionCommand("offline-" + actor, "offline-correlation", "offline-trace",
                    "material_stock_summary", "1.0.0", JsonNodeFactory.instance.objectNode()
                            .put("factoryId", "F007").put("userId", 150L).put("userRole", "factory_super_admin"),
                    new ExecutionPrincipal("F006", "FACTORY", Long.toString(actor), PrincipalType.USER,
                            Set.of("factory_super_admin"), Set.of("inventory:read", "procurement:price:view"), Set.of()),
                    ToolExecutionSource.SKILL_WORKFLOW, mode, Optional.empty(), Optional.empty(),
                    Optional.empty(), Instant.now().plusSeconds(60));
        }

        private static User actor(long id, String factory, String role, boolean active) {
            User user = new User();
            user.setId(id); user.setFactoryId(factory); user.setRoleCode(role); user.setIsActive(active);
            return user;
        }
        private static MaterialBatch row(String id, String factory, long creator) {
            MaterialBatch row = new MaterialBatch();
            row.setId(id); row.setFactoryId(factory); row.setCreatedBy(creator);
            row.setReceiptQuantity(new BigDecimal("12")); row.setUnitPrice(new BigDecimal("8.25"));
            return row;
        }
    }

    private ToolExecutionCommand command(ToolExecutionMode mode) {
        return new ToolExecutionCommand(
                "request-1", "correlation-1", "trace-1", "scope_probe", "1.0.0",
                JsonNodeFactory.instance.objectNode(), current.principal(), ToolExecutionSource.AI_CHAT,
                mode, Optional.empty(), Optional.empty(), Optional.empty(), Instant.now().plusSeconds(60));
    }
}
