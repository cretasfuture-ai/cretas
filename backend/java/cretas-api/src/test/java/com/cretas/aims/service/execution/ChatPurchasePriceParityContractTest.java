package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.purchase.PurchaseOrderListTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.security.PriceSensitiveSerializerModifier;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.inventory.PurchaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ERP-S6-PO-PRICE-OVERMASK-001 (acc-s6 prod retest of #1370, F006): f006_procurement_mgr (procurement rw,
 * finance「-」) 在对话里「查询采购单」看到 totalAmount / taxAmount 为 null, 而同一账号在 web 采购单列表看得到。
 *
 * <p>成因不在直调分支的价格口径 (那里按配置判定可见), 在响应层: 对话结果到响应层已是 Map, 响应层对 Map 只能按
 * 键名猜 —— Rule 2b 对没有 finance:read 的角色剥掉一切像金额的数字键; REST 的类型化列表只按 @PriceSensitive
 * 口径剥。修复后, 直调分支把「按口径判定可见、已写出」的价格属性绑定到那一份结果上, 响应层不再按键名剥它们。
 *
 * <p>本测试钉「同一个人, 对话里的采购价可见性 == REST 列表的可见性」, 覆盖 F006 的角色。权限判定用真
 * {@link PermissionServiceImpl}, L1 角色 × 模块级别与 prod 一致 (F006 没有这些角色的 L2 覆盖), 响应层用真
 * {@link PriceFieldResponseAdvice}, 序列化用注册了 {@link PriceSensitiveSerializerModifier} 的 ObjectMapper。
 */
@DisplayName("对话采购价可见性 == REST 列表可见性 (同一用户, F006 角色)")
class ChatPurchasePriceParityContractTest {

    private static final String F = "F006";
    /** prod L1 (platform_role_permissions) 里这些角色的 procurement / finance 级别。 */
    private static final Map<String, Map<String, String>> L1 = Map.of(
            "procurement_manager", Map.of("procurement", "rw", "finance", "-"),
            "finance_manager", Map.of("procurement", "-", "finance", "rw"),
            "sales_manager", Map.of("procurement", "-", "finance", "-"),
            "warehouse_manager", Map.of("procurement", "r", "finance", "-"),
            "viewer", Map.of("procurement", "r", "finance", "-"));

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule("PriceSensitiveModule")
                    .setSerializerModifier(new PriceSensitiveSerializerModifier()));
    private final UserRepository users = mock(UserRepository.class);
    private final Map<String, Long> idOf = new HashMap<>();
    private PermissionServiceImpl permissions;
    private PriceFieldResponseAdvice advice;
    private ToolDispatchService dispatch;
    private PurchaseOrderListTool tool;

    @BeforeEach
    void setUp() {
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", l1);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", l2);
        when(l2.findByFactoryIdAndConfigVersion(anyString(), anyInt())).thenReturn(List.of());
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String level = L1.getOrDefault(inv.getArgument(0), Map.of()).get(inv.getArgument(1));
            if (level == null) return Optional.empty();
            PlatformRolePermission row = new PlatformRolePermission();
            row.setRoleCode(inv.getArgument(0));
            row.setModuleCode(inv.getArgument(1));
            row.setPermissionLevel(level);
            return Optional.of(row);
        });
        long id = 100;
        for (String role : List.of("factory_super_admin", "procurement_manager", "finance_manager",
                "sales_manager", "warehouse_manager", "viewer")) {
            User u = new User();
            u.setId(++id);
            u.setFactoryId(F);
            u.setUsername("parity-" + role);
            u.setRoleCode(role);
            u.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(u));
            idOf.put(role, id);
        }

        advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);

        PurchaseService purchases = mock(PurchaseService.class);
        when(purchases.getPurchaseOrders(anyString(), anyInt(), anyInt()))
                .thenAnswer(inv -> PageResponse.of(List.of(order()), 1, 10, 1L));
        tool = new PurchaseOrderListTool();
        ReflectionTestUtils.setField(tool, "purchaseService", purchases);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(dispatch, "priceUserRepository", users);
        ReflectionTestUtils.setField(dispatch, "pricePermissionService", permissions);
    }

    @AfterEach
    void clear() {
        PriceSensitiveContext.clear();
    }

    @ParameterizedTest(name = "{0}: 对话 == REST")
    @ValueSource(strings = {"factory_super_admin", "procurement_manager", "finance_manager",
            "sales_manager", "warehouse_manager", "viewer"})
    void chatPurchasePriceVisibilityEqualsRest(String role) throws Exception {
        Boolean rest = restSeesTotalAmount(role);
        Boolean chat = chatSeesTotalAmount(role);
        assertThat(chat).as("role=%s chat vs REST", role).isEqualTo(rest);
    }

    /** 缺陷本体: 配置给了采购读的采购主管 (finance「-」) 在对话里看得到采购价, 与 REST 一致。 */
    @Test
    void procurementManagerSeesPurchasePricesInChat() throws Exception {
        assertThat(restSeesTotalAmount("procurement_manager")).isTrue();
        assertThat(chatSeesTotalAmount("procurement_manager")).isTrue();
    }

    /** 阴性对照: 没有采购价权限 (配置里 procurement「-」) 的角色在对话里仍拿不到。 */
    @Test
    void aRoleWithoutThePurchasePricePermissionStillGetsNull() throws Exception {
        assertThat(chatSeesTotalAmount("sales_manager")).isFalse();
        assertThat(restSeesTotalAmount("sales_manager")).isFalse();
    }

    // ------------------------------------------------------------------ the two paths

    private Boolean restSeesTotalAmount(String role) throws Exception {
        try {
            Object body = ApiResponse.success(PageResponse.of(List.of(order()), 1, 10, 1L));
            JsonNode json = writeThroughAdvice(body, role);
            return !json.at("/data/content/0/totalAmount").isNull();
        } finally {
            PriceSensitiveContext.clear();
        }
    }

    private Boolean chatSeesTotalAmount(String role) throws Exception {
        try {
            AIIntentConfig intent = AIIntentConfig.builder().intentCode("PURCHASE_ORDER_LIST")
                    .intentCategory("PROCUREMENT").intentName("采购订单查询").toolName("purchase_order_list").build();
            IntentExecuteResponse response = dispatch.executeWithTool(tool, F,
                    IntentExecuteRequest.builder().userInput("查询采购单").build(), intent, idOf.get(role), role, null);
            assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
            JsonNode json = writeThroughAdvice(ApiResponse.success(response), role);
            JsonNode amount = json.at("/data/resultData/content/0/totalAmount");
            assertThat(amount.isMissingNode()).as("chat payload has the field").isFalse();
            return !amount.isNull();
        } finally {
            PriceSensitiveContext.clear();
        }
    }

    private JsonNode writeThroughAdvice(Object body, String role) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("userId", idOf.get(role));
        Object stripped = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null,
                new ServletServerHttpRequest(req), new ServletServerHttpResponse(new MockHttpServletResponse()));
        return mapper.readTree(mapper.writeValueAsString(stripped));
    }

    private static PurchaseOrder order() {
        PurchaseOrder o = new PurchaseOrder();
        o.setId("PO-PARITY-1");
        o.setFactoryId(F);
        o.setOrderNumber("PO-1");
        o.setSupplierId("SUP-1");
        o.setOrderDate(LocalDate.of(2026, 10, 15));
        o.setStatus(PurchaseOrderStatus.COMPLETED);
        o.setTotalAmount(new BigDecimal("36.00"));
        o.setTaxAmount(new BigDecimal("4.68"));
        return o;
    }
}
