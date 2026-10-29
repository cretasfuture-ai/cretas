package com.cretas.aims.controller.workflow;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.dto.workflow.WorkflowInstancePendingDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.PriceVisibility;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * S5-12 (B6): OA 列表里采购单的金额 (摘要「含税¥ / 未税¥」) 只给审批人原样看;
 * 抄送、发起、参与等视图走价格可见性 (配置的采购读) —— 被抄送不等于能看采购价。
 * 直调控制器 (同 {@link OaApprovalSummaryTaxBasisContractTest} 的理由: 中文断言不经 HTTP 编码)。
 */
@ExtendWith(MockitoExtension.class)
class OaPurchaseAmountVisibilityContractTest {

    private static final String F = "S512-OA";
    private static final String AUTH = "Bearer t";

    @Mock private WorkflowEngineService workflowEngine;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private UserRepository userRepository;
    @Mock private MobileService mobileService;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private PermissionService permissionService;

    @InjectMocks private WorkflowInstanceController controller;

    private User sales;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "purchaseOrderRepository", purchaseOrderRepository);
        ReflectionTestUtils.setField(controller, "salesOrderRepository", salesOrderRepository);
        ReflectionTestUtils.setField(controller, "priceVisibility", new PriceVisibility(permissionService));

        sales = new User();
        sales.setId(7L);
        sales.setUsername("s512-sales");
        sales.setRoleCode("sales_manager");
        sales.setFactoryId(F);
        sales.setIsActive(true);
        UserDTO dto = new UserDTO();
        dto.setId(7L);
        lenient().when(mobileService.getUserFromToken(anyString())).thenReturn(dto);
        lenient().when(userRepository.findById(7L)).thenReturn(Optional.of(sales));
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
        // 在旧固定名单里 (price:view), 但配置没给采购读
        lenient().when(permissionService.hasPermission(eq(sales), anyString())).thenReturn(true);
        lenient().when(permissionService.hasConfiguredPermission(eq(sales), anyString(), any())).thenReturn(false);

        PurchaseOrder po = new PurchaseOrder();
        po.setId("po-1");
        po.setFactoryId(F);
        po.setOrderNumber("PO-S512-1");
        po.setTotalAmount(new BigDecimal("750.00"));
        po.setTaxAmount(new BigDecimal("97.50"));
        lenient().when(purchaseOrderRepository.findAllById(any())).thenReturn(List.of(po));

        ApprovalWorkflowInstance inst = ApprovalWorkflowInstance.builder()
                .id("inst-1").factoryId(F).workflowId("wf-1")
                .moduleCode("PURCHASE_ORDER").businessEntityId("po-1")
                .status(InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>(List.of("n1")))
                .contextJson(new HashMap<>())
                .initiatedBy(200L).initiatedAt(LocalDateTime.now())
                .build();
        Pageable pageable = PageRequest.of(0, 20);
        lenient().when(workflowEngine.findCopiedTo(eq(F), eq(7L), eq("sales_manager"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inst), pageable, 1L));
        lenient().when(workflowEngine.findActedBy(eq(F), eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inst), pageable, 1L));
        lenient().when(approvalWorkflowService.getById(F, "wf-1")).thenReturn(Optional.empty());
    }

    @Test
    void copiedRecipientWithoutConfiguredProcurementRead_doesNotSeePurchaseAmounts() {
        String summary = only(controller.getCopied(F, AUTH, 1, 20)).getBusinessSummary();
        assertThat(summary).contains("PO-S512-1").doesNotContain("¥").doesNotContain("750");
    }

    @Test
    void approverWhoActed_keepsTheAmounts() {
        String summary = only(controller.getActed(F, AUTH, 1, 20)).getBusinessSummary();
        assertThat(summary).contains("含税¥847.50").contains("未税¥750.00");
    }

    @Test
    void configGrantingProcurementRead_showsAmountsToTheCopiedRecipient() {
        when(permissionService.hasConfiguredPermission(eq(sales), eq("procurement:read"), any())).thenReturn(true);
        String summary = only(controller.getCopied(F, AUTH, 1, 20)).getBusinessSummary();
        assertThat(summary).contains("含税¥847.50");
    }

    private static WorkflowInstancePendingDTO only(ApiResponse<PageResponse<WorkflowInstancePendingDTO>> resp) {
        List<WorkflowInstancePendingDTO> content = resp.getData().getContent();
        assertThat(content).hasSize(1);
        assertThat(content.get(0).getBusinessSummary()).isNotBlank();
        return content.get(0);
    }
}
