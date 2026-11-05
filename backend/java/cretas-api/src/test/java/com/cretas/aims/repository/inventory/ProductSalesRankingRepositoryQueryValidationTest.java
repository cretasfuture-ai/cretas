package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup/query gate for the sales ranking aggregation. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ProductSalesRankingRepositoryQueryValidationTest {

    @Autowired private SalesOrderItemRepository repository;
    @Autowired private EntityManager entityManager;
    private Long firstOwner;
    private Long secondOwner;

    @BeforeEach
    void seedConstraintValidReferences() {
        var owners = com.cretas.aims.testutil.FactoryCatalogJpaFixtures.seed(entityManager);
        firstOwner = owners.first(); secondOwner = owners.second();
    }

    @Test
    void rankingQueryBootsAndExecutesWithFactoryDateStatusScope() {
        List<SalesOrderItemRepository.SalesRankingRow> emptyRows = repository.rankSalesByProduct(
                "F006", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                EnumSet.complementOf(EnumSet.of(SalesOrderStatus.DRAFT,
                        SalesOrderStatus.CANCELLED, SalesOrderStatus.FINANCE_REJECTED)),
                false, List.of(-1L));
        assertThat(emptyRows).isEmpty();
    }

    @Test
    void rankingAggregatesRealRowsWithTenantDateStatusPackagingAndSelfScope() {
        persistOrder("SO-IN", "F006", LocalDate.of(2026, 1, 10), SalesOrderStatus.COMPLETED, 100L,
                item("SKU-A", "产品A", "2", "CASE", "10", "CASE", "kg", "5"));
        persistOrder("SO-IN-2", "F006", LocalDate.of(2026, 1, 11), SalesOrderStatus.CONFIRMED, 200L,
                item("SKU-A", "产品A", "3", "kg", "4", null, null, null));
        persistOrder("SO-OTHER-FACTORY", "F007", LocalDate.of(2026, 1, 10), SalesOrderStatus.COMPLETED, 100L,
                item("SKU-A", "产品A", "99", "kg", "99", null, null, null));
        persistOrder("SO-DRAFT", "F006", LocalDate.of(2026, 1, 10), SalesOrderStatus.DRAFT, 100L,
                item("SKU-A", "产品A", "50", "kg", "50", null, null, null));
        persistOrder("SO-OUTSIDE", "F006", LocalDate.of(2025, 12, 31), SalesOrderStatus.COMPLETED, 100L,
                item("SKU-A", "产品A", "40", "kg", "40", null, null, null));
        SalesOrderItem deleted = persistOrder("SO-DELETED", "F006", LocalDate.of(2026, 1, 10),
                SalesOrderStatus.COMPLETED, 100L,
                item("SKU-A", "产品A", "70", "kg", "70", null, null, null));
        deleted.softDelete();
        entityManager.flush();
        entityManager.clear();

        List<SalesOrderItemRepository.SalesRankingRow> rows = repository.rankSalesByProduct(
                "F006", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                EnumSet.complementOf(EnumSet.of(SalesOrderStatus.DRAFT,
                        SalesOrderStatus.CANCELLED, SalesOrderStatus.FINANCE_REJECTED)),
                false, List.of(-1L));

        assertThat(rows).hasSize(1);
        SalesOrderItemRepository.SalesRankingRow row = rows.get(0);
        assertThat(row.getProductTypeId()).isEqualTo("SKU-A");
        assertThat(row.getQuantity()).isEqualByComparingTo("13");
        assertThat(row.getAmount()).isEqualByComparingTo("32");
        assertThat(row.getUnit()).isEqualTo("kg");

        List<SalesOrderItemRepository.SalesRankingRow> selfRows = repository.rankSalesByProduct(
                "F006", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                EnumSet.complementOf(EnumSet.of(SalesOrderStatus.DRAFT,
                        SalesOrderStatus.CANCELLED, SalesOrderStatus.FINANCE_REJECTED)),
                true, List.of(firstOwner));
        assertThat(selfRows).hasSize(1);
        assertThat(selfRows.get(0).getQuantity()).isEqualByComparingTo("10");
        assertThat(selfRows.get(0).getAmount()).isEqualByComparingTo("20");
    }

    @Test
    void unknownPackageConversionNeverAddsCasesToKilograms() {
        persistOrder("SO-CASE-NO-FACTOR", "F006", LocalDate.of(2026, 1, 10), SalesOrderStatus.COMPLETED, 100L,
                item("SKU-A", "产品A", "2", "CASE", "10", null, null, null));
        persistOrder("SO-KG", "F006", LocalDate.of(2026, 1, 10), SalesOrderStatus.COMPLETED, 100L,
                item("SKU-A", "产品A", "3", "kg", "4", null, null, null));
        entityManager.flush(); entityManager.clear();
        var rows = repository.rankSalesByProduct("F006", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                EnumSet.of(SalesOrderStatus.COMPLETED), false, List.of(-1L));
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getUnit()).isEqualTo("CASE"); assertThat(row.getQuantity()).isEqualByComparingTo("2");
        }).anySatisfy(row -> {
            assertThat(row.getUnit()).isEqualTo("kg"); assertThat(row.getQuantity()).isEqualByComparingTo("3");
        });
    }

    private SalesOrderItem persistOrder(String id, String factoryId, LocalDate date, SalesOrderStatus status,
                                        long createdBy, SalesOrderItem item) {
        com.cretas.aims.entity.inventory.SalesOrder order = new com.cretas.aims.entity.inventory.SalesOrder();
        order.setId(id);
        order.setFactoryId(factoryId);
        order.setOrderNumber(id);
        order.setCustomerId("CUSTOMER-" + factoryId);
        order.setOrderDate(date);
        order.setStatus(status);
        order.setCreatedBy(createdBy == 100L ? firstOwner : secondOwner);
        order.setVersion(0L);
        order.setTotalAmount(BigDecimal.ZERO);
        entityManager.persist(order);
        item.setSalesOrderId(id);
        entityManager.persist(item);
        return item;
    }

    private static SalesOrderItem item(String productId, String name, String quantity, String unit,
                                       String price, String packagingUnit, String packagingBaseUnit,
                                       String factor) {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(productId);
        item.setProductName(name);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit(unit);
        item.setUnitPrice(new BigDecimal(price));
        item.setDiscountRate(BigDecimal.ZERO);
        item.setDeliveredQuantity(BigDecimal.ZERO);
        item.setLockedQty(BigDecimal.ZERO);
        item.setReservedQty(BigDecimal.ZERO);
        item.setPackagingUnit(packagingUnit);
        item.setPackagingBaseUnit(packagingBaseUnit);
        item.setPackagingFactor(factor == null ? null : new BigDecimal(factor));
        return item;
    }
}
