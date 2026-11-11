package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /sales/orders 返回的是实体本身, Jackson 序列化时在 open-in-view 会话里逐张单懒加载
 * {@code items} 与 {@code suppliedMaterials} —— 一页 20 张单 = 2 + 20×2 条 SQL (N+1)。
 * 本测试按线上形状造 20 张单 (每张 2 行明细 + 1 条客供料), 走同一个仓库查询 + 同一套 Jackson 序列化,
 * 数 Hibernate 实际发出的 SQL 条数。修复 = 两个集合加 {@code @BatchSize}, 一页内同一集合一次取齐。
 */
@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SalesOrderListStatementCountQueryValidationTest {

    private static final String F = "F-SO-N1";
    private static final int ORDERS = 20;

    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired SalesOrderRepository salesOrderRepository;

    @Test
    void onePageOfOrdersSerializesWithAConstantNumberOfStatements() throws Exception {
        seed();
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        Page<SalesOrder> page = salesOrderRepository.findByFactoryIdOrderByCreatedAtDesc(
                F, PageRequest.of(0, ORDERS, Sort.by(Sort.Direction.DESC, "createdAt")));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = mapper.writeValueAsString(page.getContent());
        long statements = stats.getPrepareStatementCount();
        System.out.printf("[SALES_ORDER_LIST_SQL] orders=%d statements=%d jsonBytes=%d%n",
                page.getContent().size(), statements, json.length());

        // 阳性对照: 真的取到了一整页, 明细与客供料真的被序列化出来 (否则「条数少」可能只是没加载)
        assertThat(page.getContent()).hasSize(ORDERS);
        assertThat(page.getContent()).allSatisfy(order -> {
            assertThat(order.getItems()).hasSize(2);
            assertThat(order.getSuppliedMaterials()).hasSize(1);
        });
        assertThat(json).contains("QA-N1-item-0-1").contains("QA-N1-material");
        // 页查询 + 计数 + 明细一次 + 客供料一次; 留 2 条余量给方言差异。N+1 时是 2 + 20×2 = 42。
        assertThat(statements).as("一页销售单的 SQL 条数应与单数无关 (N+1 时约 42)").isLessThanOrEqualTo(6);
    }

    private void seed() {
        Factory factory = new Factory();
        factory.setId(F);
        factory.setName(F);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
        User user = new User();
        user.setFactoryId(F);
        user.setUsername("so-n1-user");
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        Customer customer = new Customer();
        customer.setId("C-" + F);
        customer.setFactoryId(F);
        customer.setCode("C-" + F);
        customer.setCustomerCode("C-" + F);
        customer.setName("QA-N1-customer");
        customer.setIsActive(true);
        customer.setCreatedBy(user.getId());
        entityManager.persist(customer);
        RawMaterialType material = new RawMaterialType();
        material.setId("M-" + F);
        material.setFactoryId(F);
        material.setCode("RM-" + F);
        material.setName("QA-N1-material");
        material.setCategory("原料");
        material.setUnit("kg");
        material.setIsActive(true);
        material.setCreatedBy(user.getId());
        entityManager.persist(material);
        com.cretas.aims.entity.ProductType product = new com.cretas.aims.entity.ProductType();
        product.setId("P-" + F);
        product.setFactoryId(F);
        product.setCode("P-" + F);
        product.setName("QA-N1-product");
        product.setUnit("box");
        product.setIsActive(true);
        product.setCreatedBy(user.getId());
        entityManager.persist(product);
        com.cretas.aims.entity.factory.FactoryWarehouse warehouse = new com.cretas.aims.entity.factory.FactoryWarehouse();
        warehouse.setId("W-" + F);
        warehouse.setFactoryId(F);
        warehouse.setCode("WH-RAW-" + F);
        warehouse.setName("QA-N1-warehouse");
        warehouse.setType(com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType.RAW);
        warehouse.setIsActive(true);
        entityManager.persist(warehouse);

        for (int i = 0; i < ORDERS; i++) {
            SalesOrder order = new SalesOrder();
            order.setId("SO-" + F + "-" + i);
            order.setFactoryId(F);
            order.setOrderNumber("SO-NO-" + F + "-" + i);
            order.setCustomerId(customer.getId());
            order.setOrderDate(LocalDate.of(2026, 11, 1));
            order.setStatus(SalesOrderStatus.DRAFT);
            order.setCreatedBy(user.getId());
            order.setProcessingMode(SalesProcessingMode.TOLL_PROCESSING);
            order.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
            entityManager.persist(order);
            SalesOrderItem first = null;
            for (int j = 0; j < 2; j++) {
                SalesOrderItem item = new SalesOrderItem();
                item.setSalesOrderId(order.getId());
                item.setProductTypeId("P-" + F);
                item.setProductName("QA-N1-item-" + i + "-" + j);
                item.setQuantity(BigDecimal.ONE);
                item.setUnit("box");
                entityManager.persist(item);
                if (first == null) first = item;
            }
            entityManager.flush();
            SalesOrderSuppliedMaterialRequirement requirement = new SalesOrderSuppliedMaterialRequirement();
            requirement.setId("REQ-" + F + "-" + i);
            requirement.setFactoryId(F);
            requirement.setCustomerId(customer.getId());
            requirement.setSalesOrderId(order.getId());
            requirement.setSalesOrderItemId(first.getId());
            requirement.setMaterialTypeId(material.getId());
            requirement.setMaterialName(material.getName());
            requirement.setExpectedQuantity(BigDecimal.TEN);
            requirement.setReceivedQuantity(BigDecimal.ZERO);
            requirement.setUnit("kg");
            requirement.setExpectedArrivalAt(LocalDateTime.of(2026, 11, 2, 9, 0));
            requirement.setTargetWarehouseId("W-" + F);
            requirement.setStatus(SalesOrderSuppliedMaterialRequirementStatus.PENDING);
            entityManager.persist(requirement);
        }
    }
}
