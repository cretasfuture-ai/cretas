package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.security.BusinessRowScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 供应商/客户账本汇总 (G4): 真实 JPA Context, 真实 JPQL. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class CounterpartyLedgerSummaryRepositoryQueryValidationTest {

    private static final String F = "IT-LEDGER";
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

    private void tx(CounterpartyType cp, String id, ArApTransactionType type, String amount) {
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
    void restrictedAccountNeverGetsFactoryWideLedgerAmounts() {
        tx(CounterpartyType.CUSTOMER, "CUS-1", ArApTransactionType.AR_INVOICE, "150");
        tx(CounterpartyType.CUSTOMER, "CUS-1", ArApTransactionType.AR_PAYMENT, "50");
        em.flush();
        assertThat((BigDecimal) svc.customerSummary(F, "CUS-1").get("pendingCollection")).isEqualByComparingTo("100");

        when(rowScope.recordCreatorIds(F)).thenReturn(List.of(userId));

        assertThat(svc.customerSummary(F, "CUS-1")).containsEntry("pendingCollection", null)
                .containsEntry("receivedAmount", null);
    }
}
