package com.cretas.aims.integration;

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
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.ProductionCostConsistency;
import com.cretas.aims.service.inventory.FgReservationLedgerService;
import com.cretas.aims.service.inventory.SalesOrderSuppliedMaterialRequirementService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.sales.OperationalQuoteOrderGuard;
import com.cretas.aims.service.sales.impl.SalesDeliveryBatchAllocationServiceImpl;
import com.cretas.aims.service.warehouse.WarehouseLocationCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q3 (2026-11-09): 只剩规格不一致的库存时, 建子发运单不能整单失败。
 *
 * <p>根因是事务边界: {@code SalesServiceImpl.createDeliveryShipment} 是外层事务,
 * 预填调 {@code SalesDeliveryBatchAllocationServiceImpl.autoAllocateFifo} (自己也是 @Transactional, 加入外层),
 * 它抛 409 BATCH_PACKAGING_SPEC_MISMATCH 穿过代理时把外层标成 rollback-only; 外层 catch 住继续,
 * 提交时 UnexpectedRollbackException —— 子单整单回滚。⛔ 直接 new 出来调方法 (没有代理) 测不出这件事
 * (AutoFifoPrefillOnShipmentCreateTest 的「预填抛异常也成功」就是这样绿的)。
 *
 * <p>所以这里是真 Spring 上下文: 两个服务都是容器里的事务代理 (断言里先证明这一点), 真 JPA, 提交后用 JDBC 读回。
 * 默认跑 H2 (PR 车道 / 本地); 设了 CRETAS_WORKFLOW_PG_URL 时跑真 PostgreSQL (postgres-reversal-concurrency 车道)。
 */
