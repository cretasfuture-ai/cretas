package com.cretas.aims.controller.inventory;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.inventory.PurchaseService;
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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /purchase/orders: sales:read 只为销售订单详情的「关联采购」(带 salesOrderId) 而开 (W-12)。
 * 不带 salesOrderId 的全厂采购单列表要采购读权限 —— 与 AI 对话拒绝 purchase_order_list 一致 (#932)。
 * 带了 salesOrderId 也只认调用者本厂、且在其数据范围内的那张销售单。
 *
 * <p>真实链路: MockMvc → {@link PermissionInterceptor} (@RequirePermission) → 真实控制器 → GlobalExceptionHandler;
 * 权限判定用真实 {@link PermissionServiceImpl}, 只把 L1 / L2 配置表换成内存配置 (F006 形态: 销售 procurement「-」)。
 */
class PurchaseOrderListSalesScopeContractTest {

    private static final String F = "SCOPE-F";
    private static final String OTHER_F = "SCOPE-OTHER";
    private static final long PROCUREMENT = 1L, SALES = 2L, SALES_SELF = 3L, PRODUCTION = 4L;
    private static final String GATE_MESSAGE = "当前角色没有采购模块读权限，不能查看采购单列表";

    private final PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
    private final FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PurchaseService purchaseService = mock(PurchaseService.class);
    private final SalesOrderRepository salesOrders = mock(SalesOrderRepository.class);
    private final DataScopeResolver dataScope = mock(DataScopeResolver.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", l1);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", l2);
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        Map<String, Map<String, String>> override = new HashMap<>();
        override.put("procurement_manager", Map.of("procurement", "rw"));
        override.put("sales_manager", Map.of("procurement", "-", "sales", "rw"));
        override.put("production_manager", Map.of("procurement", "-", "sales", "-", "production", "rw"));
        when(l2.findByFactoryIdAndConfigVersion(F, 1)).thenAnswer(inv -> {
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(F);
            cfg.setRoleModuleOverride(override);
            return List.of(cfg);
        });
        user(PROCUREMENT, "procurement_manager");
        user(SALES, "sales_manager");
        user(SALES_SELF, "sales_manager");
        user(PRODUCTION, "production_manager");

        // SO-OWN: 本厂, SALES 创建; SO-OTHER 在别的工厂 (本厂查不到); 数据范围: SALES 全厂, SALES_SELF 只看自己
        when(salesOrders.findByIdAndFactoryId("SO-OWN", F)).thenReturn(Optional.of(salesOrder("SO-OWN", F, SALES)));
        when(salesOrders.findByIdAndFactoryId("SO-OTHER", F)).thenReturn(Optional.empty());
        when(salesOrders.findByIdAndFactoryId("SO-OTHER", OTHER_F)).thenReturn(Optional.of(salesOrder("SO-OTHER", OTHER_F, 99L)));
        when(dataScope.resolve(SALES)).thenReturn(scope(SALES, DataScope.ALL));
        DataScopeContext self = scope(SALES_SELF, DataScope.SELF);
        when(dataScope.resolve(SALES_SELF)).thenReturn(self);
        when(dataScope.resolveCreatedByChain(self)).thenReturn(List.of(SALES_SELF));

        when(purchaseService.getPurchaseOrders(F, 1, 20)).thenAnswer(inv ->
                PageResponse.of(List.of(po("PO-1", null), po("PO-2", "SO-OWN")), 1, 20, 2L));
        when(purchaseService.getPurchaseOrdersBySalesOrder(F, "SO-OWN", 1, 20)).thenAnswer(inv ->
                PageResponse.of(List.of(po("PO-2", "SO-OWN")), 1, 20, 1L));
        // 若闸漏了, 别厂销售单也会走到这里 —— 让它返回东西, 漏了就看得见
        when(purchaseService.getPurchaseOrdersBySalesOrder(F, "SO-OTHER", 1, 20)).thenAnswer(inv ->
                PageResponse.of(List.of(po("PO-X", "SO-OTHER")), 1, 20, 1L));

        PurchaseController controller = new PurchaseController(
                purchaseService, null, mock(MobileService.class), permissions, users, null, null);
        ReflectionTestUtils.setField(controller, "salesOrderRepository", salesOrders);
        ReflectionTestUtils.setField(controller, "dataScopeResolver", dataScope);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissions, users, json))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @Test
    void salesWithoutSalesOrderId_is403FromTheSalesOrderGate() throws Exception {
        MvcResult r = list(SALES, null);
        JsonNode body = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.getResponse().getStatus()).as(body.toString()).isEqualTo(403);
        assertThat(body.path("message").asText()).isEqualTo(GATE_MESSAGE);
        assertThat(body.path("actionHint").asText()).contains("关联采购");
        verify(purchaseService, never()).getPurchaseOrders(anyString(), anyInt(), anyInt());
    }

    @Test
    void attribution_aRoleWithoutSalesReadIsStoppedByTheAnnotationNotThisGate() throws Exception {
        MvcResult r = list(PRODUCTION, "SO-OWN");
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(text).path("message").asText()).isNotEqualTo(GATE_MESSAGE);
    }

    @Test
    void salesWithItsOwnSalesOrder_getsOnlyTheLinkedPurchaseOrders() throws Exception {
        JsonNode content = ok(SALES, "SO-OWN").at("/data/content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("id").asText()).isEqualTo("PO-2");
        assertThat(content.get(0).path("salesOrderId").asText()).isEqualTo("SO-OWN");
        verify(purchaseService, never()).getPurchaseOrders(anyString(), anyInt(), anyInt());
    }

    @Test
    void salesWithAnotherFactorysSalesOrder_getsNothing() throws Exception {
        JsonNode data = ok(SALES, "SO-OTHER").at("/data");
        assertThat(data.path("content")).isEmpty();
        assertThat(data.path("totalElements").asLong()).isZero();
        verify(purchaseService, never()).getPurchaseOrdersBySalesOrder(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void salesWithASalesOrderOutsideItsDataScope_getsNothing() throws Exception {
        // SALES_SELF 只看自己创建的单, SO-OWN 是 SALES 创建的
        JsonNode data = ok(SALES_SELF, "SO-OWN").at("/data");
        assertThat(data.path("content")).isEmpty();
        verify(purchaseService, never()).getPurchaseOrdersBySalesOrder(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void procurementListIsUnchanged() throws Exception {
        JsonNode content = ok(PROCUREMENT, null).at("/data/content");
        assertThat(content).hasSize(2);
        // 采购带 salesOrderId 照旧按销售单过滤, 不受销售数据范围约束
        assertThat(ok(PROCUREMENT, "SO-OWN").at("/data/content")).hasSize(1);
    }

    private JsonNode ok(long userId, String salesOrderId) throws Exception {
        MvcResult r = list(userId, salesOrderId);
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(200);
        return json.readTree(text);
    }

    private MvcResult list(long userId, String salesOrderId) throws Exception {
        var request = get("/api/mobile/" + F + "/purchase/orders").requestAttr("userId", userId);
        if (salesOrderId != null) {
            request.param("salesOrderId", salesOrderId);
        }
        return mvc.perform(request).andReturn();
    }

    private void user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setUsername("scope-" + role + "-" + id);
        u.setRoleCode(role);
        u.setIsActive(true);
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    private static DataScopeContext scope(long userId, DataScope scope) {
        return DataScopeContext.builder().userId(userId).factoryId(F).roleCode("sales_manager")
                .scope(scope).column("created_by").build();
    }

    private static SalesOrder salesOrder(String id, String factoryId, long createdBy) {
        SalesOrder so = new SalesOrder();
        so.setId(id);
        so.setFactoryId(factoryId);
        so.setCreatedBy(createdBy);
        return so;
    }

    private static PurchaseOrder po(String id, String salesOrderId) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setFactoryId(F);
        po.setSalesOrderId(salesOrderId);
        return po;
    }
}
