package com.cretas.aims.service.finance;

import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.controller.CanvasFactoryConfigController;
import com.cretas.aims.controller.FactorySettingsController;
import com.cretas.aims.controller.finance.FinanceReportController;
import com.cretas.aims.dto.FactorySettingsDTO;
import com.cretas.aims.dto.finance.report.ShippedGrossProfitDTO;
import com.cretas.aims.dto.finance.report.ShippedRevenueBasis;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.FactorySettingsServiceImpl;
import com.cretas.aims.service.inventory.ReturnedGoodsCostResolver;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Import({ShipmentSkuMarginService.class, ShipmentNetRevenueService.class, SalesReturnCostService.class,
        ReturnedGoodsCostResolver.class, UnitContractServiceImpl.class})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ShippedGrossProfitPostgresContractTest {
    private static final String F = "F-GP-BASIS";
    private static final String OTHER = "F-GP-BASIS-OTHER";
    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = START.plusDays(14);
    private static final String SETTINGS_URL = "/api/mobile/" + F + "/canvas-factory-config/settings";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired FactorySettingsRepository settings;
    @Autowired ShipmentSkuMarginService service;
    @MockBean IncomeStatementService incomeStatements;

    @BeforeEach
    void requirePostgres() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
    }

    @AfterEach
    void clearPriceContext() {
        PriceSensitiveContext.clear();
    }

    @Test
    void defaultGrossKeepsShippedRevenueAndActualCostDespiteDiscountsAndReturns() {
        scenario();
        var result = service.summarize(F, START, END);
        assertThat(result.getBasis()).isEqualTo(ShippedRevenueBasis.GROSS);
        assertThat(result.getRevenueBasis()).contains("折扣前，未扣退货");
        assertAmounts(result, "120", "36", "84", "70");
    }

    @Test
    void netSubtractsPartialShipmentDiscountAndOnlyThisPeriodsCompletedUntaxedSalesReturns() throws Exception {
        Tenant tenant = scenario();
        var config = canvasConfig();
        config.perform(get(SETTINGS_URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippedRevenueBasis").value("GROSS"));
        config.perform(put(SETTINGS_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippedRevenueBasis\":\"INVALID\",\"version\":0}"))
                .andExpect(status().isBadRequest());
        config.perform(put(SETTINGS_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippedRevenueBasis\":\"NET\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shippedRevenueBasis").value("NET"));
        flush();
        config.perform(get(SETTINGS_URL)).andExpect(jsonPath("$.data.shippedRevenueBasis").value("NET"));
        assertThat(settings.findProductionSettingsByFactoryId(F)).contains("\"defaultBatchSize\":120");

        var legacySettings = new FactorySettingsServiceImpl(settings, new ObjectMapper(), mock(FactoryRepository.class));
        legacySettings.updateProductionSettings(F, FactorySettingsDTO.ProductionSettings.builder().defaultBatchSize(140).build());
        flush();
        assertThat(legacySettings.getProductionSettings(F).getDefaultBatchSize()).isEqualTo(140);
        config.perform(get(SETTINGS_URL)).andExpect(jsonPath("$.data.shippedRevenueBasis").value("NET"));

        var result = service.summarize(F, START, END); // 120 - 12 (30 × 120 / 300) - 7
        assertThat(result.getBasis()).isEqualTo(ShippedRevenueBasis.NET);
        assertThat(result.getRevenueBasis()).contains("扣折扣与退货后净额", "不含税");
        assertThat(result.getCostBasis()).contains("扣本期已完成销售退货的回库批次成本");
        assertAmounts(result, "101", "32.50", "68.50", "67.82");
        report(tenant.user(), true).andExpect(jsonPath("$.data.basis").value("NET"))
                .andExpect(jsonPath("$.data.revenue").value(101.00))
                .andExpect(jsonPath("$.data.cost").value(32.50))
                .andExpect(jsonPath("$.data.grossProfit").value(68.50))
                .andExpect(jsonPath("$.data.marginRate").value(67.82));
        report(tenant.user(), false).andExpect(jsonPath("$.data.basis").value("NET"))
                .andExpect(jsonPath("$.data.revenueBasis", containsString("扣折扣与退货后净额")))
                .andExpect(jsonPath("$.data.shipmentLineCount").value(2))
                .andExpect(jsonPath("$.data.revenue").value(nullValue()))
                .andExpect(jsonPath("$.data.cost").value(nullValue()))
                .andExpect(jsonPath("$.data.grossProfit").value(nullValue()))
                .andExpect(jsonPath("$.data.marginRate").value(nullValue()));
    }

    @Test
    void netReportsIncompleteCostWhenCompletedPhysicalReturnLacksInboundBatch() {
        scenario();
        jdbc.update("UPDATE factory_settings SET production_settings=? WHERE factory_id=?",
                "{\"shippedRevenueBasis\":\"NET\"}", F);
        jdbc.update("UPDATE finished_goods_batches SET deleted_at=CURRENT_TIMESTAMP WHERE batch_number=? AND factory_id=?",
                "RTN-current-1", F);
        var result = service.summarize(F, START, END);
        assertThat(result.getStatus()).isEqualTo("COST_INCOMPLETE");
        assertThat(result.getUnavailableReason()).contains("退货回库批次成本不完整", "缺少回库证据");
        assertThat(result.getRevenue()).isEqualByComparingTo("101");
        assertThat(result.getCost()).isNull();
        assertThat(result.getGrossProfit()).isNull();
        assertThat(result.getMarginRate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{\"shippedRevenueBasis\":\"net\"}", "{\"shippedRevenueBasis\":42}"})
    void corruptStoredBasisKeepsGrossReportAndConfigReadableAndLegacySaveCanRepair(String corruptSettings) throws Exception {
        Tenant tenant = scenario();
        jdbc.update("UPDATE factory_settings SET production_settings=? WHERE factory_id=?", corruptSettings, F);
        report(tenant.user(), true).andExpect(jsonPath("$.data.basis").value("GROSS"))
                .andExpect(jsonPath("$.data.revenue").value(120))
                .andExpect(jsonPath("$.data.cost").value(36))
                .andExpect(jsonPath("$.data.grossProfit").value(84))
                .andExpect(jsonPath("$.data.marginRate").value(70));

        var config = canvasConfig();
        config.perform(get(SETTINGS_URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippedRevenueBasis").value("GROSS"));
        config.perform(put(SETTINGS_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippedRevenueBasis\":\"net\",\"version\":0}"))
                .andExpect(status().isBadRequest());
        assertThat(settings.findProductionSettingsByFactoryId(F)).isEqualTo(corruptSettings);

        var legacySettings = new FactorySettingsServiceImpl(settings, new ObjectMapper(), mock(FactoryRepository.class));
        var legacy = standaloneSetup(new FactorySettingsController(legacySettings, null))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        legacy.perform(put("/api/mobile/{factoryId}/settings/production", F)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultBatchSize\":140}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.defaultBatchSize").value(140));
        flush();
        assertThat(legacySettings.getProductionSettings(F).getDefaultBatchSize()).isEqualTo(140);
        assertThat(settings.findProductionSettingsByFactoryId(F)).doesNotContain("shippedRevenueBasis");
        config.perform(get(SETTINGS_URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippedRevenueBasis").value("GROSS"));
    }

    @Test
    void netCarriesDiscountRoundingAcrossPeriodsAndHonorsActualShipmentSequence() {
        Tenant tenant = tenant(F);
        var order = order(tenant, "cent", "3", "0.01");
        var item = orderItem(tenant, order, "3", "1");
        shipment(tenant, order, "current", START, item, "1", "1");
        shipment(tenant, order, "later", END.plusDays(1), item, "1", "1");
        shipment(tenant, order, "prior", START.minusDays(1), item, "1", "1");
        basis(F, ShippedRevenueBasis.NET);
        flush();
        assertThat(service.summarize(F, START, END).getRevenue()).isEqualByComparingTo("0.99");
    }

    @Test
    void explicitZeroAndAbsenceOfReturnsAreKnownButMissingDiscountOrReturnAmountIsReported() {
        Tenant tenant = tenant(F);
        var order = order(tenant, "zero", "20", "0");
        var item = orderItem(tenant, order, "2", "10");
        var delivery = shipment(tenant, order, "current", START, item, "2", "10");
        basis(F, ShippedRevenueBasis.NET);
        flush();
        assertThat(service.summarize(F, START, END).getRevenue()).isEqualByComparingTo("20");
        addReturn(tenant, "zero", START, ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, "0", false);
        flush();
        assertThat(service.summarize(F, START, END).getRevenue()).isEqualByComparingTo("20");

        jdbc.update("UPDATE sales_orders SET discount_amount=NULL WHERE id=?", order.getId());
        assertIncomplete("订单折扣资料");
        jdbc.update("UPDATE sales_orders SET discount_amount=2 WHERE id=?", order.getId());
        jdbc.update("UPDATE sales_order_items SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", item.getId());
        assertIncomplete("订单折扣无法分摊");
        jdbc.update("UPDATE sales_order_items SET deleted_at=NULL WHERE id=?", item.getId());

        Tenant other = tenant(OTHER);
        var foreign = order(other, "foreign", "20", "2");
        flush();
        jdbc.update("UPDATE sales_delivery_records SET sales_order_id=? WHERE id=?", foreign.getId(), delivery.getId());
        assertIncomplete("本厂订单折扣资料");
        jdbc.update("UPDATE sales_delivery_records SET sales_order_id=? WHERE id=?", order.getId(), delivery.getId());

        addReturn(tenant, "missing", START, ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, null, false);
        flush();
        assertIncomplete("销售退货缺少有效的不含税金额");
    }

    @Test
    void netReturnsNoShipmentAndNoAmountsWhenOnlyEarlierShipmentsAreReturned() {
        Tenant tenant = tenant(F);
        var order = order(tenant, "old", "20", "0");
        shipment(tenant, order, "old", START.minusDays(1), orderItem(tenant, order, "2", "10"), "2", "10");
        basis(F, ShippedRevenueBasis.NET);
        flush();
        assertThat(service.summarize(F, START, END).getStatus()).isEqualTo("NO_SHIPMENT");
        var returned = addReturn(tenant, "only-return", END, ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, "7", false);
        returned.setSourceOrderId(order.getId());
        restock(tenant, returned, em.find(FinishedGoodsBatch.class, tenant.batch()));
        flush();
        var result = service.summarize(F, START, END);
        assertThat(result.getStatus()).isEqualTo("NO_SHIPMENT");
        assertThat(result.getUnavailableReason()).contains("所选期间没有已确认发货");
        assertThat(result.getShipmentLineCount()).isZero();
        assertThat(result.getRevenue()).isNull();
        assertThat(result.getCost()).isNull();
        assertThat(result.getGrossProfit()).isNull();
        assertThat(result.getMarginRate()).isNull();
    }

    private Tenant scenario() {
        Tenant tenant = tenant(F);
        Tenant other = tenant(OTHER);
        FactorySettings existing = new FactorySettings();
        existing.setFactoryId(F);
        existing.setProductionSettings("{\"defaultBatchSize\":120}");
        em.persist(existing);
        basis(OTHER, ShippedRevenueBasis.NET);
        var order = order(tenant, "order", "300", "30");
        var first = orderItem(tenant, order, "8", "25");
        var second = orderItem(tenant, order, "10", "10");
        var priorBatch = batch(tenant, "prior", "3.50");
        shipment(tenant, order, "prior", START.minusDays(1), first, "4", "25", priorBatch.getId());
        var current = shipment(tenant, order, "current", START, first, "4", "25");
        shipmentLine(tenant, current, second, "2", "10", tenant.batch());
        shipment(tenant, order, "later", END.plusDays(1), second, "8", "10");
        var foreign = order(other, "order", "999", "700");
        shipment(other, foreign, "foreign", START, orderItem(other, foreign, "1", "999"), "1", "999");

        var currentReturn = addReturn(tenant, "current", END, ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, "7", false);
        currentReturn.setSourceOrderId(order.getId());
        restock(tenant, currentReturn, priorBatch);
        addReturn(tenant, "prior", START.minusDays(1), ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, "400", false).setWithGoods(true);
        addReturn(tenant, "later", END.plusDays(1), ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, "400", false).setWithGoods(true);
        addReturn(tenant, "draft", START, ReturnType.SALES_RETURN, ReturnOrderStatus.DRAFT, "400", false).setWithGoods(true);
        addReturn(tenant, "purchase", START, ReturnType.PURCHASE_RETURN, ReturnOrderStatus.COMPLETED, "400", false).setWithGoods(true);
        addReturn(tenant, "deleted", START, ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, "400", true).setWithGoods(true);
        addReturn(other, "foreign", START, ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED, "500", false).setWithGoods(true);
        flush();
        return tenant;
    }

    private Tenant tenant(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName(id);
        em.persist(factory);
        User user = new User();
        user.setFactoryId(id);
        user.setUsername(id);
        user.setPasswordHash("unused");
        user.setRoleCode("finance_manager");
        em.persist(user);
        Customer customer = new Customer();
        customer.setFactoryId(id);
        customer.setId(id + "-customer");
        customer.setCode("C1");
        customer.setCustomerCode("C1");
        customer.setName("测试客户");
        customer.setCreatedBy(user.getId());
        em.persist(customer);
        ProductType product = new ProductType();
        product.setFactoryId(id);
        product.setId(id + "-sku");
        product.setCode("SKU1");
        product.setName("测试成品");
        product.setUnit("盒");
        product.setCreatedBy(user.getId());
        em.persist(product);
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId(id + "-warehouse");
        warehouse.setFactoryId(id);
        warehouse.setCode("WH-FG");
        warehouse.setName("测试成品仓");
        warehouse.setType(FactoryWarehouse.WarehouseType.FINISHED);
        em.persist(warehouse);
        var tenant = new Tenant(id, user, customer.getId(), product.getId(), id + "-batch-B1");
        batch(tenant, "B1", "6");
        return tenant;
    }

    private SalesOrder order(Tenant tenant, String suffix, String value, String discount) {
        var order = new SalesOrder();
        order.setId(tenant.id() + "-" + suffix);
        order.setFactoryId(tenant.id());
        order.setOrderNumber(suffix);
        order.setCustomerId(tenant.customer());
        order.setCreatedBy(tenant.user().getId());
        order.setOrderDate(START.minusDays(7));
        order.setTotalAmount(new BigDecimal(value));
        order.setDiscountAmount(new BigDecimal(discount));
        order.setStatus(SalesOrderStatus.PARTIAL_DELIVERED);
        em.persist(order);
        return order;
    }

    private SalesOrderItem orderItem(Tenant tenant, SalesOrder order, String qty, String price) {
        var item = new SalesOrderItem();
        item.setSalesOrderId(order.getId());
        item.setProductTypeId(tenant.product());
        item.setQuantity(new BigDecimal(qty));
        item.setUnitPrice(new BigDecimal(price));
        item.setUnit("盒");
        em.persist(item);
        return item;
    }

    private SalesDeliveryRecord shipment(Tenant tenant, SalesOrder order, String suffix, LocalDate date,
                                         SalesOrderItem sourceLine, String qty, String price) {
        return shipment(tenant, order, suffix, date, sourceLine, qty, price, tenant.batch());
    }

    private SalesDeliveryRecord shipment(Tenant tenant, SalesOrder order, String suffix, LocalDate date,
                                         SalesOrderItem sourceLine, String qty, String price, String sourceBatchId) {
        var record = new SalesDeliveryRecord();
        record.setId(tenant.id() + "-delivery-" + suffix);
        record.setFactoryId(tenant.id());
        record.setSalesOrderId(order.getId());
        record.setCustomerId(tenant.customer());
        record.setDeliveryNumber(suffix);
        record.setDeliveryDate(date);
        record.setShippedBy(tenant.user().getId());
        record.setStatus(SalesDeliveryStatus.SHIPPED);
        em.persist(record);
        shipmentLine(tenant, record, sourceLine, qty, price, sourceBatchId);
        return record;
    }

    private void shipmentLine(Tenant tenant, SalesDeliveryRecord record, SalesOrderItem sourceLine, String qty,
                              String price, String sourceBatchId) {
        var line = new SalesDeliveryItem();
        line.setDeliveryRecordId(record.getId());
        line.setSalesOrderItemId(sourceLine.getId());
        line.setProductTypeId(tenant.product());
        line.setUnit("盒");
        line.setDeliveredQuantity(new BigDecimal(qty));
        line.setUnitPrice(new BigDecimal(price));
        em.persist(line);
        var edge = new BatchLineageEdge();
        edge.setFactoryId(tenant.id());
        edge.setSourceType("FINISHED_BATCH");
        edge.setSourceId(sourceBatchId);
        edge.setTargetType("SHIPMENT_RECORD");
        edge.setTargetId(record.getId());
        edge.setEdgeType("FINISHED_TO_SHIPMENT");
        edge.setUnit("盒");
        edge.setEventTime(record.getDeliveryDate().atStartOfDay());
        edge.setQuantityUsed(line.getDeliveredQuantity());
        edge.setMeta(Map.of("shipmentCarrier", "SALES_DELIVERY_RECORD", "deliveryItemId", line.getId(),
                "origin", "SALES_DELIVERY_SHIPMENT"));
        em.persist(edge);
    }

    private ReturnOrder addReturn(Tenant tenant, String suffix, LocalDate date, ReturnType type, ReturnOrderStatus status,
                                  String amount, boolean deleted) {
        var order = new ReturnOrder();
        order.setId(tenant.id() + "-return-" + suffix);
        order.setFactoryId(tenant.id());
        order.setReturnNumber(suffix);
        order.setReturnType(type);
        order.setStatus(status);
        order.setCreatedBy(tenant.user().getId());
        order.setCounterpartyId(tenant.customer());
        order.setReturnDate(date);
        order.setTotalAmount(amount == null ? null : new BigDecimal(amount));
        order.setTaxAmount(new BigDecimal("0.91"));
        order.setWithGoods(false);
        if (deleted) order.setDeletedAt(LocalDateTime.now());
        em.persist(order);
        return order;
    }

    private FinishedGoodsBatch batch(Tenant tenant, String suffix, String unitCost) {
        var batch = new FinishedGoodsBatch();
        batch.setId(tenant.id() + "-batch-" + suffix);
        batch.setFactoryId(tenant.id());
        batch.setProductTypeId(tenant.product());
        batch.setBatchNumber(suffix);
        batch.setWarehouseId(tenant.id() + "-warehouse");
        batch.setUnit("盒");
        batch.setProducedQuantity(new BigDecimal("100"));
        batch.setUnitCost(new BigDecimal(unitCost));
        batch.setStatus("AVAILABLE");
        em.persist(batch);
        return batch;
    }

    private void restock(Tenant tenant, ReturnOrder order, FinishedGoodsBatch source) {
        order.setWithGoods(true);
        var item = new ReturnOrderItem();
        item.setReturnOrderId(order.getId());
        item.setProductTypeId(tenant.product());
        item.setQuantity(BigDecimal.ONE);
        item.setBatchNumber(source.getBatchNumber());
        item.setSourceFinishedGoodsBatchId(source.getId());
        em.persist(item);

        var returned = batch(tenant, "RTN-" + order.getReturnNumber() + "-1", source.getUnitCost().toPlainString());
        returned.setReturnOrderItemId(item.getId());
        returned.setProducedQuantity(item.getQuantity());

        var voucher = new Voucher();
        voucher.setFactoryId(tenant.id());
        voucher.setVoucherNumber("RV-" + order.getReturnNumber());
        voucher.setVoucherType(VoucherType.RETURN);
        voucher.setVoucherDate(order.getReturnDate());
        voucher.setSourceBusinessType("RETURN_ORDER");
        voucher.setSourceBusinessId(order.getId());
        voucher.setTotalDebit(order.getTotalAmount());
        voucher.setTotalCredit(order.getTotalAmount());
        voucher.setStatus(VoucherStatus.POSTED);
        em.persist(voucher);
    }

    private MockMvc canvasConfig() {
        return standaloneSetup(new CanvasFactoryConfigController(null, null, null, null, null, settings))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private void basis(String factoryId, ShippedRevenueBasis basis) {
        var row = new FactorySettings();
        row.setFactoryId(factoryId);
        ShippedRevenueBasisSettings.write(row, basis);
        em.persist(row);
    }

    private void flush() {
        em.flush();
        em.clear();
    }

    private void assertIncomplete(String reason) {
        var result = service.summarize(F, START, END);
        assertThat(result.getStatus()).isEqualTo("ADJUSTMENT_INCOMPLETE");
        assertThat(result.getRevenue()).isNull();
        assertThat(result.getUnavailableReason()).contains(reason);
    }

    private static void assertAmounts(ShippedGrossProfitDTO result, String revenue, String cost, String profit, String rate) {
        assertThat(result.getStatus()).isEqualTo("OK");
        assertThat(result.getShipmentLineCount()).isEqualTo(2);
        assertThat(result.getRevenue()).isEqualByComparingTo(revenue);
        assertThat(result.getCost()).isEqualByComparingTo(cost);
        assertThat(result.getGrossProfit()).isEqualByComparingTo(profit);
        assertThat(result.getMarginRate()).isEqualByComparingTo(rate);
    }

    private ResultActions report(User actor, boolean allowed) throws Exception {
        PriceSensitiveContext.clear();
        var users = mock(UserRepository.class);
        when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
        var permissions = mock(PermissionService.class);
        when(permissions.hasConfiguredPermission(eq(actor), eq("finance:read"), anySet())).thenReturn(allowed);
        when(permissions.hasPermission(actor, PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)).thenReturn(true);
        var advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        var mapper = new ObjectMapper().findAndRegisterModules()
                .registerModule(new PriceSensitiveJacksonConfig().priceSensitiveJacksonModule());
        return standaloneSetup(new FinanceReportController(null, incomeStatements, null, service))
                .setControllerAdvice(advice, new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build()
                .perform(get("/api/mobile/{factoryId}/finance/report/shipped-gross-profit", F)
                        .requestAttr("userId", actor.getId())
                        .param("periodStart", START.toString()).param("periodEnd", END.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    private record Tenant(String id, User user, String customer, String product, String batch) {}
}
