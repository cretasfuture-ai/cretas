package com.cretas.aims.repository;

import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.inventory.SupplierDeliveryNoteLineRepository;
import com.cretas.aims.repository.inventory.SupplierDeliveryNoteRepository;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Execute the actual Spring Data query methods using real Hibernate and disposable PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class SupplierDeliveryNativeQueryPostgresContractTest {
    @Test
    void nativeRepositoryQueriesAndPhotoLookupStayWithinTheFactory() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String schema = "supplier_repo_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password); var st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            try {
                Configuration config = new Configuration()
                        .addAnnotatedClass(SupplierDeliveryNote.class).addAnnotatedClass(SupplierDeliveryNoteLine.class);
                config.setProperty("hibernate.connection.url", url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
                config.setProperty("hibernate.connection.username", user);
                config.setProperty("hibernate.connection.password", password);
                config.setProperty("hibernate.hbm2ddl.auto", "create-drop");
                config.setProperty("hibernate.hbm2ddl.halt_on_error", "true");
                try (var factory = config.buildSessionFactory(); var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    SupplierDeliveryNote a = note("NOTE-A", "F006");
                    SupplierDeliveryNote b = note("NOTE-B", "F007");
                    b.setPhotoHash("foreign-photo");
                    em.persist(a);
                    em.persist(b); // Preserve the existing global unique constraint for rollback compatibility.
                    em.persist(line(a, "F006", "10.00"));
                    em.persist(line(a, "F006", "14.00"));
                    em.persist(line(b, "F007", "9999.00"));
                    em.persist(line(b, "F006", "8888.00")); // Legacy mismatched line tenant cannot leak the foreign note.
                    em.flush();
                    em.clear();
                    JpaRepositoryFactory repositories = new JpaRepositoryFactory(em);
                    SupplierDeliveryNoteLineRepository lines = repositories.getRepository(SupplierDeliveryNoteLineRepository.class);
                    SupplierDeliveryNoteRepository notes = repositories.getRepository(SupplierDeliveryNoteRepository.class);
                    assertThat(lines.averageRecentConfirmedPriceByMaterial("F006", "MATERIAL-1", LocalDate.of(2026, 2, 1)))
                            .isEqualByComparingTo("12.00");
                    assertThat(lines.averageRecentConfirmedPriceByIngredientName("F006", " flour ", LocalDate.of(2026, 2, 1)))
                            .isEqualByComparingTo("12.00");
                    assertThat(notes.findByFactoryIdAndPhotoHash("F006", "same-photo").orElseThrow().getId()).isEqualTo("NOTE-A");
                    assertThat(notes.findByFactoryIdAndPhotoHash("F007", "same-photo")).isEmpty();
                    assertThat(notes.findByFactoryIdAndPhotoHash("F007", "foreign-photo").orElseThrow().getId()).isEqualTo("NOTE-B");
                    assertThat(notes.findByFactoryIdAndPhotoHash("F008", "same-photo")).isEmpty();
                    em.getTransaction().rollback();
                }
            } finally {
                st.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static SupplierDeliveryNote note(String id, String tenant) {
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setId(id);
        note.setFactoryId(tenant);
        note.setPhotoHash("same-photo");
        note.setDeliveryDate(LocalDate.of(2026, 1, 1));
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        return note;
    }

    private static SupplierDeliveryNoteLine line(SupplierDeliveryNote note, String tenant, String price) {
        SupplierDeliveryNoteLine line = new SupplierDeliveryNoteLine();
        line.setNote(note);
        line.setFactoryId(tenant);
        line.setIngredientName("Flour");
        line.setRawMaterialTypeId("MATERIAL-1");
        line.setUnitPrice(new BigDecimal(price));
        return line;
    }
}
