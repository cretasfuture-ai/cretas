package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.security.PriceSensitiveSerializerModifier;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.execution.ToolDispatchService;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ERP-S6-PO-LIST-PAGE-INDEX-001: 路由修好后每次 purchase_order_list 都 400「Page index must not be less than zero」——
 * 工具先减 1, 服务再减 1。以前的测试桩了服务, 照不出来。
 *
 * <p>这里走真实分页: {@link ToolDispatchService#executeWithTool} → 真 {@link PurchaseOrderListTool}
 * → 真 {@link PurchaseServiceImpl} → 真 {@link PurchaseOrderRepository} (H2), 种入与 F006 生产同样的分布
 * (22 张: 已完成 10 / 已取消 5 / 财务已审核 3 / 财务驳回 2 / 部分到货 2)。
 *
 * <p>同时钉采购价可见性 (S5-12「采购价谁能看取决于权限配置」): 对话直调分支与 REST 列表同一口径,
 * 没有采购价权限的角色拿不到金额字段 (阴性对照), 有权限的拿得到。
 */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("对话查采购单: 真实分页 + 状态筛选 + 采购价按权限配置 (S6 2026-11-03)")
class PurchaseOrderListChatRepositoryQueryValidationTest {

    private static final String F = "IT-PO-CHAT";
    private static final long BUYER = 31L;
    private static final long VIEWER = 32L;

    @Autowired private PurchaseOrderRepository orders;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule("PriceSensitiveModule")
                    .setSerializerModifier(new PriceSensitiveSerializerModifier()));
    private ToolDispatchService dispatch;
    private PurchaseOrderListTool tool;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("DELETE FROM purchase_orders");
        int seq = 0;
        for (Map.Entry<PurchaseOrderStatus, Integer> e : Map.of(
                PurchaseOrderStatus.COMPLETED, 10, PurchaseOrderStatus.CANCELLED, 5,
                PurchaseOrderStatus.FINANCE_APPROVED, 3, PurchaseOrderStatus.FINANCE_REJECTED, 2,
                PurchaseOrderStatus.PARTIAL_RECEIVED, 2).entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                order("PO-" + (seq++), e.getKey());
            }
        }

        PurchaseServiceImpl service = new PurchaseServiceImpl(orders, null, null, null, null, null, null, null,
                null, null);
        tool = new PurchaseOrderListTool();
        ReflectionTestUtils.setField(tool, "purchaseService", service);
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

        // 权限配置: BUYER 有采购读权限 (采购价可见), VIEWER 没有
        UserRepository users = mock(UserRepository.class);
        User buyer = user(BUYER, "procurement_manager");
        User viewer = user(VIEWER, "viewer");
        when(users.findById(BUYER)).thenReturn(Optional.of(buyer));
        when(users.findById(VIEWER)).thenReturn(Optional.of(viewer));
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasConfiguredPermission(eq(buyer), anyString(), any())).thenReturn(true);
        when(permissions.hasPermission(eq(buyer), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "priceUserRepository", users);
        ReflectionTestUtils.setField(dispatch, "pricePermissionService", permissions);
    }

    @Test
    void theFirstPageOfPurchaseOrdersLoads() throws Exception {
        Map<String, Object> data = ask("查询采购单", BUYER);

        assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(22);
        // 对话里列表默认每页 20 条 (Steve 2026-11-12 口径 7; 工具自己的默认 10 只用于非对话调用)
        assertThat(rows(data)).hasSize(20);
    }

    @Test
    void aStatusSentenceReturnsTheFilteredRows() throws Exception {
        Map<String, Object> data = ask("已完成的采购单", BUYER);

        assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(10);
        assertThat(rows(data)).hasSize(10).extracting(r -> r.get("status")).containsOnly("COMPLETED");
        assertThat(ask("已取消的采购单", BUYER).get("totalElements")).isEqualTo(5);
    }

    @Test
    void purchasePricesFollowThePermissionConfiguration() throws Exception {
        assertThat(rows(ask("查询采购单", BUYER))).allSatisfy(r -> assertThat(r.get("totalAmount")).isNotNull());
        // 阴性对照: 没有采购价权限的角色拿不到金额字段
        assertThat(rows(ask("查询采购单", VIEWER))).isNotEmpty()
                .allSatisfy(r -> assertThat(r.get("totalAmount")).isNull());
    }

    private Map<String, Object> ask(String sentence, long userId) throws Exception {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("PURCHASE_ORDER_LIST").intentCategory("PROCUREMENT")
                .intentName("采购订单查询").toolName("purchase_order_list").build();
        IntentExecuteResponse response = dispatch.executeWithTool(tool, F,
                IntentExecuteRequest.builder().userInput(sentence).build(), intent, userId, "factory_super_admin", null);
        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = mapper.convertValue(response.getResultData(), Map.class);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("content");
    }

    private void order(String id, PurchaseOrderStatus status) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId(id);
        o.setFactoryId(F);
        o.setOrderNumber("NO-" + id);
        o.setSupplierId("SUP-1");
        o.setOrderDate(LocalDate.of(2026, 10, 15));
        o.setStatus(status);
        o.setCreatedBy(BUYER);
        o.setTotalAmount(new BigDecimal("1234.50"));
        orders.saveAndFlush(o);
    }

    private static User user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setUsername("u" + id);
        ReflectionTestUtils.setField(u, "roleCode", role);
        return u;
    }
}
