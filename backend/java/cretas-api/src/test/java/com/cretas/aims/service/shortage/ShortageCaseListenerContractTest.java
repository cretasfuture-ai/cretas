package com.cretas.aims.service.shortage;

import com.cretas.aims.event.MaterialBatchReleasedEvent;
import com.cretas.aims.event.MaterialReceivedEvent;
import com.cretas.aims.event.PurchaseOrderSupplyChangedEvent;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺料刷新失败 ⛔ 不能把放行 / 收货 / 审批报成失败:
 * 监听方法不吞不住就会抛回调用方; 监听方法若自己开事务, 内层失败会把它标成只能回滚, 提交时照样抛 —
 * 放行工具 (无外层事务, fallbackExecution 同步调用) 就会把一次成功的放行报成失败。
 * 所以: 监听方法不挂 @Transactional, 失败被吞; 刷新在服务的 REQUIRES_NEW 事务里, 只回滚它自己。
 */
class ShortageCaseListenerContractTest {

    private final ShortageCaseService service = mock(ShortageCaseService.class);
    private final ShortageCaseListener listener = new ShortageCaseListener(service);

    @Test
    void aRefreshFailureNeverEscapesTheListener() {
        when(service.refreshForMaterial(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("db down"));
        when(service.refreshForSalesOrder(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onMaterialBatchReleased(new MaterialBatchReleasedEvent(this, "F1", "M1", "MB-1")))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onMaterialReceived(new MaterialReceivedEvent(this, "F1", "PO-1", "M1", BigDecimal.ONE)))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onMaterialBatchInspectionClosed(new com.cretas.aims.event.MaterialBatchInspectionClosedEvent(
                this, "F1", "M1", "MB-1", com.cretas.aims.entity.enums.MaterialBatchStatus.DEFECTIVE)))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onSalesOrderFinanceApproved(
                new SalesOrderFinanceApprovedEvent(this, "F1", "SO-1", 9L)))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onPurchaseOrderSupplyChanged(
                new PurchaseOrderSupplyChangedEvent(this, "F1", "PO-1", Set.of("M1"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aPurchaseOrderSupplyChangeRefreshesEveryMaterialOnTheOrder() {
        listener.onPurchaseOrderSupplyChanged(new PurchaseOrderSupplyChangedEvent(this, "F1", "PO-1", Set.of("M1", "M2")));

        verify(service).refreshForMaterial("F1", "M1", ShortageCaseListener.REASON_PURCHASE_SUPPLY_CHANGED);
        verify(service).refreshForMaterial("F1", "M2", ShortageCaseListener.REASON_PURCHASE_SUPPLY_CHANGED);
    }

    @Test
    void aQcRejectionRefreshesTheMaterialAfterCommit() throws Exception {
        listener.onMaterialBatchInspectionClosed(new com.cretas.aims.event.MaterialBatchInspectionClosedEvent(
                this, "F1", "M1", "MB-1", com.cretas.aims.entity.enums.MaterialBatchStatus.DEFECTIVE));
        verify(service).refreshForMaterial("F1", "M1", ShortageCaseListener.REASON_BATCH_QC_REJECTED);

        // 与放行同一语义: 事务提交之后才刷新 (回滚的判不合格不该动缺料实例), 无事务时照样执行
        var tel = ShortageCaseListener.class.getMethod("onMaterialBatchInspectionClosed",
                        com.cretas.aims.event.MaterialBatchInspectionClosedEvent.class)
                .getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
        assertThat(tel.fallbackExecution()).isTrue();
    }

    @Test
    void listenerMethodsOpenNoTransactionAndTheRefreshRunsInItsOwn() throws Exception {
        for (Method m : List.of(
                ShortageCaseListener.class.getMethod("onMaterialBatchReleased", MaterialBatchReleasedEvent.class),
                ShortageCaseListener.class.getMethod("onMaterialReceived", MaterialReceivedEvent.class),
                ShortageCaseListener.class.getMethod("onMaterialBatchInspectionClosed",
                        com.cretas.aims.event.MaterialBatchInspectionClosedEvent.class),
                ShortageCaseListener.class.getMethod("onPurchaseOrderSupplyChanged", PurchaseOrderSupplyChangedEvent.class),
                ShortageCaseListener.class.getMethod("onSalesOrderFinanceApproved", SalesOrderFinanceApprovedEvent.class))) {
            assertThat(m.getAnnotation(Transactional.class)).as(m.getName()).isNull();
        }
        for (Method m : List.of(
                ShortageCaseService.class.getMethod("refreshForMaterial", String.class, String.class, String.class),
                ShortageCaseService.class.getMethod("refreshForSalesOrder", String.class, String.class, String.class))) {
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx).as(m.getName()).isNotNull();
            assertThat(tx.propagation()).as(m.getName()).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
