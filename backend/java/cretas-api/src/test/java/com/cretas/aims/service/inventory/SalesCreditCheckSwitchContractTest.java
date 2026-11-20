package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.customer.CreditStatusDTO;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.CreditStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.crm.CustomerCreditCheckService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工厂纪律 A17 (Steve 2026-11-10): 「超信用额度检查」整体是一个功能开关, 默认关;
 * 开了以后工厂再选 WARN (只提示并留痕) 还是 BLOCK (拦截, 可特批);
 * 检查本身出错时不能当作通过, 要提示「额度未核对」。
 *
 * <p>跑真实 {@code createSalesOrder} 路径。阳性对照: 每个用例都断言订单是否真的保存 / 备注是否真的写进去,
 * 而不是只看有没有抛异常 (「没发生」的读数要配一个「一定会发生」的读数)。
 */
@DisplayName("A17 超信用额度检查开关 — createSalesOrder")
class SalesCreditCheckSwitchContractTest {

    final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    final SalesOrderItemRepository items = mock(SalesOrderItemRepository.class);
    final CustomerRepository customers = mock(CustomerRepository.class);
    final ProductTypeRepository products = mock(ProductTypeRepository.class);
    final CustomerCreditCheckService creditCheck = mock(CustomerCreditCheckService.class);
    final ThresholdResolverService thresholds = mock(ThresholdResolverService.class);
    final PermissionService permissions = mock(PermissionService.class);
    final UserRepository users = mock(UserRepository.class);

    final SalesServiceImpl service = new SalesServiceImpl(orders, items, mock(SalesDeliveryRecordRepository.class),
            mock(FinishedGoodsBatchRepository.class), customers, products, mock(ArApService.class),
            mock(ApplicationEventPublisher.class));

    private void prepare(String mode) {
        var em = mock(jakarta.persistence.EntityManager.class);
        var query = mock(jakarta.persistence.Query.class);
        ReflectionTestUtils.setField(service, "entityManager", em);
        ReflectionTestUtils.setField(service, "warehouseResolver", mock(com.cretas.aims.service.factory.WarehouseResolver.class));
        ReflectionTestUtils.setField(service, "customerCreditCheckService", creditCheck);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        ReflectionTestUtils.setField(service, "userRepository", users);
        if (mode != null) {
            ReflectionTestUtils.setField(service, "thresholdResolverService", thresholds);
            when(thresholds.getString(eq("F"), eq(ThresholdKeys.CREDIT_CHECK_MODE), anyString())).thenReturn(mode);
        }
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);
        var customer = new Customer();
        customer.setId("C");
        customer.setFactoryId("F");
        customer.setName("验收客户");
        when(customers.findByIdAndFactoryId("C", "F")).thenReturn(Optional.of(customer));
        var product = new ProductType();
        product.setId("P");
        product.setFactoryId("F");
        product.setName("验收成品");
        product.setProductCategory("FINISHED_PRODUCT");
        product.setUnit("个");
        product.setIsActive(true);
        product.setCreatedBy(1L);
        when(products.findByIdAndFactoryId("P", "F")).thenReturn(Optional.of(product));
        when(products.findById("P")).thenReturn(Optional.of(product));
        when(orders.save(any())).thenAnswer(i -> {
            SalesOrder o = i.getArgument(0);
            if (o.getId() == null) {
                o.setId("O");
            }
            return o;
        });
        when(items.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    }

    private CreateSalesOrderRequest request() {
        var request = new CreateSalesOrderRequest();
        request.setCustomerId("C");
        request.setProcessingMode(com.cretas.aims.entity.enums.SalesProcessingMode.STANDARD_SALE);
        request.setMaterialSupplyMode(com.cretas.aims.entity.enums.MaterialSupplyMode.FACTORY_SUPPLIED);
        var line = new CreateSalesOrderRequest.SalesOrderItemDTO();
        line.setProductTypeId("P");
        line.setProductName("验收成品");
        line.setQuantity(new BigDecimal("2"));
        line.setUnit("个");
        line.setUnitPrice(new BigDecimal("50"));
        line.setTaxRate(BigDecimal.ZERO);
        request.setItems(List.of(line));
        return request;
    }

    private void creditStatus(CreditStatus status, boolean exceeds) {
        when(creditCheck.checkCreditAvailable(eq("F"), eq("C"), any())).thenReturn(
                CreditStatusDTO.builder().creditStatus(status).exceeds(exceeds)
                        .available(new BigDecimal("10")).suggestedAction("请先收款").build());
    }

