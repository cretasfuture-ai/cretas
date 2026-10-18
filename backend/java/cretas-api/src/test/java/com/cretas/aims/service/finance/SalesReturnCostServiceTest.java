package com.cretas.aims.service.finance;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.inventory.ReturnedGoodsCostResolver;
import com.cretas.aims.service.unit.UnitContractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesReturnCostServiceTest {

    private static final String FACTORY = "F006";
    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ReturnedGoodsCostResolver returnedGoodsCostResolver;
    @Mock private VoucherRepository voucherRepository;
    @Mock private FinishedGoodsAdjustmentLogRepository adjustmentLogRepository;
    @Mock private UnitContractService unitContractService;
    private SalesReturnCostService service;

    @BeforeEach
    void setUp() {
        service = new SalesReturnCostService(returnOrderRepository, finishedGoodsBatchRepository,
                returnedGoodsCostResolver, voucherRepository, adjustmentLogRepository);
        org.mockito.Mockito.lenient().when(unitContractService.storageUnit(any(), any())).thenAnswer(invocation ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                        .crossLanguageCode(invocation.getArgument(1)));
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
    }

    @Test
    void sumsOriginalLineQuantityUsingSavedCostAfterReturnedBatchWasDisposed() {
        ReturnOrderItem item = item(11L, "SRC-1", "SKU-1", "盒", "2");
        ReturnOrder order = order("RO-1", "RTN-1", item);
        FinishedGoodsBatch source = batch("SRC-1", "SKU-1", "盒", "7.125");
        FinishedGoodsBatch returned = batch("RTN-RTN-1-1", "SKU-1", "盒", "7.125");
        returned.setId("RET-1");
        returned.setReturnOrderItemId(11L);
        returned.setProducedQuantity(new BigDecimal("0"));
        returned.setShippedQuantity(new BigDecimal("2"));
        when(adjustmentLogRepository.findByBatchIdOrderByCreatedAtDesc("RET-1"))
                .thenReturn(List.of(FinishedGoodsAdjustmentLog.builder().factoryId(FACTORY)
                        .batchId("RET-1").adjustmentQuantity(new BigDecimal("-2")).build()));

        stub(order, item, source, returned);
        assertThat(service.returnedCost(FACTORY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .isEqualByComparingTo("14.250");
    }

    @Test
    void blocksMissingInboundEvidenceInsteadOfSilentlySkipping() {
        ReturnOrderItem item = item(12L, "SRC-1", "SKU-1", "盒", "1");
        ReturnOrder order = order("RO-2", "RTN-2", item);
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq(ReturnOrderStatus.COMPLETED), any(), any()))
                .thenReturn(List.of(order));
        when(voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_ORDER", "RO-2"))
                .thenReturn(Optional.of(postedVoucher("RO-2", order.getReturnDate())));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 12L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("缺少回库证据");
    }

    @Test
    void blocksUnpostedIncomeVoucher() {
        ReturnOrderItem item = item(13L, "SRC-1", "SKU-1", "盒", "1");
        ReturnOrder order = order("RO-3", "RTN-3", item);
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq(ReturnOrderStatus.COMPLETED), any(), any()))
                .thenReturn(List.of(order));
        Voucher voucher = postedVoucher("RO-3", order.getReturnDate());
        voucher.setStatus(VoucherStatus.DRAFT);
        when(voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_ORDER", "RO-3"))
                .thenReturn(Optional.of(voucher));

        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("凭证");
    }

    @Test
    void acceptsStableBatchWhoseNameIsAlsoTheLegacyPointerWhenIdentityIsSame() {
        ReturnOrderItem item = item(14L, "SRC-1", "SKU-1", "盒", "1");
        ReturnOrder order = order("RO-4", "RTN-4", item);
        FinishedGoodsBatch source = batch("SRC-1", "SKU-1", "盒", "3.5");
        FinishedGoodsBatch returned = batch("RTN-RTN-4-1", "SKU-1", "盒", "3.5");
        returned.setId("RET-4");
        returned.setReturnOrderItemId(14L);
        returned.setProducedQuantity(new BigDecimal("1"));
        stub(order, item, source, returned);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "RTN-RTN-4-1"))
                .thenReturn(Optional.of(returned));

        assertThat(service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isEqualByComparingTo("3.5");
    }

    @Test
    void rejectsNullQuantityInsteadOfSilentlyDroppingTheLine() {
        ReturnOrderItem item = item(15L, "SRC-1", "SKU-1", "盒", "1");
        item.setQuantity(null);
        ReturnOrder order = order("RO-5", "RTN-5", item);
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq(ReturnOrderStatus.COMPLETED), any(), any()))
                .thenReturn(List.of(order));
        when(voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_ORDER", "RO-5"))
                .thenReturn(Optional.of(postedVoucher("RO-5", order.getReturnDate())));

        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("无效退货数量");
    }

    @Test
    void blocksSourceFactorySkuOrUnitMismatch() {
        ReturnOrderItem item = item(16L, "SRC-1", "SKU-1", "盒", "1");
        ReturnOrder order = order("RO-6", "RTN-6", item);
        FinishedGoodsBatch returned = batch("RTN-RTN-6-1", "SKU-1", "盒", "2");
        returned.setId("RET-6"); returned.setReturnOrderItemId(16L);
        FinishedGoodsBatch source = batch("SRC-1", "SKU-1", "盒", "2");
        source.setFactoryId("OTHER");
        stub(order, item, source, returned);
        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("工厂");

        source.setFactoryId(FACTORY);
        source.setProductTypeId("SKU-X");
        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("SKU");

        source.setProductTypeId("SKU-1");
        source.setUnit("箱");
        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("单位");
    }

    @Test
    void blocksMissingCostAndExtraUnlinkedLegacyBatch() {
        ReturnOrderItem item = item(17L, "SRC-1", "SKU-1", "盒", "1");
        ReturnOrder order = order("RO-7", "RTN-7", item);
        FinishedGoodsBatch returned = batch("RTN-RTN-7-1", "SKU-1", "盒", null);
        returned.setId("RET-7"); returned.setReturnOrderItemId(null);
        returned.setProducedQuantity(BigDecimal.ONE);
        FinishedGoodsBatch source = batch("SRC-1", "SKU-1", "盒", "2");
        stub(order, item, source, returned);
        when(returnedGoodsCostResolver.resolve(FACTORY, returned)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("可验证库存成本");

        FinishedGoodsBatch extraLegacy = batch("RTN-RTN-7-1", "SKU-1", "盒", "2");
        extraLegacy.setId("LEGACY-7");
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "RTN-RTN-7-1"))
                .thenReturn(Optional.of(extraLegacy));
        returned.setUnitCost(new BigDecimal("2"));
        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("稳定和旧批次");
    }

    @Test
    void rejectsSavedSnapshotDifferentFromSourceCostAndNullSourceCost() {
        ReturnOrderItem item = item(20L, "SRC-1", "SKU-1", "盒", "1");
        ReturnOrder order = order("RO-9", "RTN-9", item);
        FinishedGoodsBatch source = batch("SRC-1", "SKU-1", "盒", "7.4165");
        FinishedGoodsBatch returned = batch("RTN-RTN-9-1", "SKU-1", "盒", "15");
        returned.setId("RET-9"); returned.setReturnOrderItemId(20L);
        returned.setProducedQuantity(new BigDecimal("1"));
        stub(order, item, source, returned);
        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("成本快照");

        source.setUnitCost(null);
        returned.setUnitCost(new BigDecimal("15"));
        assertThatThrownBy(() -> service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("源批次");
    }

    @Test
    void sumsTwoStableRowsInOneReturn() {
        ReturnOrderItem first = item(18L, "SRC-1", "SKU-1", "盒", "2");
        ReturnOrderItem second = item(19L, "SRC-1", "SKU-1", "盒", "3");
        ReturnOrder order = order("RO-8", "RTN-8", first);
        order.setItems(List.of(first, second));
        FinishedGoodsBatch source = batch("SRC-1", "SKU-1", "盒", "2");
        FinishedGoodsBatch one = batch("RTN-RTN-8-1", "SKU-1", "盒", "2");
        one.setId("RET-8-1"); one.setReturnOrderItemId(18L);
        one.setProducedQuantity(new BigDecimal("2"));
        FinishedGoodsBatch two = batch("RTN-RTN-8-2", "SKU-1", "盒", "2");
        two.setId("RET-8-2"); two.setReturnOrderItemId(19L);
        two.setProducedQuantity(new BigDecimal("3"));
        stub(order, first, source, one);
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 19L))
                .thenReturn(List.of(two));
        assertThat(service.returnedCost(FACTORY, order.getReturnDate(), order.getReturnDate()))
                .isEqualByComparingTo("10");
    }

    private void stub(ReturnOrder order, ReturnOrderItem item, FinishedGoodsBatch source,
                      FinishedGoodsBatch returned) {
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                eq(FACTORY), eq(ReturnType.SALES_RETURN), eq(ReturnOrderStatus.COMPLETED), any(), any()))
                .thenReturn(List.of(order));
        when(voucherRepository.findBySourceBusinessTypeAndSourceBusinessIdAndDeletedAtIsNull("RETURN_ORDER", order.getId()))
                .thenReturn(Optional.of(postedVoucher(order.getId(), order.getReturnDate())));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, item.getId()))
                .thenReturn(List.of(returned));
        when(finishedGoodsBatchRepository.findById("SRC-1")).thenReturn(Optional.of(source));
    }

    private static ReturnOrder order(String id, String number, ReturnOrderItem item) {
        ReturnOrder order = new ReturnOrder();
        order.setId(id);
        order.setFactoryId(FACTORY);
        order.setReturnNumber(number);
        order.setReturnType(ReturnType.SALES_RETURN);
        order.setStatus(ReturnOrderStatus.COMPLETED);
        order.setWithGoods(true);
        order.setReturnDate(LocalDate.of(2026, 1, 10));
        order.setItems(List.of(item));
        return order;
    }

    private static ReturnOrderItem item(Long id, String sourceBatch, String sku, String unit, String quantity) {
        ReturnOrderItem item = new ReturnOrderItem();
        item.setId(id);
        item.setProductTypeId(sku);
        item.setBatchNumber(sourceBatch);
        item.setQuantity(new BigDecimal(quantity));
        item.setSourceFinishedGoodsBatchId("SRC-1");
        return item;
    }

    private static FinishedGoodsBatch batch(String number, String sku, String unit, String cost) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber(number);
        batch.setProductTypeId(sku);
        batch.setUnit(unit);
        batch.setUnitCost(cost == null ? null : new BigDecimal(cost));
        batch.setProducedQuantity(new BigDecimal("2"));
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        return batch;
    }

    private static Voucher postedVoucher(String sourceId, LocalDate date) {
        Voucher voucher = new Voucher();
        voucher.setFactoryId(FACTORY);
        voucher.setSourceBusinessId(sourceId);
        voucher.setSourceBusinessType("RETURN_ORDER");
        voucher.setVoucherType(VoucherType.RETURN);
        voucher.setVoucherDate(date);
        voucher.setStatus(VoucherStatus.POSTED);
        return voucher;
    }
}
