package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 退货列表补名称 (G8) + 发货单检索 (G9): 真实 JPA Context, 真实 JPQL. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ReturnListEnrichAndDeliverySearchRepositoryQueryValidationTest extends TradeJpaQueryTestSupport {

    private static final String F = "IT-RD";
    @PersistenceContext private EntityManager em;
    private ReturnOrderListEnricher enricher;
    private SalesDeliverySearchService search;
    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        enricher = new ReturnOrderListEnricher();
        ReflectionTestUtils.setField(enricher, "em", em);
        search = new SalesDeliverySearchService();
        ReflectionTestUtils.setField(search, "em", em);

        userId = TradeJpaFixtures.createFactoryUser(em, F);
        otherUserId = TradeJpaFixtures.createFactoryUser(em, "OTHER");
        TradeJpaFixtures.createCustomer(em, F, userId, "C1", "甲客户");
        TradeJpaFixtures.createCustomer(em, F, userId, "C2", "乙客户");
        TradeJpaFixtures.createCustomer(em, "OTHER", otherUserId, "C-OTHER", "其他客户");
        TradeJpaFixtures.createSupplier(em, F, userId, "S1", "乙供应商");
        SalesOrder so = new SalesOrder();
        so.setId("SO1"); so.setFactoryId(F); so.setOrderNumber("SO-001"); so.setCreatedBy(userId); so.setCustomerId("C1"); so.setOrderDate(LocalDate.now());
        em.persist(so);
        PurchaseOrder po = new PurchaseOrder();
        po.setId("PO1"); po.setFactoryId(F); po.setOrderNumber("PO-001"); po.setCreatedBy(userId); po.setSupplierId("S1"); po.setOrderDate(LocalDate.now());
        em.persist(po);
        em.flush();
    }

    private ReturnOrder ret(String no, ReturnType type, String cp, String src) {
        ReturnOrder r = new ReturnOrder();
        r.setId("R-" + no);
        r.setFactoryId(F);
        r.setReturnNumber(no);
        r.setReturnType(type);
        r.setCounterpartyId(cp);
        r.setSourceOrderId(src);
        r.setReturnDate(LocalDate.now());
        r.setCreatedBy(1L);
        return r;
    }

    @Test
    void listGetsCounterpartyNameAndSourceOrderNumberForBothTypes() {
        ReturnOrder sales = ret("RT1", ReturnType.SALES_RETURN, "C1", "SO1");
        ReturnOrder purchase = ret("RT2", ReturnType.PURCHASE_RETURN, "S1", "PO1");
        ReturnOrder unknown = ret("RT3", ReturnType.SALES_RETURN, "NOPE", "NOPE");
        ReturnOrder noSource = ret("RT4", ReturnType.SALES_RETURN, "C1", null);

        enricher.enrich(F, List.of(sales, purchase, unknown, noSource));

        assertThat(sales.getCounterpartyName()).isEqualTo("甲客户");
        assertThat(sales.getSourceOrderNumber()).isEqualTo("SO-001");
        assertThat(purchase.getCounterpartyName()).isEqualTo("乙供应商");
        assertThat(purchase.getSourceOrderNumber()).isEqualTo("PO-001");
        assertThat(unknown.getCounterpartyName()).isNull();      // 查不到就 null, 不猜
        assertThat(unknown.getSourceOrderNumber()).isNull();
        assertThat(noSource.getSourceOrderNumber()).isNull();
    }

    @Test
    void otherFactoryNamesAreNotResolved() {
        ReturnOrder r = ret("RT9", ReturnType.SALES_RETURN, "C1", "SO1");
        enricher.enrich("OTHER-FACTORY", List.of(r));
        assertThat(r.getCounterpartyName()).isNull();
        assertThat(r.getSourceOrderNumber()).isNull();
    }

    private void salesOrder(String factory, Long owner, String id, String number, String customer) {
        SalesOrder order = new SalesOrder();
        order.setFactoryId(factory);
        order.setId(id);
        order.setOrderNumber(number);
        order.setCustomerId(customer);
        order.setOrderDate(LocalDate.of(2026, 10, 1));
        order.setCreatedBy(owner);
        em.persist(order);
    }

    private void delivery(String no, String orderId, String customerId, SalesDeliveryStatus st, LocalDate date, String factory) {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        d.setId("D-" + no);
        d.setFactoryId(factory);
        d.setDeliveryNumber(no);
        d.setSalesOrderId(orderId);
        d.setCustomerId(customerId);
        d.setStatus(st);
        d.setDeliveryDate(date);
        d.setShippedBy(F.equals(factory) ? userId : otherUserId);
        em.persist(d);
    }

    @Test
    void deliverySearchFiltersByKeywordOrderStatusDateAndFactory() {
        salesOrder(F, userId, "ORDER-100", "SO-100", "C1");
        salesOrder(F, userId, "ORDER-200", "SO-200", "C2");
        salesOrder("OTHER", otherUserId, "OTHER-100", "SO-100", "C-OTHER");
        delivery("DN-001", "ORDER-100", "C1", SalesDeliveryStatus.SHIPPED, LocalDate.of(2026, 11, 1), F);
        delivery("DN-002", "ORDER-200", "C2", SalesDeliveryStatus.DRAFT, LocalDate.of(2026, 11, 10), F);
        delivery("DN-003", "ORDER-100", "C1", SalesDeliveryStatus.DELIVERED, LocalDate.of(2026, 11, 15), F);
        delivery("DN-X", "OTHER-100", "C-OTHER", SalesDeliveryStatus.SHIPPED, LocalDate.of(2026, 11, 1), "OTHER");
        em.flush();
        em.clear();

        assertThat(search.search(F, "乙", null, null, null, null, 1, 20).getTotalElements()).isEqualTo(1);
        assertThat(search.search(F, null, "so-100", null, null, null, 1, 20).getTotalElements()).isEqualTo(2);
        assertThat(search.search(F, null, null, SalesDeliveryStatus.SHIPPED, null, null, 1, 20).getTotalElements()).isEqualTo(1);
        assertThat(search.search(F, null, null, null, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12), 1, 20)
                .getContent()).extracting(SalesDeliveryRecord::getDeliveryNumber).containsExactly("DN-002");
        PageResponse<SalesDeliveryRecord> combo = search.search(F, "dn-00", "SO-100", SalesDeliveryStatus.DELIVERED,
                null, null, 1, 20);
        assertThat(combo.getContent()).extracting(SalesDeliveryRecord::getDeliveryNumber).containsExactly("DN-003");
        assertThat(search.search(F, "DN-X", null, null, null, null, 1, 20).getTotalElements()).isZero(); // 别的厂
    }

    @Test
    void inconsistentLinksNeverResolveAnotherFactorysCustomerOrOrder() {
        salesOrder("OTHER", otherUserId, "OTHER-300", "SO-300", "C-OTHER");
        delivery("DN-LOCAL", "OTHER-300", "C-OTHER", SalesDeliveryStatus.DRAFT, LocalDate.of(2026, 11, 1), F);
        em.flush();
        em.clear();

        assertThat(search.search(F, "其他客户", "SO-300", null, null, null, 1, 20).getTotalElements()).isZero();
        assertThat(search.search(F, "DN-LOCAL", null, null, null, null, 1, 20).getContent())
                .singleElement().satisfies(row -> {
                    assertThat(row.getCustomerName()).isNull();
                    assertThat(row.getOrderNumber()).isNull();
                });
    }
}
