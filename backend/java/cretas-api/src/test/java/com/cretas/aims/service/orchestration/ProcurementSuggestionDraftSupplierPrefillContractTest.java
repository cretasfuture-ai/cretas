package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交接单第二节 (2026-11-11): 缺料自动生成的采购草稿 PO-AUTO-* 预填供应商 ——
 * 原料的默认 (首选) 供应商 → 最近一次真实采购的供应商 → 都取不到就留空, ⛔ 不猜。
 * 读数取的是真正写进 {@code purchaseOrderRepository.save} 的那张单 (产品真实入口 generateSuggestions)。
 */
class ProcurementSuggestionDraftSupplierPrefillContractTest {

    private static final String F = "F006";
    private static final String MAT = "RMT-QA-salt";

    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository items = mock(PurchaseOrderItemRepository.class);
    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final SupplierMaterialRepository relations = mock(SupplierMaterialRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private ProcurementSuggestionService service;
    private final List<SupplierMaterial> relationRows = new ArrayList<>();
    private final List<String> recentSuppliers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ProcurementSuggestionService(orders, items, materials);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", relations);
        ReflectionTestUtils.setField(service, "supplierRepository", suppliers);
        when(orders.save(any(PurchaseOrder.class))).thenAnswer(inv -> {
            PurchaseOrder po = inv.getArgument(0);
            po.setId("PO-" + po.getOrderNumber());
            return po;
        });
        RawMaterialType material = new RawMaterialType();
        material.setName("QA-盐");
        material.setUnit("kg");
        when(materials.findById(MAT)).thenReturn(Optional.of(material));
        when(relations.findByFactoryIdAndMaterialTypeIdOrderByActiveDescPreferredDescCreatedAtDesc(F, MAT))
                .thenAnswer(inv -> List.copyOf(relationRows));
        when(orders.findRecentSupplierIdsForMaterial(eq(F), eq(MAT), anyCollection(), any(Pageable.class)))
                .thenAnswer(inv -> List.copyOf(recentSuppliers));
    }

    private void supplier(String id, boolean active) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setFactoryId(F);
        s.setIsActive(active);
        when(suppliers.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(s));
    }

    private void relation(String supplierId, boolean preferred, boolean active) {
        SupplierMaterial r = new SupplierMaterial();
        r.setFactoryId(F);
        r.setSupplierId(supplierId);
        r.setMaterialTypeId(MAT);
        r.setPreferred(preferred);
        r.setActive(active);
        relationRows.add(r);
        when(relations.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(F, supplierId, MAT))
                .thenReturn(active);
    }

    private String generatedSupplier() {
        service.generateSuggestions(F, "PP-QA-1", List.of(new MaterialShortfall(MAT, "QA-盐",
                new BigDecimal("100"), new BigDecimal("20"), new BigDecimal("80"))), 1552L);
        ArgumentCaptor<PurchaseOrder> saved = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(orders).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        return saved.getValue().getSupplierId();
    }

    @Test
    @DisplayName("有默认 (首选) 供应商 → 预填它, 不看采购历史")
    void preferredSupplierIsPrefilled() {
        supplier("SUP-PREF", true);
        supplier("SUP-LAST", true);
        relation("SUP-PREF", true, true);
        relation("SUP-LAST", false, true);
        recentSuppliers.add("SUP-LAST");

        assertThat(generatedSupplier()).isEqualTo("SUP-PREF");
    }

    @Test
    @DisplayName("没有默认供应商 → 预填最近一次真实采购的供应商; 查询排除了草稿 / 取消 / 财务驳回")
    void lastPurchaseSupplierIsPrefilledWhenNoPreferred() {
        supplier("SUP-LAST", true);
        relation("SUP-LAST", false, true);
        recentSuppliers.add("SUP-LAST");

        assertThat(generatedSupplier()).isEqualTo("SUP-LAST");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<PurchaseOrderStatus>> excluded = ArgumentCaptor.forClass(Collection.class);
        verify(orders).findRecentSupplierIdsForMaterial(eq(F), eq(MAT), excluded.capture(), any(Pageable.class));
        assertThat(excluded.getValue()).containsExactlyInAnyOrder(
                PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.CANCELLED, PurchaseOrderStatus.FINANCE_REJECTED);
    }

    @Test
    @DisplayName("默认供应商已暂停合作 → 跳过它, 落到最近一次采购的供应商")
    void pausedPreferredSupplierFallsBackToLastPurchase() {
        supplier("SUP-PREF", false);
        supplier("SUP-LAST", true);
        relation("SUP-PREF", true, true);
        relation("SUP-LAST", false, true);
        recentSuppliers.add("SUP-LAST");

        assertThat(generatedSupplier()).isEqualTo("SUP-LAST");
    }

    @Test
    @DisplayName("阴性对照: 最近采购的供应商已停了这个原料的供应关系 → 留空, 不预填一个提交不了的供应商")
    void lastPurchaseSupplierWithInactiveRelationIsNotPrefilled() {
        supplier("SUP-LAST", true);
        relation("SUP-LAST", false, false);
        recentSuppliers.add("SUP-LAST");

        assertThat(generatedSupplier()).isNull();
    }

    @Test
    @DisplayName("阴性对照: 只有一条普通 (非首选) 供应关系、也没采购过 → 留空, ⛔ 不拿唯一一家去猜")
    void onlyANonPreferredRelationAndNoHistoryLeavesItEmpty() {
        supplier("SUP-ANY", true);
        relation("SUP-ANY", false, true);

        assertThat(generatedSupplier()).isNull();
    }
}
