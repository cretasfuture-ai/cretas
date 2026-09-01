package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷 18 —— 「报错指路让去配主数据, 配完不回填草稿单, 用户照做仍然失败」。
 *
 * <h3>被守的行为</h3>
 * 生产实证 (2026-09-01, LIUSHANMEN): {@code PO-20260901-0001} 提交审批报
 * {@code PURCHASE_PRICE_REQUIRED} → owner 照提示把默认采购价填成 8.85 并保存成功 →
 * 回到同一张单重新提交 → <b>报一模一样的错</b>。
 *
 * <p>本契约钉的是: <b>提交审批时会重新读一次主数据</b>, 而不是只读行上那个
 * 转 PO 那一刻抄进来的、必为 null 的值。
 *
 * <h3>⚠️ 断言打在真实产品入口上</h3>
 * 三条都走 {@link PurchaseServiceImpl#submitOrder}, ⛔ 不直接调私有回解方法 ——
 * 「机制在、没接上」正是这条链上已经发生过的事 (转 PO 那侧其实一直会解主数据,
 * 坏的是草稿单存在之后没有任何一处再解一次)。
 *
 * <p>本用例里 {@code workflowEngine} 为 null, 所以<b>通过价格闸之后</b>必然撞上
 * {@code OA_RUNTIME_UNAVAILABLE}。⇒ 「抛的是 503 而不是 422」就是「价格闸放行了」的读数。
 *
 * <h3>阴性对照</h3>
 * {@link #submitStillRefusesWhenMasterDataHasNoPriceEither} 是让它红的那一条:
 * 主数据三个价源全空时仍须以 {@code PURCHASE_PRICE_REQUIRED} 拒绝。
 * ⛔ 回解不得兜底成 0 —— 兜底的默认值会把「我不知道」翻译成「是 0」。
 */
class PurchaseSubmitPriceBackfillContractTest {

    private static final String FACTORY = "LIUSHANMEN";
    private static final String SUPPLIER = "supplier-235005";
    private static final String MATERIAL = "material-haitian";
    private static final String ORDER = "order-20260901-0001";

    private final PurchaseOrderRepository orderRepository = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository itemRepository = mock(PurchaseOrderItemRepository.class);
    private final SupplierRepository supplierRepository = mock(SupplierRepository.class);
    private final RawMaterialTypeRepository materialRepository = mock(RawMaterialTypeRepository.class);
    private final SupplierMaterialRepository relationRepository = mock(SupplierMaterialRepository.class);
    private final SupplierMaterialPurchaseSpecRepository specRepository =
            mock(SupplierMaterialPurchaseSpecRepository.class);

    private PurchaseServiceImpl service;
    private RawMaterialType material;
    private SupplierMaterial relation;
    private PurchaseOrder order;
    private PurchaseOrderItem item;

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(orderRepository, itemRepository, null,
                supplierRepository, materialRepository, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", relationRepository);
        ReflectionTestUtils.setField(service, "supplierMaterialPurchaseSpecRepository", specRepository);

        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER);
        supplier.setFactoryId(FACTORY);
        supplier.setIsActive(true);
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER, FACTORY)).thenReturn(Optional.of(supplier));

        material = new RawMaterialType();
        material.setId(MATERIAL);
        material.setFactoryId(FACTORY);
        material.setName("13.9kg海天草菇老抽");
        material.setUnit("kg");
        // ⚠️ 物料参考价刻意留空 —— 否则回解会命中它, 就分不清「读到了供应关系」
        //    还是「兜到了物料参考价」。要量的是供应关系那一档。
        material.setUnitPrice(null);
        material.setTaxTreatment(TaxTreatment.TAXABLE);
        material.setTaxRate(TaxRate.TAX_13);
        when(materialRepository.findById(MATERIAL)).thenReturn(Optional.of(material));

        relation = new SupplierMaterial();
        relation.setId("relation-1");
        relation.setFactoryId(FACTORY);
        relation.setSupplierId(SUPPLIER);
        relation.setMaterialTypeId(MATERIAL);
        relation.setPurchaseUnit("kg");
        relation.setActive(true);
        // 生产复现的第 2 步: owner 事后把默认采购价填成 8.85。
        relation.setDefaultPurchasePrice(new BigDecimal("8.85"));
        when(relationRepository.findByFactoryIdAndSupplierIdAndMaterialTypeId(FACTORY, SUPPLIER, MATERIAL))
                .thenReturn(Optional.of(relation));
        when(relationRepository.existsByFactoryIdAndSupplierIdAndMaterialTypeIdAndActiveTrue(
                FACTORY, SUPPLIER, MATERIAL)).thenReturn(true);

        // 生产复现的第 1 步的产物: 草稿 PO 的行价是 null —— 转 PO 那一刻主数据还没配。
        item = new PurchaseOrderItem();
        item.setId(1L);
        item.setPurchaseOrderId(ORDER);
        item.setMaterialTypeId(MATERIAL);
        item.setMaterialName("13.9kg海天草菇老抽");
        item.setQuantity(new BigDecimal("100"));
        item.setUnit("kg");
        item.setPriceUnit("kg");
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        item.setTaxRate(new BigDecimal("13"));
        item.setUnitPrice(null);
        when(itemRepository.findByPurchaseOrderId(ORDER)).thenReturn(List.of(item));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        order = new PurchaseOrder();
        order.setId(ORDER);
        order.setFactoryId(FACTORY);
        order.setOrderNumber("PO-20260901-0001");
        order.setSupplierId(SUPPLIER);
        order.setStatus(PurchaseOrderStatus.DRAFT);
        when(orderRepository.findByIdAndFactoryIdForUpdate(ORDER, FACTORY)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 通过了价格闸的读数 —— workflowEngine 为 null, 所以下一道必然是 503。 */
    private void submitAndExpectToReachTheWorkflowStage() {
        assertThatThrownBy(() -> service.submitOrder(FACTORY, ORDER, 1641L))
                .as("提交没能走到 OA 那一步, 说明价格闸仍然拦着 —— 死结没解开")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审批运行时暂不可用");
    }

    @Test
    @DisplayName("主数据事后补的默认采购价, 提交审批时会被重新读到")
    void submitReresolvesLinePriceFromSupplierRelation() {
        when(specRepository.findById(any())).thenReturn(Optional.empty());

        submitAndExpectToReachTheWorkflowStage();

        assertThat(item.getUnitPrice())
                .as("行价没有被回解 —— 用户照提示配完主数据回来仍然会撞同一个错")
                .isEqualByComparingTo("8.85");
        assertThat(order.getTotalAmount())
                .as("回解了价却没重算单头金额 —— 单子会带着 0 元进审批")
                .isEqualByComparingTo("885.00");
        assertThat(order.getTaxAmount())
                .as("税额未随回解重算")
                .isEqualByComparingTo("115.05");
        assertThat(item.getUntaxedAmountSnapshot())
                .as("金额快照未随回解重算")
                .isEqualByComparingTo("885.00");
    }

    @Test
    @DisplayName("采购包装规格的报价优先于供应关系默认价")
    void specQuotedPriceWinsOverRelationDefault() {
        SupplierMaterialPurchaseSpec spec = new SupplierMaterialPurchaseSpec();
        spec.setId("spec-1");
        spec.setFactoryId(FACTORY);
        spec.setSupplierMaterialId("relation-1");
        spec.setMaterialTypeId(MATERIAL);
        spec.setPurchasePackageUnit("kg");
        spec.setInventoryBaseUnit("kg");
        spec.setConversionFactor(BigDecimal.ONE);
        spec.setQuotedPrice(new BigDecimal("8.50"));
        spec.setActive(true);
        item.setSupplierMaterialId("relation-1");
        item.setPurchasePackagingSpecId("spec-1");
        when(specRepository.findById("spec-1")).thenReturn(Optional.of(spec));

        submitAndExpectToReachTheWorkflowStage();

        assertThat(item.getUnitPrice())
                .as("回解走的不是既有的 resolvePurchaseUnitPrice 那份权威顺序")
                .isEqualByComparingTo("8.50");
    }

    @Test
    @DisplayName("阴性对照: 主数据也没有价时, 仍以 PURCHASE_PRICE_REQUIRED 拒绝, ⛔ 不兜底成 0")
    void submitStillRefusesWhenMasterDataHasNoPriceEither() {
        relation.setDefaultPurchasePrice(null);
        when(specRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitOrder(FACTORY, ORDER, 1641L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("采购物料未配置有效采购价")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo("PURCHASE_PRICE_REQUIRED");

        assertThat(item.getUnitPrice())
                .as("解不出来就该保持为空 —— 落 0 会把「我不知道」变成「免费」")
                .isNull();
    }

    @Test
    @DisplayName("行上已有的价不被主数据改写 —— 谈好的一次性价格不许被悄悄覆盖")
    void existingLinePriceIsNeverOverwrittenByMasterData() {
        item.setUnitPrice(new BigDecimal("12.00"));
        when(specRepository.findById(any())).thenReturn(Optional.empty());

        submitAndExpectToReachTheWorkflowStage();

        assertThat(item.getUnitPrice())
                .as("回解越界了: 它只该补空的行, 不该覆盖已有的价")
                .isEqualByComparingTo("12.00");
    }
}
