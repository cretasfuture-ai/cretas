package com.cretas.aims.service.product;

import com.cretas.aims.ai.tool.impl.dataop.ProductUpdateTool;
import com.cretas.aims.ai.tool.impl.restaurant.RestaurantDishDeleteTool;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.impl.ProductTypeServiceImpl;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.workflow.WorkflowUnitReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下架闸的<b>接线</b>测试 —— 每条断言都跑在产品真实入口上, 不是直接调 guard。
 *
 * <p>「机制在、只是没接上」是本仓反复踩的形态: guard 自己的单测全绿, 而生产上那条路径
 * 根本没调它。所以这里逐个入口驱动:
 * <ol>
 *   <li>{@code ProductTypeServiceImpl.updateProductType} —— REST PUT/PATCH 产品详情</li>
 *   <li>{@code ProductTypeServiceImpl.updateProductTypesStatus} —— REST 批量启停</li>
 *   <li>{@code ProductUpdateTool} —— AI 工具 {@code product_update} (2026-08-28 生产事故那条路)</li>
 *   <li>{@code RestaurantDishDeleteTool} —— AI 工具 {@code restaurant_dish_delete}</li>
 * </ol>
 * 每个入口都配一条阴性对照(无在飞订单 → 放行且真的写了库)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductRetirementGuardWiringTest {

    private static final String FACTORY = "F006";
    private static final String SKU_ID = "PT-BUTTER-CHICKEN-800G";
    private static final String SKU_NAME = "黄油鸡-成品800g";

    @Mock ProductTypeRepository productTypeRepository;
    @Mock CustomerRepository customerRepository;
    @Mock WorkflowUnitReviewService workflowUnitReviewService;
    @Mock ProductSpecificationConversionSyncService conversionSyncService;
    @Mock ProductPackagingSpecService packagingSpecService;
    @Mock SalesOrderItemRepository salesOrderItemRepository;

    private ProductRetirementGuard guard;
    private ProductTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        guard = new ProductRetirementGuard(salesOrderItemRepository);
        service = new ProductTypeServiceImpl(productTypeRepository, new ObjectMapper(), customerRepository,
                workflowUnitReviewService, conversionSyncService, packagingSpecService, guard);
        when(productTypeRepository.save(any(ProductType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ProductType activeSku() {
        ProductType p = new ProductType();
        p.setId(SKU_ID);
        p.setFactoryId(FACTORY);
        p.setCode("CPF0060150");
        p.setName(SKU_NAME);
        p.setUnit("袋");
        p.setIsActive(true);
        return p;
    }

    /** 假仓库: 用调用方传进来的 statuses 真的过滤, 白名单因此是被测对象而不是被 stub 绕过。 */
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

    private Map<String, SalesOrderStatus> twoApprovedOrders() {
        Map<String, SalesOrderStatus> fixture = new LinkedHashMap<>();
        fixture.put("SO-20260812-0001", SalesOrderStatus.FINANCE_APPROVED);
        fixture.put("SO-20260812-0002", SalesOrderStatus.FINANCE_APPROVED);
        return fixture;
    }

    private Map<String, SalesOrderStatus> onlyTerminalOrders() {
        Map<String, SalesOrderStatus> fixture = new LinkedHashMap<>();
        fixture.put("SO-DONE-0001", SalesOrderStatus.COMPLETED);
        fixture.put("SO-DEAD-0002", SalesOrderStatus.CANCELLED);
        return fixture;
    }

    // ==================== 入口 1: REST 单条更新 ====================

    @Test
    void restUpdateBlocksRetirementWhenOrdersAreInFlight() {
        ProductType sku = activeSku();
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        stubOrders(twoApprovedOrders());

        ProductTypeDTO dto = ProductTypeDTO.builder().isActive(false).build();

        assertThatThrownBy(() -> service.updateProductType(FACTORY, SKU_ID, dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getErrorCode()).isEqualTo(ProductRetirementGuard.ERROR_CODE);
                    assertThat(business.getMessage())
                            .contains("SO-20260812-0001")
                            .contains("SO-20260812-0002");
                });

        // 闸拦住了就不该落库, 也不该把实体改脏
        verify(productTypeRepository, never()).save(any(ProductType.class));
        assertThat(sku.getIsActive()).isTrue();
    }

    @Test
    void restUpdateAllowsRetirementWhenNoOrdersAreInFlight() {
        ProductType sku = activeSku();
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        stubOrders(Map.of());

        ProductTypeDTO dto = ProductTypeDTO.builder().isActive(false).build();

        assertThatCode(() -> service.updateProductType(FACTORY, SKU_ID, dto)).doesNotThrowAnyException();
        assertThat(sku.getIsActive()).isFalse();
        verify(productTypeRepository).save(any(ProductType.class));
    }

    @Test
    void restUpdateAllowsRetirementWhenOnlyTerminalOrdersExist() {
        ProductType sku = activeSku();
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        stubOrders(onlyTerminalOrders());

        ProductTypeDTO dto = ProductTypeDTO.builder().isActive(false).build();

        assertThatCode(() -> service.updateProductType(FACTORY, SKU_ID, dto)).doesNotThrowAnyException();
        assertThat(sku.getIsActive()).isFalse();
    }

    @Test
    void restUpdateAllowsReactivationWithInFlightOrders() {
        ProductType sku = activeSku();
        sku.setIsActive(false);
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        stubOrders(twoApprovedOrders());

        ProductTypeDTO dto = ProductTypeDTO.builder().isActive(true).build();

        assertThatCode(() -> service.updateProductType(FACTORY, SKU_ID, dto)).doesNotThrowAnyException();
        assertThat(sku.getIsActive()).isTrue();
    }

    @Test
    void restUpdateAllowsRenamingARetiredSkuWithInFlightOrders() {
        ProductType sku = activeSku();
        sku.setIsActive(false);
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        when(productTypeRepository.existsByFactoryIdAndNormalizedNameExcludingId(anyString(), anyString(), anyString()))
                .thenReturn(false);
        stubOrders(twoApprovedOrders());

        // 已下架商品改名 + 重复写 false —— ⛔ 不该被下架闸堵死
        ProductTypeDTO dto = ProductTypeDTO.builder().name("黄油鸡-成品800g(旧)").isActive(false).build();

        assertThatCode(() -> service.updateProductType(FACTORY, SKU_ID, dto)).doesNotThrowAnyException();
        assertThat(sku.getName()).isEqualTo("黄油鸡-成品800g(旧)");
    }

    @Test
    void restUpdateThatDoesNotTouchStatusIsNeverBlocked() {
        ProductType sku = activeSku();
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        when(productTypeRepository.existsByFactoryIdAndNormalizedNameExcludingId(anyString(), anyString(), anyString()))
                .thenReturn(false);
        stubOrders(twoApprovedOrders());

        ProductTypeDTO dto = ProductTypeDTO.builder().name("黄油鸡-成品800g 改名").build();

        assertThatCode(() -> service.updateProductType(FACTORY, SKU_ID, dto)).doesNotThrowAnyException();
        assertThat(sku.getIsActive()).isTrue();
        verify(salesOrderItemRepository, never())
                .findInFlightOrderNumbersByProduct(anyString(), anyString(), any());
    }

    // ==================== 入口 2: REST 批量启停 ====================

    @Test
    void bulkStatusUpdateBlocksRetirementWhenOrdersAreInFlight() {
        ProductType sku = activeSku();
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        stubOrders(twoApprovedOrders());

        assertThatThrownBy(() -> service.updateProductTypesStatus(FACTORY, List.of(SKU_ID), false))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .contains("SO-20260812-0001")
                        .contains("SO-20260812-0002"));

        verify(productTypeRepository, never()).save(any(ProductType.class));
        assertThat(sku.getIsActive()).isTrue();
    }

    @Test
    void bulkStatusUpdateAllowsRetirementWhenNoOrdersAreInFlight() {
        ProductType sku = activeSku();
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        stubOrders(Map.of());

        assertThatCode(() -> service.updateProductTypesStatus(FACTORY, List.of(SKU_ID), false))
                .doesNotThrowAnyException();
        assertThat(sku.getIsActive()).isFalse();
        verify(productTypeRepository).save(any(ProductType.class));
    }

    @Test
    void bulkStatusUpdateAllowsBulkReactivationWithInFlightOrders() {
        ProductType sku = activeSku();
        sku.setIsActive(false);
        when(productTypeRepository.findById(SKU_ID)).thenReturn(Optional.of(sku));
        stubOrders(twoApprovedOrders());

        assertThatCode(() -> service.updateProductTypesStatus(FACTORY, List.of(SKU_ID), true))
                .doesNotThrowAnyException();
        assertThat(sku.getIsActive()).isTrue();
    }

    // ==================== 入口 3: AI 工具 product_update (生产事故那条路) ====================

    private ProductUpdateTool productUpdateTool() {
        ProductUpdateTool tool = new ProductUpdateTool();
        ReflectionTestUtils.setField(tool, "productTypeRepository", productTypeRepository);
        ReflectionTestUtils.setField(tool, "productRetirementGuard", guard);
        return tool;
    }

    @Test
    void aiProductUpdateToolBlocksRetirementWhenOrdersAreInFlight() {
        ProductType sku = activeSku();
        when(productTypeRepository.findByIdAndFactoryId(SKU_ID, FACTORY)).thenReturn(Optional.of(sku));
        stubOrders(twoApprovedOrders());

        ProductUpdateTool tool = productUpdateTool();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                tool, "doExecute", FACTORY,
                Map.of("productTypeId", SKU_ID, "status", "DISCONTINUED"),
                Map.of("factoryId", FACTORY)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .contains("SO-20260812-0001")
                        .contains("SO-20260812-0002")
                        .contains(SKU_NAME));

        verify(productTypeRepository, never()).save(any(ProductType.class));
        assertThat(sku.getIsActive()).isTrue();
    }

    @Test
    void aiProductUpdateToolAllowsRetirementWhenNoOrdersAreInFlight() {
        ProductType sku = activeSku();
        when(productTypeRepository.findByIdAndFactoryId(SKU_ID, FACTORY)).thenReturn(Optional.of(sku));
        stubOrders(Map.of());

        ProductUpdateTool tool = productUpdateTool();
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
                tool, "doExecute", FACTORY,
                Map.of("productTypeId", SKU_ID, "status", "DISCONTINUED"),
                Map.of("factoryId", FACTORY));

        assertThat(result).isNotNull();
        assertThat(sku.getIsActive()).isFalse();
        verify(productTypeRepository).save(any(ProductType.class));
    }

    @Test
    void aiProductUpdateToolAllowsReactivationWithInFlightOrders() {
        ProductType sku = activeSku();
        sku.setIsActive(false);
        when(productTypeRepository.findByIdAndFactoryId(SKU_ID, FACTORY)).thenReturn(Optional.of(sku));
        stubOrders(twoApprovedOrders());

        ProductUpdateTool tool = productUpdateTool();
        ReflectionTestUtils.invokeMethod(tool, "doExecute", FACTORY,
                Map.of("productTypeId", SKU_ID, "status", "ACTIVE"),
                Map.of("factoryId", FACTORY));

        assertThat(sku.getIsActive()).isTrue();
    }

    @Test
    void aiProductUpdateToolRefusesToRetireWhenTheGuardIsNotWired() {
        ProductType sku = activeSku();
        when(productTypeRepository.findByIdAndFactoryId(SKU_ID, FACTORY)).thenReturn(Optional.of(sku));

        ProductUpdateTool tool = new ProductUpdateTool();
        ReflectionTestUtils.setField(tool, "productTypeRepository", productTypeRepository);
        // 闸没注入 —— 必须失败得刺眼, ⛔ 不能静默放行
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                tool, "doExecute", FACTORY,
                Map.of("productTypeId", SKU_ID, "status", "DISCONTINUED"),
                Map.of("factoryId", FACTORY)))
                .isInstanceOf(IllegalStateException.class);
        verify(productTypeRepository, never()).save(any(ProductType.class));
    }

    // ==================== 入口 4: AI 工具 restaurant_dish_delete ====================

    private RestaurantDishDeleteTool dishDeleteTool() {
        RestaurantDishDeleteTool tool = new RestaurantDishDeleteTool();
        ReflectionTestUtils.setField(tool, "productTypeRepository", productTypeRepository);
        ReflectionTestUtils.setField(tool, "productRetirementGuard", guard);
        return tool;
    }

    @Test
    void restaurantDishDeleteToolBlocksRetirementWhenOrdersAreInFlight() {
        ProductType dish = activeSku();
        when(productTypeRepository.findByIdAndFactoryId(SKU_ID, FACTORY)).thenReturn(Optional.of(dish));
        stubOrders(twoApprovedOrders());

        RestaurantDishDeleteTool tool = dishDeleteTool();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                tool, "doExecute", FACTORY,
                Map.of("id", SKU_ID),
                Map.of("factoryId", FACTORY)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(error.getMessage()).contains("SO-20260812-0001"));

        verify(productTypeRepository, never()).save(any(ProductType.class));
        assertThat(dish.getIsActive()).isTrue();
    }

    @Test
    void restaurantDishDeleteToolAllowsRetirementWhenNoOrdersAreInFlight() {
        ProductType dish = activeSku();
        when(productTypeRepository.findByIdAndFactoryId(SKU_ID, FACTORY)).thenReturn(Optional.of(dish));
        stubOrders(Map.of());

        RestaurantDishDeleteTool tool = dishDeleteTool();
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
                tool, "doExecute", FACTORY, Map.of("id", SKU_ID), Map.of("factoryId", FACTORY));

        assertThat(result).isNotNull();
        assertThat(dish.getIsActive()).isFalse();
        verify(productTypeRepository).save(any(ProductType.class));
    }
}
