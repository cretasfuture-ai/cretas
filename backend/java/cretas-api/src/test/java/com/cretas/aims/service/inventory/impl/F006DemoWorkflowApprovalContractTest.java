package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.controller.workflow.WorkflowInstanceController;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.inventory.TransferService;
import com.cretas.aims.service.workflow.OaActionIdempotencyService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class F006DemoWorkflowApprovalContractTest {

    @Mock PurchaseOrderRepository orderRepository;
    @Mock PurchaseOrderItemRepository itemRepository;
    @Mock PurchaseReceiveRecordRepository receiveRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock RawMaterialTypeRepository materialRepository;
    @Mock MaterialBatchRepository batchRepository;
    @Mock BomRecipeItemRepository bomRepository;
    @Mock ArApService arApService;
    @Mock ApplicationEventPublisher events;
    @Mock MaterialBatchService batchService;
    @Mock WorkflowEngineService workflowEngine;
    @Mock ApprovalWorkflowService approvalWorkflowService;
    @Mock SupplierMaterialRepository supplierMaterialRepository;
    @Mock SupplierMaterialPurchaseSpecRepository purchaseSpecRepository;
    @Mock UserRepository userRepository;
    @Mock MobileService mobileService;
    @Mock PurchaseService controllerPurchaseService;
    @Mock SalesService salesService;
    @Mock TransferService transferService;
    @Mock FactoryStocktakeService stocktakeService;
    @Mock OaActionIdempotencyService oaActionIdempotencyService;

    private PurchaseServiceImpl service;
    private WorkflowInstanceController controller;
    private PurchaseOrder order;
    private ApprovalWorkflowInstance running;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orderRepository, itemRepository, receiveRepository,
                supplierRepository, materialRepository, batchRepository, bomRepository,
                arApService, events, batchService);
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", supplierMaterialRepository);
        ReflectionTestUtils.setField(service, "supplierMaterialPurchaseSpecRepository", purchaseSpecRepository);
        ReflectionTestUtils.setField(service, "approvalWorkflowService", approvalWorkflowService);

        controller = new WorkflowInstanceController(workflowEngine, approvalWorkflowService,
                userRepository, mobileService, controllerPurchaseService, salesService,
                transferService, stocktakeService);
        ReflectionTestUtils.setField(controller, "purchaseOrderRepository", orderRepository);
        ReflectionTestUtils.setField(controller, "oaActionIdempotencyService", oaActionIdempotencyService);
        lenient().when(workflowEngine.canTransition(any(ApprovalWorkflowInstance.class), any(User.class)))
                .thenReturn(true);

        order = new PurchaseOrder();
        order.setId("po-demo");
        order.setFactoryId("F006");
        order.setCreatedBy(1309L);
        order.setRemark("DEMO-F006-FLOW-20261105 purchase approval");
        order.setStatus(PurchaseOrderStatus.WORKFLOW_RUNNING);
        lenient().when(orderRepository.findByIdAndFactoryIdForUpdate("po-demo", "F006"))
                .thenReturn(Optional.of(order));
        lenient().when(orderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(itemRepository.findByPurchaseOrderId("po-demo")).thenReturn(List.of());

        running = ApprovalWorkflowInstance.builder()
                .id("instance-demo")
                .factoryId("F006")
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId("po-demo")
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .currentNodeIds(List.of("node-1"))
                .initiatedBy(1309L)
                .contextJson(Map.of())
                .build();
        lenient().when(workflowEngine.getInstance("F006", "instance-demo"))
                .thenReturn(Optional.of(running));
    }

    @Test
    void markedF006AdminMayApproveOwnDemoOrder() {
        ApprovalWorkflowInstance approved = copyWithStatus(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.transitionNode("instance-demo", 1309L, "factory_super_admin",
                HistoryAction.APPROVE, "演示通过")).thenReturn(approved);

        PurchaseOrder result = service.applyDemoApproveAction("F006", "po-demo", "instance-demo",
                1309L, "factory_super_admin", "node-1", HistoryAction.APPROVE, "演示通过");

        assertThat(result.getStatus()).isEqualTo(PurchaseOrderStatus.FINANCE_APPROVED);
        verify(workflowEngine).transitionNode("instance-demo", 1309L, "factory_super_admin",
                HistoryAction.APPROVE, "演示通过");
    }

    @Test
    void demoRejectsWrongTenantRoleMarkerActorAndRejectAction() {
        assertCode(() -> service.applyDemoApproveAction("F007", "po-demo", "instance-demo",
                1309L, "factory_super_admin", "node-1", HistoryAction.APPROVE, null), "F006_DEMO_TENANT_REQUIRED");
        assertCode(() -> service.applyDemoApproveAction("F006", "po-demo", "instance-demo",
                1309L, "procurement_manager", "node-1", HistoryAction.APPROVE, null), "F006_DEMO_ROLE_REQUIRED");
        assertCode(() -> service.applyDemoApproveAction("F006", "po-demo", "instance-demo",
                1309L, "factory_super_admin", "node-1", HistoryAction.REJECT, null), "F006_DEMO_APPROVE_ONLY");

        order.setRemark("ordinary PO");
        assertCode(() -> service.applyDemoApproveAction("F006", "po-demo", "instance-demo",
                1309L, "factory_super_admin", "node-1", HistoryAction.APPROVE, null), "F006_DEMO_MARKER_REQUIRED");
        order.setRemark("DEMO-F006-FLOW-20261105 purchase approval");
        assertCode(() -> service.applyDemoApproveAction("F006", "po-demo", "instance-demo",
                2001L, "factory_super_admin", "node-1", HistoryAction.APPROVE, null), "F006_DEMO_ACTOR_REQUIRED");
        verify(workflowEngine, never()).transitionNode(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void replayOfTerminalDemoInstanceDoesNotTransitionAgain() {
        ApprovalWorkflowInstance approved = copyWithStatus(ApprovalWorkflowInstance.InstanceStatus.APPROVED);
        when(workflowEngine.getInstance("F006", "instance-demo")).thenReturn(Optional.of(approved));

        PurchaseOrder result = service.applyDemoApproveAction("F006", "po-demo", "instance-demo",
                1309L, "factory_super_admin", "node-1", HistoryAction.APPROVE, null);

        assertThat(result).isSameAs(order);
        verify(workflowEngine, never()).transitionNode(anyString(), any(), anyString(), any(), any());
        verify(orderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    void freshWorkflowNodeMismatchRejectsBeforeTransition() {
        ApprovalWorkflowInstance fresh = copyWithStatus(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        fresh.setCurrentNodeIds(List.of("node-2"));
        when(workflowEngine.getInstance("F006", "instance-demo")).thenReturn(Optional.of(fresh));

        assertCode(() -> service.applyDemoApproveAction("F006", "po-demo", "instance-demo",
                1309L, "factory_super_admin", "node-1", HistoryAction.APPROVE, null),
                "OA_TASK_NODE_CHANGED");
        verify(workflowEngine, never()).transitionNode(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void expectedParallelNodeCannotCauseTransitionOfFirstFreshNode() {
        ApprovalWorkflowInstance fresh = copyWithStatus(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        fresh.setCurrentNodeIds(List.of("node-1", "node-2"));
        when(workflowEngine.getInstance("F006", "instance-demo")).thenReturn(Optional.of(fresh));

        assertCode(() -> service.applyDemoApproveAction("F006", "po-demo", "instance-demo",
                1309L, "factory_super_admin", "node-2", HistoryAction.APPROVE, null),
                "OA_TASK_NODE_CHANGED");
        verify(workflowEngine, never()).transitionNode(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void controllerRejectsTenantRoleRejectWrongModuleMarkerNodeAndMissingIdempotency() {
        UserDTO tokenUser = new UserDTO();
        tokenUser.setId(1309L);
        when(mobileService.getUserFromToken("demo-token")).thenReturn(tokenUser);

        User wrongTenant = controllerUser("F007", "factory_super_admin");
        when(userRepository.findById(1309L)).thenReturn(Optional.of(wrongTenant));
        assertControllerCode(new WorkflowInstanceController.WorkflowActionRequest(
                "APPROVE", null, "id-1", "node-1"), "F006_DEMO_TENANT_REQUIRED");

        User wrongRole = controllerUser("F006", "procurement_manager");
        when(userRepository.findById(1309L)).thenReturn(Optional.of(wrongRole));
        assertControllerCode(new WorkflowInstanceController.WorkflowActionRequest(
                "APPROVE", null, "id-2", "node-1"), "F006_DEMO_ROLE_REQUIRED");

        User demoAdmin = controllerUser("F006", "factory_super_admin");
        when(userRepository.findById(1309L)).thenReturn(Optional.of(demoAdmin));
        assertControllerCode(new WorkflowInstanceController.WorkflowActionRequest(
                "REJECT", null, "id-3", "node-1"), "F006_DEMO_APPROVE_ONLY");
        assertControllerCode(new WorkflowInstanceController.WorkflowActionRequest(
                "APPROVE", null, "", "node-1"), "OA_IDEMPOTENCY_KEY_REQUIRED");

        ApprovalWorkflowInstance wrongModule = copyWithStatus(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        wrongModule.setModuleCode("SALES_ORDER");
        when(workflowEngine.getInstance("F006", "instance-demo")).thenReturn(Optional.of(wrongModule));
        assertControllerCode(new WorkflowInstanceController.WorkflowActionRequest(
                "APPROVE", null, "id-4", "node-1"), "F006_DEMO_PURCHASE_REQUIRED");

        ApprovalWorkflowInstance markedInstance = copyWithStatus(ApprovalWorkflowInstance.InstanceStatus.RUNNING);
        when(workflowEngine.getInstance("F006", "instance-demo")).thenReturn(Optional.of(markedInstance));
        order.setRemark("ordinary order");
        when(orderRepository.findByIdAndFactoryId("po-demo", "F006")).thenReturn(Optional.of(order));
        assertControllerCode(new WorkflowInstanceController.WorkflowActionRequest(
                "APPROVE", null, "id-5", "node-1"), "F006_DEMO_MARKER_REQUIRED");

        order.setRemark("DEMO-F006-FLOW-20261105 purchase approval");
        markedInstance.setCurrentNodeIds(List.of("node-2"));
        assertControllerCode(new WorkflowInstanceController.WorkflowActionRequest(
                "APPROVE", null, "id-6", "node-1"), "OA_TASK_NODE_CHANGED");
        verify(controllerPurchaseService, never()).applyDemoApproveAction(
                anyString(), anyString(), anyString(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void endpointKeepsDemoPathSeparateFromOrdinaryActionAndUsesNoClientBypassFlag() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/cretas/aims/controller/workflow/WorkflowInstanceController.java"),
                StandardCharsets.UTF_8);
        assertThat(source).contains("/{instanceId}/demo-actions")
                .contains("purchaseService.applyDemoApproveAction")
                .doesNotContain("request.bypassSelfApproval");
    }

    private ApprovalWorkflowInstance copyWithStatus(ApprovalWorkflowInstance.InstanceStatus status) {
        return ApprovalWorkflowInstance.builder()
                .id("instance-demo")
                .factoryId("F006")
                .moduleCode("PURCHASE_ORDER")
                .businessEntityId("po-demo")
                .status(status)
                .currentNodeIds(status == ApprovalWorkflowInstance.InstanceStatus.RUNNING
                        ? List.of("node-1") : List.of())
                .initiatedBy(1309L)
                .contextJson(Map.of())
                .build();
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String code) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(code);
    }

    private void assertControllerCode(WorkflowInstanceController.WorkflowActionRequest request, String code) {
        assertThatThrownBy(() -> controller.executeDemoAction(
                "F006", "instance-demo", "Bearer demo-token", request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(code);
    }

    private User controllerUser(String factoryId, String roleCode) {
        User user = new User();
        user.setId(1309L);
        user.setFactoryId(factoryId);
        user.setRoleCode(roleCode);
        user.setIsActive(true);
        return user;
    }
}
