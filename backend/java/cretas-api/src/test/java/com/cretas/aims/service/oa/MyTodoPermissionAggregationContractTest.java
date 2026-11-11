package com.cretas.aims.service.oa;

import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.dto.oa.TodoItemDTO.TodoType;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纪律排查 B13: 「我的待办」按权限出数, 不再只有财务经理 / 出纳有待办; 并把强制插单审批待办聚合进来。
 * 默认值下财务经理 / 出纳 / 厂长拿到的待办类型 = 改动前的名单 (报损随 B9 只给厂长), 没有相关权限的岗位仍是空。
 */
@DisplayName("我的待办按权限聚合 (MyTodoAggregatorServiceImpl)")
class MyTodoPermissionAggregationContractTest {

    private static final String F = "F-TODO";

    private MyTodoAggregatorServiceImpl service;
    private PermissionServiceImpl permissions;
    private ThresholdResolverService thresholds;
    private WorkflowEngineService workflowEngine;

    @BeforeEach
    void setUp() {
        service = new MyTodoAggregatorServiceImpl(
                mock(com.cretas.aims.service.inventory.PurchaseService.class),
                mock(com.cretas.aims.service.inventory.SalesService.class),
                mock(com.cretas.aims.service.inventory.SupplierDeliveryNoteService.class),
                mock(com.cretas.aims.repository.factory.FactoryStocktakeRepository.class),
                mock(com.cretas.aims.repository.factory.FactoryWarehouseRepository.class),
                mock(com.cretas.aims.repository.inventory.ReturnOrderRepository.class),
                mock(com.cretas.aims.repository.CustomerRepository.class),
                mock(com.cretas.aims.repository.SupplierRepository.class),
                mock(com.cretas.aims.service.inventory.PaymentRequestService.class),
                mock(com.cretas.aims.service.inventory.SalesPriceAdjustmentService.class),
                mock(com.cretas.aims.service.inventory.WastageReportService.class));
        permissions = new PermissionServiceImpl();
        com.cretas.aims.testutil.PermissionRepositoryFixtures.installSuccessfulEmpty(permissions);
        thresholds = mock(ThresholdResolverService.class);
        ReflectionTestUtils.setField(permissions, "thresholdResolver", thresholds);
        workflowEngine = mock(WorkflowEngineService.class);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        ReflectionTestUtils.setField(service, "workflowEngineService", workflowEngine);
    }

    @SuppressWarnings("unchecked")
    private Set<TodoType> typesFor(String role) {
        return (Set<TodoType>) ReflectionTestUtils.invokeMethod(service, "typesFor", F, role);
    }

    @Test
    @DisplayName("一人厂: 只有厂长时, 厂长的待办 tab 拿到全部类型 (以前永远是空)")
    void factoryHeadSeesEveryType() {
        assertThat(typesFor("factory_super_admin")).containsExactlyInAnyOrder(TodoType.values());
    }

    @Test
    @DisplayName("默认: 财务经理拿到原来的财审 / 价格异常 / 盘点 / 退货 / 改价 (报损随 B9 只给厂长, 付款只给出纳)")
    void financeManagerDefaultsMatchTheOldListExceptWhatSettingsNarrowed() {
        assertThat(typesFor("finance_manager")).contains(
                TodoType.PURCHASE_FINANCE_REVIEW, TodoType.SALES_FINANCE_REVIEW, TodoType.PRICE_ANOMALY,
                TodoType.STOCKTAKE_APPROVAL, TodoType.RETURN_FINANCE_REVIEW, TodoType.SALES_PRICE_ADJUSTMENT);
        assertThat(typesFor("finance_manager")).doesNotContain(TodoType.WASTAGE_APPROVAL, TodoType.PAYMENT_DISBURSE);
    }

    @Test
    @DisplayName("默认: 出纳拿到付款待办, 拿不到报损 / 盘点审批")
    void cashierDefaultsToPaymentDisburse() {
        assertThat(typesFor("cashier")).contains(TodoType.PAYMENT_DISBURSE);
        assertThat(typesFor("cashier")).doesNotContain(TodoType.WASTAGE_APPROVAL, TodoType.STOCKTAKE_APPROVAL);
    }

    @Test
    @DisplayName("没有任何相关权限的岗位 (质检员 / 空岗位) 仍然是空")
    void rolesWithoutRelevantPermissionStayEmpty() {
        assertThat(typesFor("quality_inspector")).isEmpty();
        assertThat(typesFor(null)).isEmpty();
        assertThat(typesFor("not_a_role")).isEmpty();
    }

    @Test
    @DisplayName("工厂把报损审批授给生产经理 ⇒ 他的待办里出报损; 财务经理的不出 (跟着权限配置走)")
    void wastageFollowsFactoryConfiguration() {
        when(thresholds.getString(F, ThresholdKeys.INVENTORY_PERMISSION_WASTAGE_APPROVE, null))
                .thenReturn("production_manager");
        assertThat(typesFor("production_manager")).contains(TodoType.WASTAGE_APPROVAL);
        assertThat(typesFor("finance_manager")).doesNotContain(TodoType.WASTAGE_APPROVAL);
    }

    @Test
    @DisplayName("强制插单审批待办: 有排程权限且 OA 当前节点点名了这个岗位 ⇒ 出一条, 带 instanceId / expectedNodeId")
    void forceInsertApprovalIsAggregated() {
        ApprovalWorkflowInstance inst = ApprovalWorkflowInstance.builder()
                .id("inst-1").factoryId(F).moduleCode("FORCE_INSERT").businessEntityId("PLAN-9")
                .status(ApprovalWorkflowInstance.InstanceStatus.RUNNING)
                .currentNodeIds(new ArrayList<>(List.of("approval_boss")))
                .initiatedAt(LocalDateTime.of(2026, 11, 11, 9, 0)).build();
        when(workflowEngine.findPendingForRole(eq(F), eq("factory_super_admin"), eq("FORCE_INSERT"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inst)));

        List<TodoItemDTO> todos = service.listTodos(F, "factory_super_admin");

        TodoItemDTO item = todos.stream().filter(t -> t.getType() == TodoType.FORCE_INSERT_APPROVAL)
                .findFirst().orElseThrow();
        assertThat(item.getRefId()).isEqualTo("PLAN-9");
        assertThat(item.getInstanceId()).isEqualTo("inst-1");
        assertThat(item.getExpectedNodeId()).isEqualTo("approval_boss");
        assertThat(item.isNeedDetail()).isFalse();
    }

    @Test
    @DisplayName("阴性: 没有排程 / 生产权限的岗位不会去查强制插单 (质检员), 也不出这类待办")
    void noForceInsertForRolesWithoutSchedulingPermission() {
        assertThat(service.listTodos(F, "quality_inspector")).isEmpty();
        verify(workflowEngine, never()).findPendingForRole(anyString(), anyString(), eq("FORCE_INSERT"), any(Pageable.class));
    }
}
