package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 销售商品行只允许成品和半成品，而且这道拦要在<b>后端</b>。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测)</h2>
 *
 * 判据：销售订单商品行只允许成品和半成品；原料/辅材/包材走 suppliedMaterials。
 *
 * 前端下拉和后端写闸必须使用同一销售订单口径；原料、包材或未知 ID 不能因绕过
 * 下拉、AI 工具直调接口或复制旧订单而进入商品行。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>判定<b>只用</b> {@code ProductCategory.isSalesOrderSellable}。
 *       ⛔ 本守卫不重复那个条件，否则就是本仓最高频的「两处口径打架」。</li>
 *   <li>原料/辅料/包材走 suppliedMaterials；查不到的商品 ID 失败关闭。</li>
 * </ul>
 */
class SalesOrderSellableGuardTest {

    private static final String F = "F006";
    private static final String SEMI = "17c929e1-9d90-408f-b064-7970b9b7dac0";
    private static final String FG = "eb0aa47b-a5dd-49dc-af20-bf48ce8e1207";
    private static final String RAW_MATERIAL = "RMT_1777441647274";

    private SalesServiceImpl service;
    private ProductTypeRepository repo;

    @BeforeEach
    void setUp() {
        service = mock(SalesServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        repo = mock(ProductTypeRepository.class);
        // 默认: 查不到 (物料字典那三类走这条)
        when(repo.findByIdAndFactoryId(anyString(), eq(F))).thenReturn(Optional.empty());
        when(repo.findByIdAndFactoryId(eq(SEMI), eq(F)))
                .thenReturn(Optional.of(productType(SEMI, "SOP-20260817-01-黄油鸡-处理后半成品", "SEMI_FINISHED")));
        when(repo.findByIdAndFactoryId(eq(FG), eq(F)))
                .thenReturn(Optional.of(productType(FG, "SOP-20260817-01-黄油鸡-成品800g", "FINISHED_PRODUCT")));
        ReflectionTestUtils.setField(service, "productTypeRepository", repo);
    }

    private static ProductType productType(String id, String name, String category) {
        ProductType pt = new ProductType();
        pt.setId(id);
        pt.setName(name);
        pt.setProductCategory(category);
        return pt;
    }

    private static CreateSalesOrderRequest.SalesOrderItemDTO item(String productTypeId) {
        CreateSalesOrderRequest.SalesOrderItemDTO it = new CreateSalesOrderRequest.SalesOrderItemDTO();
        it.setProductTypeId(productTypeId);
        it.setQuantity(BigDecimal.ONE);
        it.setUnitPrice(BigDecimal.TEN);
        return it;
    }

    private void assertSellable(List<CreateSalesOrderRequest.SalesOrderItemDTO> items) {
        ReflectionTestUtils.invokeMethod(service, "assertItemsAreSellable", F, items);
    }

    @Test
    @DisplayName("阳性对照: 成品放行 (否则下面的「该拦」断言可能只是因为它拦了一切)")
    void finishedProductPasses() {
        assertDoesNotThrow(() -> assertSellable(List.of(item(FG))));
    }

    @Test
    @DisplayName("半成品允许作为销售订单成品行")
    void semiFinishedIsAllowed() {
        assertDoesNotThrow(() -> assertSellable(List.of(item(SEMI))));
    }

    @Test
    @DisplayName("原料必须走 suppliedMaterials，销售商品行失败关闭")
    void dictionaryMaterialsAreBlocked() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> assertSellable(List.of(item(RAW_MATERIAL))));
        assertEquals("SALES_ORDER_ITEM_NOT_SELLABLE", e.getErrorCode());
    }

    @Test
    @DisplayName("混单: 原料行被拒而成品和半成品均允许")
    void mixedOrderRejectsMaterialLine() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> assertSellable(List.of(item(FG), item(SEMI), item(RAW_MATERIAL))));
        assertEquals("SALES_ORDER_ITEM_NOT_SELLABLE", e.getErrorCode());
    }

    @Test
    @DisplayName("空/无 productTypeId 的行不炸 —— 守卫不该把没填完的草稿拦死")
    void blankLinesAreSkipped() {
        assertDoesNotThrow(() -> assertSellable(List.of(item(null), item(""))));
        assertDoesNotThrow(() -> assertSellable(List.of()));
    }
}
