package com.cretas.aims.controller.inventory;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.PriceSensitiveContextFilter;
import com.cretas.aims.controller.ListSummaryController;
import com.cretas.aims.controller.SupplierMaterialController;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.MaterialPriceComparisonDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceLineViewDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceMatchStatusDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceViewDTO;
import com.cretas.aims.dto.listsummary.ListSummaryResponse;
import com.cretas.aims.dto.supplier.SupplierMaterialDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveSerializerModifier;
import com.cretas.aims.service.listsummary.ListSummaryService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.controller.MaterialPackagingHierarchyController;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.MaterialPackagingHierarchyDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.security.PriceVisibility;
import com.cretas.aims.service.MaterialPackagingHierarchyService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.cretas.aims.service.inventory.ReturnOrderService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.inventory.PurchaseInvoiceService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.supplier.SupplierMaterialPurchaseSpecService;
import com.cretas.aims.service.supplier.SupplierMaterialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S5-12 (D-S5-02): 采购价 / 采购金额看不看得到, 只由工厂配置的 procurement 读权限决定
 * (L2 工厂覆盖 → L1; 两层都没有行时只认采购岗, 财务经由财务入口), 不由 procurement:price:view 的固定角色名单决定。
 *
 * <p>走真实链路: MockMvc → {@link PriceSensitiveContextFilter} → {@link PermissionInterceptor} (@RequirePermission)
 * → 真实控制器 → {@link PriceFieldResponseAdvice} → Jackson (注册了 {@link PriceSensitiveSerializerModifier},
 * 与生产 PriceSensitiveJacksonConfig 同一个模块)。权限判定用真实 {@link PermissionServiceImpl},
 * 只把 L1 / L2 两张配置表换成内存配置; 测试中途改配置、清缓存, 可见性必须跟着翻。
 */
class PurchasePriceVisibilityConfigContractTest {

    private static final String F = "S512-F";
    private static final String NO_CFG = "S512-NOCFG";
    private static final long PROCUREMENT = 1L, FINANCE = 2L, SALES = 3L, PRODUCTION = 4L, WAREHOUSE = 5L,
            FINANCE_OTHER = 6L, N_CASHIER = 18L;
    private static final long N_PROCUREMENT = 11L, N_FINANCE = 12L, N_WAREHOUSE = 15L, N_DISPATCHER = 16L,
            N_PRODUCTION = 17L;

    private final PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
    private final FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PurchaseService purchaseService = mock(PurchaseService.class);
    private final SupplierMaterialService supplierMaterialService = mock(SupplierMaterialService.class);
    private final PurchaseInvoiceService invoiceService = mock(PurchaseInvoiceService.class);
    private final ListSummaryService listSummaryService = mock(ListSummaryService.class);
    private final ReturnOrderService returnOrderService = mock(ReturnOrderService.class);
    private final ApprovalWorkflowInstanceRepository approvals = mock(ApprovalWorkflowInstanceRepository.class);
    private final WorkflowEngineService workflowEngine = mock(WorkflowEngineService.class);
    private final ApprovalHistoryRepository approvalHistory = mock(ApprovalHistoryRepository.class);
    private final MaterialPackagingHierarchyService packagingService = mock(MaterialPackagingHierarchyService.class);
    private final MobileService mobileForExport = mock(MobileService.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule("PriceSensitiveModule")
                    .setSerializerModifier(new PriceSensitiveSerializerModifier()));

