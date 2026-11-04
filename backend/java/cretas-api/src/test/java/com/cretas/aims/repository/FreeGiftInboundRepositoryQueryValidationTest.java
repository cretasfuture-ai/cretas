package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.repository.inventory.InventoryLedgerSnapshotRepository;
import com.cretas.aims.service.inventory.impl.InventoryLedgerServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Real JPA gate for the factory/source-event idempotency lookup used by FREE_GIFT. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class FreeGiftInboundRepositoryQueryValidationTest {

    @Autowired private MaterialBatchRepository repository;
    @Autowired private EntityManager entityManager;
    private Long receiver;

    @BeforeEach
    void seedConstraintValidReferences() {
        receiver = com.cretas.aims.testutil.FactoryCatalogJpaFixtures.seed(entityManager).first();
    }

    @Test
    void sourceEventLookupBootsAndIsFactoryScoped() {
        persistMaterial("MAT-GIFT", "F006");
        persistBatch("GIFT-F006", "F006", "MAT-GIFT", "5", "0", "DOC-1#line:1");
        persistBatch("GIFT-F007", "F007", "MAT-GIFT", "99", "0", "DOC-1#line:1");
        entityManager.flush();

        assertThat(repository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "FREE_GIFT", "DOC-1#line:1")).isPresent();
        assertThat(repository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F007", "FREE_GIFT", "DOC-1#line:1")).isPresent();
        assertThat(repository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(
                "F006", "FREE_GIFT", "other-line")).isEmpty();
    }

    @Test
    void ledgerUsesGiftQuantityAndAmountWithoutDoubleCountingPurchaseOrCrossFactoryRows() {
        persistMaterial("MAT-MIXED", "F006");
        persistMaterial("MAT-GIFT-ONLY", "F006");
        persistBatch("GIFT-MIXED", "F006", "MAT-MIXED", "5", "0", "DOC-MIX#line:1");
        persistBatch("GIFT-HISTORICAL-VALUE", "F006", "MAT-MIXED", "1", "3", "DOC-MIX#line:legacy");
        persistBatch("GIFT-CROSS-FACTORY", "F007", "MAT-MIXED", "99", "0", "DOC-MIX#line:2");
        persistBatch("GIFT-ONLY", "F006", "MAT-GIFT-ONLY", "4", "0", "DOC-ONLY#line:1");
        persistPurchase("RECEIVE-1", "F006", "MAT-MIXED", "2", "10");
        entityManager.flush();

        InventoryLedgerServiceImpl ledger = new InventoryLedgerServiceImpl(
                mock(InventoryLedgerSnapshotRepository.class), mock(RawMaterialTypeRepository.class),
                mock(AccountingPeriodRepository.class), mock(MaterialBatchRepository.class));
        ReflectionTestUtils.setField(ledger, "em", entityManager);

        BigDecimal mixedQty = ReflectionTestUtils.invokeMethod(ledger, "aggregateInboundQty",
                "F006", "MAT-MIXED", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        BigDecimal mixedAmount = ReflectionTestUtils.invokeMethod(ledger, "aggregateInboundAmount",
                "F006", "MAT-MIXED", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        BigDecimal giftOnlyQty = ReflectionTestUtils.invokeMethod(ledger, "aggregateInboundQty",
                "F006", "MAT-GIFT-ONLY", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        BigDecimal giftOnlyAmount = ReflectionTestUtils.invokeMethod(ledger, "aggregateInboundAmount",
                "F006", "MAT-GIFT-ONLY", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(mixedQty).isEqualByComparingTo("8");
        assertThat(mixedAmount).isEqualByComparingTo("23.00");
        assertThat(giftOnlyQty).isEqualByComparingTo("4");
        assertThat(giftOnlyAmount).isEqualByComparingTo("0.00");
    }

    private void persistMaterial(String id, String factoryId) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(factoryId);
        material.setCode(id);
        material.setName(id);
        material.setUnit("kg");
        material.setCreatedBy(receiver);
        entityManager.persist(material);
    }

    private void persistBatch(String id, String factoryId, String materialTypeId,
                              String quantity, String price, String eventKey) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(factoryId);
        batch.setBatchNumber("BATCH-" + id);
        batch.setMaterialTypeId(materialTypeId);
        batch.setSupplierId("SUP-" + factoryId);
        batch.setReceiptDate(LocalDate.of(2026, 2, 1));
        batch.setWarehouseId("WH-" + factoryId);
        batch.setReceiptQuantity(new BigDecimal(quantity));
        batch.setQuantityUnit("kg");
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setUnitPrice(new BigDecimal(price));
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setSourceDocType("FREE_GIFT");
        batch.setSourceDocId(eventKey.substring(0, eventKey.indexOf("#")));
        batch.setSourceEventKey(eventKey);
        batch.setCreatedBy(receiver);
        entityManager.persist(batch);
    }

    private void persistPurchase(String id, String factoryId, String materialTypeId,
                                 String quantity, String price) {
        PurchaseReceiveRecord record = new PurchaseReceiveRecord();
        record.setId(id);
        record.setFactoryId(factoryId);
        record.setReceiveNumber("RN-" + id);
        record.setSupplierId("SUP-" + factoryId);
        record.setReceiveDate(LocalDate.of(2026, 2, 1));
        record.setStatus(PurchaseReceiveStatus.CONFIRMED);
        record.setReceivedBy(receiver);
        record.setTotalAmount(new BigDecimal("20"));
        entityManager.persist(record);

        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setReceiveRecordId(id);
        item.setMaterialTypeId(materialTypeId);
        item.setMaterialName(materialTypeId);
        item.setReceivedQuantity(new BigDecimal(quantity));
        item.setUnit("kg");
        item.setPriceUnit("kg");
        item.setUnitPrice(new BigDecimal(price));
        entityManager.persist(item);
    }
}
