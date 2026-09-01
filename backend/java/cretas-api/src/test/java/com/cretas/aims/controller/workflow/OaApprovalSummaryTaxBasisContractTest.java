package com.cretas.aims.controller.workflow;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.dto.workflow.WorkflowInstancePendingDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.workflow.OaActionIdempotencyService;
import com.cretas.aims.service.workflow.OaModuleLabelResolver;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 缺陷 24 (2026-09-01, LIUSHANMEN 生产走查): OA 审批摘要只显示未税金额且不标口径。
 *
 * <p><b>现场</b>: 审批列表的业务摘要是 {@code PO-20260901-0001 ¥0.09 (操作手册演示供应商235005)}。
 * 而 {@code ¥0.09} 是**未税**额(含税 ¥0.10 / 税额 ¥0.01), 摘要里没有任何「未税」字样
 * ⇒ 审批人会把它读成「这单要付 0.09 元」。
 *
 * <p><b>为什么这是实质风险而不是文案洁癖</b>: 审批人正是**按这个数**做批准决定的,
 * 而按 13% 税率**金额越大绝对偏差越大**。本例差 1 分, 换成 10 万的单就是 1.3 万。
 *
 * <p><b>口径来自源码, 不是推断</b>:
 * {@code PurchaseServiceImpl#createPurchaseOrder} 的累加循环里
 * {@code totalAmount += item.getLineAmount()}(未税) 与
 * {@code taxAmount += lineAmountWithTax - lineAmount}(税额) ⇒ 含税 = 两者之和。
 *
 * <h2>为什么类名以 ContractTest 结尾</h2>
 * 本仓 Java CI 的选择器是
 * {@code *RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest,*ContractTest}
 * —— 同包的 {@code WorkflowInstanceControllerTest} **不在选择器里**, 只编译不执行。
 * ⛔ 把断言加进那个类等于写一条**永远不会跑**的测试(本仓已有同形先例:
 * ENH-F006-SUPPLIER-IMPORT 的两个测试类因不在选择器内而长期「无核销证据」)。
 *
 * <h2>用 Mockito 直调 controller, ⛔ 不用 MockMvc</h2>
 * MockMvc 默认响应编码是 ISO-8859-1, 中文断言会变成恒真式(本仓踩过)。
 * 直调 controller 拿 DTO 的 String 字段, 断言全在 JVM 内, 不经过 HTTP 编码。
 */
@DisplayName("缺陷 24 — OA 审批摘要必须标注金额口径(未税/含税)")
@ExtendWith(MockitoExtension.class)
class OaApprovalSummaryTaxBasisContractTest {

    private static final String FACTORY_ID = "LIUSHANMEN";
    private static final String AUTH_HEADER = "Bearer fake-token";

    @Mock private WorkflowEngineService workflowEngine;
    @Mock private OaModuleLabelResolver oaModuleLabelResolver;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private UserRepository userRepository;
    @Mock private MobileService mobileService;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private OaActionIdempotencyService oaActionIdempotencyService;

    @InjectMocks private WorkflowInstanceController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "purchaseOrderRepository", purchaseOrderRepository);
        ReflectionTestUtils.setField(controller, "salesOrderRepository", salesOrderRepository);
        ReflectionTestUtils.setField(controller, "oaActionIdempotencyService", oaActionIdempotencyService);
        ReflectionTestUtils.setField(controller, "oaModuleLabelResolver", oaModuleLabelResolver);
    }

    @Test
    @DisplayName("有税额 → 两个口径都写出来, 且都带标签")
    void summaryStatesBothTaxBasesWhenTaxPresent() {
        String summary = summaryFor(new BigDecimal("0.09"), new BigDecimal("0.01"));

        // 正向: 两个数各自带口径
        assertTrue(summary.contains("未税¥0.09"), "缺「未税」口径: " + summary);
        assertTrue(summary.contains("含税¥0.10"), "缺「含税」口径: " + summary);

        // 🔴 阴性对照 —— 这一条才是本用例真正在守的东西:
        // 「出现了 0.09」不够, 必须保证**不存在**一个不带口径、孤零零的 `¥0.09`。
        // 缺陷本身就长这样: 一个正确的数配一个缺失的标签。
        assertFalse(summary.matches(".*(?<!未税|含税)¥0\\.09.*"),
                "存在不带口径的裸金额, 审批人会读成付款额: " + summary);
    }

    @Test
    @DisplayName("🔒 owner 裁定(2026-09-01): 含税领先, 税率说明, 未税殿后 —— 顺序不能反")
    void summaryLeadsWithTaxInclusiveAmount() {
        // owner 原话:「首先肯定是计算含税总额, 然后告诉这个税率是多少, 然后再计算未税总额」。
        // 税额 0.01 / 未税 0.09 → 税率 11.11%(0.01/0.09*100, HALF_UP 到 2 位)。
        String summary = summaryFor(new BigDecimal("0.09"), new BigDecimal("0.01"));

        assertTrue(summary.contains("税率11.11%"), "缺税率说明: " + summary);

        int taxInclusiveIdx = summary.indexOf("含税¥0.10");
        int taxUntaxedIdx = summary.indexOf("未税¥0.09");
        assertTrue(taxInclusiveIdx >= 0 && taxUntaxedIdx >= 0,
                "两个口径本身应都在(与上面那条用例同一份数据): " + summary);
        assertTrue(taxInclusiveIdx < taxUntaxedIdx,
                "含税应该排在未税前面(owner 裁定「先算含税」), 实际: " + summary);
    }

    @Test
    @DisplayName("税额为 0/缺失 → 只写未税一个数, ⛔ 不凭空造一个含税")
    void summaryDoesNotInventTaxInclusiveWhenTaxUnknown() {
        String summary = summaryFor(new BigDecimal("40000"), BigDecimal.ZERO);

        assertTrue(summary.contains("未税¥40000"), "税额为 0 时仍要标未税: " + summary);
        // ⛔ 税额未知时算一个「含税」出来就是编数字 —— 那比不标口径更糟。
        assertFalse(summary.contains("含税"),
                "税额为 0/未知时不得出现含税口径: " + summary);
    }

    @Test
    @DisplayName("订单号与供应商名仍在 —— 加口径不能把原有信息挤掉")
    void summaryKeepsOrderNumberAndSupplier() {
        String summary = summaryFor(new BigDecimal("0.09"), new BigDecimal("0.01"));

        assertTrue(summary.contains("PO-20260901-0001"), "丢了订单号: " + summary);
        assertTrue(summary.contains("操作手册演示供应商235005"), "丢了供应商名: " + summary);
    }

    @Test
    @DisplayName("缺陷 30 续: businessTaxAmount 结构化下发, 前端才拼得出「金额」列/确认框的含税")
    void dtoCarriesStructuredTaxAmountForPurchaseOrder() {
        WorkflowInstancePendingDTO dto = pendingFor(new BigDecimal("750.00"), new BigDecimal("97.50"));

        assertTrue(dto.getBusinessAmount() != null
                        && dto.getBusinessAmount().compareTo(new BigDecimal("750.00")) == 0,
                "businessAmount 应是未税额: " + dto.getBusinessAmount());
        assertTrue(dto.getBusinessTaxAmount() != null
                        && dto.getBusinessTaxAmount().compareTo(new BigDecimal("97.50")) == 0,
                "businessTaxAmount 应下发税额, 前端才拼得出含税总额, 实际: " + dto.getBusinessTaxAmount());
    }

    // ==================== fixture ====================

    /** 跑一次 /pending 并取回该实例的 businessSummary。 */
    private String summaryFor(BigDecimal totalAmount, BigDecimal taxAmount) {
        return pendingFor(totalAmount, taxAmount).getBusinessSummary();
    }

    /** 跑一次 /pending 并取回该实例完整的 DTO(含结构化字段, 不只是拼好的摘要句)。 */
    private WorkflowInstancePendingDTO pendingFor(BigDecimal totalAmount, BigDecimal taxAmount) {
        mockAuth();

        String poId = "po-1";
        ApprovalWorkflowInstance inst = ApprovalWorkflowInstance.builder()
                .id("inst-1")
                .factoryId(FACTORY_ID)
                .workflowId("wf-1")
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId(poId)
                .status(InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>(List.of("admin_approval")))
                .contextJson(new HashMap<>())
                .initiatedBy(200L)
                .initiatedAt(LocalDateTime.now())
                .build();

        Pageable pageable = PageRequest.of(0, 20);
        when(workflowEngine.findPendingForRole(eq(FACTORY_ID), eq("factory_super_admin"),
                eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inst), pageable, 1L));

        // 节点用六膳门实际那条链的形状: 单个 admin_approval, approverRoles=[factory_super_admin]
        ApprovalWorkflow wf = new ApprovalWorkflow();
        wf.setId("wf-1");
        wf.setFactoryId(FACTORY_ID);
        wf.setNodesJson("[]");
        lenient().when(approvalWorkflowService.getById(FACTORY_ID, "wf-1"))
                .thenReturn(Optional.of(wf));
        lenient().when(approvalWorkflowService.deserializeNodes(anyString()))
                .thenReturn(List.of(ApprovalWorkflowNode.builder()
                        .id("admin_approval")
                        .type("approval")
                        .label("LIUSHANMEN admin approval")
                        .config(Map.of("approverRoles", List.of("factory_super_admin")))
                        .build()));

        PurchaseOrder po = new PurchaseOrder();
        po.setId(poId);
        po.setFactoryId(FACTORY_ID);
        po.setOrderNumber("PO-20260901-0001");
        po.setTotalAmount(totalAmount);
        po.setTaxAmount(taxAmount);
        // supplierName 是 @Formula 字段, 测试里直接反射写入。
        ReflectionTestUtils.setField(po, "supplierName", "操作手册演示供应商235005");
        when(purchaseOrderRepository.findAllById(any())).thenReturn(List.of(po));

        lenient().when(userRepository.findAllById(any()))
                .thenReturn(List.of(buildUser(200L, "lsm003", "procurement_manager")));

        ApiResponse<PageResponse<WorkflowInstancePendingDTO>> resp =
                controller.getPendingForUser(FACTORY_ID, AUTH_HEADER, null, 1, 20);

        List<WorkflowInstancePendingDTO> content = resp.getData().getContent();
        assertTrue(content.size() == 1, "阳性对照: 应当恰好取回 1 条实例, 实际 " + content.size());
        WorkflowInstancePendingDTO dto = content.get(0);
        assertTrue(dto.getBusinessSummary() != null && !dto.getBusinessSummary().isBlank(),
                "businessSummary 为空, 后面的断言无意义");
        return dto;
    }

    private void mockAuth() {
        UserDTO dto = new UserDTO();
        dto.setId(100L);
        dto.setUsername("lsm_admin");
        lenient().when(mobileService.getUserFromToken(anyString())).thenReturn(dto);
        lenient().when(userRepository.findById(100L))
                .thenReturn(Optional.of(buildUser(100L, "lsm_admin", "factory_super_admin")));
    }

    private User buildUser(Long id, String username, String roleCode) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRoleCode(roleCode);
        u.setIsActive(true);
        return u;
    }
}