    private void grantOverrideTo(Long userId, boolean granted) {
        var user = new User();
        user.setId(userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(permissions.hasPermission(user, PermissionService.CREDIT_OVERRIDE_PERMISSION)).thenReturn(granted);
    }

    @Test
    @DisplayName("OFF(默认): 超额检查整体不做, 超额客户照常下单, 备注无留痕")
    void off_exceeds_notChecked() {
        prepare("OFF");
        creditStatus(CreditStatus.WARNING, true);

        SalesOrder created = service.createSalesOrder("F", request(), 1L);

        assertEquals("O", created.getId()); // 阳性对照: 订单真的保存了
        assertNull(created.getRemark());
    }

    @Test
    @DisplayName("🔴 OFF: 冻结(SUSPENDED)是账户状态不归这个开关管 —— 仍然 409, 订单不保存")
    void off_suspendedCustomer_stillBlocked() {
        prepare("OFF");
        creditStatus(CreditStatus.SUSPENDED, false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSalesOrder("F", request(), 1L));

        assertEquals(409, ex.getCode());
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("没有阈值中心 bean 时按 OFF: 超额放行, 冻结仍拦")
    void noResolver_isOff() {
        prepare(null);
        creditStatus(CreditStatus.WARNING, true);
        assertEquals("O", service.createSalesOrder("F", request(), 1L).getId());

        creditStatus(CreditStatus.SUSPENDED, false);
        assertThrows(BusinessException.class, () -> service.createSalesOrder("F", request(), 1L));
    }

    @Test
    @DisplayName("OFF + 信用检查服务缺失: 原有流程不受影响, 照常下单")
    void off_noCreditService_savesOrder() {
        prepare("OFF");
        ReflectionTestUtils.setField(service, "customerCreditCheckService", null);

        assertEquals("O", service.createSalesOrder("F", request(), 1L).getId());
    }

    @Test
    @DisplayName("WARN + 信用检查服务缺失 ⇒ 不静默放行: 订单备注写「额度未核对」")
    void warn_noCreditService_notesUnverified() {
        prepare("WARN");
        ReflectionTestUtils.setField(service, "customerCreditCheckService", null);

        SalesOrder created = service.createSalesOrder("F", request(), 1L);

        assertEquals("O", created.getId());
        assertTrue(created.getRemark().contains("额度未核对"), created.getRemark());
    }

    @Test
    @DisplayName("BLOCK + 信用检查服务缺失 ⇒ 409 额度未核对, 订单不保存")
    void block_noCreditService_throws409() {
        prepare("BLOCK");
        ReflectionTestUtils.setField(service, "customerCreditCheckService", null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSalesOrder("F", request(), 1L));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("额度未核对"), ex.getMessage());
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("WARN: 冻结客户同样 409 (冻结不是只提示)")
    void warn_suspended_blocked() {
        prepare("WARN");
        creditStatus(CreditStatus.SUSPENDED, false);

        assertThrows(BusinessException.class, () -> service.createSalesOrder("F", request(), 1L));
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("WARN: 超额只提示并留痕, 不拦, 备注里有「信用提示」")
    void warn_exceeds_savesWithNote() {
        prepare("WARN");
        creditStatus(CreditStatus.WARNING, true);

        SalesOrder created = service.createSalesOrder("F", request(), 1L);

        assertEquals("O", created.getId());
        assertTrue(created.getRemark().contains("[信用提示]"), created.getRemark());
    }

    @Test
    @DisplayName("WARN: 检查本身出错 ⇒ 不当作通过, 备注写「额度未核对」, 订单放行")
    void warn_checkFails_notesUnverified() {
        prepare("WARN");
        when(creditCheck.checkCreditAvailable(eq("F"), eq("C"), any())).thenThrow(new RuntimeException("库连不上"));

        SalesOrder created = service.createSalesOrder("F", request(), 1L);

        assertEquals("O", created.getId());
        assertTrue(created.getRemark().contains("额度未核对"), created.getRemark());
    }

    @Test
    @DisplayName("WARN: 额度正常 ⇒ 没有任何留痕")
    void warn_withinLimit_noNote() {
        prepare("WARN");
        creditStatus(CreditStatus.NORMAL, false);

        SalesOrder created = service.createSalesOrder("F", request(), 1L);

        assertNull(created.getRemark());
    }

    @Test
    @DisplayName("BLOCK: 超额且没有特批 ⇒ 409, 订单一行都没保存")
    void block_exceeds_throws409() {
        prepare("BLOCK");
        creditStatus(CreditStatus.WARNING, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSalesOrder("F", request(), 1L));

        assertEquals(409, ex.getCode());
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("BLOCK: 冻结的客户同样被拦")
    void block_suspended_throws409() {
        prepare("BLOCK");
        creditStatus(CreditStatus.SUSPENDED, false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSalesOrder("F", request(), 1L));

        assertEquals(409, ex.getCode());
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("BLOCK: 检查本身出错 ⇒ 拦截并说「额度未核对」, 不当作通过")
    void block_checkFails_throws409Unverified() {
        prepare("BLOCK");
        when(creditCheck.checkCreditAvailable(eq("F"), eq("C"), any())).thenThrow(new RuntimeException("库连不上"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSalesOrder("F", request(), 1L));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("额度未核对"), ex.getMessage());
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("BLOCK + 有信用特批权限者填了原因 ⇒ 放行并把特批原因留在订单备注")
    void block_overrideByAuthorisedUser_savesWithNote() {
        prepare("BLOCK");
        creditStatus(CreditStatus.WARNING, true);
        grantOverrideTo(7L, true);
        var request = request();
        request.setCreditOverrideReason("老客户本周五回款");

        SalesOrder created = service.createSalesOrder("F", request, 7L);

        assertEquals("O", created.getId());
        assertTrue(created.getRemark().contains("[信用特批]") && created.getRemark().contains("老客户本周五回款"),
                created.getRemark());
    }

    @Test
    @DisplayName("BLOCK + 填了原因但没有特批权限 ⇒ 仍然 409 (原因不是通行证, 权限才是)")
    void block_overrideWithoutAuthority_throws409() {
        prepare("BLOCK");
        creditStatus(CreditStatus.WARNING, true);
        grantOverrideTo(8L, false);
        var request = request();
        request.setCreditOverrideReason("我就想下单");

        assertThrows(BusinessException.class, () -> service.createSalesOrder("F", request, 8L));
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("BLOCK + 有权限但没填原因 ⇒ 409 (特批必须留原因)")
    void block_authorisedButNoReason_throws409() {
        prepare("BLOCK");
        creditStatus(CreditStatus.WARNING, true);
        grantOverrideTo(7L, true);

        assertThrows(BusinessException.class, () -> service.createSalesOrder("F", request(), 7L));
        verify(orders, never()).save(any());
    }
}
