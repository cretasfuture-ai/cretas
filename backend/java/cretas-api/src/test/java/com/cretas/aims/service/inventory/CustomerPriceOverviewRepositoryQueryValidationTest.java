package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CustomerPriceOverviewRow;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.CustomerPriceHistory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.inventory.PriceList;
import com.cretas.aims.entity.inventory.PriceListItem;
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

import static org.assertj.core.api.Assertions.assertThat;

/** 跨客户的客户价目表 + 最近/平均成交价 (G7): 真实 JPA Context, 真实 JPQL (含实体 JOIN ON). */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class CustomerPriceOverviewRepositoryQueryValidationTest {

    private static final String F = "IT-CPO";
    @PersistenceContext private EntityManager em;
    private CustomerPriceOverviewService svc;
    private int seq;

    @BeforeEach
    void setUp() {
        svc = new CustomerPriceOverviewService();
        ReflectionTestUtils.setField(svc, "em", em);

        customer("C1", "甲客户");
        customer("C2", "乙客户");
        product("P1", "SKU1", "面包", "310g*42袋/箱");
        product("P2", "SKU2", "蛋糕", null);
        priceList("C1", "SELLING_PRICE", "P1", "10");
        priceList("C1", "SELLING_PRICE", "P2", "20");
        priceList("C2", "SELLING_PRICE", "P1", "11");
        priceList(null, "SELLING_PRICE", "P1", "99");      // 全局价目, 不属于任何客户
        priceList("C1", "PURCHASE_PRICE", "P1", "1");       // 采购价目, 不能出现
        history("C1", "P1", "9", LocalDate.of(2026, 10, 1));
        history("C1", "P1", "13", LocalDate.of(2026, 11, 1));
        em.flush();
    }

    private void customer(String id, String name) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(F);
        c.setCode(id);
        c.setCustomerCode(id);
        c.setName(name);
        c.setCreatedBy(1L);
        em.persist(c);
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

    private void history(String customer, String product, String price, LocalDate date) {
        CustomerPriceHistory h = new CustomerPriceHistory();
        h.setFactoryId(F);
        h.setCustomerId(customer);
        h.setProductTypeId(product);
        h.setUnitPrice(new BigDecimal(price));
        h.setSourceSalesOrderId("SO-" + (++seq));
        h.setOrderDate(date);
        em.persist(h);
    }

    @Test
    void listsOnlyCustomerSellingPricesWithTradedPricesAndSpec() {
        PageResponse<CustomerPriceOverviewRow> page = svc.list(F, null, 1, 50);

        assertThat(page.getTotalElements()).isEqualTo(3); // 全局价目与采购价目都不在
        CustomerPriceOverviewRow c1p1 = page.getContent().stream()
                .filter(r -> r.getCustomerId().equals("C1") && r.getProductTypeId().equals("P1")).findFirst().orElseThrow();
        assertThat(c1p1.getCustomerName()).isEqualTo("甲客户");
        assertThat(c1p1.getProductCode()).isEqualTo("SKU1");
        assertThat(c1p1.getSpecification()).isEqualTo("310g*42袋/箱");
        assertThat(c1p1.getStandardPrice()).isEqualByComparingTo("10");
        assertThat(c1p1.getLatestTradedPrice()).isEqualByComparingTo("13");
        assertThat(c1p1.getLatestTradedDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(c1p1.getAverageTradedPrice()).isEqualByComparingTo("11");
        assertThat(c1p1.getTradedCount()).isEqualTo(2);
        assertThat(c1p1.isEffective()).isTrue();
    }

    @Test
    void neverTradedPairHasNullTradedPricesNotZero() {
        CustomerPriceOverviewRow r = svc.list(F, null, 1, 50).getContent().stream()
                .filter(x -> x.getCustomerId().equals("C2")).findFirst().orElseThrow();
        assertThat(r.getLatestTradedPrice()).isNull();
        assertThat(r.getAverageTradedPrice()).isNull();
        assertThat(r.getTradedCount()).isZero();
    }

    @Test
    void keywordFiltersAndPagingAndFactoryIsolation() {
        assertThat(svc.list(F, "乙", 1, 50).getTotalElements()).isEqualTo(1);
        assertThat(svc.list(F, "sku2", 1, 50).getTotalElements()).isEqualTo(1);
        PageResponse<CustomerPriceOverviewRow> p2 = svc.list(F, null, 2, 2);
        assertThat(p2.getContent()).hasSize(1);
        assertThat(p2.getTotalElements()).isEqualTo(3);
        assertThat(svc.list("OTHER-FACTORY", null, 1, 50).getTotalElements()).isZero();
    }
}
