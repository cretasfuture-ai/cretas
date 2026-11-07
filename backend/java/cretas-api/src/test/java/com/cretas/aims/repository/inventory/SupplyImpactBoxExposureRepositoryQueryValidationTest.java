package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.service.supplyimpact.SupplyImpactBoxExposureService;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.repository.projection.SupplyImpactBoxExposureRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Hibernate startup plus execution gate for the native projection. The positive execution
 * is opt-in PostgreSQL because jsonb_extract_path_text and the production schema are PostgreSQL
 * contracts. A skipped run does not prove JPA startup or SQL execution.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SupplyImpactBoxExposureRepositoryQueryValidationTest {

    private static final String OWN_SCHEMA = "box_exposure_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        boolean hasUrl = url != null && !url.isBlank();
        boolean verify = "true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"));
        if (hasUrl != verify) {
            throw new IllegalStateException(
                    "CRETAS_WORKFLOW_PG_URL and CRETAS_WORKFLOW_PG_VERIFY=true must be set together");
        }
        if (hasUrl) {
            String safe = com.cretas.aims.integration.DisposablePostgresTargetGuard.requireSafeUrl(url);
            String separator = safe.contains("?") ? "&" : "?";
            try (Connection connection = DriverManager.getConnection(safe,
                    System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"),
                    System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + OWN_SCHEMA);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Cannot create owned projection-test schema", e);
            }
            String bound = safe + separator + "currentSchema=" + OWN_SCHEMA
                    + "&stringtype=unspecified&ApplicationName=SupplyImpactBoxExposureRepositoryQueryValidationTest";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () ->
                    System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () ->
                    System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.default_schema", () -> OWN_SCHEMA);
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @AfterAll
    static void dropOwnedSchema() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url == null || url.isBlank()) return;
        String safe = com.cretas.aims.integration.DisposablePostgresTargetGuard.requireSafeUrl(url);
        try (Connection connection = DriverManager.getConnection(safe,
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + OWN_SCHEMA + " CASCADE");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot drop owned projection-test schema", e);
        }
    }

    @Autowired TestEntityManager entityManager;
    @Autowired FinishedGoodsBatchRepository repository;
    private Long fixtureUserId;

    @Test
    @EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
    void executesScopedSplitDeductionsAndFiltersMasterNonactualAndForeignCustomer() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String factoryId = "BOX-QA-" + suffix.substring(0, 16);
        String foreignFactoryId = "BOX-OTHER-" + suffix.substring(0, 12);
        String warehouseId = "WH-BOX-" + suffix.substring(0, 16);
        String batchId = "FG-BOX-1-" + suffix.substring(0, 14);
        String secondBatchId = "FG-BOX-2-" + suffix.substring(0, 14);
        String customerId = "C-BOX-" + suffix.substring(0, 14);
        String foreignCustomerId = "C-OTHER-" + suffix.substring(0, 12);
        String deliveryId = "DR-BOX-" + suffix.substring(0, 14);

        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName("box exposure " + suffix);
        entityManager.persist(factory);

        Factory foreignFactory = new Factory();
        foreignFactory.setId(foreignFactoryId);
        foreignFactory.setName("foreign box exposure " + suffix);
        entityManager.persist(foreignFactory);
        seedProductAndUser(factoryId, suffix);

        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId(warehouseId);
        warehouse.setFactoryId(factoryId);
        warehouse.setCode("WH-BOX");
        warehouse.setName("箱数闸门仓");
        warehouse.setType(FactoryWarehouse.WarehouseType.FINISHED);
        entityManager.persist(warehouse);

        entityManager.persist(customer(customerId, factoryId, "本厂客户"));
        entityManager.persist(customer(foreignCustomerId, foreignFactoryId, "异厂客户"));

        var batch = finishedBatch(batchId, factoryId, warehouseId, "BOX-BATCH-1-" + suffix.substring(0, 8), "100", "3", "8");
        var secondBatch = finishedBatch(secondBatchId, factoryId, warehouseId,
                "BOX-BATCH-2-" + suffix.substring(0, 8), "20", "5", "5");
        entityManager.persist(batch);
        entityManager.persist(secondBatch);
        entityManager.persist(delivery(deliveryId, factoryId, customerId, "D-BOX-1"));
        entityManager.flush();

        SalesDeliveryItem firstItem = deliveryItem(deliveryId, "7", "8");
        SalesDeliveryItem secondItem = deliveryItem(deliveryId, "999", "10");
        entityManager.persist(firstItem);
        entityManager.persist(secondItem);
        entityManager.flush();
        entityManager.persist(edge("E-BOX-1-" + suffix.substring(0, 8), factoryId, batchId, deliveryId,
                firstItem.getId(), "2"));
        entityManager.persist(edge("E-BOX-2-" + suffix.substring(0, 8), factoryId, secondBatchId, deliveryId,
                firstItem.getId(), "5"));

        String foreignCustomerDeliveryId = "DR-BOX-2-" + suffix.substring(0, 14);
        entityManager.persist(delivery(foreignCustomerDeliveryId, factoryId, foreignCustomerId, "D-BOX-2"));
        SalesDeliveryItem foreignCustomerItem = deliveryItem(foreignCustomerDeliveryId, "1", "8");
        entityManager.persist(foreignCustomerItem);
        entityManager.flush();
        entityManager.persist(edge("E-BOX-3-" + suffix.substring(0, 8), factoryId, batchId,
                foreignCustomerDeliveryId, foreignCustomerItem.getId(), "1"));

        SalesDeliveryRecord master = delivery("DR-BOX-M-" + suffix.substring(0, 14), factoryId,
                customerId, "D-BOX-M");
        master.setRecordRole("MASTER");
        entityManager.persist(master);
        SalesDeliveryItem masterItem = deliveryItem(master.getId(), "1", "8");
        entityManager.persist(masterItem);
        entityManager.flush();
        entityManager.persist(edgeWithCarrier("E-BOX-M-" + suffix.substring(0, 8), factoryId, batchId,
                master.getId(), masterItem.getId(), "1", "SALES_DELIVERY_RECORD"));
        entityManager.persist(edgeWithCarrier("E-BOX-X-" + suffix.substring(0, 8), factoryId, batchId,
                deliveryId, firstItem.getId(), "1", "OTHER"));
        entityManager.flush();
        assertThat(entityManager.getEntityManager().createNativeQuery("SELECT current_schema()")
                .getSingleResult()).isEqualTo(OWN_SCHEMA);
        entityManager.clear();

        List<SupplyImpactBoxExposureRow> rows = repository.findSupplyImpactBoxExposureRows(
                factoryId, List.of(batchId, secondBatchId), List.of(deliveryId, foreignCustomerDeliveryId, master.getId()));

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(SupplyImpactBoxExposureRow::getFinishedGoodsBatchId)
                .containsExactly(batchId, batchId, secondBatchId);
        assertThat(rows).extracting(SupplyImpactBoxExposureRow::getEdgeId)
                .containsExactly("E-BOX-1-" + suffix.substring(0, 8),
                        "E-BOX-3-" + suffix.substring(0, 8), "E-BOX-2-" + suffix.substring(0, 8));
        assertThat(rows.get(0).getFactoryId()).isEqualTo(factoryId);
        assertThat(rows.get(0).getProducedQuantity()).isEqualByComparingTo("100");
        assertThat(rows.get(0).getShippedQuantity()).isEqualByComparingTo("3");
        assertThat(rows.get(0).getReservedQuantity()).isEqualByComparingTo("10");
        assertThat(rows.get(0).getCustomerId()).isEqualTo(customerId);
        assertThat(rows.get(1).getCustomerId()).isNull();
        assertThat(rows.get(1).getCustomerName()).isNull();
        assertThat(repository.findSupplyImpactBoxExposureRows("OTHER-" + suffix,
                List.of(batchId, secondBatchId), List.of(deliveryId))).isEmpty();

        var exposure = new SupplyImpactBoxExposureService(repository).evaluate(factoryId,
                List.of(batchId, secondBatchId), List.of(deliveryId, foreignCustomerDeliveryId), true);
        assertThat(exposure.getStock().getTotalBoxQuantity()).isEqualTo("15.125");
        assertThat(exposure.getShipped().getTotalBoxQuantity()).isEqualTo("1");
        assertThat(exposure.getShipmentSources()).hasSize(3);
        assertThat(exposure.getShipmentSources().stream().filter(source -> deliveryId.equals(source.getDeliveryRecordId())))
                .allSatisfy(source -> assertThat(source.getDeliveryItemId()).isEqualTo(firstItem.getId().toString()));
        assertThat(exposure.getCustomers().stream().filter(customer -> customerId.equals(customer.getCustomerId())))
                .singleElement().satisfies(customer -> assertThat(customer.getTotals().getTotalBoxQuantity()).isEqualTo("0.875"));

        var bounded = repository.findSupplyImpactBoxExposureRows(factoryId,
                List.of(batchId, secondBatchId), List.of(deliveryId));
        assertThat(bounded).hasSize(2).allSatisfy(row -> assertThat(row.getShipmentId()).isEqualTo(deliveryId));
        var incomplete = new SupplyImpactBoxExposureService(repository).evaluate(factoryId,
                List.of(batchId, secondBatchId), List.of(deliveryId), true);
        assertThat(incomplete.getShipped().getKnownBoxSubtotal()).isEqualTo("0.875");
        assertThat(incomplete.getShipped().getTotalBoxQuantity()).isNull();
        assertThat(incomplete.getCustomers()).allSatisfy(customer -> assertThat(customer.getTotals().getTotalBoxQuantity()).isNull());
    }

    @Test
    void keepsDefectivePhysicalStockWhenNoShipmentMatchesTheBoundedJoin() {
        Fixture fixture = seed();
        var batch = finishedBatch("FG-" + fixture.suffix(), fixture.factoryId(), fixture.warehouseId(),
                "B-" + fixture.suffix(), "100", "20", "8");
        batch.setStatus("DEFECTIVE"); entityManager.persist(batch); entityManager.flush(); entityManager.clear();
        var rows = repository.findSupplyImpactBoxExposureRows(fixture.factoryId(), List.of(batch.getId()), List.of("UNMATCHED-SHIPMENT"));
        assertThat(rows).hasSize(1).allSatisfy(row -> assertThat(row.getEdgeId()).isNull());
        var exposure = new SupplyImpactBoxExposureService(repository).evaluate(fixture.factoryId(), List.of(batch.getId()), List.of(), true);
        assertThat(exposure.getStock().getTotalBoxQuantity()).isEqualTo("10");
        assertThat(exposure.getStockSources()).singleElement().satisfies(source -> {
            assertThat(source.getSourceQuantity()).isEqualTo("80"); assertThat(source.getReservedQuantity()).isEqualTo("10");
        });
        assertThat(exposure.getShipped().getTotalBoxQuantity()).isNull();
    }

    @Test
    void deletedAndWrongProductItemsCannotSupplySnapshotOrInventKnownBoxes() {
        Fixture fixture = seed();
        var batch = finishedBatch("FG-" + fixture.suffix(), fixture.factoryId(), fixture.warehouseId(),
                "B-" + fixture.suffix(), "80", "4", "8"); entityManager.persist(batch);
        String customerId = "C-" + fixture.suffix(); entityManager.persist(customer(customerId, fixture.factoryId(), "客户"));
        String deliveryId = "DR-" + fixture.suffix(); entityManager.persist(delivery(deliveryId, fixture.factoryId(), customerId, "D-" + fixture.suffix()));
        ProductType other = new ProductType(); other.setId("OTHER-PRODUCT"); other.setFactoryId(fixture.factoryId());
        other.setCode("OTHER"); other.setName("其他产品"); other.setUnit("盒"); entityManager.persist(other);
        SalesDeliveryItem deleted = deliveryItem(deliveryId, "2", "8"); entityManager.persist(deleted);
        SalesDeliveryItem mismatch = deliveryItem(deliveryId, "2", "8"); mismatch.setProductTypeId(other.getId()); entityManager.persist(mismatch);
        entityManager.flush();
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        entityManager.persist(edge("E-DEL-" + fixture.suffix(), fixture.factoryId(), batch.getId(), deliveryId, deleted.getId(), "2"));
        entityManager.persist(edge("E-MISMATCH-" + fixture.suffix(), fixture.factoryId(), batch.getId(), deliveryId, mismatch.getId(), "2"));
        entityManager.flush(); entityManager.clear();
        var exposure = new SupplyImpactBoxExposureService(repository).evaluate(fixture.factoryId(), List.of(batch.getId()), List.of(deliveryId), true);
        assertThat(exposure.getShipmentSources()).hasSize(2).allSatisfy(source -> {
            assertThat(source.getDeliveryItemId()).isNull(); assertThat(source.getBoxQuantity()).isNull();
            assertThat(source.getGaps()).contains("SHIPMENT_LINE_FACT_MISSING");
        });
        assertThat(exposure.getShipped().getKnownBoxSubtotal()).isNull();
        assertThat(exposure.getGaps()).extracting(gap -> gap.getCode()).contains("SHIPMENT_LINE_FACT_MISSING");
    }

    @Test
    void rejectsDraftDeletedForeignAndSoftDeletedEdgesWithoutDroppingTheStockRow() {
        Fixture fixture = seed();
        var batch = finishedBatch("FG-" + fixture.suffix(), fixture.factoryId(), fixture.warehouseId(),
                "B-" + fixture.suffix(), "80", "0", "8"); entityManager.persist(batch);
        String customerId = "C-" + fixture.suffix(); entityManager.persist(customer(customerId, fixture.factoryId(), "客户"));
        String foreignFactory = "FOREIGN-" + fixture.suffix(); Factory foreign = new Factory(); foreign.setId(foreignFactory); foreign.setName(foreignFactory); entityManager.persist(foreign);
        for (String label : List.of("DRAFT", "DELETED", "FOREIGN", "EDGE-DELETED", "WRONG-ORIGIN")) {
            String deliveryId = label + "-" + fixture.suffix();
            SalesDeliveryRecord record = delivery(deliveryId, label.equals("FOREIGN") ? foreignFactory : fixture.factoryId(), customerId, deliveryId);
            if (label.equals("DRAFT")) record.setStatus(SalesDeliveryStatus.DRAFT);
            if (label.equals("DELETED")) record.setDeletedAt(java.time.LocalDateTime.now());
            entityManager.persist(record); SalesDeliveryItem item = deliveryItem(deliveryId, "2", "8"); entityManager.persist(item); entityManager.flush();
            BatchLineageEdge edge = edge("E-" + deliveryId, fixture.factoryId(), batch.getId(), deliveryId, item.getId(), "2");
            if (label.equals("EDGE-DELETED")) edge.setDeletedAt(java.time.LocalDateTime.now());
            if (label.equals("WRONG-ORIGIN")) edge.setMeta(Map.of("shipmentCarrier", "SALES_DELIVERY_RECORD", "origin", "ALLOCATION_INTENT", "deliveryItemId", item.getId().toString()));
            entityManager.persist(edge);
        }
        entityManager.flush(); entityManager.clear();
        var rows = repository.findSupplyImpactBoxExposureRows(fixture.factoryId(), List.of(batch.getId()),
                List.of("DRAFT-" + fixture.suffix(), "DELETED-" + fixture.suffix(), "FOREIGN-" + fixture.suffix(), "EDGE-DELETED-" + fixture.suffix(), "WRONG-ORIGIN-" + fixture.suffix()));
        assertThat(rows).hasSize(1).allSatisfy(row -> assertThat(row.getEdgeId()).isNull());
        entityManager.getEntityManager().createNativeQuery("UPDATE finished_goods_batches SET deleted_at=NOW() WHERE factory_id=:factory AND id=:id")
                .setParameter("factory", fixture.factoryId()).setParameter("id", batch.getId()).executeUpdate();
        assertThat(repository.findSupplyImpactBoxExposureRows(fixture.factoryId(), List.of(batch.getId()), List.of("UNMATCHED"))).isEmpty();
    }

    private record Fixture(String factoryId, String warehouseId, String suffix) {}

    private Fixture seed() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String factoryId = "BOX-" + suffix; Factory factory = new Factory(); factory.setId(factoryId); factory.setName(factoryId); entityManager.persist(factory);
        seedProductAndUser(factoryId, suffix);
        String warehouseId = "WH-" + suffix; FactoryWarehouse warehouse = new FactoryWarehouse(); warehouse.setId(warehouseId);
        warehouse.setFactoryId(factoryId); warehouse.setCode("WH-BOX"); warehouse.setName("测试仓"); warehouse.setType(FactoryWarehouse.WarehouseType.FINISHED); entityManager.persist(warehouse);
        return new Fixture(factoryId, warehouseId, suffix);
    }

    private void seedProductAndUser(String factoryId, String suffix) {
        User user = new User(); user.setFactoryId(factoryId); user.setUsername("box_" + suffix);
        user.setPasswordHash("NON_LOGIN_TEST_FIXTURE"); entityManager.persist(user); fixtureUserId = user.getId();
        ProductType product = new ProductType(); product.setId("PRODUCT-BOX"); product.setFactoryId(factoryId);
        product.setCode("BOX"); product.setName("箱装产品"); product.setUnit("盒"); entityManager.persist(product);
    }

    private Customer customer(String id, String factoryId, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFactoryId(factoryId);
        customer.setCode(id);
        customer.setCustomerCode(id);
        customer.setName(name);
        customer.setIsActive(true);
        customer.setCreatedBy(fixtureUserId);
        return customer;
    }

    private static com.cretas.aims.entity.inventory.FinishedGoodsBatch finishedBatch(
            String id, String factoryId, String warehouseId, String number,
            String produced, String shipped, String factor) {
        var batch = new com.cretas.aims.entity.inventory.FinishedGoodsBatch();
        batch.setId(id);
        batch.setFactoryId(factoryId);
        batch.setBatchNumber(number);
        batch.setProductTypeId("PRODUCT-BOX");
        batch.setProducedQuantity(new BigDecimal(produced));
        batch.setShippedQuantity(new BigDecimal(shipped));
        batch.setReservedQuantity(new BigDecimal("10"));
        batch.setUnit("盒");
        batch.setPackagingUnit("箱");
        batch.setPackagingBaseUnit("盒");
        batch.setPackagingFactor(new BigDecimal(factor));
        batch.setWarehouseId(warehouseId);
        batch.setStatus("AVAILABLE");
        return batch;
    }

    private SalesDeliveryRecord delivery(String id, String factoryId, String customerId, String number) {
        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId(id);
        delivery.setFactoryId(factoryId);
        delivery.setDeliveryNumber(number);
        delivery.setCustomerId(customerId);
        delivery.setDeliveryDate(java.time.LocalDate.of(2026, 11, 7));
        delivery.setStatus(SalesDeliveryStatus.SHIPPED);
        delivery.setShippedBy(fixtureUserId);
        delivery.setRecordRole("LEGACY");
        return delivery;
    }

    private static SalesDeliveryItem deliveryItem(String deliveryId, String quantity, String factor) {
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setDeliveryRecordId(deliveryId);
        item.setProductTypeId("PRODUCT-BOX");
        item.setProductName("箱装成品");
        item.setDeliveredQuantity(new BigDecimal(quantity));
        item.setUnit("盒");
        item.setPackagingUnit("箱");
        item.setPackagingBaseUnit("盒");
        item.setPackagingFactor(new BigDecimal(factor));
        return item;
    }

    private static BatchLineageEdge edge(String id, String factoryId, String sourceId,
                                         String targetId, Long deliveryItemId, String quantity) {
        return edgeWithCarrier(id, factoryId, sourceId, targetId, deliveryItemId, quantity,
                "SALES_DELIVERY_RECORD");
    }

    private static BatchLineageEdge edgeWithCarrier(String id, String factoryId, String sourceId,
                                                    String targetId, Long deliveryItemId, String quantity,
                                                    String carrier) {
        BatchLineageEdge edge = new BatchLineageEdge();
        edge.setId(id);
        edge.setFactoryId(factoryId);
        edge.setSourceType("FINISHED_BATCH");
        edge.setSourceId(sourceId);
        edge.setTargetType("SHIPMENT_RECORD");
        edge.setTargetId(targetId);
        edge.setEdgeType("FINISHED_TO_SHIPMENT");
        edge.setQuantityUsed(new BigDecimal(quantity));
        edge.setUnit("盒");
        edge.setMeta(Map.of("shipmentCarrier", carrier, "origin", "SALES_DELIVERY_SHIPMENT",
                "deliveryItemId", String.valueOf(deliveryItemId)));
        return edge;
    }
}
