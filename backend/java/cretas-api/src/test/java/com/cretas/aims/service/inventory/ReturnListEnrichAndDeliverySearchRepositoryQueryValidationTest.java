package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Supplier;
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
class ReturnListEnrichAndDeliverySearchRepositoryQueryValidationTest {

    private static final String F = "IT-RD";
    @PersistenceContext private EntityManager em;
    private ReturnOrderListEnricher enricher;
    private SalesDeliverySearchService search;

    @BeforeEach
    void setUp() {
        enricher = new ReturnOrderListEnricher();
        ReflectionTestUtils.setField(enricher, "em", em);
        search = new SalesDeliverySearchService();
        ReflectionTestUtils.setField(search, "em", em);

        Customer c = new Customer();
        c.setId("C1"); c.setFactoryId(F); c.setCode("C1"); c.setCustomerCode("C1"); c.setName("甲客户"); c.setCreatedBy(1L);
        em.persist(c);
        Supplier s = new Supplier();
        s.setId("S1"); s.setFactoryId(F); s.setCode("S1"); s.setSupplierCode("S1"); s.setName("乙供应商"); s.setCreatedBy(1L);
        em.persist(s);
        SalesOrder so = new SalesOrder();
        so.setId("SO1"); so.setFactoryId(F); so.setOrderNumber("SO-001"); so.setCreatedBy(1L); so.setCustomerId("C1"); so.setOrderDate(LocalDate.now());
        em.persist(so);
        PurchaseOrder po = new PurchaseOrder();
        po.setId("PO1"); po.setFactoryId(F); po.setOrderNumber("PO-001"); po.setCreatedBy(1L); po.setSupplierId("S1"); po.setOrderDate(LocalDate.now());
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

    private void delivery(String no, String orderNo, String customer, SalesDeliveryStatus st, LocalDate date, String factory) {
        SalesDeliveryRecord d = new SalesDeliveryRecord();
        d.setId("D-" + no);
        d.setFactoryId(factory);
        d.setDeliveryNumber(no);
        d.setOrderNumber(orderNo);
        d.setCustomerId("C1");
        d.setCustomerName(customer);
        d.setStatus(st);
        d.setDeliveryDate(date);
        d.setShippedBy(1L);
        em.persist(d);
    }

    @Test
    void deliverySearchFiltersByKeywordOrderStatusDateAndFactory() {
        delivery("DN-001", "SO-100", "甲客户", SalesDeliveryStatus.SHIPPED, LocalDate.of(2026, 11, 1), F);
        delivery("DN-002", "SO-200", "乙客户", SalesDeliveryStatus.DRAFT, LocalDate.of(2026, 11, 10), F);
        delivery("DN-003", "SO-100", "甲客户", SalesDeliveryStatus.DELIVERED, LocalDate.of(2026, 11, 15), F);
        delivery("DN-X", "SO-100", "甲客户", SalesDeliveryStatus.SHIPPED, LocalDate.of(2026, 11, 1), "OTHER");
        em.flush();

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
}
