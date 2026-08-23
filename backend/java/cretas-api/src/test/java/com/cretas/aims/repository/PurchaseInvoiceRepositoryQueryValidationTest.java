package com.cretas.aims.repository;

import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup gate for tenant-scoped purchase-invoice lock query. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PurchaseInvoiceRepositoryQueryValidationTest {

    @Autowired
    private PurchaseInvoiceRepository purchaseInvoiceRepository;

    @Test
    void tenantScopedPessimisticLockQueryBootsAndCarriesWriteLock() throws Exception {
        assertThat(purchaseInvoiceRepository).isNotNull();
        for (Method method : new Method[] {
                PurchaseInvoiceRepository.class.getMethod(
                        "findByIdAndFactoryIdForUpdate", String.class, String.class),
                PurchaseInvoiceRepository.class.getMethod(
                        "findByFactoryIdAndInvoiceNumberForUpdate", String.class, String.class)}) {
            assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        }
    }
}
