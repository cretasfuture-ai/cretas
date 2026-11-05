package com.cretas.aims.repository;

import com.cretas.aims.repository.inventory.SupplierDeliveryNoteLineRepository;
import com.cretas.aims.repository.inventory.SupplierDeliveryNoteRepository;
import com.cretas.aims.repository.inventory.WastageRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup gate for the factory inventory delivery/wastage repositories. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SupplierDeliveryAndWastageRepositoryQueryValidationTest {
    @Autowired SupplierDeliveryNoteRepository supplierDeliveryNoteRepository;
    @Autowired SupplierDeliveryNoteLineRepository supplierDeliveryNoteLineRepository;
    @Autowired WastageRecordRepository wastageRecordRepository;

    @Test
    void repositoriesBootAndDerivedQueriesParse() {
        assertNotNull(supplierDeliveryNoteRepository);
        assertNotNull(supplierDeliveryNoteLineRepository);
        assertNotNull(wastageRecordRepository);
        org.junit.jupiter.api.Assertions.assertTrue(supplierDeliveryNoteRepository
                .findByFactoryIdAndPhotoHash("FACTORY_QUERY_GATE", "not-present").isEmpty());
        supplierDeliveryNoteRepository.findConfirmedBySupplierAndDateRange(
                "FACTORY_QUERY_GATE", "SUPPLIER_QUERY_GATE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        wastageRecordRepository.findByFactoryIdAndDateRange(
                "FACTORY_QUERY_GATE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        wastageRecordRepository.findByFilters(
                "FACTORY_QUERY_GATE", null, null, null, null, PageRequest.of(0, 10));
    }
}
