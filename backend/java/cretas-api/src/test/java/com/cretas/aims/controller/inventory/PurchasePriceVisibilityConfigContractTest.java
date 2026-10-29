package com.cretas.aims.controller.inventory;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.controller.SupplierMaterialController;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.PurchaseInvoiceLineViewDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceMatchStatusDTO;
import com.cretas.aims.dto.inventory.PurchaseInvoiceViewDTO;
import com.cretas.aims.dto.supplier.SupplierMaterialDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.inventory.PurchaseInvoiceService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.supplier.SupplierMaterialPurchaseSpecService;
import com.cretas.aims.service.supplier.SupplierMaterialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * S5-12 (D-S5-02): 采购价 / 采购金额看不看得到, 只由工厂配置的 procurement 模块读权限决定
 * (L2 工厂覆盖 → L1 → 兜底), 不由 procurement:price:view 的固定角色名单决定。
 *
 * <p>走真实链路: MockMvc → {@link PermissionInterceptor} (@RequirePermission) → 真实控制器 →
 * {@link PriceFieldResponseAdvice} → Jackson。权限判定用真实 {@link PermissionServiceImpl},
 * 只把 L1 / L2 两张配置表换成内存里的配置; 测试中途改配置、清缓存, 可见性必须跟着翻。
 */
class PurchasePriceVisibilityConfigContractTest {

    private static final String F = "S512-F";
    private static final long PROCUREMENT = 1L, FINANCE = 2L, SALES = 3L, PRODUCTION = 4L, WAREHOUSE = 5L;

