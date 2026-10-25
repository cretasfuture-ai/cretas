package com.cretas.aims.repository;

import com.cretas.aims.entity.InsertSlot;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Hibernate gate for the urgent-insert slot queries added on 2026-10-24:
 * {@code findByIdAndFactoryId} (tenant-scoped lookup for write paths) and
 * {@code claimAvailableSlot} (conditional UPDATE that is the atomic claim).
 *
 * <p>Status is read back with JdbcTemplate (not the repository) so the assertion
 * sees what is actually in the table, not a cached entity.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class InsertSlotRepositoryQueryValidationTest {

    @Autowired InsertSlotRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbcTemplate;

    private InsertSlot persistSlot(String factoryId, String status) {
        LocalDateTime start = LocalDateTime.now().plusHours(2).withNano(0);
        InsertSlot slot = InsertSlot.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .productionLineId("default")
                .productionLineName("动态分配")
                .startTime(start)
                .endTime(start.plusHours(2))
                .availableCapacity(new BigDecimal("200"))
                .impactLevel("none")
                .requiredWorkers(4)
                .availableWorkers(4)
                .switchCostMinutes(0)
                .recommendScore(80)
                .status(status)
                .build();
        repository.saveAndFlush(slot);
        entityManager.clear();
        return slot;
    }

    private String statusInDb(String id) {
        return jdbcTemplate.queryForObject("SELECT status FROM insert_slots WHERE id = ?", String.class, id);
    }

    @Test
    void findByIdAndFactoryIdOnlyReturnsOwnFactorySlot() {
        InsertSlot slot = persistSlot("F-IS-A", "available");

        assertThat(repository.findByIdAndFactoryId(slot.getId(), "F-IS-A")).isPresent();
        assertThat(repository.findByIdAndFactoryId(slot.getId(), "F-IS-B")).isEmpty();
        assertThat(repository.findByIdAndFactoryId("no-such-slot", "F-IS-A")).isEmpty();
    }

    @Test
    void claimSucceedsOnceThenReturnsZeroRows() {
        InsertSlot slot = persistSlot("F-IS-A", "available");

        int first = repository.claimAvailableSlot(slot.getId(), "F-IS-A", LocalDateTime.now());
        int second = repository.claimAvailableSlot(slot.getId(), "F-IS-A", LocalDateTime.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).as("same slot claimed again (duplicate submit) must hit 0 rows").isZero();
        assertThat(statusInDb(slot.getId())).isEqualTo("selected");
    }

    @Test
    void claimOfOtherFactorySlotTouchesNothing() {
        InsertSlot slot = persistSlot("F-IS-A", "available");

        int rows = repository.claimAvailableSlot(slot.getId(), "F-IS-B", LocalDateTime.now());

        assertThat(rows).isZero();
        assertThat(statusInDb(slot.getId())).as("other factory's claim must not change the row").isEqualTo("available");
    }

    @Test
    void claimOfNonAvailableOrDeletedSlotTouchesNothing() {
        InsertSlot expired = persistSlot("F-IS-A", "expired");
        InsertSlot deleted = persistSlot("F-IS-A", "available");
        jdbcTemplate.update("UPDATE insert_slots SET deleted_at = ? WHERE id = ?",
                LocalDateTime.now(), deleted.getId());

        assertThat(repository.claimAvailableSlot(expired.getId(), "F-IS-A", LocalDateTime.now())).isZero();
        assertThat(repository.claimAvailableSlot(deleted.getId(), "F-IS-A", LocalDateTime.now())).isZero();
        assertThat(statusInDb(expired.getId())).isEqualTo("expired");
        assertThat(statusInDb(deleted.getId())).isEqualTo("available");
    }
}
