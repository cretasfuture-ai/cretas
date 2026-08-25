package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.processentry.FinishedGoodsStockItem;
import com.cretas.aims.dto.sales.SalesProductInventorySummaryDTO;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SalesControllerProductInventorySummaryTest {

    @Test
    void aggregatesFinishedAndSemiFinishedAvailableInventoryByProduct() {
        FinishedGoodsFeedService finishedGoods = mock(FinishedGoodsFeedService.class);
        WipInventoryService wipInventory = mock(WipInventoryService.class);
        SalesController controller = controller();
        ReflectionTestUtils.setField(controller, "finishedGoodsFeedService", finishedGoods);
        ReflectionTestUtils.setField(controller, "wipInventoryService", wipInventory);

        when(finishedGoods.listAvailableForFeed("F006", null)).thenReturn(List.of(
                FinishedGoodsStockItem.builder().productTypeId("FG-1").availableQuantity(new BigDecimal("6")).unit("box").build(),
                FinishedGoodsStockItem.builder().productTypeId("FG-1").availableQuantity(new BigDecimal("4")).unit("box").build()));
        when(wipInventory.listWipByFactory("F006", null, null)).thenReturn(List.of(
                WipRowDTO.builder().productTypeId("SEMI-1").availableQuantity(new BigDecimal("2.5")).unit("kg").build(),
                WipRowDTO.builder().productTypeId("SEMI-1").availableQuantity(BigDecimal.ZERO).unit("kg").build()));

        List<SalesProductInventorySummaryDTO> rows = controller.productInventorySummary("F006").getData();

        assertThat(rows).extracting(SalesProductInventorySummaryDTO::getProductTypeId)
                .containsExactly("FG-1", "SEMI-1");
        assertThat(rows.get(0).getAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(rows.get(1).getAvailableQuantity()).isEqualByComparingTo("2.5");
    }

    /**
     * 🔴 同一个单位的两种写法必须合并 —— 判不等在这个接口上是<b>静默丢量</b>, 不是报错。
     *
     * <p>成品批次那侧与 WIP 那侧的单位来自不同录入路径, 一边存英文码一边存中文名是常态
     * (LIUSHANMEN 2026-07-30: 调拨把「只」存成 {@code pcs}, 报工按字面比较看不见整批 501 只)。
     * 这里曾是 {@code Objects.equals(existing.getUnit(), unit)}: {@code case} 与「箱」判不等,
     * 那 4 箱就<b>一声不吭地不进汇总</b> —— 选品弹窗里这个产品的可用库存少一截。
     */
    @Test
    void mergesTwoWritingsOfTheSameUnitInsteadOfSilentlyDroppingOne() {
        FinishedGoodsFeedService finishedGoods = mock(FinishedGoodsFeedService.class);
        WipInventoryService wipInventory = mock(WipInventoryService.class);
        UnitContractService unitContract = mock(UnitContractService.class);
        when(unitContract.storageUnit("F006", "case")).thenReturn("case");
        when(unitContract.storageUnit("F006", "箱")).thenReturn("case");

        SalesController controller = controller();
        ReflectionTestUtils.setField(controller, "finishedGoodsFeedService", finishedGoods);
        ReflectionTestUtils.setField(controller, "wipInventoryService", wipInventory);
        ReflectionTestUtils.setField(controller, "unitContractService", unitContract);

        when(finishedGoods.listAvailableForFeed("F006", null)).thenReturn(List.of(
                FinishedGoodsStockItem.builder().productTypeId("FG-1")
                        .availableQuantity(new BigDecimal("6")).unit("case").build()));
        when(wipInventory.listWipByFactory("F006", null, null)).thenReturn(List.of(
                WipRowDTO.builder().productTypeId("FG-1")
                        .availableQuantity(new BigDecimal("4")).unit("箱").build()));

        List<SalesProductInventorySummaryDTO> rows = controller.productInventorySummary("F006").getData();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAvailableQuantity())
                .as("case 与「箱」是同一个单位的两种写法 —— 判不等的后果是那 4 箱被静默丢掉")
                .isEqualByComparingTo("10");
    }

    /**
     * ⛔ 阴性对照: 契约<b>刻意</b>判不等的两个单位不许被合并。
     *
     * <p>「只 / 个 / 件」编在同一个码上但各自保留字面({@code storageUnit} 规则 2), 因为
     * 「一只鸡 = 一件包材」是工厂才能下的判断, 不是系统该替它下的(#1976 已定案)。
     * 这条对照钉住的是「不许图省事换成 {@code areEquivalent}」—— 那个方法只比
     * {@code normalize().code()}, 会把这三个合并成一个数。
     */
    @Test
    void doesNotMergeUnitsTheContractDeliberatelyKeepsDistinct() {
        FinishedGoodsFeedService finishedGoods = mock(FinishedGoodsFeedService.class);
        WipInventoryService wipInventory = mock(WipInventoryService.class);
        UnitContractService unitContract = mock(UnitContractService.class);
        when(unitContract.storageUnit("F006", "只")).thenReturn("只");
        when(unitContract.storageUnit("F006", "件")).thenReturn("件");

        SalesController controller = controller();
        ReflectionTestUtils.setField(controller, "finishedGoodsFeedService", finishedGoods);
        ReflectionTestUtils.setField(controller, "wipInventoryService", wipInventory);
        ReflectionTestUtils.setField(controller, "unitContractService", unitContract);

        when(finishedGoods.listAvailableForFeed("F006", null)).thenReturn(List.of(
                FinishedGoodsStockItem.builder().productTypeId("FG-1")
                        .availableQuantity(new BigDecimal("6")).unit("只").build()));
        when(wipInventory.listWipByFactory("F006", null, null)).thenReturn(List.of(
                WipRowDTO.builder().productTypeId("FG-1")
                        .availableQuantity(new BigDecimal("4")).unit("件").build()));

        List<SalesProductInventorySummaryDTO> rows = controller.productInventorySummary("F006").getData();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAvailableQuantity())
                .as("「只」与「件」是契约刻意分开的两个单位, 合并等于替工厂断定「一只 = 一件」")
                .isEqualByComparingTo("6");
    }

    /**
     * 契约服务缺失(直接 new 出来的控制器)时回落字面比较 —— 与注入前的旧行为一致,
     * 不许因为拿不到契约就把两种写法当成同一个。
     */
    @Test
    void fallsBackToLiteralComparisonWhenContractServiceIsAbsent() {
        FinishedGoodsFeedService finishedGoods = mock(FinishedGoodsFeedService.class);
        WipInventoryService wipInventory = mock(WipInventoryService.class);

        SalesController controller = controller(); // unitContractService 保持 null
        ReflectionTestUtils.setField(controller, "finishedGoodsFeedService", finishedGoods);
        ReflectionTestUtils.setField(controller, "wipInventoryService", wipInventory);

        when(finishedGoods.listAvailableForFeed("F006", null)).thenReturn(List.of(
                FinishedGoodsStockItem.builder().productTypeId("FG-1")
                        .availableQuantity(new BigDecimal("6")).unit("case").build()));
        when(wipInventory.listWipByFactory("F006", null, null)).thenReturn(List.of(
                WipRowDTO.builder().productTypeId("FG-1")
                        .availableQuantity(new BigDecimal("4")).unit("箱").build()));

        List<SalesProductInventorySummaryDTO> rows = controller.productInventorySummary("F006").getData();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAvailableQuantity()).isEqualByComparingTo("6");
    }

    private static SalesController controller() {
        return new SalesController(
                mock(SalesService.class),
                mock(MobileService.class),
                mock(PermissionService.class),
                mock(UserRepository.class),
                mock(SalesOrderRepository.class),
                mock(BusinessLinkRepository.class),
                mock(ProductTypeRepository.class),
                mock(FinishedGoodsBatchRepository.class),
                mock(GrossMarginRedlineService.class),
                mock(EstimatePriceCheckService.class),
                mock(SalesPriceAdjustmentService.class),
                mock(ProductPackagingSpecService.class));
    }
}
