package com.cretas.aims.service.product;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductRetirementGuard} 单测。
 *
 * <p><b>为什么用「会按状态过滤的假仓库」而不是固定返回值</b>: 如果 stub 无条件返回一串订单号,
 * 「只有已完成/已取消的订单 → 放行」这条断言就是<b>恒真式</b> —— 它测不到白名单, 只测到 stub。
 * 这里的 {@link #stubOrders} 拿着调用方传进来的 {@code statuses} 真的去过滤 fixture,
 * 于是白名单选错(比如误用 {@code SO_ALL} 或含 COMPLETED 的集合)会让那条断言变红。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductRetirementGuardTest {

    @Mock
    SalesOrderItemRepository salesOrderItemRepository;

    private ProductRetirementGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ProductRetirementGuard(salesOrderItemRepository);
    }

    // ==================== fixtures ====================

    private ProductType activeSku() {
        ProductType p = new ProductType();
        p.setId("PT-BUTTER-CHICKEN-800G");
        p.setFactoryId("F006");
        p.setName("黄油鸡-成品800g");
        p.setIsActive(true);
        return p;
    }

    private ProductType retiredSku() {
        ProductType p = activeSku();
        p.setIsActive(false);
        return p;
    }

    /**
     * 假仓库: 用调用方传进来的 statuses 真的过滤 fixture。
     * 这样白名单本身就是被测对象, 而不是被 stub 绕过去。
     */
    private void stubOrders(Map<String, SalesOrderStatus> fixture) {
        when(salesOrderItemRepository.findInFlightOrderNumbersByProduct(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Collection<SalesOrderStatus> statuses = invocation.getArgument(2);
                    return fixture.entrySet().stream()
                            .filter(e -> statuses.contains(e.getValue()))
                            .map(Map.Entry::getKey)
                            .sorted()
                            .toList();
                });
    }

    /** 生产实测那两张单 (2026-08-28 F006, SOP-20260730-01-黄油鸡-成品800g)。 */
    private Map<String, SalesOrderStatus> productionIncidentOrders() {
        Map<String, SalesOrderStatus> fixture = new LinkedHashMap<>();
        fixture.put("SO-20260812-0001", SalesOrderStatus.FINANCE_APPROVED);
        fixture.put("SO-20260812-0002", SalesOrderStatus.FINANCE_APPROVED);
        return fixture;
    }

    // ==================== 正向: 有在飞订单 → 拦 ====================

    @Test
    void blocksRetirementWhenInFlightSalesOrdersExistAndNamesThem() {
        stubOrders(productionIncidentOrders());

        assertThatThrownBy(() -> guard.assertRetirable(activeSku(), Boolean.FALSE))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getErrorCode()).isEqualTo(ProductRetirementGuard.ERROR_CODE);
                    assertThat(business.getSeverity()).isEqualTo("BLOCKING");
                    assertThat(business.getHintTarget()).isEqualTo("/sales/orders");
                    // hint 必须可执行 —— 告诉用户下一步做什么, 不是只说「有订单」
                    assertThat(business.getActionHint()).contains("销售订单");
                    // 🔴 必须带真实订单号, 否则用户拿到异常也不知道去看哪几张单
                    assertThat(business.getMessage())
                            .contains("SO-20260812-0001")
                            .contains("SO-20260812-0002")
                            .contains("黄油鸡-成品800g")
                            .contains("2 张");
                });
    }

    @Test
    void listsAtMostFiveOrderNumbersButReportsTheTrueTotal() {
        Map<String, SalesOrderStatus> fixture = new LinkedHashMap<>();
        for (int i = 1; i <= 7; i++) {
            fixture.put(String.format("SO-20260812-%04d", i), SalesOrderStatus.CONFIRMED);
        }
        stubOrders(fixture);

        assertThatThrownBy(() -> guard.assertRetirable(activeSku(), Boolean.FALSE))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    String message = error.getMessage();
                    assertThat(message).contains("还有 7 张");
                    assertThat(message).contains("等 7 张");
                    assertThat(message).contains("SO-20260812-0001");
                    assertThat(message).contains("SO-20260812-0005");
                    // 第 6、7 条被折叠掉
                    assertThat(message).doesNotContain("SO-20260812-0006");
                    assertThat(message).doesNotContain("SO-20260812-0007");
                });
    }

    // ==================== 阴性对照 1: 没有在飞订单 → 放行 ====================

    @Test
    void allowsRetirementWhenNoSalesOrdersReferenceTheSku() {
        stubOrders(Map.of());

        assertThatCode(() -> guard.assertRetirable(activeSku(), Boolean.FALSE))
                .doesNotThrowAnyException();
    }

    // ==================== 阴性对照 2: 只有已完成/已取消的单 → 放行 ====================

    @Test
    void allowsRetirementWhenOnlyTerminalOrdersReferenceTheSku() {
        Map<String, SalesOrderStatus> fixture = new LinkedHashMap<>();
        fixture.put("SO-DONE-0001", SalesOrderStatus.COMPLETED);
        fixture.put("SO-DEAD-0002", SalesOrderStatus.CANCELLED);
        fixture.put("SO-DRAFT-0003", SalesOrderStatus.DRAFT);
        fixture.put("SO-REJECT-0004", SalesOrderStatus.FINANCE_REJECTED);
        stubOrders(fixture);

        // 证明「不是有订单就拦」—— 拦的依据是状态白名单
        assertThatCode(() -> guard.assertRetirable(activeSku(), Boolean.FALSE))
                .doesNotThrowAnyException();
    }

    /**
     * 白名单口径本身的钉子。上一条测「终结态放行」, 这一条测「在飞态确实在名单里」——
     * 两条一起才说明白名单既不过宽也不过窄。
     */
    @Test
    void whitelistCoversExactlyTheStatesThatStillOweFulfilment() {
        stubOrders(Map.of());
        guard.assertRetirable(activeSku(), Boolean.FALSE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<SalesOrderStatus>> captor =
                ArgumentCaptor.forClass(Collection.class);
        verify(salesOrderItemRepository)
                .findInFlightOrderNumbersByProduct(anyString(), anyString(), captor.capture());

        assertThat(captor.getValue()).containsExactlyInAnyOrder(
                SalesOrderStatus.CONFIRMED,
                SalesOrderStatus.PENDING_FINANCE_REVIEW,
                SalesOrderStatus.FINANCE_APPROVED,
                SalesOrderStatus.PROCESSING,
                SalesOrderStatus.PARTIAL_DELIVERED);
    }

    // ==================== 阴性对照 3: 上架方向 → 放行 ====================

    @Test
    void allowsReactivationEvenWithInFlightOrders() {
        stubOrders(productionIncidentOrders());

        assertThatCode(() -> guard.assertRetirable(retiredSku(), Boolean.TRUE))
                .doesNotThrowAnyException();
        // 上架根本不该去查订单
        verify(salesOrderItemRepository, never())
                .findInFlightOrderNumbersByProduct(anyString(), anyString(), any());
    }

    // ==================== 阴性对照 4: 本来就是 false, 再写一次 false → 放行 ====================

    @Test
    void allowsIdempotentWriteOnAlreadyRetiredSku() {
        stubOrders(productionIncidentOrders());

        assertThatCode(() -> guard.assertRetirable(retiredSku(), Boolean.FALSE))
                .doesNotThrowAnyException();
        verify(salesOrderItemRepository, never())
                .findInFlightOrderNumbersByProduct(anyString(), anyString(), any());
    }

    // ==================== 阴性对照 5: 不动状态字段 → 放行 ====================

    @Test
    void allowsUpdatesThatDoNotTouchTheActiveFlag() {
        stubOrders(productionIncidentOrders());

        // newIsActive == null = 「这次不改状态」, 例如只改名字。⛔ 不该被下架闸堵住。
        assertThatCode(() -> guard.assertRetirable(activeSku(), null))
                .doesNotThrowAnyException();
        verify(salesOrderItemRepository, never())
                .findInFlightOrderNumbersByProduct(anyString(), anyString(), any());
    }

    @Test
    void nullProductIsANoOp() {
        assertThatCode(() -> guard.assertRetirable(null, Boolean.FALSE)).doesNotThrowAnyException();
    }

    // ==================== 工厂隔离 ====================

    @Test
    void scopesTheLookupToTheOwningFactoryAndProduct() {
        stubOrders(Map.of());
        guard.assertRetirable(activeSku(), Boolean.FALSE);

        verify(salesOrderItemRepository).findInFlightOrderNumbersByProduct(
                org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("PT-BUTTER-CHICKEN-800G"),
                any());
    }

    /** 仓库返回 null (防御) 时不该 NPE。 */
    @Test
    void nullRepositoryResultIsTreatedAsNoInFlightOrders() {
        when(salesOrderItemRepository.findInFlightOrderNumbersByProduct(anyString(), anyString(), any()))
                .thenReturn(null);

        assertThatCode(() -> guard.assertRetirable(activeSku(), Boolean.FALSE))
                .doesNotThrowAnyException();
    }

    /** 排序稳定 —— 消息里订单号的顺序不该随查询返回顺序漂。 */
    @Test
    void ordersAppearInTheOrderTheRepositoryReturnsThem() {
        when(salesOrderItemRepository.findInFlightOrderNumbersByProduct(anyString(), anyString(), any()))
                .thenReturn(List.of("SO-A", "SO-B"));

        assertThatThrownBy(() -> guard.assertRetirable(activeSku(), Boolean.FALSE))
                .hasMessageContaining("SO-A、SO-B");
    }
}
