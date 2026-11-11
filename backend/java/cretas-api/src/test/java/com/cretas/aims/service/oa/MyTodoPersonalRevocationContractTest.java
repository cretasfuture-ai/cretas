package com.cretas.aims.service.oa;

import com.cretas.aims.dto.oa.TodoItemDTO.TodoType;
import com.cretas.aims.entity.User;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
 * #1611 独立审查 MEDIUM: 去掉「我的待办」的财务门槛后, 唯一按个人看权限的那道检查没了;
 * 岗位路径 (roleHasPermission) 不叠加个人覆盖, 被平台单独收回财务模块的财务经理仍会读到财审待办。
 *
 * <p>入口改走「按具体用户」: 每类用 {@code hasPermission(user, code)} 判。这里让岗位层说「有」、
 * 个人层说「没有」(= 个人收回), 断言按用户出的类型为空, 且根本没去问岗位层。
 */
@DisplayName("我的待办按具体用户判权限, 个人收回生效 (MyTodoAggregatorServiceImpl)")
class MyTodoPersonalRevocationContractTest {

    private static final String F = "F-TODO";

    private MyTodoAggregatorServiceImpl service;
    private PermissionService permissions;

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
        permissions = mock(PermissionService.class);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
    }

    private static User financeManager() {
        User u = new User();
        u.setId(42L);
        u.setFactoryId(F);
        u.setRoleCode("finance_manager");
        return u;
    }

    @SuppressWarnings("unchecked")
    private Set<TodoType> typesForUser(User user) {
        return (Set<TodoType>) ReflectionTestUtils.invokeMethod(service, "typesForUser", F, user);
    }

    @Test
    @DisplayName("岗位有财务权限、个人被收回: 按用户一类都不出, 列表为空")
    void personalRevocationRemovesEveryType() {
        User user = financeManager();
        when(permissions.roleHasPermission(eq(F), anyString(), anyString())).thenReturn(true);
        when(permissions.hasPermission(eq(user), anyString())).thenReturn(false);

        assertThat(typesForUser(user)).isEmpty();
        assertThat(service.listTodosForUser(F, user)).isEmpty();
        verify(permissions, never()).roleHasPermission(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("阳性对照: 个人只保留财务读写时, 只出财务那几类, 不出付款 / 报损 / 强制插单")
    void personalGrantDecidesTypes() {
        User user = financeManager();
        when(permissions.hasPermission(eq(user), anyString())).thenReturn(false);
        when(permissions.hasPermission(eq(user), eq("finance:read_write"))).thenReturn(true);

        assertThat(typesForUser(user))
                .contains(TodoType.PURCHASE_FINANCE_REVIEW, TodoType.SALES_FINANCE_REVIEW,
                        TodoType.PRICE_ANOMALY, TodoType.RETURN_FINANCE_REVIEW)
                .doesNotContain(TodoType.PAYMENT_DISBURSE, TodoType.WASTAGE_APPROVAL,
                        TodoType.FORCE_INSERT_APPROVAL);
    }

    @Test
    @DisplayName("没有用户: 空列表, 不问任何权限")
    void nullUserGetsNothing() {
        assertThat(service.listTodosForUser(F, null)).isEmpty();
        verify(permissions, never()).hasPermission(any(), anyString());
    }
}
