package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L3 报价可比: 选定报价回填<b>已有</b>采购草稿 ({@link PurchaseServiceImpl#applyQuoteToDraftLine})。
 *
 * <p>守的行为: 只改本物料那一行 + 单头供应商与合计; 其它行原样; 不生成新单号、不新建采购单;
 * 非草稿 / 供应商冲突 → 409。
 */
class PurchaseDraftQuoteFillContractTest {

    private static final String FACTORY = "F006";
    private final PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
    private final SupplierMaterialRepository relationRepository = mock(SupplierMaterialRepository.class);
    private final SupplierMaterialPurchaseSpecRepository specRepository =
            mock(SupplierMaterialPurchaseSpecRepository.class);
    private PurchaseServiceImpl service;
    private PurchaseOrder draft;
    private PurchaseOrderItem quotedLine;
    private PurchaseOrderItem otherLine;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orderRepository, itemRepository, null,
                supplierRepository, materialRepository, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", relationRepository);
        ReflectionTestUtils.setField(service, "supplierMaterialPurchaseSpecRepository", specRepository);

        for (String supplierId : List.of("supplier-1", "supplier-2")) {
            Supplier supplier = new Supplier();
            supplier.setId(supplierId);
            supplier.setFactoryId(FACTORY);
            supplier.setIsActive(true);
            when(supplierRepository.findByIdAndFactoryId(supplierId, FACTORY)).thenReturn(Optional.of(supplier));
        }
        material("material-1");
        material("material-2");
        relation("supplier-1", "material-1");
        relation("supplier-1", "material-2");
        relation("supplier-2", "material-1");
        when(specRepository.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(any(), any())).thenReturn(List.of());

        draft = new PurchaseOrder();
        draft.setId("PO-DRAFT");
        draft.setOrderNumber("PO-20261028-0001");
        draft.setFactoryId(FACTORY);
        draft.setSupplierId("supplier-1");
        draft.setStatus(PurchaseOrderStatus.DRAFT);
        when(orderRepository.findById("PO-DRAFT")).thenReturn(Optional.of(draft));
        when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(i -> i.getArgument(0));

        quotedLine = line(1L, "material-1", "5", "10", "13");
        otherLine = line(2L, "material-2", "2", "3", "13");
        otherLine.setRemark("另一行备注");
        when(itemRepository.findByPurchaseOrderId("PO-DRAFT"))
                .thenReturn(new ArrayList<>(List.of(quotedLine, otherLine)));
    }

    @Test
    void fillsOnlyTheQuotedLineOfTheExistingDraft() {
        PurchaseOrder result = service.applyQuoteToDraftLine(FACTORY, "PO-DRAFT", "material-1",
                "supplier-1", new BigDecimal("8"), "kg", new BigDecimal("9"), null);

        assertThat(result.getId()).isEqualTo("PO-DRAFT");
        assertThat(result.getOrderNumber()).isEqualTo("PO-20261028-0001");
        assertThat(quotedLine.getUnitPrice()).isEqualByComparingTo("8");
        assertThat(quotedLine.getTaxRate()).isEqualByComparingTo("9");
        assertThat(quotedLine.getSupplierMaterialId()).isEqualTo("REL-supplier-1-material-1");
        // 其它行原样
        assertThat(otherLine.getUnitPrice()).isEqualByComparingTo("3");
        assertThat(otherLine.getTaxRate()).isEqualByComparingTo("13");
        assertThat(otherLine.getRemark()).isEqualTo("另一行备注");
        // 合计 = 5×8 + 2×3
        assertThat(result.getTotalAmount()).isEqualByComparingTo("46");
        // 不生成新单号, 不删行重建
        verify(orderRepository, never()).countByFactoryIdAndDate(any(), any());
        verify(itemRepository, never()).deleteAll(any());
        verify(itemRepository, times(1)).save(quotedLine);
    }

    @Test
    void singleLineDraftMayChangeSupplier() {
        when(itemRepository.findByPurchaseOrderId("PO-DRAFT")).thenReturn(new ArrayList<>(List.of(quotedLine)));

        PurchaseOrder result = service.applyQuoteToDraftLine(FACTORY, "PO-DRAFT", "material-1",
                "supplier-2", new BigDecimal("8"), "kg", new BigDecimal("13"), null);

        assertThat(result.getSupplierId()).isEqualTo("supplier-2");
        assertThat(quotedLine.getSupplierMaterialId()).isEqualTo("REL-supplier-2-material-1");
    }

    @Test
    void otherSupplierOnMultiLineDraftIsRejected() {
        assertThatThrownBy(() -> service.applyQuoteToDraftLine(FACTORY, "PO-DRAFT", "material-1",
                "supplier-2", new BigDecimal("8"), "kg", new BigDecimal("13"), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("PURCHASE_DRAFT_SUPPLIER_CONFLICT");
        assertThat(quotedLine.getUnitPrice()).isEqualByComparingTo("10");
        verify(orderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    void submittedOrderIsNotFilled() {
        draft.setStatus(PurchaseOrderStatus.SUBMITTED);

        assertThatThrownBy(() -> service.applyQuoteToDraftLine(FACTORY, "PO-DRAFT", "material-1",
                "supplier-1", new BigDecimal("8"), "kg", new BigDecimal("13"), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("PURCHASE_DRAFT_NOT_EDITABLE");
        verify(orderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    void otherFactoryDraftIsRejected() {
        draft.setFactoryId("F999");

        assertThatThrownBy(() -> service.applyQuoteToDraftLine(FACTORY, "PO-DRAFT", "material-1",
                "supplier-1", new BigDecimal("8"), "kg", new BigDecimal("13"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问");
        verify(orderRepository, never()).save(any(PurchaseOrder.class));
    }

    private void material(String id) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(FACTORY);
        material.setName(id);
        material.setUnit("kg");
        material.setTaxTreatment(TaxTreatment.TAXABLE);
        material.setTaxRate(TaxRate.TAX_13);
        when(materialRepository.findById(id)).thenReturn(Optional.of(material));
    }

    private void relation(String supplierId, String materialId) {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setId("REL-" + supplierId + "-" + materialId);
        relation.setFactoryId(FACTORY);
        relation.setSupplierId(supplierId);
        relation.setMaterialTypeId(materialId);
        relation.setPurchaseUnit("kg");
        relation.setActive(true);
        when(relationRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, supplierId, materialId)).thenReturn(true);
        when(relationRepository.findByFactoryIdAndSupplierIdAndMaterialTypeId(FACTORY, supplierId, materialId))
                .thenReturn(Optional.of(relation));
    }

    private static PurchaseOrderItem line(Long id, String materialId, String qty, String price, String tax) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(id);
        item.setPurchaseOrderId("PO-DRAFT");
        item.setMaterialTypeId(materialId);
        item.setMaterialName(materialId);
        item.setQuantity(new BigDecimal(qty));
        item.setUnit("kg");
        item.setPriceUnit("kg");
        item.setUnitPrice(new BigDecimal(price));
        item.setTaxRate(new BigDecimal(tax));
        return item;
    }
}
