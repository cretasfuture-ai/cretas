package com.cretas.aims.repository.inventory;

import com.cretas.aims.domain.OrderUsageWhitelists;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下架闸那条 JPQL 的真实 JPA 启动闸 + 行为验收。
 *
 * <p><b>为什么单靠 mock 单测不够</b>: {@code ProductRetirementGuardTest} /
 * {@code ProductRetirementGuardWiringTest} 都把 {@code SalesOrderItemRepository} mock 掉了 ——
 * JPQL 写错(字段名、join 路径、DISTINCT+ORDER BY 组合)在那两个测试里<b>一个字都不会说</b>,
 * 要到 Spring 起容器才炸。这里用真 PostgreSQL 落数据再查, 同时验:
 * <ol>
 *   <li>查询能编译、能跑;</li>
 *   <li>状态白名单在<b>数据库层</b>真的在过滤(终结态订单不该被查出来);</li>
 *   <li>工厂隔离生效。</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.inventory")
@DisplayName("SKU 下架闸: 在飞销售订单查询的真实 JPA 闸")
class SalesOrderItemInFlightByProductRepositoryQueryValidationTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F007";
    private static final String SKU_ID = "PT-RETIRE-GATE-1";

    @Autowired SalesOrderItemRepository salesOrderItemRepository;
    @Autowired EntityManager entityManager;

    private Long creatorId;

    @BeforeEach
    void seedTenant() {
        for (String factoryId : List.of(FACTORY, OTHER_FACTORY)) {
            Factory factory = new Factory();
            factory.setId(factoryId);
            factory.setName("Retire gate factory " + factoryId);
            entityManager.persist(factory);
        }

        User creator = new User();
        creator.setFactoryId(FACTORY);
        creator.setUsername("retire-gate-jpa");
        creator.setPasswordHash("not-used");
        creator.setIsActive(true);
        entityManager.persist(creator);
        entityManager.flush();
        creatorId = creator.getId();

        for (String factoryId : List.of(FACTORY, OTHER_FACTORY)) {
            Customer customer = new Customer();
            customer.setId("C-RETIRE-" + factoryId);
            customer.setFactoryId(factoryId);
            customer.setCode("C-RETIRE-" + factoryId);
            customer.setCustomerCode("C-RETIRE-" + factoryId);
            customer.setName("Retire gate customer " + factoryId);
            customer.setCreatedBy(creatorId);
            customer.setIsActive(true);
            entityManager.persist(customer);
        }

        ProductType sku = new ProductType();
        sku.setId(SKU_ID);
        sku.setFactoryId(FACTORY);
        sku.setCode("CPF006RETIRE");
        sku.setName("黄油鸡-成品800g");
        sku.setUnit("袋");
        sku.setIsActive(true);
        sku.setCreatedBy(creatorId);
        entityManager.persist(sku);
        entityManager.flush();
    }

    private void seedOrder(String factoryId, String orderNumber, SalesOrderStatus status) {
        SalesOrder order = new SalesOrder();
        order.setId("SO-ID-" + orderNumber);
        order.setFactoryId(factoryId);
        order.setOrderNumber(orderNumber);
        order.setCustomerId("C-RETIRE-" + factoryId);
        order.setOrderDate(LocalDate.now());
        order.setStatus(status);
        order.setCreatedBy(creatorId);
        entityManager.persist(order);

        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrderId(order.getId());
        item.setProductTypeId(SKU_ID);
        item.setProductName("黄油鸡-成品800g");
        item.setQuantity(new BigDecimal("10"));
        item.setUnit("袋");
        item.setUnitPrice(new BigDecimal("50"));
        entityManager.persist(item);
    }

    private List<String> query(String factoryId) {
        entityManager.flush();
        entityManager.clear();
        return salesOrderItemRepository.findInFlightOrderNumbersByProduct(
                factoryId, SKU_ID, OrderUsageWhitelists.SO_PLANNABLE);
    }

    @Test
    void inFlightOrdersAreReturnedAndTerminalOnesAreFilteredByTheDatabase() {
        // 在飞 —— 应该被查出来 (SO_PLANNABLE 五个状态各一张)
        seedOrder(FACTORY, "SO-INFLIGHT-CONFIRMED", SalesOrderStatus.CONFIRMED);
        seedOrder(FACTORY, "SO-INFLIGHT-PENDING", SalesOrderStatus.PENDING_FINANCE_REVIEW);
        seedOrder(FACTORY, "SO-INFLIGHT-APPROVED", SalesOrderStatus.FINANCE_APPROVED);
        seedOrder(FACTORY, "SO-INFLIGHT-PROCESSING", SalesOrderStatus.PROCESSING);
        seedOrder(FACTORY, "SO-INFLIGHT-PARTIAL", SalesOrderStatus.PARTIAL_DELIVERED);
        // 终结/未承诺 —— 不该被查出来
        seedOrder(FACTORY, "SO-TERMINAL-COMPLETED", SalesOrderStatus.COMPLETED);
        seedOrder(FACTORY, "SO-TERMINAL-CANCELLED", SalesOrderStatus.CANCELLED);
        seedOrder(FACTORY, "SO-TERMINAL-DRAFT", SalesOrderStatus.DRAFT);
        seedOrder(FACTORY, "SO-TERMINAL-REJECTED", SalesOrderStatus.FINANCE_REJECTED);

        assertThat(query(FACTORY)).containsExactlyInAnyOrder(
                "SO-INFLIGHT-CONFIRMED",
                "SO-INFLIGHT-PENDING",
                "SO-INFLIGHT-APPROVED",
                "SO-INFLIGHT-PROCESSING",
                "SO-INFLIGHT-PARTIAL");
    }

    /** 阴性对照: 只有终结态订单时读数必须是空的 —— 否则「有订单就拦」。 */
    @Test
    void onlyTerminalOrdersYieldNothing() {
        seedOrder(FACTORY, "SO-TERMINAL-ONLY-1", SalesOrderStatus.COMPLETED);
        seedOrder(FACTORY, "SO-TERMINAL-ONLY-2", SalesOrderStatus.CANCELLED);

        assertThat(query(FACTORY)).isEmpty();
    }

    /** 阴性对照: 该 SKU 完全没被订过时读数是空的 (阳性对照见上一条, 证明查询本身查得出东西)。 */
    @Test
    void noOrdersAtAllYieldNothing() {
        assertThat(query(FACTORY)).isEmpty();
    }

    /** 别的工厂的在飞订单不该拦住本厂下架。 */
    @Test
    void otherFactoriesOrdersDoNotLeakIn() {
        seedOrder(OTHER_FACTORY, "SO-OTHER-FACTORY", SalesOrderStatus.FINANCE_APPROVED);

        assertThat(query(FACTORY)).isEmpty();
        // 阳性对照: 同一条数据在它自己的工厂下确实查得到 —— 证明上一句的空不是查询坏了
        assertThat(query(OTHER_FACTORY)).containsExactly("SO-OTHER-FACTORY");
    }

    /** 同一张单里同一个 SKU 出现多行时只报一次订单号 (DISTINCT)。 */
    @Test
    void duplicateLinesOnTheSameOrderCollapseToOneOrderNumber() {
        seedOrder(FACTORY, "SO-DUP-LINES", SalesOrderStatus.FINANCE_APPROVED);
        SalesOrderItem secondLine = new SalesOrderItem();
        secondLine.setSalesOrderId("SO-ID-SO-DUP-LINES");
        secondLine.setProductTypeId(SKU_ID);
        secondLine.setProductName("黄油鸡-成品800g");
        secondLine.setQuantity(new BigDecimal("3"));
        secondLine.setUnit("袋");
        secondLine.setUnitPrice(new BigDecimal("50"));
        entityManager.persist(secondLine);

        assertThat(query(FACTORY)).containsExactly("SO-DUP-LINES");
    }
}
