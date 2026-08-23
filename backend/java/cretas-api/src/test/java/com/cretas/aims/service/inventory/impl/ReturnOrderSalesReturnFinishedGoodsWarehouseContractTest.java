package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测试同学 2026-08-07 报的「销售退回成功的成品显示会自动退回至成品仓，但是没有」。
 *
 * <p>prod 实证 (2026-08-19 查证): 三条完成过的销售退货单在日志里都打了
 * {@code 销售退货 不良品入库完成 ... warehouse=WH-LOG} —— 落的是**物流仓**, 而界面承诺的是
 * **成品仓**。F006 一直有 WH-FG 成品仓, {@code resolveFinishedGoodsId} 也一直存在。
 *
 * <p>本闸钉三件事:
 * <ol>
 *   <li><b>落点</b>: 销售退货的成品批次必须落 {@code resolveFinishedGoodsId} (成品仓),
 *       ⛔ 不是 {@code resolveLogisticsId} (物流仓)。</li>
 *   <li><b>隔离状态保留</b>: status 仍是 {@code DEFECTIVE} —— 退回来的货不可直接再卖,
 *       被 {@code status='AVAILABLE'} 的查询过滤掉**是设计意图**, 不是缺陷。
 *       这是一条**阴性对照**: 防止「修落点」时顺手把隔离也改掉。</li>
 *   <li><b>失败要阻断</b>: 入库异常不再被吞成 log.error 后照常 COMPLETED ——
 *       与同一方法里 PURCHASE_RETURN 分支的 fail-loud 纪律一致 (形态 D: 同一件事两份相反行为)。</li>
 * </ol>
 */
@DisplayName("销售退货 with-goods: 成品落成品仓 + 保持隔离 + 入库失败要阻断")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest {

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;
    @Mock private ArApService arApService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    private ReturnOrderServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String WH_FG = "wh-fg-f006";   // 成品仓 (正确落点)
    private static final String WH_LOG = "wh-log-f006"; // 物流仓 (旧的错误落点)
    private static final Long APPROVER = 1309L;

    @BeforeEach
    void setUp() {
        service = new ReturnOrderServiceImpl(
                returnOrderRepository, returnOrderItemRepository, arApService, applicationEventPublisher);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        ReflectionTestUtils.setField(service, "finishedGoodsBatchRepository", finishedGoodsBatchRepository);
        when(warehouseResolver.resolveFinishedGoodsId(FACTORY)).thenReturn(WH_FG);
        when(warehouseResolver.resolveLogisticsId(FACTORY)).thenReturn(WH_LOG);
    }

    /** 真实形状: prod 的 SALES_RETURN 行带 productTypeId + 正数量 (materialTypeId 恒为空)。 */
    private ReturnOrderItem productLine(String productTypeId, BigDecimal qty) {
        ReturnOrderItem it = new ReturnOrderItem();
        it.setProductTypeId(productTypeId);
        it.setItemName("SOP-20260730-01-黄油鸡-成品800g");
        it.setQuantity(qty);
        it.setUnitPrice(new BigDecimal("40.00"));
        it.setReason("质量问题");
        return it;
    }

    private ReturnOrder salesReturn(List<ReturnOrderItem> items) {
        ReturnOrder o = new ReturnOrder();
        o.setId("RO-" + System.nanoTime());
        o.setFactoryId(FACTORY);
        o.setReturnNumber("RT-SAL-20260807-0001");
        o.setReturnType(ReturnType.SALES_RETURN);
        o.setWithGoods(true);
        o.setStatus(ReturnOrderStatus.FINANCE_APPROVED);
        o.setCounterpartyId("CUST-001");
        o.setTotalAmount(new BigDecimal("200.00"));
        o.setReason("质量问题");
        o.setApprovedBy(APPROVER);
        o.setItems(items);
        when(returnOrderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(returnOrderRepository.save(o)).thenReturn(o);
        return o;
    }

    @Test
    @DisplayName("落点是成品仓 WH-FG, 不是物流仓 WH-LOG")
    void salesReturnLandsInFinishedGoodsWarehouse() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("5.0000"))));

        service.completeReturnOrder(FACTORY, order.getId());

        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository, times(1)).save(captor.capture());
        FinishedGoodsBatch saved = captor.getValue();

        assertThat(saved.getWarehouseId())
                .as("销售退货的成品必须落成品仓 —— 界面承诺的就是成品仓")
                .isEqualTo(WH_FG);
        assertThat(saved.getWarehouseId())
                .as("阴性对照: 不许再落物流仓 (prod 日志里那三条 warehouse=WH-LOG 就是这个 bug)")
                .isNotEqualTo(WH_LOG);
        verify(warehouseResolver, never()).resolveLogisticsId(FACTORY);
    }

    @Test
    @DisplayName("隔离状态 DEFECTIVE 保留 —— 退回来的货不可直接再卖 (这是设计意图, 不是缺陷)")
    void returnedGoodsStayQuarantined() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("5.0000"))));

        service.completeReturnOrder(FACTORY, order.getId());

        ArgumentCaptor<FinishedGoodsBatch> captor = ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(finishedGoodsBatchRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus())
                .as("DEFECTIVE 让它被所有 status='AVAILABLE' 的销售/调拨/生产选择器过滤掉 —— 改落点时不许把这个一起改掉")
                .isEqualTo("DEFECTIVE");
        assertThat(captor.getValue().getStatus())
                .as("阴性对照: 绝不能变成可售")
                .isNotEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("入库失败要阻断完成, 不能静默 COMPLETED (与 PURCHASE_RETURN 分支同一纪律)")
    void inboundFailureBlocksCompletion() {
        ReturnOrder order = salesReturn(List.of(productLine("PT-001", new BigDecimal("5.0000"))));
        when(finishedGoodsBatchRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.completeReturnOrder(FACTORY, order.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退货成品入库未成功");

        assertThat(order.getStatus())
                .as("入库失败时退货单必须停在 FINANCE_APPROVED, ⛔ 不许翻成 COMPLETED —— 那正是「界面说成了、实际没成」")
                .isEqualTo(ReturnOrderStatus.FINANCE_APPROVED);
    }

    @Test
    @DisplayName("跳过的行不算入库成功 —— 只有带 productTypeId 且数量为正的行才建批次")
    void skippedLinesAreNotCountedAsInbound() {
        ReturnOrderItem valid = productLine("PT-001", new BigDecimal("5.0000"));
        ReturnOrderItem noProductType = productLine(null, new BigDecimal("3.0000"));
        ReturnOrderItem zeroQty = productLine("PT-002", BigDecimal.ZERO);
        ReturnOrder order = salesReturn(new ArrayList<>(List.of(valid, noProductType, zeroQty)));

        service.completeReturnOrder(FACTORY, order.getId());

        // 3 行进来, 只有 1 行该建批次。旧代码日志按 items.size() 报 "items=3" 是假读数。
        verify(finishedGoodsBatchRepository, times(1)).save(any());
    }
}
