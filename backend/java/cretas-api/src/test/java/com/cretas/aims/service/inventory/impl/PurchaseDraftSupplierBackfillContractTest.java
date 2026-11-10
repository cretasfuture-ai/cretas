package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.UpdatePurchaseOrderRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.material.MaterialReferenceWriteGuard;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S411R-01 (2026-11-10, F006 实测): 缺料自动生成的采购草稿 (PO-AUTO-*) 没有供应商、单价 0,
 * 提交被 422 PURCHASE_SUPPLIER_REQUIRED 拒绝, 提示「请编辑采购草稿并选择供应商后再提交」。
 *
 * <h3>被守的行为</h3>
 * 后端<b>编辑草稿</b>入口 {@link PurchaseServiceImpl#updateDraftOrder} 必须能给这种草稿补上供应商,
 * 补完之后<b>同一张单</b>的 {@link PurchaseServiceImpl#submitOrder} 能走通 —— 也就是 422 的提示
 * 是一条真能照做的路, 而不是死路。
 *
 * <p>补供应商时的校验沿用既有口径 (⛔ 不新造规则): 供应商必须属于本厂 (findByIdAndFactoryId)、
 * 处于启用、且与草稿里每个原料有生效的供应关系。
 *
 * <h3>阴性对照</h3>
 * <ul>
 *   <li>已提交 (非草稿) 的单改供应商 → 409, 供应商与单据都不变 (b)。</li>
 *   <li>别厂的供应商 → 按「不存在或不属于当前组织」拒绝, 草稿不落库 (c)。</li>
 *   <li>本厂启用供应商但不供应这个原料 → 409「未启用所选物料的供应关系」, 草稿不落库 (d)。</li>
 * </ul>
 *
 * <p>夹具照着 {@code ProcurementSuggestionService.generateSuggestions} 写出来的草稿形状搭:
 * supplierId=null、单价 0、税率 0、单位 kg、数量即缺口量 56.74 —— 那是线上 PO-AUTO-20261110-7 的样子。
 */
class PurchaseDraftSupplierBackfillContractTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F007";
    private static final String SUPPLIER = "supplier-salt";
    private static final String FOREIGN_SUPPLIER = "supplier-of-f007";
    private static final String NON_SUPPLYING_SUPPLIER = "supplier-no-salt";
    private static final String MATERIAL = "material-salt";
    private static final String RELATION = "relation-salt";
    /** 另一家完全合格的本厂供应商 (启用 + 供应盐料) —— 阴性对照里只剩「状态」这一道闸能拦它。 */
    private static final String OTHER_VALID_SUPPLIER = "supplier-salt-2";
    private static final String OTHER_VALID_RELATION = "relation-salt-2";
    private static final String ORDER = "po-auto-20261110-7";

    private final PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
    private final SupplierMaterialRepository relationRepository = mock(SupplierMaterialRepository.class);
    private final SupplierMaterialPurchaseSpecRepository specRepository =
            mock(SupplierMaterialPurchaseSpecRepository.class);
    private final WorkflowEngineService workflowEngine = mock(WorkflowEngineService.class);
    private final MaterialReferenceWriteGuard materialReferenceWriteGuard = mock(MaterialReferenceWriteGuard.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private PurchaseServiceImpl service;
    private PurchaseOrder order;
    /** 「库里」当前这张单的明细 —— saveAll 会替换它, findByPurchaseOrderId 读它。 */
    private final List<PurchaseOrderItem> storedItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 物料引用写锁 (MaterialReferenceWriteGuard) 是与本缺陷正交的另一道闸, 由它自己的测试守;
        // 这里桩成放行, 让断言落在供应商补录这条路径上。
        service = new PurchaseServiceImpl(orderRepository, itemRepository,
                mock(PurchaseReceiveRecordRepository.class), supplierRepository, materialRepository,
                mock(MaterialBatchRepository.class), mock(BomRecipeItemRepository.class),
                mock(com.cretas.aims.service.finance.ArApService.class), events,
                mock(MaterialBatchService.class), materialReferenceWriteGuard);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", relationRepository);
        ReflectionTestUtils.setField(service, "supplierMaterialPurchaseSpecRepository", specRepository);
        ReflectionTestUtils.setField(service, "workflowEngine", workflowEngine);
        ReflectionTestUtils.setField(service, "self", service);

        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY))
                .thenReturn(Optional.of(supplier(SUPPLIER, FACTORY)));
        when(supplierRepository.findByIdAndFactoryId(OTHER_VALID_SUPPLIER, FACTORY))
                .thenReturn(Optional.of(supplier(OTHER_VALID_SUPPLIER, FACTORY)));
        when(supplierRepository.findByIdAndFactoryId(NON_SUPPLYING_SUPPLIER, FACTORY))
                .thenReturn(Optional.of(supplier(NON_SUPPLYING_SUPPLIER, FACTORY)));
        // 别厂供应商在别厂是真实存在且启用的 —— 拒绝它必须是因为「不属于本厂」, 不是因为查无此人。
        when(supplierRepository.findByIdAndFactoryId(FOREIGN_SUPPLIER, OTHER_FACTORY))
                .thenReturn(Optional.of(supplier(FOREIGN_SUPPLIER, OTHER_FACTORY)));
        when(supplierRepository.findByIdAndFactoryId(FOREIGN_SUPPLIER, FACTORY)).thenReturn(Optional.empty());

        RawMaterialType material = new RawMaterialType();
        material.setId(MATERIAL);
        material.setFactoryId(FACTORY);
        material.setName("QA-S4-1029-盐料");
        material.setUnit("kg");
        material.setUnitPrice(null);
        material.setTaxTreatment(TaxTreatment.TAXABLE);
        material.setTaxRate(TaxRate.TAX_13);
        when(materialRepository.findById(MATERIAL)).thenReturn(Optional.of(material));

        SupplierMaterial relation = new SupplierMaterial();
        relation.setId(RELATION);
        relation.setFactoryId(FACTORY);
        relation.setSupplierId(SUPPLIER);
        relation.setMaterialTypeId(MATERIAL);
        relation.setPurchaseUnit("kg");
        relation.setActive(true);
        relation.setDefaultPurchasePrice(new BigDecimal("6"));
        when(relationRepository.findByIdAndFactoryId(RELATION, FACTORY)).thenReturn(Optional.of(relation));
        when(relationRepository.findByFactoryIdAndSupplierIdAndMaterialTypeId(FACTORY, SUPPLIER, MATERIAL))
                .thenReturn(Optional.of(relation));
        when(relationRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, SUPPLIER, MATERIAL)).thenReturn(true);
        when(relationRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, NON_SUPPLYING_SUPPLIER, MATERIAL)).thenReturn(false);
        SupplierMaterial otherRelation = new SupplierMaterial();
        otherRelation.setId(OTHER_VALID_RELATION);
        otherRelation.setFactoryId(FACTORY);
        otherRelation.setSupplierId(OTHER_VALID_SUPPLIER);
        otherRelation.setMaterialTypeId(MATERIAL);
        otherRelation.setPurchaseUnit("kg");
        otherRelation.setActive(true);
        otherRelation.setDefaultPurchasePrice(new BigDecimal("5.5"));
        when(relationRepository.findByFactoryIdAndSupplierIdAndMaterialTypeId(FACTORY, OTHER_VALID_SUPPLIER, MATERIAL))
                .thenReturn(Optional.of(otherRelation));
        when(relationRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, OTHER_VALID_SUPPLIER, MATERIAL)).thenReturn(true);
        when(specRepository.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(FACTORY, OTHER_VALID_RELATION))
                .thenReturn(List.of());
        when(specRepository.findByFactoryIdAndSupplierMaterialIdAndActiveTrue(FACTORY, RELATION))
                .thenReturn(List.of());
        when(specRepository.findById(any())).thenReturn(Optional.empty());

        // ProcurementSuggestionService.generateSuggestions 写出来的草稿行
        PurchaseOrderItem autoLine = new PurchaseOrderItem();
        autoLine.setId(1L);
        autoLine.setPurchaseOrderId(ORDER);
        autoLine.setMaterialTypeId(MATERIAL);
        autoLine.setMaterialName("QA-S4-1029-盐料");
        autoLine.setQuantity(new BigDecimal("56.74"));
        autoLine.setUnit("kg");
        autoLine.setPriceUnit("kg");
        autoLine.setQuantityToPriceFactor(BigDecimal.ONE);
        autoLine.setUnitPrice(BigDecimal.ZERO);
        autoLine.setTaxRate(BigDecimal.ZERO);
        autoLine.setReceivedQuantity(BigDecimal.ZERO);
        storedItems.add(autoLine);
        when(itemRepository.findByPurchaseOrderId(ORDER)).thenAnswer(invocation -> List.copyOf(storedItems));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<PurchaseOrderItem> saved = invocation.getArgument(0);
            List<PurchaseOrderItem> copy = new ArrayList<>();
            saved.forEach(copy::add);
            // updateDraftOrder 先 deleteAll 再 saveAll 新行; 回解价格时 saveAll 的是原行子集 ——
            // 两种情况下「库里」都应当是「这次保存后的全部行」, 所以按 id/引用合并而不是整体替换。
            for (PurchaseOrderItem item : copy) {
                if (!storedItems.contains(item)) {
                    storedItems.add(item);
                }
            }
            return copy;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            Iterable<PurchaseOrderItem> deleted = invocation.getArgument(0);
            deleted.forEach(storedItems::remove);
            return null;
        }).when(itemRepository).deleteAll(any());

        order = new PurchaseOrder();
        order.setId(ORDER);
        order.setFactoryId(FACTORY);
        order.setOrderNumber("PO-AUTO-20261110-7");
        order.setSupplierId(null);
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setCreatedBy(1552L);
        order.setVersion(3L);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        when(orderRepository.findByIdAndFactoryIdForUpdate(ORDER, FACTORY)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 本厂未配置采购审批 → 提交直接 FINANCE_APPROVED。这是 submitOrder 的真实成功出口之一。
        when(workflowEngine.getLatestInstance(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(workflowEngine.startWorkflowIfConfigured(
                eq(FACTORY), eq("PURCHASE_ORDER"), eq(ORDER), anyMap(), any()))
                .thenReturn(Optional.empty());
    }

    private static Supplier supplier(String id, String factoryId) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setFactoryId(factoryId);
        supplier.setName(id);
        supplier.setIsActive(true);
        return supplier;
    }

    private static UpdatePurchaseOrderRequest supplierOnly(String supplierId) {
        UpdatePurchaseOrderRequest request = new UpdatePurchaseOrderRequest();
        request.setSupplierId(supplierId);
        request.setVersion(3L);
        return request;
    }

    /** web-admin 编辑弹窗「保存修改」实际发的形状: 供应商 + 整组明细 (按原料对应好的供应关系, 数量不变)。 */
    private static UpdatePurchaseOrderRequest editorPayload(String supplierId, String relationId) {
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO line = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        line.setSupplierMaterialId(relationId);
        line.setMaterialTypeId(MATERIAL);
        line.setMaterialName("QA-S4-1029-盐料");
        line.setQuantity(new BigDecimal("56.74"));
        line.setUnit("kg");
        line.setQuantityUnit("kg");
        line.setPriceUnit("kg");
        line.setUnitPrice(new BigDecimal("6"));
        line.setTaxRate(new BigDecimal("13"));
        UpdatePurchaseOrderRequest request = supplierOnly(supplierId);
        request.setItems(List.of(line));
        return request;
    }

    @Test
    @DisplayName("阳性对照: 没补供应商时提交仍是 422 PURCHASE_SUPPLIER_REQUIRED (线上复现)")
    void submitWithoutSupplierIsStillRejected() {
        assertThatThrownBy(() -> service.submitOrder(FACTORY, ORDER, 1552L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("PURCHASE_SUPPLIER_REQUIRED");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
    }

    @Test
    @DisplayName("a1) 空供应商草稿只补供应商 → 保存成功 → 同一张单提交成功, 行价按主数据回解")
    void backfillSupplierOnlyThenSubmitSucceeds() {
        PurchaseOrder updated = service.updateDraftOrder(FACTORY, ORDER, supplierOnly(SUPPLIER));

        assertThat(updated.getSupplierId()).isEqualTo(SUPPLIER);
        assertThat(updated.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);

        PurchaseOrder submitted = service.submitOrder(FACTORY, ORDER, 1552L);

        assertThat(submitted.getStatus())
                .as("补完供应商之后提交仍然走不通 —— 422 的提示还是死路")
                .isEqualTo(PurchaseOrderStatus.FINANCE_APPROVED);
        assertThat(submitted.getSupplierId()).isEqualTo(SUPPLIER);
        assertThat(storedItems).hasSize(1);
        assertThat(storedItems.get(0).getUnitPrice())
                .as("自动草稿的 0 元行价没有按该供应商的供应关系回解")
                .isEqualByComparingTo("6");
        assertThat(storedItems.get(0).getQuantity())
                .as("补供应商不得改动缺口数量")
                .isEqualByComparingTo("56.74");
        // 提交成功会广播供给变化 —— 缺料实例靠它刷新在途量 (S4-11 线上看到的 PURCHASE_ORDER_SUPPLY_CHANGED)。
        verify(events).publishEvent(any(com.cretas.aims.event.PurchaseOrderSupplyChangedEvent.class));
    }

    @Test
    @DisplayName("a2) 按编辑弹窗实际发的形状补供应商 (带明细) → 保存成功 → 提交成功")
    void backfillSupplierWithEditorPayloadThenSubmitSucceeds() {
        PurchaseOrder updated = service.updateDraftOrder(FACTORY, ORDER, editorPayload(SUPPLIER, RELATION));

        assertThat(updated.getSupplierId()).isEqualTo(SUPPLIER);
        assertThat(storedItems).hasSize(1);
        PurchaseOrderItem line = storedItems.get(0);
        assertThat(line.getSupplierMaterialId()).isEqualTo(RELATION);
        assertThat(line.getQuantity()).isEqualByComparingTo("56.74");
        assertThat(line.getUnitPrice()).isEqualByComparingTo("6");

        PurchaseOrder submitted = service.submitOrder(FACTORY, ORDER, 1552L);

        assertThat(submitted.getStatus()).isEqualTo(PurchaseOrderStatus.FINANCE_APPROVED);
        assertThat(submitted.getSupplierId()).isEqualTo(SUPPLIER);
    }

    @Test
    @DisplayName("b) 已提交的单改供应商 → 409 拒绝, 供应商与状态都不变, 不落库")
    void submittedOrderSupplierCannotBeChanged() {
        order.setStatus(PurchaseOrderStatus.SUBMITTED);
        order.setSupplierId(SUPPLIER);

        assertThatThrownBy(() -> service.updateDraftOrder(FACTORY, ORDER, supplierOnly(OTHER_VALID_SUPPLIER)))
                .as("已提交的单被改了供应商 —— 状态闸没拦住 (换的是一家完全合格的供应商, 只有状态闸能拦)")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有草稿状态的订单可以编辑");

        assertThat(order.getSupplierId()).isEqualTo(SUPPLIER);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.SUBMITTED);
        verify(orderRepository, never()).save(any(PurchaseOrder.class));
    }

    @Test
    @DisplayName("b') 已审批的单同样拒绝改供应商")
    void approvedOrderSupplierCannotBeChanged() {
        order.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        order.setSupplierId(SUPPLIER);

        assertThatThrownBy(() -> service.updateDraftOrder(FACTORY, ORDER,
                editorPayload(OTHER_VALID_SUPPLIER, OTHER_VALID_RELATION)))
                .as("已审批的单被改了供应商 —— 状态闸没拦住")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有草稿状态的订单可以编辑");

        assertThat(order.getSupplierId()).isEqualTo(SUPPLIER);
        verify(orderRepository, never()).save(any(PurchaseOrder.class));
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("c) 别厂的供应商 → 拒绝, 草稿供应商仍为空, 不落库")
    void foreignFactorySupplierIsRejected() {
        assertThatThrownBy(() -> service.updateDraftOrder(FACTORY, ORDER, supplierOnly(FOREIGN_SUPPLIER)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不属于当前组织");

        assertThat(order.getSupplierId()).isNull();
        verify(orderRepository, never()).save(any(PurchaseOrder.class));
        // 查询带的是本厂 factoryId —— ⛔ 不是按 id 全局查再比厂。
        verify(supplierRepository).findByIdAndFactoryId(FOREIGN_SUPPLIER, FACTORY);
        verify(supplierRepository, never()).findByIdAndFactoryId(FOREIGN_SUPPLIER, OTHER_FACTORY);
    }

    @Test
    @DisplayName("d) 本厂供应商但不供应该原料 → 409 供应关系未启用, 不落库")
    void supplierWithoutActiveRelationIsRejected() {
        assertThatThrownBy(() -> service.updateDraftOrder(FACTORY, ORDER, supplierOnly(NON_SUPPLYING_SUPPLIER)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未启用所选物料的供应关系");

        verify(orderRepository, never()).save(any(PurchaseOrder.class));
    }
}
