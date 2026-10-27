package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateDeliveryShipmentRequest;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.CustomerStockFulfillmentMode;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ERP-S3-TOLL-DELIVERY-PREFILL-COMPANY-FG-001 (prod F006, 2026-10-27).
 *
 * <p>代加工 (TOLL_PROCESSING) 订单「新建发货单 → 一次性全量」后, 子发运单的 FIFO 预填写进了
 * 公司自有的期初成品批次, 而本订单生产入库的客户所有批次没被选上; 仓库确认随后被
 * {@code SalesFinishedGoodsOwnershipGuard} 以 409 拒绝 —— 代加工订单发不出货。
 *
 * <p>根因: {@code createDeliveryShipment} 新建的 {@link SalesDeliveryItem} 只写了
 * {@code deliveryRecordId}, 关联字段 {@code deliveryRecord} (insertable=false) 在同一个
 * 持久化上下文里一直是 null。分配服务按 {@code item.getDeliveryRecord()} 反查销售订单,
 * 拿到 null 就把它当成「未关联订单的普通发货」, 推荐和分配两处都走了公司库存分支。
 *
 * <p>⛔ 这个缺陷只在真实 Hibernate 会话里出现 (Mockito 桩出来的 item 关联是你想给什么就有什么),
 * 所以本用例走真实入口 {@link SalesService#createDeliveryShipment} + 真实 JPA。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("代加工订单子发运预填只能用同客户同订单的客户所有成品")
class TollDeliveryPrefillOwnershipIntegrationTest {

    private final String factoryId = "TOLLDLV-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired private SalesService salesService;
    @Autowired private SalesDeliveryBatchAllocationService batchAllocationService;
    @Autowired private FactoryRepository factoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ProductTypeRepository productTypeRepository;
    @Autowired private FactoryWarehouseRepository warehouseRepository;
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private SalesOrderItemRepository salesOrderItemRepository;
    @Autowired private SalesDeliveryRecordRepository deliveryRecordRepository;
    @Autowired private SalesDeliveryItemRepository deliveryItemRepository;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Autowired private SalesDeliveryItemBatchAllocationRepository allocationRepository;
    @Autowired private EntityManager entityManager;

    private Long userId;
    private String customerId;
    private String otherCustomerId;
    private String productTypeId;
    private String warehouseId;

    @BeforeEach
    void seed() {
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName("Toll delivery IT " + factoryId);
        factoryRepository.saveAndFlush(factory);

        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername("tolldlv-" + factoryId);
        user.setPasswordHash("$2a$10$dummyHashForTesting");
        user.setFullName("Toll delivery IT");
        user.setIsActive(true);
        userId = userRepository.saveAndFlush(user).getId();

        customerId = customer("A");
        otherCustomerId = customer("B");

        ProductType productType = new ProductType();
        productType.setFactoryId(factoryId);
        productType.setCode("PT-" + factoryId);
        productType.setName("代加工成品");
        productType.setUnit("箱");
        productType.setIsActive(true);
        productType.setCreatedBy(userId);
        productTypeId = productTypeRepository.saveAndFlush(productType).getId();

        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setFactoryId(factoryId);
        warehouse.setCode("WH-FG");
        warehouse.setName("成品仓");
        warehouse.setType(FactoryWarehouse.WarehouseType.FINISHED);
        warehouse.setIsActive(true);
        warehouseId = warehouseRepository.saveAndFlush(warehouse).getId();
    }

    @Test
    @DisplayName("代加工订单: 预填与推荐只给本客户本订单的客户所有批次, 公司期初库存不出现")
    void tollOrderPrefillsOnlyCustomerOwnedBatchOfSameOrder() {
        SalesOrder order = order(SalesProcessingMode.TOLL_PROCESSING, MaterialSupplyMode.CUSTOMER_SUPPLIED);
        // FEFO 排序上公司期初批次更早到期, 排在最前 —— 修复前预填选中的就是它 (prod 上的 QA-S3-1027-FG01)。
        String companyBatch = batch("FG-COMPANY", "20", InventoryOwnership.COMPANY_OWNED, null, null,
                LocalDate.now().minusDays(30));
        String otherOrderBatch = batch("FG-OTHER-ORDER", "20", InventoryOwnership.CUSTOMER_OWNED,
                customerId, UUID.randomUUID().toString(), LocalDate.now().minusDays(20));
        String otherCustomerBatch = batch("FG-OTHER-CUST", "20", InventoryOwnership.CUSTOMER_OWNED,
                otherCustomerId, order.getId(), LocalDate.now().minusDays(15));
        String ownBatch = batch("FG-OWN", "16", InventoryOwnership.CUSTOMER_OWNED,
                customerId, order.getId(), LocalDate.now().minusDays(1));

        SalesDeliveryRecord shipment = createFullShipment(order, "16");
        SalesDeliveryItem line = shipment.getItems().get(0);

        Map<String, BigDecimal> allocated = allocationsOf(line.getId());
        assertThat(allocated)
                .as("预填只能写本订单的客户所有批次; 不得出现公司批次 %s / 别的订单 %s / 别的客户 %s",
                        companyBatch, otherOrderBatch, otherCustomerBatch)
                .containsOnlyKeys(ownBatch);
        assertThat(allocated.get(ownBatch)).isEqualByComparingTo("16");

        // 「查看/修改批次」对话框的候选来源 = recommend-fifo (新请求, 新会话)
        entityManager.flush();
        entityManager.clear();
        List<Object> recommended = batchAllocationService.recommendFifo(factoryId, String.valueOf(line.getId()),
                        productTypeId, new BigDecimal("16"), "箱", null)
                .stream().map(r -> r.get("batchId")).collect(Collectors.toList());
        assertThat(recommended).containsExactly(ownBatch);
    }

    @Test
    @DisplayName("代加工订单没有本订单的客户所有成品: 不分配任何批次 (不拿公司库存凑)")
    void tollOrderWithoutCustomerStockAllocatesNothing() {
        SalesOrder order = order(SalesProcessingMode.TOLL_PROCESSING, MaterialSupplyMode.CUSTOMER_SUPPLIED);
        batch("FG-COMPANY", "50", InventoryOwnership.COMPANY_OWNED, null, null, LocalDate.now().minusDays(30));

        SalesDeliveryRecord shipment = createFullShipment(order, "16");

        assertThat(allocationsOf(shipment.getItems().get(0).getId())).isEmpty();
    }

    @Test
    @DisplayName("修复前写下的公司库存预填: 「查看/修改批次」把它标成不能发, 推荐给本订单批次")
    void staleCompanyAllocationOnTollLineIsFlaggedIneligible() {
        SalesOrder order = order(SalesProcessingMode.TOLL_PROCESSING, MaterialSupplyMode.CUSTOMER_SUPPLIED);
        String companyBatch = batch("FG-COMPANY", "20", InventoryOwnership.COMPANY_OWNED, null, null,
                LocalDate.now().minusDays(30));
        String ownBatch = batch("FG-OWN", "16", InventoryOwnership.CUSTOMER_OWNED,
                customerId, order.getId(), LocalDate.now().minusDays(1));
        SalesDeliveryRecord shipment = createFullShipment(order, "16");
        String lineId = String.valueOf(shipment.getItems().get(0).getId());
        // 模拟 prod 上 DLV-20261027-3560-01 的现状: 这一行已被写成公司批次 (绕过服务, 直接落一条旧分配)
        allocationRepository.deleteByFactoryIdAndDeliveryItemId(factoryId, lineId);
        SalesDeliveryItemBatchAllocation stale = new SalesDeliveryItemBatchAllocation();
        stale.setFactoryId(factoryId);
        stale.setDeliveryItemId(lineId);
        stale.setFinishedGoodsBatchId(companyBatch);
        stale.setBatchNumber("FG-COMPANY-" + factoryId);
        stale.setAllocatedQty(new BigDecimal("16"));
        stale.setUnit("箱");
        allocationRepository.saveAndFlush(stale);
        entityManager.clear();

        List<SalesDeliveryItemBatchAllocation> review = batchAllocationService.listForReview(factoryId, lineId);
        assertThat(review).hasSize(1);
        assertThat(review.get(0).getEligible()).isFalse();
        assertThat(review.get(0).getIneligibleReason()).contains("同一客户、同一销售订单");

        entityManager.clear();
        assertThat(batchAllocationService.recommendFifo(factoryId, lineId, productTypeId,
                        new BigDecimal("16"), "箱", null).stream().map(r -> r.get("batchId")))
                .containsExactly(ownBatch);
    }

    @Test
    @DisplayName("普通订单: 预填只用公司库存, 客户所有批次永不出现")
    void normalOrderNeverPrefillsCustomerOwnedBatch() {
        SalesOrder order = order(SalesProcessingMode.STANDARD_SALE, MaterialSupplyMode.FACTORY_SUPPLIED);
        String customerOwned = batch("FG-CUST", "50", InventoryOwnership.CUSTOMER_OWNED,
                customerId, order.getId(), LocalDate.now().minusDays(30));
        String companyBatch = batch("FG-COMPANY", "50", InventoryOwnership.COMPANY_OWNED, null, null,
                LocalDate.now().minusDays(1));

        SalesDeliveryRecord shipment = createFullShipment(order, "16");

        assertThat(allocationsOf(shipment.getItems().get(0).getId()))
                .as("普通订单不得预填客户所有批次 %s", customerOwned)
                .containsOnlyKeys(companyBatch);
    }

    // ------------------------------------------------------------------ fixtures

    private String customer(String suffix) {
        Customer customer = new Customer();
        customer.setFactoryId(factoryId);
        customer.setCode("CUST-" + suffix + "-" + factoryId);
        customer.setCustomerCode("CUST-" + suffix + "-" + factoryId);
        customer.setName("客户" + suffix);
        customer.setIsActive(true);
        customer.setCreatedBy(userId);
        return customerRepository.saveAndFlush(customer).getId();
    }

    private SalesOrder order(SalesProcessingMode processingMode, MaterialSupplyMode supplyMode) {
        SalesOrder order = new SalesOrder();
        order.setFactoryId(factoryId);
        order.setOrderNumber("SO-TOLLDLV-" + UUID.randomUUID());
        order.setCustomerId(customerId);
        order.setOrderDate(LocalDate.now());
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setProcessingMode(processingMode);
        order.setMaterialSupplyMode(supplyMode);
        order.setCustomerStockFulfillmentMode(CustomerStockFulfillmentMode.ORDER_DRIVEN);
        order.setCreatedBy(userId);
        return salesOrderRepository.saveAndFlush(order);
    }

    private String batch(String number, String qty, InventoryOwnership ownership, String ownerCustomerId,
                         String sourceSalesOrderId, LocalDate productionDate) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId(factoryId);
        batch.setBatchNumber(number + "-" + factoryId);
        batch.setProductTypeId(productTypeId);
        batch.setProducedQuantity(new BigDecimal(qty));
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit("箱");
        batch.setWarehouseId(warehouseId);
        batch.setStatus("AVAILABLE");
        batch.setProductionDate(productionDate);
        batch.setExpireDate(productionDate.plusDays(180));
        batch.setOwnership(ownership);
        batch.setOwnerCustomerId(ownerCustomerId);
        batch.setSourceSalesOrderId(sourceSalesOrderId);
        return finishedGoodsBatchRepository.saveAndFlush(batch).getId();
    }

    /** 母发货单 + 一行, 然后走真实入口「一次性全量」建子发运单 (= 销售详情 → 新建发货单)。 */
    private SalesDeliveryRecord createFullShipment(SalesOrder order, String qty) {
        SalesOrderItem orderItem = new SalesOrderItem();
        orderItem.setSalesOrderId(order.getId());
        orderItem.setProductTypeId(productTypeId);
        orderItem.setProductName("代加工成品");
        orderItem.setUnit("箱");
        orderItem.setQuantity(new BigDecimal(qty));
        orderItem.setProcessingMode(order.getProcessingMode());
        orderItem.setMaterialSupplyMode(order.getMaterialSupplyMode());
        Long orderItemId = salesOrderItemRepository.saveAndFlush(orderItem).getId();

        SalesDeliveryRecord master = new SalesDeliveryRecord();
        master.setFactoryId(factoryId);
        master.setDeliveryNumber("DLV-TOLLDLV-" + UUID.randomUUID().toString().substring(0, 8));
        master.setRecordRole("MASTER");
        master.setSalesOrderId(order.getId());
        master.setCustomerId(order.getCustomerId());
        master.setDeliveryDate(LocalDate.now());
        master.setDeliveryAddress("测试地址");
        master.setStatus(SalesDeliveryStatus.DRAFT);
        master.setShippedBy(userId);
        master.setTotalAmount(BigDecimal.ZERO);
        master = deliveryRecordRepository.saveAndFlush(master);

        SalesDeliveryItem masterItem = new SalesDeliveryItem();
        masterItem.setDeliveryRecordId(master.getId());
        masterItem.setSalesOrderItemId(orderItemId);
        masterItem.setProductTypeId(productTypeId);
        masterItem.setProductName("代加工成品");
        masterItem.setDeliveredQuantity(new BigDecimal(qty));
        masterItem.setUnit("箱");
        Long masterItemId = deliveryItemRepository.saveAndFlush(masterItem).getId();
        // 母单在新会话里读, 和线上一次 HTTP 请求一致
        entityManager.flush();
        entityManager.clear();

        CreateDeliveryShipmentRequest.Item item = new CreateDeliveryShipmentRequest.Item();
        item.setParentDeliveryItemId(masterItemId);
        item.setQuantity(new BigDecimal(qty));
        CreateDeliveryShipmentRequest request = new CreateDeliveryShipmentRequest();
        request.setDeliveryMode("FULL");
        request.setDeliveryMethod("SELF_PICKUP");
        request.setPlannedShipmentDate(LocalDate.now());
        request.setItems(List.of(item));
        return salesService.createDeliveryShipment(factoryId, master.getId(), request, userId);
    }

    private Map<String, BigDecimal> allocationsOf(Long deliveryItemId) {
        entityManager.flush();
        return allocationRepository.findByFactoryIdAndDeliveryItemId(factoryId, String.valueOf(deliveryItemId))
                .stream().collect(Collectors.toMap(
                        SalesDeliveryItemBatchAllocation::getFinishedGoodsBatchId,
                        SalesDeliveryItemBatchAllocation::getAllocatedQty));
    }
}