@SpringBootTest(classes = ShipmentPrefillPackagingMismatchTransactionContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "management.endpoint.health.validate-group-membership=false"
})
@ActiveProfiles("test")
@DisplayName("Q3: 只剩规格不一致库存时子发运单照样建成, 原因可见")
class ShipmentPrefillPackagingMismatchTransactionContractTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({SalesServiceImpl.class, SalesDeliveryBatchAllocationServiceImpl.class, WarehouseResolver.class})
    static class Runtime {
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || url.isBlank()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:q3shipment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                    + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
            return;
        }
        String safe = DisposablePostgresTargetGuard.requireSafeUrl(url);
        String bound = safe.contains("stringtype=") ? safe : safe + (safe.contains("?") ? "&" : "?") + "stringtype=unspecified";
        registry.add("spring.datasource.url", () -> bound);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    // 子发运创建路径之外的协作者: 本合约只测事务边界与预填结果, 它们各有自己的测试
    @MockBean WarehouseLocationCatalog locationCatalog;
    @MockBean SalesOrderSuppliedMaterialRequirementService suppliedMaterialRequirementService;
    @MockBean OperationalQuoteOrderGuard operationalQuoteOrderGuard;
    @MockBean ProductionCostConsistency productionCostConsistency;
    @MockBean ArApService arApService;
    @MockBean ProductPackagingSpecService productPackagingSpecService;
    @MockBean FgReservationLedgerService reservationLedgerService;

    @Autowired SalesService salesService;
    @Autowired com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService allocationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired FactoryRepository factories;
    @Autowired UserRepository users;
    @Autowired CustomerRepository customers;
    @Autowired ProductTypeRepository productTypes;
    @Autowired FactoryWarehouseRepository warehouses;
    @Autowired SalesOrderRepository orders;
    @Autowired SalesOrderItemRepository orderItems;
    @Autowired SalesDeliveryRecordRepository deliveries;
    @Autowired SalesDeliveryItemRepository deliveryItems;
    @Autowired FinishedGoodsBatchRepository finishedGoods;

    private final String factoryId = "Q3SHIP-" + UUID.randomUUID().toString().substring(0, 8);
    private Long userId;
    private String customerId;
    private String productTypeId;
    private String warehouseId;

    @BeforeEach
    void seed() {
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName("Q3 shipment " + factoryId);
        factories.saveAndFlush(factory);

        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername("q3-" + factoryId);
        user.setPasswordHash("$2a$10$dummyHashForTesting");
        user.setFullName("Q3 销售");
        user.setIsActive(true);
        userId = users.saveAndFlush(user).getId();

        Customer customer = new Customer();
        customer.setFactoryId(factoryId);
        customer.setCode("CUST-" + factoryId);
        customer.setCustomerCode("CUST-" + factoryId);
        customer.setName("客户Q3");
        customer.setIsActive(true);
        customer.setCreatedBy(userId);
        customerId = customers.saveAndFlush(customer).getId();

        ProductType product = new ProductType();
        product.setFactoryId(factoryId);
        product.setCode("PT-" + factoryId);
        product.setName("卤猪蹄");
        product.setUnit("box");
        product.setIsActive(true);
        product.setCreatedBy(userId);
        productTypeId = productTypes.saveAndFlush(product).getId();

        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setFactoryId(factoryId);
        warehouse.setCode("WH-FG");
        warehouse.setName("成品仓");
        warehouse.setType(FactoryWarehouse.WarehouseType.FINISHED);
        warehouse.setIsActive(true);
        warehouseId = warehouses.saveAndFlush(warehouse).getId();
    }

    @Test
    @DisplayName("只剩规格不一致的批次: 子单提交成功、没写任何分配、响应里有规格不一致的原因; 推荐里看得到那批 (确认入口)")
    void childShipmentCommitsWithPackagingMismatchNotice() {
        assertThat(AopUtils.isAopProxy(salesService)).as("销售服务必须是容器里的事务代理").isTrue();
        assertThat(AopUtils.isAopProxy(allocationService)).as("分配服务必须是容器里的事务代理").isTrue();
        String mismatched = batch("FG-Q3-10", "20", "10");

        SalesDeliveryRecord shipment = createShipment("12", "6");

        assertThat(shipment.getAllocationNotices()).hasSize(1);
        Map<String, Object> notice = shipment.getAllocationNotices().get(0);
        assertThat(notice.get("code")).isEqualTo("BATCH_PACKAGING_SPEC_MISMATCH");
        assertThat(String.valueOf(notice.get("message"))).contains("规格").contains("不一致");
        assertThat(notice.get("productName")).isEqualTo("卤猪蹄");
        // 提交后另起连接读回: 子单与行真的落库了, 分配一行没写
        assertThat(jdbc.queryForObject("select count(*) from sales_delivery_records where id = ? and record_role = 'SHIPMENT'",
                Long.class, shipment.getId())).isEqualTo(1L);
        Long lineId = shipment.getItems().get(0).getId();
        assertThat(jdbc.queryForObject("select count(*) from sales_delivery_items where id = ?", Long.class, lineId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from sales_delivery_item_batch_allocations where delivery_item_id = ?",
                Long.class, String.valueOf(lineId))).isZero();
        // 确认入口: 分配对话框的推荐里那批货在, 标着规格不一致 (前端据此问「确认换算」再带 packagingConversionConfirmed 提交)
        List<Map<String, Object>> recommended = allocationService.recommendFifo(factoryId, String.valueOf(lineId),
                productTypeId, new BigDecimal("6"), "box", null);
        assertThat(recommended).extracting(r -> r.get("batchId")).containsExactly(mismatched);
        assertThat(recommended.get(0).get("packagingMismatch")).isEqualTo(true);
    }

    @Test
    @DisplayName("阳性对照: 规格一致的批次照常自动预填, 没有说明")
    void matchingSpecIsPrefilledWithoutNotice() {
        String matching = batch("FG-Q3-12", "20", "12");

        SalesDeliveryRecord shipment = createShipment("12", "6");

        assertThat(shipment.getAllocationNotices()).isNullOrEmpty();
        Long lineId = shipment.getItems().get(0).getId();
        assertThat(jdbc.queryForObject("select finished_goods_batch_id from sales_delivery_item_batch_allocations "
                + "where delivery_item_id = ?", String.class, String.valueOf(lineId))).isEqualTo(matching);
    }

    @Test
    @DisplayName("一批都没有: 子单照样建成, 说明是库存不够 (不是规格问题)")
    void noStockAtAllSaysShortfall() {
        SalesDeliveryRecord shipment = createShipment("12", "6");

        assertThat(shipment.getAllocationNotices()).hasSize(1);
        assertThat(shipment.getAllocationNotices().get(0).get("code")).isEqualTo("FIFO_STOCK_SHORTFALL");
        assertThat(jdbc.queryForObject("select count(*) from sales_delivery_records where id = ?",
                Long.class, shipment.getId())).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ fixtures

    /** 成品批: 1箱 = factor 袋。 */
    private String batch(String number, String qty, String bagsPerBox) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setFactoryId(factoryId);
        batch.setBatchNumber(number + "-" + factoryId);
        batch.setProductTypeId(productTypeId);
        batch.setProducedQuantity(new BigDecimal(qty));
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnit("box");
        batch.setPackagingUnit("box");
        batch.setPackagingBaseUnit("bag");
        batch.setPackagingFactor(new BigDecimal(bagsPerBox));
        batch.setWarehouseId(warehouseId);
        batch.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        batch.setProductionDate(LocalDate.now().minusDays(2));
        batch.setExpireDate(LocalDate.now().plusDays(180));
        batch.setOwnership(InventoryOwnership.COMPANY_OWNED);
        return finishedGoods.saveAndFlush(batch).getId();
    }

    /** 母发货单一行 (规格 1箱 = lineBagsPerBox 袋), 然后走真实入口建子发运单。 */
    private SalesDeliveryRecord createShipment(String lineBagsPerBox, String qty) {
        SalesOrder order = new SalesOrder();
        order.setFactoryId(factoryId);
        order.setOrderNumber("SO-Q3-" + UUID.randomUUID());
        order.setCustomerId(customerId);
        order.setOrderDate(LocalDate.now());
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setProcessingMode(SalesProcessingMode.STANDARD_SALE);
        order.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        order.setCustomerStockFulfillmentMode(CustomerStockFulfillmentMode.ORDER_DRIVEN);
        order.setCreatedBy(userId);
        order = orders.saveAndFlush(order);

        SalesOrderItem orderItem = new SalesOrderItem();
        orderItem.setSalesOrderId(order.getId());
        orderItem.setProductTypeId(productTypeId);
        orderItem.setProductName("卤猪蹄");
        orderItem.setUnit("box");
        orderItem.setQuantity(new BigDecimal(qty));
        orderItem.setProcessingMode(order.getProcessingMode());
        orderItem.setMaterialSupplyMode(order.getMaterialSupplyMode());
        Long orderItemId = orderItems.saveAndFlush(orderItem).getId();

        SalesDeliveryRecord master = new SalesDeliveryRecord();
        master.setFactoryId(factoryId);
        master.setDeliveryNumber("DLV-Q3-" + UUID.randomUUID().toString().substring(0, 8));
        master.setRecordRole("MASTER");
        master.setSalesOrderId(order.getId());
        master.setCustomerId(customerId);
        master.setDeliveryDate(LocalDate.now());
        master.setDeliveryAddress("测试地址");
        master.setStatus(SalesDeliveryStatus.DRAFT);
        master.setShippedBy(userId);
        master.setTotalAmount(BigDecimal.ZERO);
        master = deliveries.saveAndFlush(master);

        SalesDeliveryItem masterItem = new SalesDeliveryItem();
        masterItem.setDeliveryRecordId(master.getId());
        masterItem.setSalesOrderItemId(orderItemId);
        masterItem.setProductTypeId(productTypeId);
        masterItem.setProductName("卤猪蹄");
        masterItem.setDeliveredQuantity(new BigDecimal(qty));
        masterItem.setUnit("box");
        masterItem.setPackagingUnit("box");
        masterItem.setPackagingBaseUnit("bag");
        masterItem.setPackagingFactor(new BigDecimal(lineBagsPerBox));
        Long masterItemId = deliveryItems.saveAndFlush(masterItem).getId();

        CreateDeliveryShipmentRequest.Item item = new CreateDeliveryShipmentRequest.Item();
        item.setParentDeliveryItemId(masterItemId);
        item.setQuantity(new BigDecimal(qty));
        CreateDeliveryShipmentRequest request = new CreateDeliveryShipmentRequest();
        request.setIdempotencyKey("q3-" + UUID.randomUUID());
        request.setDeliveryMode("FULL");
        request.setDeliveryMethod("SELF_PICKUP");
        request.setDeliveryAddress("测试地址");
        request.setPlannedShipmentDate(LocalDate.now());
        request.setItems(List.of(item));
        return salesService.createDeliveryShipment(factoryId, master.getId(), request, userId);
    }
}
