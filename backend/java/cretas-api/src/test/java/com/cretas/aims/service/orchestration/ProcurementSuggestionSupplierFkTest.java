package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔴 2026-08-24 生产事故的回归闸：自动采购建议写了一个查无此人的 supplier_id。
 *
 * <p>{@code purchase_orders.supplier_id} 有外键 {@code fk_po_supplier → suppliers(id)}，
 * 而这里原来填的是占位字符串 {@code "PENDING"}，{@code suppliers} 里没有这一行。
 * prod 实测：{@code Key (supplier_id)=(PENDING)} 撞了 <b>21 次</b>，
 * 而 {@code purchase_orders} 里 {@code order_number LIKE 'PO-AUTO%'} 是 <b>0 行</b>
 * —— 这个方法<b>一次都没成功写入过</b>。
 *
 * <p>⚠️ 它的代价远不止「少一张采购单」：外键异常把外层联动事务标记成 rollback-only，
 * 于是同一次联动里已经建好的<b>生产计划一起被回滚</b>，而接口返回 200。
 * 六膳门(真实客户) 8-24 两张单 ¥92,260 财审通过后既无计划也无预留，此后停用了 ERP 侧功能。
 *
 * <p>⛔ 这与本服务 javadoc 里记着的 {@code createdBy = 0L} 是<b>同一个形状</b>：
 * 拿一个假值去填有外键的列。2026-08-19 修了 created_by，隔壁这个留下了。
 * ⇒ 「不知道」必须写成 NULL，不是一个编出来的 id（形态 A¹⁰）。
 */
@ExtendWith(MockitoExtension.class)
class ProcurementSuggestionSupplierFkTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @InjectMocks private ProcurementSuggestionService service;

    @Test
    @DisplayName("🔴 同一天第二次生成建议要接着编号, ⛔ 不许从 1 重来(会撞 uk_po_factory_order)")
    void numbersContinueAfterExistingSameDaySuggestions() {
        PurchaseOrder savedOrder = new PurchaseOrder();
        savedOrder.setId("PO-1");
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(savedOrder);
        // 当天已经有 3 张 PO-AUTO
        when(purchaseOrderRepository.countAutoSuggestionsOn(any(), any())).thenReturn(3L);

        RawMaterialType material = new RawMaterialType();
        material.setName("鸡肉");
        material.setUnit("kg");
        when(rawMaterialTypeRepository.findById("MAT-1")).thenReturn(Optional.of(material));

        service.generateSuggestions("F006", "PP-2",
                List.of(new MaterialShortfall("MAT-1", "鸡肉",
                        new BigDecimal("100"), new BigDecimal("20"), new BigDecimal("80"))), 1309L);

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderNumber())
                .as("当天已有 3 张就该从 4 开始; 从 1 开始会撞 UNIQUE (factory_id, order_number) —— "
                        + "这个坑此前被 supplier_id 那个坑挡着, 修好之后才变得可达")
                .endsWith("-4");
    }

    private PurchaseOrder captureGeneratedOrder() {
        PurchaseOrder savedOrder = new PurchaseOrder();
        savedOrder.setId("PO-1");
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenReturn(savedOrder);

        RawMaterialType material = new RawMaterialType();
        material.setName("鸡肉");
        material.setUnit("kg");
        when(rawMaterialTypeRepository.findById("MAT-1")).thenReturn(Optional.of(material));

        MaterialShortfall shortfall = new MaterialShortfall(
                "MAT-1", "鸡肉", new BigDecimal("100"), new BigDecimal("20"), new BigDecimal("80"));

        service.generateSuggestions("F006", "PP-1", List.of(shortfall), 1309L);

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("supplier_id 未知时必须是 NULL —— ⛔ 不许是编出来的 id")
    void supplierIdIsNullWhenUnknown() {
        PurchaseOrder generated = captureGeneratedOrder();

        assertThat(generated.getSupplierId())
                .as("supplier_id 有外键指向 suppliers；任何非空占位值都会 100%% 撞 fk_po_supplier，"
                        + "而外键违例会把外层联动事务标记 rollback-only，连累已建好的生产计划")
                .isNull();
    }

    @Test
    @DisplayName("⛔ 阴性对照 · 「PENDING」这个具体的坏值不许回来")
    void neverWritesThePendingPlaceholder() {
        PurchaseOrder generated = captureGeneratedOrder();

        assertThat(generated.getSupplierId())
                .as("2026-08-24 生产上撞了 21 次的就是这个字面值")
                .isNotEqualTo("PENDING");
    }

    @Test
    @DisplayName("阳性对照 · 其余字段照常填, 这道闸没有把整条路关掉")
    void stillProducesAUsableDraft() {
        PurchaseOrder generated = captureGeneratedOrder();

        assertThat(generated.getFactoryId()).isEqualTo("F006");
        assertThat(generated.getOrderNumber()).startsWith("PO-AUTO-");
        assertThat(generated.getCreatedBy())
                .as("createdBy 是同一族的坑(fk_po_created_by), 2026-08-19 修过, 不许退回去")
                .isEqualTo(1309L);
        assertThat(generated.getRemark()).contains("PP-1");
    }
}
