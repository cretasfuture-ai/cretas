package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 缺料实例的 JPQL 在真实映射上跑 (H2; 设了 CRETAS_WORKFLOW_PG_URL 时在 PG 上跑)。
 * 供应量查询要证明: 只算本厂、只算质检中 / 已下单, 草稿和别厂的不算。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = ShortageCaseRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {ShortageCaseRepository.class, ShortageCaseLineRepository.class, ShortageSupplyQueryRepository.class}))
class ShortageCaseRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 8, 0);

    @Autowired TestEntityManager em;
    @Autowired ShortageCaseRepository cases;
    @Autowired ShortageCaseLineRepository lines;
    @Autowired ShortageSupplyQueryRepository supply;

    private static boolean onH2() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        return url == null || url.isBlank();
    }

    private ShortageCase newCase(String factoryId, String source, boolean closed) {
        ShortageCase c = new ShortageCase();
        c.setFactoryId(factoryId);
        c.setSourceType(ShortageCase.SourceType.SALES_ORDER);
        c.setSourceId(source);
        c.setStage(closed ? ShortageCase.Stage.CLOSED : ShortageCase.Stage.DETECTED);
        c.setOwnerRole("procurement_manager");
        c.setInputFingerprint("fp");
        c.setLastRefreshedAt(NOW);
        c.setLastRefreshReason("TEST");
        if (closed) c.setClosedAt(NOW);
        return cases.saveAndFlush(c);
    }

    private void line(ShortageCase c, String material) {
        ShortageCaseLine l = new ShortageCaseLine();
        l.setCaseId(c.getId());
        l.setFactoryId(c.getFactoryId());
        l.setMaterialTypeId(material);
        l.setRequiredQty(BigDecimal.TEN);
        l.setReleasedQty(BigDecimal.ONE);
        l.setInspectingQty(BigDecimal.ZERO);
        l.setOnOrderQty(BigDecimal.ZERO);
        l.setGapQty(new BigDecimal("9"));
        lines.saveAndFlush(l);
    }

    @Test
    void openCasesForAMaterialAreThisFactoryAndOpenOnly() {
        ShortageCase mine = newCase("F1", "SO-1", false);
        line(mine, "M1");
        ShortageCase closed = newCase("F1", "SO-2", true);
        line(closed, "M1");
        ShortageCase other = newCase("F2", "SO-3", false);
        line(other, "M1");
        ShortageCase otherMaterial = newCase("F1", "SO-4", false);
        line(otherMaterial, "M2");

        assertThat(cases.findOpenByFactoryIdAndMaterial("F1", "M1")).extracting(ShortageCase::getId).containsExactly(mine.getId());
        assertThat(cases.findOpenForUpdate("F1", ShortageCase.SourceType.SALES_ORDER, "SO-1")).isPresent();
        assertThat(cases.findOpenForUpdate("F1", ShortageCase.SourceType.SALES_ORDER, "SO-2")).isEmpty();
        assertThat(cases.findByIdAndFactoryId(mine.getId(), "F2")).isEmpty();
        assertThat(cases.findForFactory("F1", true, PageRequest.of(0, 10)).getContent())
                .extracting(ShortageCase::getId).containsExactlyInAnyOrder(mine.getId(), otherMaterial.getId());
        assertThat(cases.findForFactory("F1", false, PageRequest.of(0, 10)).getContent()).hasSize(3);
    }

    @Test
    void supplyQueriesOnAnEmptyFactoryAreZeroAndEmpty() {
        assertThat(supply.sumInspectingQuantity("F-NONE", "M1")).isEqualByComparingTo("0");
        assertThat(supply.findOpenOrderRemaining("F-NONE", "M1")).isEmpty();
    }

    @Test
    void inspectingCountsOnlyThisFactorysCompanyOwnedInspectingStock() {
        assumeTrue(onH2(), "seeds bypass FK parents; H2 only (PG covers the schema in the migration test)");
        em.getEntityManager().createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        batch("F1", "M1", MaterialBatchStatus.INSPECTING, InventoryOwnership.COMPANY_OWNED, "5");
        batch("F1", "M1", MaterialBatchStatus.AVAILABLE, InventoryOwnership.COMPANY_OWNED, "7");
        batch("F1", "M1", MaterialBatchStatus.INSPECTING, InventoryOwnership.CUSTOMER_OWNED, "11");
        batch("F2", "M1", MaterialBatchStatus.INSPECTING, InventoryOwnership.COMPANY_OWNED, "13");
        batch("F1", "M2", MaterialBatchStatus.INSPECTING, InventoryOwnership.COMPANY_OWNED, "17");
        em.flush();

        assertThat(supply.sumInspectingQuantity("F1", "M1")).isEqualByComparingTo("5");
    }

    @Test
    void openOrdersAreSubmittedOrLaterAndNotFullyReceivedInThisFactory() {
        assumeTrue(onH2(), "seeds bypass FK parents; H2 only (PG covers the schema in the migration test)");
        em.getEntityManager().createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        item(order("F1", PurchaseOrderStatus.APPROVED), "M1", "kg", "10", "4");          // 未收 6 ✓
        item(order("F1", PurchaseOrderStatus.PARTIAL_RECEIVED), "M1", "箱", "3", "0");   // 未收 3 箱 ✓ (单位另算)
        item(order("F1", PurchaseOrderStatus.DRAFT), "M1", "kg", "50", "0");             // 草稿不算
        item(order("F1", PurchaseOrderStatus.CANCELLED), "M1", "kg", "50", "0");         // 取消不算
        item(order("F1", PurchaseOrderStatus.APPROVED), "M1", "kg", "8", "8");           // 收完不算
        item(order("F2", PurchaseOrderStatus.APPROVED), "M1", "kg", "50", "0");          // 别厂不算
        em.flush();

        List<Object[]> rows = supply.findOpenOrderRemaining("F1", "M1");
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo("kg");
            assertThat((BigDecimal) r[1]).isEqualByComparingTo("6");
        });
        assertThat(rows).anySatisfy(r -> assertThat(r[0]).isEqualTo("箱"));
    }

    private void batch(String factoryId, String material, MaterialBatchStatus status, InventoryOwnership ownership, String qty) {
        MaterialBatch b = new MaterialBatch();
        b.setId(UUID.randomUUID().toString());
        b.setFactoryId(factoryId);
        b.setBatchNumber("B-" + UUID.randomUUID());
        b.setSupplierId("SUP");
        b.setReceiptDate(LocalDate.of(2026, 11, 1));
        b.setWarehouseId("WH");
        b.setMaterialTypeId(material);
        b.setReceiptQuantity(new BigDecimal(qty));
        b.setQuantityUnit("kg");
        b.setStatus(status);
        b.setOwnership(ownership);
        b.setCreatedBy(1L);
        em.persist(b);
    }

    private PurchaseOrder order(String factoryId, PurchaseOrderStatus status) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId(UUID.randomUUID().toString());
        o.setFactoryId(factoryId);
        o.setOrderNumber("PO-" + UUID.randomUUID());
        o.setOrderDate(LocalDate.of(2026, 11, 1));
        o.setStatus(status);
        o.setCreatedBy(1L);
        em.persist(o);
        return o;
    }

    private void item(PurchaseOrder o, String material, String unit, String qty, String received) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setPurchaseOrderId(o.getId());
        i.setMaterialTypeId(material);
        i.setUnit(unit);
        i.setPriceUnit(unit);
        i.setQuantity(new BigDecimal(qty));
        i.setReceivedQuantity(new BigDecimal(received));
        i.setUnitPrice(BigDecimal.ONE);
        em.persist(i);
    }
}
