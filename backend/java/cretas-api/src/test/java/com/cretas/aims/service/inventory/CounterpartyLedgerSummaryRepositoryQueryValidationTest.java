package com.cretas.aims.service.inventory;

import com.cretas.aims.controller.CustomerController;
import com.cretas.aims.controller.SupplierController;
import com.cretas.aims.controller.finance.ArApController;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.RoleDefinitionRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.finance.FinanceRowScopeQuery;
import com.cretas.aims.service.finance.impl.ArApServiceImpl;
import com.cretas.aims.service.impl.CustomerServiceImpl;
import com.cretas.aims.service.impl.SupplierServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 供应商/客户账本汇总 (G4): 真实 JPA Context, 真实 JPQL. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class CounterpartyLedgerSummaryRepositoryQueryValidationTest extends TradeJpaQueryTestSupport {

    private static final String F = "IT-LEDGER";
    private static final String SELF_ROLE = "finance_manager";
    private static final String ALL_ROLE = "factory_super_admin";
    @Autowired private ArApTransactionRepository transactions;
    @Autowired private CustomerRepository customers;
    @Autowired private SupplierRepository suppliers;
    @Autowired private RoleDefinitionRepository roles;
    @Autowired private UserRepository users;
    @Autowired private FactorySettingsRepository settings;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;
    private CounterpartyLedgerSummaryService svc;
    private BusinessRowScope rowScope;
    private int seq;
    private Long userId;

    @BeforeEach
    void setUp() {
        userId = TradeJpaFixtures.createFactoryUser(em, F);
        TradeJpaFixtures.createMaterial(em, F, "M1");
        for (String id : List.of("SUP-1", "SUP-2", "SUP-3")) {
            TradeJpaFixtures.createSupplier(em, F, userId, id, id);
        }
        for (String id : List.of("CUS-1", "CUS-2")) {
            TradeJpaFixtures.createCustomer(em, F, userId, id, id);
        }
        ProductType product = new ProductType();
        product.setId("P1");
        product.setFactoryId(F);
        product.setCode("P1");
        product.setName("面包");
        product.setUnit("个");
        em.persist(product);
        svc = new CounterpartyLedgerSummaryService();
        ReflectionTestUtils.setField(svc, "em", em);
        ReflectionTestUtils.setField(svc, "transactionRepository", transactions);
        ReflectionTestUtils.setField(svc, "tradeCaliberSettings", new TradeCaliberSettings(settings));
        rowScope = mock(BusinessRowScope.class);
        when(rowScope.recordCreatorIds(F)).thenReturn(null);
        ReflectionTestUtils.setField(svc, "businessRowScope", rowScope);
    }

    private PurchaseOrder po(String supplier, PurchaseOrderStatus st, String total, PurchaseInvoiceStatus inv) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId("PO-" + (++seq));
        o.setFactoryId(F);
        o.setOrderNumber("PON-" + seq);
        o.setCreatedBy(userId);
        o.setSupplierId(supplier);
        o.setOrderDate(LocalDate.of(2026, 11, 1));
        o.setStatus(st);
        o.setTotalAmount(new BigDecimal(total));
        o.setInvoiceStatus(inv);
        em.persist(o);
        return o;
    }

    private void poItem(PurchaseOrder o, String qty, String received, String price) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setPurchaseOrderId(o.getId());
        i.setMaterialName("盐");
        i.setQuantity(new BigDecimal(qty));
        i.setUnit("kg");
        i.setPriceUnit("kg");
        i.setMaterialTypeId("M1");
        i.setReceivedQuantity(new BigDecimal(received));
        i.setUnitPrice(price == null ? null : new BigDecimal(price));
        em.persist(i);
    }

    private SalesOrder so(String customer, SalesOrderStatus st, String total, String invoiced) {
        SalesOrder o = new SalesOrder();
        o.setId("SO-" + (++seq));
        o.setFactoryId(F);
        o.setOrderNumber("SON-" + seq);
        o.setCreatedBy(userId);
        o.setCustomerId(customer);
        o.setCustomerName("客户");
        o.setOrderDate(LocalDate.of(2026, 11, 2 + seq % 5));
        o.setStatus(st);
        o.setTotalAmount(new BigDecimal(total));
        o.setInvoicedAmount(invoiced == null ? null : new BigDecimal(invoiced));
        em.persist(o);
        return o;
    }

    private void soItem(SalesOrder o, String qty, String delivered, String price) {
        SalesOrderItem i = new SalesOrderItem();
        i.setSalesOrderId(o.getId());
        i.setProductTypeId("P1");
        i.setProductName("面包");
        i.setQuantity(new BigDecimal(qty));
        i.setUnit("个");
        i.setDeliveredQuantity(new BigDecimal(delivered));
        i.setUnitPrice(price == null ? null : new BigDecimal(price));
        em.persist(i);
    }

    private ArApTransaction tx(CounterpartyType cp, String id, ArApTransactionType type, String amount) {
        ArApTransaction t = new ArApTransaction();
        t.setFactoryId(F);
        t.setTransactionNumber("TX-" + (++seq));
        t.setTransactionType(type);
        t.setCounterpartyType(cp);
        t.setCounterpartyId(id);
        t.setCounterpartyName("x");
        t.setAmount(new BigDecimal(amount));
        t.setBalanceAfter(BigDecimal.ZERO);
        t.setTransactionDate(LocalDate.of(2026, 11, 3));
        em.persist(t);
        return t;
    }

    @Test
    void supplierSummaryAddsUpOrdersLedgerAndPendingInvoice() {
        PurchaseOrder open = po("SUP-1", PurchaseOrderStatus.PARTIAL_RECEIVED, "1000", PurchaseInvoiceStatus.NOT_RECEIVED);
        poItem(open, "100", "40", "10");           // 待收 60 * 10 = 600
        po("SUP-1", PurchaseOrderStatus.COMPLETED, "500", PurchaseInvoiceStatus.RECEIVED);
        po("SUP-1", PurchaseOrderStatus.DRAFT, "999", PurchaseInvoiceStatus.NOT_RECEIVED);       // 草稿不算
        po("SUP-1", PurchaseOrderStatus.CANCELLED, "888", PurchaseInvoiceStatus.NOT_RECEIVED);   // 取消不算
        po("SUP-2", PurchaseOrderStatus.COMPLETED, "7777", PurchaseInvoiceStatus.NOT_RECEIVED);  // 别家
        tx(CounterpartyType.SUPPLIER, "SUP-1", ArApTransactionType.AP_INVOICE, "1500");
        tx(CounterpartyType.SUPPLIER, "SUP-1", ArApTransactionType.AP_PAYMENT, "400");
        tx(CounterpartyType.SUPPLIER, "SUP-2", ArApTransactionType.AP_INVOICE, "9999");
        em.flush();

        Map<String, Object> m = svc.supplierSummary(F, "SUP-1");

        assertThat((BigDecimal) m.get("purchaseAmount")).isEqualByComparingTo("1500");
        assertThat(m.get("committedOrderCount")).isEqualTo(2L);
        assertThat((BigDecimal) m.get("pendingReceiptAmount")).isEqualByComparingTo("600");
        assertThat((BigDecimal) m.get("pendingInvoiceAmount")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) m.get("pendingPayable")).isEqualByComparingTo("1100");
        assertThat((BigDecimal) m.get("paidAmount")).isEqualByComparingTo("400");
    }

    @Test
    void missingUnitPriceMakesPendingReceiptNullNotZero() {
        PurchaseOrder open = po("SUP-3", PurchaseOrderStatus.FINANCE_APPROVED, "0", PurchaseInvoiceStatus.NOT_RECEIVED);
        poItem(open, "10", "0", null);
        em.flush();
        assertThat(svc.supplierSummary(F, "SUP-3").get("pendingReceiptAmount")).isNull();
    }

    @Test
    void customerSummaryAndHistory() {
        SalesOrder a = so("CUS-1", SalesOrderStatus.PARTIAL_DELIVERED, "1000", "300");
        soItem(a, "100", "30", "10");              // 待发 70*10 = 700
        so("CUS-1", SalesOrderStatus.COMPLETED, "500", "500");
        so("CUS-1", SalesOrderStatus.DRAFT, "999", null);
        so("CUS-1", SalesOrderStatus.CANCELLED, "888", null);
        so("CUS-2", SalesOrderStatus.COMPLETED, "7777", null);
        tx(CounterpartyType.CUSTOMER, "CUS-1", ArApTransactionType.AR_INVOICE, "1500");
        tx(CounterpartyType.CUSTOMER, "CUS-1", ArApTransactionType.AR_PAYMENT, "600");
        em.flush();

        Map<String, Object> m = svc.customerSummary(F, "CUS-1");

        assertThat((BigDecimal) m.get("contractAmount")).isEqualByComparingTo("1500");
        assertThat((BigDecimal) m.get("averageOrderValue")).isEqualByComparingTo("750");
        assertThat((BigDecimal) m.get("pendingShipmentAmount")).isEqualByComparingTo("700");
        assertThat((BigDecimal) m.get("pendingInvoiceAmount")).isEqualByComparingTo("700");
        assertThat((BigDecimal) m.get("pendingCollection")).isEqualByComparingTo("900");
        assertThat((BigDecimal) m.get("receivedAmount")).isEqualByComparingTo("600");

        List<Map<String, Object>> hist = svc.customerPurchaseHistory(F, "CUS-1");
        assertThat(hist).hasSize(1); // 只有一个订单有行
        assertThat((BigDecimal) hist.get(0).get("amount")).isEqualByComparingTo("1000");
    }

    @Test
    void emptyCounterpartyHasZeroAmountsButNullAverage() {
        Map<String, Object> m = svc.customerSummary(F, "CUS-NONE");
        assertThat(m.get("averageOrderValue")).isNull();
        assertThat(m.get("lastOrderDate")).isNull();
        assertThat((BigDecimal) m.get("contractAmount")).isEqualByComparingTo("0");
    }

    @Test
    void httpLedgerTotalsMatchVisibleTransactionsForCustomersAndSuppliers() throws Exception {
        em.find(User.class, userId).setRoleCode(SELF_ROLE);
        Long other = TradeJpaFixtures.createUser(em, F, "ledger-other", SELF_ROLE);
        Long all = TradeJpaFixtures.createUser(em, F, "ledger-all", ALL_ROLE);
        var yours = so("CUS-1", SalesOrderStatus.CONFIRMED, "150", null);
        var theirs = so("CUS-1", SalesOrderStatus.CONFIRMED, "900", null);
        theirs.setCreatedBy(other);
        var yourPo = po("SUP-1", PurchaseOrderStatus.FINANCE_APPROVED, "400", PurchaseInvoiceStatus.NOT_RECEIVED);
        var theirPo = po("SUP-1", PurchaseOrderStatus.FINANCE_APPROVED, "700", PurchaseInvoiceStatus.NOT_RECEIVED);
        theirPo.setCreatedBy(other);
        var ar = CounterpartyType.CUSTOMER;
        var ap = CounterpartyType.SUPPLIER;
        tx(ar, "CUS-1", ArApTransactionType.AR_INVOICE, "150").setSalesOrderId(yours.getId());
        tx(ar, "CUS-1", ArApTransactionType.AR_PAYMENT, "50").setSalesOrderId(yours.getId());
        tx(ar, "CUS-1", ArApTransactionType.AR_INVOICE, "900").setSalesOrderId(theirs.getId());
        var hiddenAr = tx(ar, "CUS-1", ArApTransactionType.AR_PAYMENT, "100");
        hiddenAr.setSalesOrderId(theirs.getId());
        hiddenAr.setOperatedBy(userId);
        var adjustment = tx(ar, "CUS-1", ArApTransactionType.AR_ADJUSTMENT, "12");
        adjustment.setApprovalStatus(ArApApprovalStatus.APPROVED);
        adjustment.setOperatedBy(userId);
        var pending = tx(ar, "CUS-1", ArApTransactionType.AR_ADJUSTMENT, "80");
        pending.setApprovalStatus(ArApApprovalStatus.PENDING);
        pending.setOperatedBy(userId);

        tx(ap, "SUP-1", ArApTransactionType.AP_INVOICE, "400").setPurchaseOrderId(yourPo.getId());
        tx(ap, "SUP-1", ArApTransactionType.AP_PAYMENT, "75").setPurchaseOrderId(yourPo.getId());
        tx(ap, "SUP-1", ArApTransactionType.AP_INVOICE, "700").setPurchaseOrderId(theirPo.getId());
        tx(ap, "SUP-1", ArApTransactionType.AP_PAYMENT, "200").setPurchaseOrderId(theirPo.getId());
        tx(ap, "SUP-1", ArApTransactionType.AP_CREDIT_NOTE, "10").setPurchaseOrderId(yourPo.getId());
        tx(ap, "SUP-2", ArApTransactionType.AP_INVOICE, "999").setOperatedBy(userId);
        em.flush();

        MockMvc mvc = ledgerHttp();
        assertHttpTotals(mvc, userId, SELF_ROLE, ar, "CUS-1", 4, "112", "50", "SCOPED");
        assertHttpTotals(mvc, all, ALL_ROLE, ar, "CUS-1", 6, "912", "150", "SCOPED");
        assertHttpTotals(mvc, userId, SELF_ROLE, ap, "SUP-1", 3, "315", "75", "SCOPED");
        assertHttpTotals(mvc, all, ALL_ROLE, ap, "SUP-1", 5, "815", "275", "SCOPED");

        TradeJpaFixtures.createProductionSettings(em, F, "{\"counterpartyLedgerScopeMode\":\"HIDDEN\"}");
        var hiddenArSummary = httpSummary(mvc, userId, SELF_ROLE, true, "CUS-1");
        assertThat(hiddenArSummary.required("counterpartyLedgerScopeMode").asText()).isEqualTo("HIDDEN");
        assertThat(hiddenArSummary.required("pendingCollection").isNull()).isTrue();
        assertThat(hiddenArSummary.required("receivedAmount").isNull()).isTrue();
        var hiddenApSummary = httpSummary(mvc, userId, SELF_ROLE, false, "SUP-1");
        assertThat(hiddenApSummary.required("counterpartyLedgerScopeMode").asText()).isEqualTo("HIDDEN");
        assertThat(hiddenApSummary.required("pendingPayable").isNull()).isTrue();
        assertThat(hiddenApSummary.required("paidAmount").isNull()).isTrue();
        assertHttpTotals(mvc, all, ALL_ROLE, ar, "CUS-1", 6, "912", "150", "HIDDEN");
        assertHttpTotals(mvc, all, ALL_ROLE, ap, "SUP-1", 5, "815", "275", "HIDDEN");
    }

    private MockMvc ledgerHttp() {
        JdbcTemplate config = spy(jdbc);
        String readScope = "SELECT data_scope FROM factory_role_data_scopes WHERE factory_id=? AND role_code=?";
        doReturn("SELF").when(config).queryForObject(readScope, String.class, F, SELF_ROLE);
        doReturn("ALL").when(config).queryForObject(readScope, String.class, F, ALL_ROLE);
        doReturn(1L).when(config).queryForObject(
                "SELECT COUNT(*) FROM factory_role_data_scopes WHERE factory_id=? AND role_code=?", Long.class, F, SELF_ROLE);
        DataScopeResolver resolver = new DataScopeResolver(users, roles, config);
        BusinessRowScope scope = new BusinessRowScope(resolver, config);
        ReflectionTestUtils.setField(svc, "businessRowScope", scope);
        var financeScope = new FinanceRowScopeQuery(transactions, null, null, null, null, null, null);
        ReflectionTestUtils.setField(financeScope, "businessRowScope", scope);
        var finance = new ArApServiceImpl(transactions, customers, suppliers);
        ReflectionTestUtils.setField(finance, "financeRowScopeQuery", financeScope);
        var arAp = new ArApController(finance, null, null, null, null);
        ReflectionTestUtils.setField(arAp, "financeRowScopeQuery", financeScope);

        var customer = new CustomerServiceImpl(customers, null, null);
        var supplier = new SupplierServiceImpl(suppliers, null, null);
        ReflectionTestUtils.setField(customer, "ledgerSummaryService", svc);
        ReflectionTestUtils.setField(supplier, "ledgerSummaryService", svc);
        var permissions = mock(PermissionService.class);
        when(permissions.hasConfiguredPermission(any(User.class), eq("finance:read"), anySet())).thenReturn(true);
        when(permissions.hasPermission(any(User.class), eq("procurement:price:view"))).thenReturn(true);
        var advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        return MockMvcBuilders.standaloneSetup(new CustomerController(customer, null, null, null),
                        new SupplierController(supplier, null, null, null, null), arAp)
                .addInterceptors(new DataScopeRouteInterceptor(resolver, config))
                .setControllerAdvice(advice, new GlobalExceptionHandler()).build();
    }

    private void assertHttpTotals(MockMvc mvc, long actorId, String role, CounterpartyType type, String id,
                                  int count, String netExpected, String paidExpected, String mode) throws Exception {
        boolean ar = type == CounterpartyType.CUSTOMER;
        String root = "/api/mobile/" + F;
        JsonNode details = getAs(mvc, get(root + "/finance/transactions").param("counterpartyType", type.name())
                .param("counterpartyId", id), actorId, role).path("content");
        assertThat(details).hasSize(count);
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (JsonNode row : details) {
            BigDecimal amount = row.required("amount").decimalValue();
            String transactionType = row.required("transactionType").asText();
            switch (transactionType) {
                case "AR_INVOICE", "AP_INVOICE" -> net = net.add(amount);
                case "AR_PAYMENT", "AP_PAYMENT" -> {
                    net = net.subtract(amount.abs());
                    paid = paid.add(amount.abs());
                }
                case "AR_CREDIT_NOTE", "AP_CREDIT_NOTE" -> net = net.subtract(amount.abs());
                case "AR_ADJUSTMENT", "AP_ADJUSTMENT", "AR_REFUND" -> {
                    if ("APPROVED".equals(row.path("approvalStatus").asText())) net = net.add(amount);
                }
                default -> throw new AssertionError("Unexpected ledger transaction " + transactionType);
            }
        }
        assertThat(net).isEqualByComparingTo(netExpected);
        assertThat(paid).isEqualByComparingTo(paidExpected);
        JsonNode summary = httpSummary(mvc, actorId, role, ar, id);
        assertThat(summary.required("counterpartyLedgerScopeMode").asText()).isEqualTo(mode);
        String netKey = ar ? "pendingCollection" : "pendingPayable";
        String paidKey = ar ? "receivedAmount" : "paidAmount";
        assertThat(summary.path(netKey).numberValue()).as(netKey).isNotNull();
        assertThat(summary.path(paidKey).numberValue()).as(paidKey).isNotNull();
        assertThat(summary.path(netKey).decimalValue()).isEqualByComparingTo(net);
        assertThat(summary.path(paidKey).decimalValue()).isEqualByComparingTo(paid);
    }

    private JsonNode httpSummary(MockMvc mvc, long actorId, String role, boolean ar, String id) throws Exception {
        return getAs(mvc, get("/api/mobile/" + F + (ar ? "/customers/" : "/suppliers/") + id + "/statistics"), actorId, role);
    }

    private JsonNode getAs(MockMvc mvc, MockHttpServletRequestBuilder request, long id, String role) throws Exception {
        String body = mvc.perform(request.requestAttr("userId", id).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                        new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, role)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).required("data");
    }
}
