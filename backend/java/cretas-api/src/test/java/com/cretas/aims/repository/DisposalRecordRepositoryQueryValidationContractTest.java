package com.cretas.aims.repository;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.service.DisposalRecordService;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Real Hibernate gate for tenant/status/FG filtering before pagination. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class DisposalRecordRepositoryQueryValidationContractTest {

    @Autowired private DisposalRecordRepository repository;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    private DisposalRecordService service;

    @BeforeEach
    void setUp() {
        service = new DisposalRecordService(repository,
                mock(MaterialBatchRepository.class), mock(MaterialBatchAdjustmentRepository.class),
                mock(ProductionBatchRepository.class), finishedGoodsBatchRepository,
                mock(FinishedGoodsAdjustmentLogRepository.class));
    }

    @Test
    void filtersFactoryStatusAndFinishedGoodsBatchBeforePaging() {
        repository.saveAndFlush(record("F-A", "FG-A", "APPROVED", "match-me"));
        repository.saveAndFlush(record("F-A", "FG-A", "PENDING", "match-me"));
        repository.saveAndFlush(record("F-A", "FG-B", "APPROVED", "match-me"));
        repository.saveAndFlush(record("F-B", "FG-A", "APPROVED", "match-me"));

        Page<DisposalRecord> page = service.getByFactoryIdWithFilters(
                "F-A", "product", "APPROVED", "match", "FG-A", 0, 1);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement()
                .satisfies(value -> assertThat(value.getFactoryId()).isEqualTo("F-A"));
    }

    @Test
    void keywordPredicateDoesNotLeakOtherFactoryRows() {
        repository.saveAndFlush(record("F-A", "FG-A", "PENDING", "needle in reason"));
        repository.saveAndFlush(record("F-B", "FG-A", "PENDING", "needle in reason"));

        Page<DisposalRecord> page = service.getByFactoryIdWithFilters(
                "F-A", null, null, "needle", null, 0, 10);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement()
                .satisfies(value -> assertThat(value.getFactoryId()).isEqualTo("F-A"));
    }

    private DisposalRecord record(String factoryId, String batchId, String status, String reason) {
        DisposalRecord value = new DisposalRecord();
        value.setFactoryId(factoryId);
        value.setFinishedGoodsBatchId(batchId);
        value.setDisposalQuantity(new BigDecimal("1"));
        value.setDisposalType("product");
        value.setDisposalDate(LocalDateTime.of(2026, 10, 16, 10, 0));
        value.setIsApproved("APPROVED".equals(status));
        value.setApprovalStatus(status);
        value.setDisposalReason(reason);
        return value;
    }
}