    private final PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
    private final FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PurchaseService purchaseService = mock(PurchaseService.class);
    private final SupplierMaterialService supplierMaterialService = mock(SupplierMaterialService.class);
    private final PurchaseInvoiceService invoiceService = mock(PurchaseInvoiceService.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

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
        override.put("sales_manager", new HashMap<>(Map.of("procurement", "-", "sales", "rw")));
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
        user(PROCUREMENT, "procurement_manager");
        user(FINANCE, "finance_manager");
        user(SALES, "sales_manager");
        user(PRODUCTION, "production_manager");
        user(WAREHOUSE, "warehouse_manager");

        PurchaseOrder po = order();
        when(purchaseService.getPurchaseOrders(F, 1, 20)).thenAnswer(inv -> PageResponse.of(List.of(order()), 1, 20, 1L));
        when(purchaseService.getPurchaseOrderById(F, "PO-1")).thenAnswer(inv -> order());
        when(supplierMaterialService.listBySupplier(F, "SUP-1")).thenAnswer(inv -> List.of(SupplierMaterialDTO.builder()
                .supplierId("SUP-1").materialTypeId("MAT-A")
                .defaultPurchasePrice(new BigDecimal("20.00"))
                .taxIncludedDefaultPurchasePrice(new BigDecimal("22.60"))
                .effectivePurchasePrice(new BigDecimal("20.00")).build()));
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
        mvc = MockMvcBuilders.standaloneSetup(
                        new PurchaseController(purchaseService, null, mobile, permissions, users, null, null),
                        new SupplierMaterialController(supplierMaterialService, mock(SupplierMaterialPurchaseSpecService.class)),
                        new PurchaseInvoiceController(invoiceService, mobile))
                .addInterceptors(new PermissionInterceptor(permissions, users, json))
                .setControllerAdvice(advice, new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @Test
    void procurementModuleRead_isTheOnlyThingThatShowsPurchasePrices() throws Exception {
        // 采购经理 (procurement=rw): 每个入口都看得到
        assertThat(supplierPrice(PROCUREMENT)).isEqualTo("20.00");
        assertThat(poListAmount(PROCUREMENT)).isEqualTo("150.00");
        assertThat(poDetailUnitPrice(PROCUREMENT)).isEqualTo("1.50");

        // 财务 / 销售 / 生产 (procurement=「-」) 都在旧的固定名单里, 但配置没给采购读 → 每个入口都看不到
        for (long role : new long[] {FINANCE, SALES, PRODUCTION, WAREHOUSE}) {
            assertThat(supplierPrice(role)).as("supplier materials role=%s", role).isNull();
            String list = poListAmountOrForbidden(role);
            assertThat(list).as("PO list role=%s", role).isIn(null, "403");
            String detail = poDetailUnitPriceOrForbidden(role);
            assertThat(detail).as("PO detail role=%s", role).isIn(null, "403");
        }
        // 原来同一角色列表给金额、详情 403 的那种不一致 (销售): 现在列表也不给金额
        assertThat(poListAmountOrForbidden(SALES)).isNull();
        assertThat(poDetailUnitPriceOrForbidden(SALES)).isEqualTo("403");
    }

    @Test
    void financeSeesInvoiceAmountsButNotPurchaseOrderPricesUnlessConfigured() throws Exception {
        JsonNode views = body(FINANCE, "/api/mobile/" + F + "/invoices/views?poId=PO-1", 200);
        assertThat(views.at("/data/0/amount").decimalValue()).isEqualByComparingTo("1130.00");
        assertThat(views.at("/data/0/lines/0/amount").decimalValue()).isEqualByComparingTo("1130.00");
        assertThat(views.at("/data/0/lines/0/expectedAmount").isNull()).isTrue();   // 由采购单价推出
        JsonNode status = body(FINANCE, "/api/mobile/" + F + "/invoices/match-status?poId=PO-1", 200);
        assertThat(status.at("/data/lines/0/expectedUnitAmount").isNull()).isTrue();
        assertThat(status.at("/data/lines/0/receivedQuantity").decimalValue()).isEqualByComparingTo("100");
        assertThat(poDetailUnitPriceOrForbidden(FINANCE)).isNull();   // 财务速览抽屉: 能进, 无价

        // 配置给财务采购读 → 同样这几个入口都看得到采购价
        grant("finance_manager", "procurement", "r");
        views = body(FINANCE, "/api/mobile/" + F + "/invoices/views?poId=PO-1", 200);
        assertThat(views.at("/data/0/lines/0/expectedAmount").decimalValue()).isEqualByComparingTo("1130.00");
        status = body(FINANCE, "/api/mobile/" + F + "/invoices/match-status?poId=PO-1", 200);
        assertThat(status.at("/data/lines/0/expectedUnitAmount").decimalValue()).isEqualByComparingTo("11.30");
        assertThat(poDetailUnitPrice(FINANCE)).isEqualTo("1.50");
        assertThat(supplierPrice(FINANCE)).isEqualTo("20.00");
    }

    @Test
    void changingTheRoleConfig_flipsVisibilityOnEverySurface() throws Exception {
        assertThat(supplierPrice(SALES)).isNull();
        assertThat(poListAmountOrForbidden(SALES)).isNull();

        grant("sales_manager", "procurement", "r");
        assertThat(supplierPrice(SALES)).isEqualTo("20.00");
        assertThat(poListAmount(SALES)).isEqualTo("150.00");
        assertThat(poDetailUnitPrice(SALES)).isEqualTo("1.50");   // 采购读同时打开了详情入口

        // 收回 → 立刻看不到 (不是靠角色名单)
        grant("sales_manager", "procurement", "-");
        assertThat(supplierPrice(SALES)).isNull();
        assertThat(poListAmountOrForbidden(SALES)).isNull();

        // 反方向: 仓管不在旧名单里, 配置给了采购读就能看
        grant("warehouse_manager", "procurement", "r");
        assertThat(supplierPrice(WAREHOUSE)).isEqualTo("20.00");
        assertThat(poDetailUnitPrice(WAREHOUSE)).isEqualTo("1.50");
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void grant(String role, String module, String level) {
        override.computeIfAbsent(role, r -> new HashMap<>()).put(module, level);
        permissions.invalidateCache();
    }

    private String supplierPrice(long userId) throws Exception {
        JsonNode node = body(userId, "/api/mobile/" + F + "/suppliers/SUP-1/materials", 200)
                .at("/data/0/defaultPurchasePrice");
        return node.isNull() || node.isMissingNode() ? null : node.decimalValue().setScale(2).toPlainString();
    }

    private String poListAmount(long userId) throws Exception {
        String v = poListAmountOrForbidden(userId);
        assertThat(v).isNotNull().isNotEqualTo("403");
        return v;
    }

    private String poListAmountOrForbidden(long userId) throws Exception {
        MvcResult r = call(userId, "/api/mobile/" + F + "/purchase/orders");
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
        MvcResult r = call(userId, "/api/mobile/" + F + "/purchase/orders/PO-1");
        if (r.getResponse().getStatus() == 403) return "403";
        JsonNode root = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode total = root.at("/data/totalAmount");
        JsonNode node = root.at("/data/items/0/unitPrice");
        // 详情里订单总额与行单价必须同进同出
        assertThat(total.isNull() || total.isMissingNode()).isEqualTo(node.isNull() || node.isMissingNode());
        return node.isNull() || node.isMissingNode() ? null : node.decimalValue().setScale(2).toPlainString();
    }

    private JsonNode body(long userId, String url, int expectStatus) throws Exception {
        MvcResult r = call(userId, url);
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(expectStatus);
        return json.readTree(text);
    }

    private MvcResult call(long userId, String url) throws Exception {
        return mvc.perform(get(url).requestAttr("userId", userId).requestAttr("factoryId", F)).andReturn();
    }

    private void user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setUsername("s512-" + role);
        u.setRoleCode(role);
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
        po.setItems(new java.util.ArrayList<>(List.of(item)));
        return po;
    }
}
