package com.cretas.aims.service.inventory;

import com.cretas.aims.controller.sales.CustomerPriceOverviewController;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CustomerPriceOverviewRow;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.CustomerPriceHistory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.PriceList;
import com.cretas.aims.entity.inventory.PriceListItem;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.event.SalesOrderConfirmedEvent;
import com.cretas.aims.event.listener.SalesOrderConfirmedPriceHistoryListener;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.CustomerPriceHistoryRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.RoleDefinitionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.impl.CustomerPriceMemoryServiceImpl;
import com.cretas.aims.service.impl.FactorySettingsServiceImpl;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 跨客户的客户价目表 + 最近/平均成交价 (G7): 真实 JPA Context, 真实 JPQL (含实体 JOIN ON). */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class CustomerPriceOverviewRepositoryQueryValidationTest extends TradeJpaQueryTestSupport {

    private static final String F = "IT-CPO";
    @Autowired private UserRepository users;
    @Autowired private RoleDefinitionRepository roles;
    @Autowired private CustomerPriceHistoryRepository prices;
    @Autowired private SalesOrderRepository orders;
    @Autowired private SalesOrderItemRepository items;
    @Autowired private FactorySettingsRepository settings;
    @Autowired private FactoryRepository factories;
    @PersistenceContext private EntityManager em;
    private CustomerPriceOverviewService svc;
    private int seq;
    private Long userId;

    @BeforeEach
    void setUp() {
        svc = new CustomerPriceOverviewService(new TradeCaliberSettings(settings));
        ReflectionTestUtils.setField(svc, "em", em);

        userId = TradeJpaFixtures.createFactoryUser(em, F);
        TradeJpaFixtures.createCustomer(em, F, userId, "C1", "甲客户");
        TradeJpaFixtures.createCustomer(em, F, userId, "C2", "乙客户");
        product("P1", "SKU1", "面包", "310g*42袋/箱");
        product("P2", "SKU2", "蛋糕", null);
        priceList("C1", "SELLING_PRICE", "P1", "10");
        priceList("C1", "SELLING_PRICE", "P2", "20");
        priceList("C2", "SELLING_PRICE", "P1", "11");
        priceList(null, "SELLING_PRICE", "P1", "99");      // 全局价目, 不属于任何客户
        priceList("C1", "PURCHASE_PRICE", "P1", "1");       // 采购价目, 不能出现
        history(F, "C1", "P1", "9", "1", LocalDate.of(2026, 10, 1), "SO-OLD");
        history(F, "C1", "P1", "13", "3", LocalDate.of(2026, 11, 1), "SO-NEW");
        history(F, "C1", "P1", "99", null, LocalDate.of(2026, 10, 15), "SO-NO-QTY");
        history("OTHER-FACTORY", "C1", "P1", "200", "1000", LocalDate.of(2026, 11, 3), "SO-FOREIGN");
        em.flush();
    }

    private void product(String id, String code, String name, String spec) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(F);
        p.setCode(code);
        p.setName(name);
        p.setUnit("个");
        p.setSpecification(spec);
        em.persist(p);
    }

    private void priceList(String customerId, String type, String productId, String price) {
        PriceList pl = new PriceList();
        pl.setId("PL-" + (++seq));
        pl.setFactoryId(F);
        pl.setName("表" + seq);
        pl.setCustomerId(customerId);
        pl.setPriceType(type);
        pl.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        em.persist(pl);
        PriceListItem it = new PriceListItem();
        it.setPriceListId(pl.getId());
        it.setProductTypeId(productId);
        it.setItemName("P1".equals(productId) ? "面包" : "蛋糕");
        it.setUnit("个");
        it.setStandardPrice(new BigDecimal(price));
        em.persist(it);
    }

    private CustomerPriceHistory history(String factory, String customer, String product, String price, String quantity,
                         LocalDate date, String orderId) {
        CustomerPriceHistory h = new CustomerPriceHistory();
        h.setFactoryId(factory);
        h.setCustomerId(customer);
        h.setProductTypeId(product);
        h.setUnitPrice(new BigDecimal(price));
        h.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        h.setQuantityUnit("个");
        h.setSourceSalesOrderId(orderId);
        h.setOrderDate(date);
        em.persist(h);
        return h;
    }

    @Test
    void httpScopesCustomersCountsAndTheSourceOrdersOfTradedPrices() throws Exception {
        String role = "sales_manager";
        em.find(User.class, userId).setRoleCode(role);
        Long other = TradeJpaFixtures.createUser(em, F, "overview-other", role);
        em.find(Customer.class, "C2").setCreatedBy(other);
        trade(userId, "12", "2", LocalDate.of(2026, 11, 4));
        trade(userId, "99", null, LocalDate.of(2026, 11, 2));
        trade(other, "88", "10", LocalDate.of(2026, 11, 5));
        em.flush();

        var scope = TradeJpaFixtures.scopedHttp(users, roles, F, role);
        var controller = new CustomerPriceOverviewController(svc, new FactoryAccessValidator(users), scope.scope());
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(scope.interceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
        String path = "/api/mobile/" + F + "/customer-prices";

        mvc.perform(scope.asUser(get(path).param("keyword", "SKU1").param("size", "1"), userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].customerId").value("C1"))
                .andExpect(jsonPath("$.data.content[0].standardPrice").value(10.0))
                .andExpect(jsonPath("$.data.content[0].latestTradedPrice").value(12.0))
                .andExpect(jsonPath("$.data.content[0].averageTradedPrice").value(12.0))
                .andExpect(jsonPath("$.data.customerTradedAverageMode").value("QUANTITY_WEIGHTED"))
                .andExpect(jsonPath("$.data.content[0].tradedCount").value(2))
                .andExpect(jsonPath("$.data.content[0].excludedTradeCount").value(1));
        mvc.perform(scope.asUser(get(path).param("keyword", "SKU1").param("page", "2").param("size", "1"), userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.customerTradedAverageMode").value("QUANTITY_WEIGHTED"))
                .andExpect(jsonPath("$.data.content.length()").value(0));
        mvc.perform(scope.asUser(get(path).param("keyword", "乙"), userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(0));
        mvc.perform(scope.asUser(get(path).param("keyword", "乙"), other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].customerId").value("C2"));

        TradeJpaFixtures.createProductionSettings(em, F, "{\"customerTradedAverageMode\":\"SIMPLE\"}");
        mvc.perform(scope.asUser(get(path).param("keyword", "SKU1").param("size", "1"), userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.customerTradedAverageMode").value("SIMPLE"))
                .andExpect(jsonPath("$.data.content[0].averageTradedPrice").value(55.5))
                .andExpect(jsonPath("$.data.content[0].excludedTradeCount").value(0));
    }

    private void trade(Long owner, String price, String quantity, LocalDate date) {
        String orderId = salesOrder(owner, price, date).getId();
        history(F, "C1", "P1", price, quantity, date, orderId);
    }

    private SalesOrder salesOrder(Long owner, String price, LocalDate date) {
        String id = "CPO-SO-" + (++seq);
        SalesOrder order = new SalesOrder();
        order.setId(id);
        order.setFactoryId(F);
        order.setOrderNumber(id);
        order.setCreatedBy(owner);
        order.setCustomerId("C1");
        order.setCustomerName("甲客户");
        order.setOrderDate(date);
        order.setStatus(SalesOrderStatus.CONFIRMED);
        order.setTotalAmount(new BigDecimal(price));
        em.persist(order);
        return order;
    }

    @Test
    void confirmedSalesOrderCapturesQuantityFromTheSameLineAsTheUnitPrice() {
        SalesOrder order = salesOrder(userId, "50", LocalDate.of(2026, 11, 5));
        saleLine(order, "18", "2.5");
        saleLine(order, "99", "3");
        em.flush();
        var listener = new SalesOrderConfirmedPriceHistoryListener(orders, new CustomerPriceMemoryServiceImpl(prices, items));
        listener.onSalesOrderConfirmed(new SalesOrderConfirmedEvent(this, "OTHER-FACTORY", order.getId()));
        assertThat(prices.findByFactoryIdAndCustomerIdAndProductTypeIdAndSourceSalesOrderId(F, "C1", "P1", order.getId()))
                .isEmpty();
        listener.onSalesOrderConfirmed(new SalesOrderConfirmedEvent(this, F, order.getId()));
        listener.onSalesOrderConfirmed(new SalesOrderConfirmedEvent(this, F, order.getId()));
        em.flush();
        em.clear();

        var row = prices.findByFactoryIdAndCustomerIdAndProductTypeIdAndSourceSalesOrderId(F, "C1", "P1", order.getId())
                .orElseThrow();
        assertThat(row.getUnitPrice()).isEqualByComparingTo("18");
        assertThat(row.getQuantity()).isEqualByComparingTo("2.5");
        assertThat(row.getQuantityUnit()).isEqualTo("个");
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(row);
        assertThat(json.has("unitPrice")).isTrue();
        for (String internal : new String[]{"quantity", "quantityUnit", "packagingUnit", "packagingBaseUnit", "packagingFactor"}) {
            assertThat(json.has(internal)).as(internal).isFalse();
        }
    }

    @Test
    void duplicateSourcePriceLeavesQuantityUnknownAndIncrementsExclusions() {
        SalesOrder order = salesOrder(userId, "50", LocalDate.of(2026, 11, 5));
        saleLine(order, "18", "2.5");
        saleLine(order, "18", "3");
        var listener = new SalesOrderConfirmedPriceHistoryListener(orders, new CustomerPriceMemoryServiceImpl(prices, items));
        listener.onSalesOrderConfirmed(new SalesOrderConfirmedEvent(this, F, order.getId()));
        em.flush();
        em.clear();

        var row = prices.findByFactoryIdAndCustomerIdAndProductTypeIdAndSourceSalesOrderId(F, "C1", "P1", order.getId())
                .orElseThrow();
        assertThat(row.getQuantity()).isNull();
        var overview = svc.list(F, "SKU1", 1, 50, null).getContent().stream()
                .filter(v -> v.getCustomerId().equals("C1")).findFirst().orElseThrow();
        assertThat(overview.getLatestTradedPrice()).isEqualByComparingTo("18");
        assertThat(overview.getExcludedTradeCount()).isEqualTo(2);
        assertThat(overview.getAverageTradedPrice()).isEqualByComparingTo("12");
    }

    private void saleLine(SalesOrder order, String price, String quantity) {
        var line = new SalesOrderItem();
        line.setSalesOrderId(order.getId());
        line.setProductTypeId("P1");
        line.setProductName("面包");
        line.setUnit("个");
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitPrice(new BigDecimal(price));
        em.persist(line);
    }

    @Test
    void listsOnlyCustomerSellingPricesWithTradedPricesAndSpec() {
        PageResponse<CustomerPriceOverviewRow> page = svc.list(F, null, 1, 50, null);

        assertThat(page.getTotalElements()).isEqualTo(3); // 全局价目与采购价目都不在
        CustomerPriceOverviewRow c1p1 = page.getContent().stream()
                .filter(r -> r.getCustomerId().equals("C1") && r.getProductTypeId().equals("P1")).findFirst().orElseThrow();
        assertThat(c1p1.getCustomerName()).isEqualTo("甲客户");
        assertThat(c1p1.getProductCode()).isEqualTo("SKU1");
        assertThat(c1p1.getSpecification()).isEqualTo("310g*42袋/箱");
        assertThat(c1p1.getStandardPrice()).isEqualByComparingTo("10");
        assertThat(c1p1.getLatestTradedPrice()).isEqualByComparingTo("13");
        assertThat(c1p1.getLatestTradedDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(c1p1.getAverageTradedPrice()).isEqualByComparingTo("12");
        assertThat(c1p1.getTradedCount()).isEqualTo(3);
        assertThat(c1p1.getExcludedTradeCount()).isEqualTo(1);
        assertThat(c1p1.isEffective()).isTrue();
    }

    @Test
    void neverTradedPairHasNullTradedPricesNotZero() {
        CustomerPriceOverviewRow r = svc.list(F, null, 1, 50, null).getContent().stream()
                .filter(x -> x.getCustomerId().equals("C2")).findFirst().orElseThrow();
        assertThat(r.getLatestTradedPrice()).isNull();
        assertThat(r.getAverageTradedPrice()).isNull();
        assertThat(r.getTradedCount()).isZero();
        assertThat(r.getExcludedTradeCount()).isZero();
    }

    @Test
    void unknownQuantitiesLeaveAverageNullWhileLatestPriceRemainsAvailable() {
        history(F, "C2", "P1", "20", null, LocalDate.of(2026, 11, 1), "SO-C2-NO-QTY");
        var row = svc.list(F, "乙", 1, 50, null).getContent().getFirst();
        assertThat(row.getTradedCount()).isEqualTo(1);
        assertThat(row.getExcludedTradeCount()).isEqualTo(1);
        assertThat(row.getLatestTradedPrice()).isEqualByComparingTo("20");
        assertThat(row.getAverageTradedPrice()).isNull();
    }

    @Test
    void weightedAverageUsesRecordedPackSizesAndFixedPhysicalConversions() {
        LocalDate date = LocalDate.of(2026, 11, 6);
        var box = history(F, "C2", "P1", "20", "2", date, "BOX");
        pack(box, "box", "盒", "个", "10");
        var carton = history(F, "C2", "P1", "60", "1", date, "CASE");
        pack(carton, "case", "箱", "个", "30");
        var cartonLarge = history(F, "C2", "P1", "60", "1", date, "CASE-LARGE");
        pack(cartonLarge, "case", "箱", "个", "50");
        history(F, "C2", "P1", "999", "10", date, "CASE-UNKNOWN").setQuantityUnit("case");
        em.find(ProductType.class, "P2").setUnit("千克");
        history(F, "C1", "P2", "0.02", "1000", date, "GRAM").setQuantityUnit("g");
        history(F, "C1", "P2", "40", "1", date, "KILO").setQuantityUnit("kg");

        var packaged = svc.list(F, "乙", 1, 50, null).getContent().getFirst();
        assertThat(packaged.getAverageTradedPrice()).isEqualByComparingTo("1.6");
        assertThat(packaged.getAverageTradedPriceUnit()).isEqualTo("个");
        assertThat(packaged.getExcludedTradeCount()).isEqualTo(1);
        var mass = svc.list(F, "SKU2", 1, 50, null).getContent().getFirst();
        assertThat(mass.getAverageTradedPrice()).isEqualByComparingTo("30");
        assertThat(mass.getAverageTradedPriceUnit()).isEqualTo("千克");
        assertThat(mass.getExcludedTradeCount()).isZero();
    }

    @Test
    void generalProductionSettingsWritesPreserveTheGovernedPolicies() throws Exception {
        TradeJpaFixtures.createProductionSettings(em, F, """
                {"counterpartyLedgerScopeMode":"HIDDEN","purchasePriceSourceMode":"CLIENT_PRESERVED",
                 "customerTradedAverageMode":"SIMPLE","shippedRevenueBasis":"NET"}
                """);
        var mapper = new ObjectMapper();
        var service = new FactorySettingsServiceImpl(settings, mapper, factories);
        var ordinaryRequest = mapper.readValue("""
                {"defaultBatchSize":45,"counterpartyLedgerScopeMode":"SCOPED",
                 "purchasePriceSourceMode":"SERVER_DETERMINED","customerTradedAverageMode":"QUANTITY_WEIGHTED"}
                """, com.cretas.aims.dto.FactorySettingsDTO.ProductionSettings.class);
        service.updateProductionSettings(F, ordinaryRequest);
        em.flush();
        var json = mapper.readTree(settings.findProductionSettingsByFactoryId(F));
        assertThat(json.path("defaultBatchSize").intValue()).isEqualTo(45);
        assertThat(json.path("shippedRevenueBasis").textValue()).isEqualTo("NET");
        assertThat(json.path("counterpartyLedgerScopeMode").textValue()).isEqualTo("HIDDEN");
        assertThat(json.path("purchasePriceSourceMode").textValue()).isEqualTo("CLIENT_PRESERVED");
        assertThat(json.path("customerTradedAverageMode").textValue()).isEqualTo("SIMPLE");
        assertThat(new TradeCaliberSettings(settings).customerAverageMode("OTHER-FACTORY"))
                .isEqualTo(TradeCaliberSettings.CustomerAverageMode.QUANTITY_WEIGHTED);
    }

    @Test
    void malformedAndUnknownFactoryPoliciesUseDefaults() {
        TradeJpaFixtures.createProductionSettings(em, F, """
                {"counterpartyLedgerScopeMode":true,"purchasePriceSourceMode":"CLIENT","customerTradedAverageMode":null}
                """);
        var policy = new TradeCaliberSettings(settings);
        assertThat(policy.ledgerMode(F)).isEqualTo(TradeCaliberSettings.LedgerMode.SCOPED);
        assertThat(policy.purchasePriceMode(F)).isEqualTo(TradeCaliberSettings.PurchasePriceMode.SERVER_DETERMINED);
        assertThat(policy.customerAverageMode(F)).isEqualTo(TradeCaliberSettings.CustomerAverageMode.QUANTITY_WEIGHTED);
        settings.findByFactoryId(F).orElseThrow().setProductionSettings("{ broken");
        assertThat(policy.customerAverageMode(F)).isEqualTo(TradeCaliberSettings.CustomerAverageMode.QUANTITY_WEIGHTED);
    }

    private static void pack(CustomerPriceHistory history, String unit, String packUnit, String baseUnit, String factor) {
        history.setQuantityUnit(unit);
        history.setPackagingUnit(packUnit);
        history.setPackagingBaseUnit(baseUnit);
        history.setPackagingFactor(new BigDecimal(factor));
    }

    @Test
    void keywordFiltersAndPagingAndFactoryIsolation() {
        assertThat(svc.list(F, "乙", 1, 50, null).getTotalElements()).isEqualTo(1);
        assertThat(svc.list(F, "sku2", 1, 50, null).getTotalElements()).isEqualTo(1);
        PageResponse<CustomerPriceOverviewRow> p2 = svc.list(F, null, 2, 2, null);
        assertThat(p2.getContent()).hasSize(1);
        assertThat(p2.getTotalElements()).isEqualTo(3);
        assertThat(svc.list("OTHER-FACTORY", null, 1, 50, null).getTotalElements()).isZero();
    }
}