    /** 与 F006 验收时读到的一致: procurement 只有超管 / 采购经理 rw, 其余角色「-」。 */
    private final Map<String, Map<String, String>> override = new HashMap<>();
    private PermissionServiceImpl permissions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", l1);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", l2);
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        override.put("procurement_manager", new HashMap<>(Map.of("procurement", "rw")));
        override.put("finance_manager", new HashMap<>(Map.of("procurement", "-", "finance", "rw")));
        override.put("sales_manager", new HashMap<>(Map.of("procurement", "-", "sales", "rw", "finance", "-")));
        override.put("production_manager", new HashMap<>(Map.of("procurement", "-", "warehouse", "r")));
        override.put("warehouse_manager", new HashMap<>(Map.of("procurement", "-", "warehouse", "rw")));
        when(l2.findByFactoryIdAndConfigVersion(F, 1)).thenAnswer(inv -> {
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(F);
            Map<String, Map<String, String>> copy = new HashMap<>();
            override.forEach((role, levels) -> copy.put(role, Map.copyOf(levels)));
            cfg.setRoleModuleOverride(copy);
            return List.of(cfg);
        });
        // 没有任何 L2 / L1 行的工厂: 只能落到兜底
        when(l2.findByFactoryIdAndConfigVersion(NO_CFG, 1)).thenReturn(List.of());
        user(PROCUREMENT, F, "procurement_manager");
        user(FINANCE, F, "finance_manager");
        user(FINANCE_OTHER, F, "finance_manager");   // 同角色, 但不是这张单的审批人
        user(N_CASHIER, NO_CFG, "cashier");
        user(SALES, F, "sales_manager");
        user(PRODUCTION, F, "production_manager");
        user(WAREHOUSE, F, "warehouse_manager");
        user(N_PROCUREMENT, NO_CFG, "procurement_manager");
        user(N_FINANCE, NO_CFG, "finance_manager");
        user(N_WAREHOUSE, NO_CFG, "warehouse_manager");
        user(N_DISPATCHER, NO_CFG, "dispatcher");
        user(N_PRODUCTION, NO_CFG, "production_manager");

        for (String fid : List.of(F, NO_CFG)) {
            when(purchaseService.getPurchaseOrders(fid, 1, 20)).thenAnswer(inv -> PageResponse.of(List.of(order()), 1, 20, 1L));
            when(purchaseService.getPurchaseOrderById(fid, "PO-1")).thenAnswer(inv -> order());
            when(purchaseService.getPurchaseStatistics(fid)).thenAnswer(inv -> new HashMap<>(Map.of(
                    "monthlyOrderCount", 3L, "pendingApprovalCount", 1L,
                    "monthlyPurchaseAmount", new BigDecimal("450.00"))));
            when(purchaseService.getOrderPriceComparison(fid, "PO-1")).thenAnswer(inv -> List.of(
                    MaterialPriceComparisonDTO.builder().materialTypeId("MAT-A")
                            .currentPrice(new BigDecimal("1.50")).movingAvgPrice(new BigDecimal("1.40")).build()));
            when(supplierMaterialService.listBySupplier(fid, "SUP-1")).thenAnswer(inv -> List.of(SupplierMaterialDTO.builder()
                    .supplierId("SUP-1").materialTypeId("MAT-A")
                    .defaultPurchasePrice(new BigDecimal("20.00"))
                    .taxIncludedDefaultPurchasePrice(new BigDecimal("22.60"))
                    .effectivePurchasePrice(new BigDecimal("20.00")).build()));
            when(listSummaryService.computeSummary(eq(fid), anyString(), any())).thenAnswer(inv -> ListSummaryResponse.builder()
                    .entityType(inv.getArgument(1))
                    .stats(new ArrayList<>(List.of(
                            ListSummaryResponse.SummaryStat.builder().label("单数").value(3).format("number").build(),
                            ListSummaryResponse.SummaryStat.builder().label("合计金额")
                                    .value(new BigDecimal("450.00")).format("currency").canViewPrice(true).build())))
                    .build());
            when(returnOrderService.getReturnOrderDetail(fid, "RO-P")).thenAnswer(inv -> returnOrder(ReturnType.PURCHASE_RETURN));
            when(returnOrderService.getReturnOrderDetail(fid, "RO-S")).thenAnswer(inv -> returnOrder(ReturnType.SALES_RETURN));
        }
        when(approvals.findFirstByFactoryIdAndModuleCodeAndBusinessEntityIdOrderByInitiatedAtDesc(anyString(), eq("PURCHASE_ORDER"), eq("PO-1")))
                .thenAnswer(inv -> {
                    ApprovalWorkflowInstance oa = new ApprovalWorkflowInstance();
                    oa.setId("OA-1");
                    oa.setFactoryId(inv.getArgument(0));
                    return Optional.of(oa);
                });
        // 审批人: F 的 FINANCE 当前节点轮到他 (待办); NO_CFG 的 N_FINANCE 在实例上操作过 (已办)
        when(workflowEngine.canTransition(any(ApprovalWorkflowInstance.class), any(User.class)))
                .thenAnswer(inv -> ((User) inv.getArgument(1)).getId() == FINANCE);
        when(approvalHistory.existsByFactoryIdAndInstanceIdAndActorId(NO_CFG, "OA-1", N_FINANCE)).thenReturn(true);
        when(approvals.findFirstByFactoryIdAndModuleCodeAndBusinessEntityIdOrderByInitiatedAtDesc(anyString(), eq("PURCHASE_ORDER"), eq("PO-NO-OA")))
                .thenReturn(Optional.empty());
        when(invoiceService.listViews(F, "PO-1")).thenAnswer(inv -> List.of(PurchaseInvoiceViewDTO.builder()
                .id("INV-1").purchaseOrderId("PO-1").amount(new BigDecimal("1130.00"))
                .reconcileStatus("PENDING")
                .lines(List.of(PurchaseInvoiceLineViewDTO.builder().id(1L).purchaseOrderItemId(11L)
                        .quantity(new BigDecimal("100")).unit("kg")
                        .amount(new BigDecimal("1130.00")).expectedAmount(new BigDecimal("1130.00"))
                        .matchStatus("MATCHED").build()))
                .build()));
        when(invoiceService.matchStatus(F, "PO-1")).thenAnswer(inv -> PurchaseInvoiceMatchStatusDTO.builder()
                .purchaseOrderId("PO-1").lines(List.of(PurchaseInvoiceMatchStatusDTO.Line.builder()
                        .purchaseOrderItemId(11L).unit("kg").receivedQuantity(new BigDecimal("100"))
                        .expectedUnitAmount(new BigDecimal("11.30")).status("UNINVOICED").build()))
                .build());

        PriceFieldResponseAdvice advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);

        MobileService mobile = mock(MobileService.class);
        PurchaseController purchaseController = new PurchaseController(purchaseService, null, mobile, permissions, users, null, null);
        ReflectionTestUtils.setField(purchaseController, "approvalInstanceRepository", approvals);
        ReflectionTestUtils.setField(purchaseController, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(purchaseController, "approvalHistoryRepository", approvalHistory);
        MaterialPackagingHierarchyController packagingController =
                new MaterialPackagingHierarchyController(packagingService, mobile);
        ReflectionTestUtils.setField(packagingController, "priceVisibility", new PriceVisibility(permissions));
        ReflectionTestUtils.setField(packagingController, "userRepository", users);
        when(packagingService.getByMaterialTypeId(anyString(), anyString(), any()))
                .thenReturn(Optional.of(new MaterialPackagingHierarchyDTO()));
        mvc = MockMvcBuilders.standaloneSetup(
                        purchaseController,
                        new SupplierMaterialController(supplierMaterialService, mock(SupplierMaterialPurchaseSpecService.class)),
                        new PurchaseInvoiceController(invoiceService, mobile),
                        new ListSummaryController(listSummaryService, permissions, users),
                        new ReturnOrderController(returnOrderService, mobile, null),
                        packagingController,
                        new BatchCostProbeController())
                .addFilters(new PriceSensitiveContextFilter())
                .addInterceptors(new PermissionInterceptor(permissions, users, json))
                .setControllerAdvice(advice, new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    // ─── F006 形态: 配置说了算 ─────────────────────────────────────────────────

    @Test
    void procurementModuleRead_isTheOnlyThingThatShowsPurchasePrices() throws Exception {
        assertThat(supplierPrice(PROCUREMENT)).isEqualTo("20.00");
        assertThat(poListAmount(PROCUREMENT)).isEqualTo("150.00");
        assertThat(poDetailUnitPrice(PROCUREMENT)).isEqualTo("1.50");

        // 财务 / 销售 / 生产 / 仓管 (procurement=「-」) 里前三个在旧的固定名单里 → 每个入口都看不到
        for (long role : new long[] {FINANCE, SALES, PRODUCTION, WAREHOUSE}) {
            assertThat(supplierPrice(role)).as("supplier materials role=%s", role).isNull();
            assertThat(poListAmountOrForbidden(role)).as("PO list role=%s", role).isIn(null, "403");
            assertThat(poDetailUnitPriceOrForbidden(role)).as("PO detail role=%s", role).isIn(null, "403");
        }
        assertThat(poListAmountOrForbidden(SALES)).isNull();
        assertThat(poDetailUnitPriceOrForbidden(SALES)).isEqualTo("403");
    }

    @Test
    void financeSeesPurchaseAmountsOnFinanceSurfacesOnly() throws Exception {
        // 三单核对 / 收票 (财务入口, 裁定 a): 发票金额与由采购单价推出的应开数都可见
        JsonNode views = body(FINANCE, "/api/mobile/" + F + "/invoices/views?poId=PO-1", 200);
        assertThat(views.at("/data/0/amount").decimalValue()).isEqualByComparingTo("1130.00");
        assertThat(views.at("/data/0/lines/0/expectedAmount").decimalValue()).isEqualByComparingTo("1130.00");
        JsonNode status = body(FINANCE, "/api/mobile/" + F + "/invoices/match-status?poId=PO-1", 200);
        assertThat(status.at("/data/lines/0/expectedUnitAmount").decimalValue()).isEqualByComparingTo("11.30");
        // OA 审批速览 (裁定 a): 看得到
        JsonNode peek = body(FINANCE, "/api/mobile/" + F + "/purchase/orders/PO-1/approval-view", 200);
        assertThat(peek.at("/data/totalAmount").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(peek.at("/data/items/0/unitPrice").decimalValue()).isEqualByComparingTo("1.50");
        // 其它采购入口仍只看采购读
        assertThat(poDetailUnitPriceOrForbidden(FINANCE)).isNull();
        assertThat(supplierPrice(FINANCE)).isNull();
        // 没走过 OA 的单子不能拿审批速览当后门
        assertThat(call(FINANCE, get("/api/mobile/" + F + "/purchase/orders/PO-NO-OA/approval-view"))
                .getResponse().getStatus()).isEqualTo(404);
        // 销售没有财务读也没有采购读: 审批速览进不去
        assertThat(call(SALES, get("/api/mobile/" + F + "/purchase/orders/PO-1/approval-view"))
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void changingTheRoleConfig_flipsVisibilityOnEverySurface() throws Exception {
        assertThat(supplierPrice(SALES)).isNull();
        assertThat(poListAmountOrForbidden(SALES)).isNull();

        grant("sales_manager", "procurement", "r");
        assertThat(supplierPrice(SALES)).isEqualTo("20.00");
        assertThat(poListAmount(SALES)).isEqualTo("150.00");
        assertThat(poDetailUnitPrice(SALES)).isEqualTo("1.50");

        grant("sales_manager", "procurement", "-");
        assertThat(supplierPrice(SALES)).isNull();
        assertThat(poListAmountOrForbidden(SALES)).isNull();

        // 反方向: 仓管不在旧名单里, 配置给了采购读就能看
        grant("warehouse_manager", "procurement", "r");
        assertThat(supplierPrice(WAREHOUSE)).isEqualTo("20.00");
        assertThat(poDetailUnitPrice(WAREHOUSE)).isEqualTo("1.50");
    }

    // ─── 裁定 b: 没有配置行时只认采购岗 (财务经由财务入口) ───────────────────────

    @Test
    void noConfigFallback_onlyProcurementSeesPurchasePrices_financeOnlyOnFinanceSurfaces() throws Exception {
        String base = "/api/mobile/" + NO_CFG;
        assertThat(supplierPrice(N_PROCUREMENT, NO_CFG)).isEqualTo("20.00");
        // 兜底矩阵给了这些角色采购读, 但 PR #415 有意不让他们看采购价
        for (long role : new long[] {N_WAREHOUSE, N_DISPATCHER, N_PRODUCTION, N_FINANCE}) {
            assertThat(supplierPrice(role, NO_CFG)).as("fallback role=%s", role).isNull();
            MvcResult r = call(role, get(base + "/purchase/orders/PO-1"));
            if (r.getResponse().getStatus() == 200) {
                assertThat(json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .at("/data/items/0/unitPrice").isNull()).as("fallback PO detail role=%s", role).isTrue();
            }
        }
        // 财务在审批速览上看得到 (兜底只认财务岗)
        assertThat(body(N_FINANCE, base + "/purchase/orders/PO-1/approval-view", 200)
                .at("/data/totalAmount").decimalValue()).isEqualByComparingTo("150.00");
    }

    // ─── B1 列表合计 / B2 统计 / B3 只给价格的入口 / B4 采购退货 ──────────────────

    @Test
    void listSummaryPurchaseAmountsFollowProcurementRead() throws Exception {
        assertThat(summaryAmount(SALES, "purchaseOrder")).isNull();          // 名单里, 配置无采购读
        assertThat(summaryCount(SALES, "purchaseOrder")).isEqualTo(3);       // 非金额统计照给
        assertThat(summaryAmount(PROCUREMENT, "purchaseOrder")).isEqualByComparingTo("450.00");
        // 退货合计不分采购 / 销售退货: 两种口径都要
        assertThat(summaryAmount(SALES, "returnOrder")).isNull();
        assertThat(summaryAmount(PROCUREMENT, "returnOrder")).isEqualByComparingTo("450.00");
        grant("sales_manager", "procurement", "r");
        assertThat(summaryAmount(SALES, "purchaseOrder")).isEqualByComparingTo("450.00");
        assertThat(summaryAmount(SALES, "returnOrder")).isEqualByComparingTo("450.00");
    }

    @Test
    void purchaseStatisticsTopLevelAmountIsMasked() throws Exception {
        grant("sales_manager", "report", "r");   // 有统计入口, 没采购读
        JsonNode stats = body(SALES, "/api/mobile/" + F + "/purchase/statistics", 200);
        assertThat(stats.at("/data/monthlyOrderCount").asInt()).isEqualTo(3);
        assertThat(stats.at("/data/monthlyPurchaseAmount").isNull()).isTrue();
        // 既有 Rule 2b: Map 里名字带 amount 的键对没有 finance:read_write 的人一律剥 (与本修复无关),
        // 所以这条阳性对照给采购经理补上财务读写, 只看采购口径这一层
        grant("procurement_manager", "finance", "rw");
        assertThat(body(PROCUREMENT, "/api/mobile/" + F + "/purchase/statistics", 200)
                .at("/data/monthlyPurchaseAmount").decimalValue()).isEqualByComparingTo("450.00");
    }

    @Test
    void priceOnlyEndpointsReturn403InsteadOfAllNulls() throws Exception {
        // 财务有 finance:read_write (进得了这两个入口), 但配置没给采购读
        assertThat(call(FINANCE, get("/api/mobile/" + F + "/purchase/orders/PO-1/price-comparison"))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(body(PROCUREMENT, "/api/mobile/" + F + "/purchase/orders/PO-1/price-comparison", 200)
                .at("/data/0/currentPrice").decimalValue()).isEqualByComparingTo("1.50");
    }

    @Test
    void purchaseReturnAmountsFollowProcurementRead_salesReturnsKeepTheirOwnRule() throws Exception {
        // 销售在旧名单里: 销售退货照看, 采购退货 (采购金额) 看不到; 行和行上的计算金额 (Jackson getter) 一起剥
        JsonNode purchaseReturn = body(SALES, "/api/mobile/" + F + "/return-orders/RO-P", 200);
        assertThat(purchaseReturn.at("/data/totalAmount").isNull()).isTrue();
        assertThat(purchaseReturn.at("/data/items/0/unitPrice").isNull()).isTrue();
        assertThat(purchaseReturn.at("/data/items/0/lineAmount").isNull()).isTrue();
        JsonNode salesReturn = body(SALES, "/api/mobile/" + F + "/return-orders/RO-S", 200);
        assertThat(salesReturn.at("/data/totalAmount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(salesReturn.at("/data/items/0/lineAmount").decimalValue()).isEqualByComparingTo("30.00");
        // 采购经理看采购退货
        JsonNode procurementView = body(PROCUREMENT, "/api/mobile/" + F + "/return-orders/RO-P", 200);
        assertThat(procurementView.at("/data/items/0/lineAmount").decimalValue()).isEqualByComparingTo("30.00");
    }

    // ─── 审批速览只给这张单的审批人 ──────────────────────────────────────────────

    @Test
    void approvalViewIsOnlyForApproversOfThatInstance() throws Exception {
        String url = "/api/mobile/" + F + "/purchase/orders/PO-1/approval-view";
        assertThat(body(FINANCE, url, 200).at("/data/totalAmount").decimalValue()).isEqualByComparingTo("150.00");
        // 同是财务经理, 不是这个实例的审批人 (没有待办、没操作过) → 404, 不是拿 PO id 就能读金额
        assertThat(call(FINANCE_OTHER, get(url)).getResponse().getStatus()).isEqualTo(404);
        // 采购经理不是审批人也走不了这条; 他有自己的详情入口
        assertThat(call(PROCUREMENT, get(url)).getResponse().getStatus()).isEqualTo(404);
        // 已办的人 (操作过) 照样能看
        assertThat(body(N_FINANCE, "/api/mobile/" + NO_CFG + "/purchase/orders/PO-1/approval-view", 200)
                .at("/data/totalAmount").decimalValue()).isEqualByComparingTo("150.00");
    }

    @Test
    void financeFallbackIsFinanceManagerOnly_notCashier() {
        PriceVisibility pv = new PriceVisibility(permissions);
        assertThat(pv.canView(users.findById(N_FINANCE).orElseThrow(), PriceSensitive.PURCHASE_OR_FINANCE)).isTrue();
        assertThat(pv.canView(users.findById(N_CASHIER).orElseThrow(), PriceSensitive.PURCHASE_OR_FINANCE)).isFalse();
    }

    // ─── 包装换算推导的供应商价 / 批次采购成本 ─────────────────────────────────────

    @Test
    void packagingDerivedSupplierPriceIsOnlyDerivedForPurchaseReaders() throws Exception {
        String url = "/api/mobile/" + F + "/material-packaging/by-material/MAT-A?supplierMaterialId=SM-1";
        body(SALES, url, 200);
        org.mockito.Mockito.verify(packagingService).getByMaterialTypeId(F, "MAT-A", null);
        body(PROCUREMENT, url, 200);
        org.mockito.Mockito.verify(packagingService).getByMaterialTypeId(F, "MAT-A", "SM-1");
    }

    @Test
    void batchPurchaseCostFollowsProcurementOrFinanceRead_jsonAndExport() throws Exception {
        String url = "/api/mobile/" + F + "/s512-probe/batch";
        JsonNode sales = body(SALES, url, 200);
        assertThat(sales.at("/data/unitPrice").isNull()).isTrue();
        assertThat(sales.at("/data/totalValue").isNull()).isTrue();
        assertThat(sales.at("/data/totalPrice").isNull()).isTrue();
        assertThat(body(FINANCE, url, 200).at("/data/unitPrice").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(body(PROCUREMENT, url, 200).at("/data/totalValue").decimalValue()).isEqualByComparingTo("125.00");

        // Excel 导出同一口径
        PriceMaskResolver resolver = new PriceMaskResolver(mobileForExport, users, permissions);
        for (long id : new long[] {SALES, FINANCE, PROCUREMENT}) {
            UserDTO dto = new UserDTO();
            dto.setId(id);
            when(mobileForExport.getUserFromToken("t" + id)).thenReturn(dto);
        }
        assertThat(resolver.shouldMaskPrice("Bearer t" + SALES, PriceSensitive.PURCHASE_OR_FINANCE)).isTrue();
        assertThat(resolver.shouldMaskPrice("Bearer t" + FINANCE, PriceSensitive.PURCHASE_OR_FINANCE)).isFalse();
        assertThat(resolver.shouldMaskPrice("Bearer t" + PROCUREMENT, PriceSensitive.PURCHASE_OR_FINANCE)).isFalse();
    }

    /** 只为把 MaterialBatchDTO 过一遍真实的 advice + Jackson; 批次控制器依赖太多, 字段口径才是被测对象。 */
    @RestController
    static class BatchCostProbeController {
        @GetMapping("/api/mobile/{factoryId}/s512-probe/batch")
        public com.cretas.aims.dto.common.ApiResponse<MaterialBatchDTO> batch() {
            return com.cretas.aims.dto.common.ApiResponse.success(MaterialBatchDTO.builder()
                    .unitPrice(new BigDecimal("12.50")).totalValue(new BigDecimal("125.00"))
                    .totalPrice(new BigDecimal("125.00")).build());
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void grant(String role, String module, String level) {
        override.computeIfAbsent(role, r -> new HashMap<>()).put(module, level);
        permissions.invalidateCache();
    }

    private BigDecimal summaryAmount(long userId, String entity) throws Exception {
        JsonNode stats = summary(userId, entity);
        for (JsonNode s : stats) {
            if ("合计金额".equals(s.path("label").asText())) {
                return s.path("value").isNull() ? null : s.path("value").decimalValue();
            }
        }
        throw new AssertionError("sumAmount stat missing: " + stats);
    }

    private int summaryCount(long userId, String entity) throws Exception {
        for (JsonNode s : summary(userId, entity)) {
            if ("单数".equals(s.path("label").asText())) return s.path("value").asInt();
        }
        throw new AssertionError("count stat missing");
    }

    private JsonNode summary(long userId, String entity) throws Exception {
        MvcResult r = call(userId, post("/api/mobile/" + F + "/list-summary/" + entity)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(200);
        return json.readTree(text).at("/data/stats");
    }

    private String supplierPrice(long userId) throws Exception {
        return supplierPrice(userId, F);
    }

    private String supplierPrice(long userId, String factoryId) throws Exception {
        JsonNode node = body(userId, "/api/mobile/" + factoryId + "/suppliers/SUP-1/materials", 200)
                .at("/data/0/defaultPurchasePrice");
        return node.isNull() || node.isMissingNode() ? null : node.decimalValue().setScale(2).toPlainString();
    }

    private String poListAmount(long userId) throws Exception {
        String v = poListAmountOrForbidden(userId);
        assertThat(v).isNotNull().isNotEqualTo("403");
        return v;
    }

    private String poListAmountOrForbidden(long userId) throws Exception {
        MvcResult r = call(userId, get("/api/mobile/" + F + "/purchase/orders"));
        if (r.getResponse().getStatus() == 403) return "403";
        JsonNode node = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .at("/data/content/0/totalAmount");
        return node.isNull() || node.isMissingNode() ? null : node.decimalValue().setScale(2).toPlainString();
    }

    private String poDetailUnitPrice(long userId) throws Exception {
        String v = poDetailUnitPriceOrForbidden(userId);
        assertThat(v).isNotNull().isNotEqualTo("403");
        return v;
    }

    private String poDetailUnitPriceOrForbidden(long userId) throws Exception {
        MvcResult r = call(userId, get("/api/mobile/" + F + "/purchase/orders/PO-1"));
        if (r.getResponse().getStatus() == 403) return "403";
        JsonNode root = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode total = root.at("/data/totalAmount");
        JsonNode node = root.at("/data/items/0/unitPrice");
        // 详情里订单总额、行单价、行小计 (Jackson getter) 必须同进同出
        JsonNode lineAmount = root.at("/data/items/0/lineAmountWithTax");
        assertThat(total.isNull() || total.isMissingNode()).isEqualTo(node.isNull() || node.isMissingNode());
        assertThat(lineAmount.isNull() || lineAmount.isMissingNode()).isEqualTo(node.isNull() || node.isMissingNode());
        return node.isNull() || node.isMissingNode() ? null : node.decimalValue().setScale(2).toPlainString();
    }

    private JsonNode body(long userId, String url, int expectStatus) throws Exception {
        MvcResult r = call(userId, get(url));
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(expectStatus);
        return json.readTree(text);
    }

    private MvcResult call(long userId, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.requestAttr("userId", userId)).andReturn();
    }

    private void user(long id, String factoryId, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(factoryId);
        u.setUsername("s512-" + role + "-" + id);
        u.setRoleCode(role);
        u.setIsActive(true);
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    private static PurchaseOrder order() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("PO-1");
        po.setFactoryId(F);
        po.setOrderNumber("CG-S512");
        po.setTotalAmount(new BigDecimal("150.00"));
        po.setTaxAmount(new BigDecimal("0.00"));
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(11L);
        item.setPurchaseOrderId("PO-1");
        item.setMaterialTypeId("MAT-A");
        item.setQuantity(new BigDecimal("100"));
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("1.50"));
        item.setTaxRate(BigDecimal.ZERO);
        po.setItems(new ArrayList<>(List.of(item)));
        return po;
    }

    private static ReturnOrder returnOrder(ReturnType type) {
        ReturnOrder ro = new ReturnOrder();
        ro.setId(type == ReturnType.PURCHASE_RETURN ? "RO-P" : "RO-S");
        ro.setFactoryId(F);
        ro.setReturnType(type);
        ro.setTotalAmount(new BigDecimal("30.00"));
        ro.setTaxAmount(BigDecimal.ZERO);
        ReturnOrderItem item = new ReturnOrderItem();
        item.setId(21L);
        item.setQuantity(new BigDecimal("3"));
        item.setUnitPrice(new BigDecimal("10.00"));
        ro.setItems(new ArrayList<>(List.of(item)));
        return ro;
    }
}
