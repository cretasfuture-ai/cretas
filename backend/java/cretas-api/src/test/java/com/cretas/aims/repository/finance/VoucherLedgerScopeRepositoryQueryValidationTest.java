package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real JPA proof that voucher reports aggregate only the pre-selected row ids. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class VoucherLedgerScopeRepositoryQueryValidationTest {

    @Autowired private VoucherRepository voucherRepository;
    @Autowired private VoucherEntryRepository entryRepository;
    @Autowired private MaterialBatchRepository materialBatchRepository;
    @Autowired private MaterialConsumptionRepository materialConsumptionRepository;
    @Autowired private SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Autowired private SalesDeliveryItemRepository salesDeliveryItemRepository;

    @Test
    void scopedQueriesExcludeOtherVoucherIdsBeforePagingAndAggregation() {
        persist("visible", "ROW-SCOPE-VOUCHER", new BigDecimal("10.00"));
        persist("hidden", "ROW-SCOPE-VOUCHER", new BigDecimal("90.00"));

        var page = voucherRepository.findByFactoryIdAndIdInAndVoucherDateBetweenAndDeletedAtIsNull(
                "ROW-SCOPE-VOUCHER", List.of("visible"), LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31));
        assertThat(page).extracting(Voucher::getId).containsExactly("visible");

        var rows = entryRepository.aggregateBySubjectExcludingDraftInScope(
                "ROW-SCOPE-VOUCHER", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                List.of("visible"));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(com.cretas.aims.dto.finance.SubjectAggregateRow::getSubjectCode)
                .containsExactly("1001","6001");
        assertThat(rows.get(0).getTotalDebit()).isEqualByComparingTo("10.00");
        assertThat(rows.get(0).getTotalCredit()).isEqualByComparingTo("0.00");
        assertThat(rows.get(1).getTotalDebit()).isEqualByComparingTo("0.00");
        assertThat(rows.get(1).getTotalCredit()).isEqualByComparingTo("10.00");
        assertThat(voucherRepository.countByFactoryIdAndIdInAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(
                "ROW-SCOPE-VOUCHER", List.of("visible"), VoucherStatus.POSTED,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEqualTo(1);
    }

    @Test
    void quantityAmountOwnerQueriesExecuteWithAnEmptyResultBeforePaging() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        List<Long> owners = List.of(560056L);

        assertThat(materialBatchRepository.findByFactoryIdAndCreatedByInAndReceiptDateBetween(
                "ROW-SCOPE-QUANTITY", owners, start, end, PageRequest.of(0, 20))).isEmpty();
        assertThat(materialConsumptionRepository.findByTimeRangeAndSourceOwnerIn(
                "ROW-SCOPE-QUANTITY", start.atStartOfDay(), end.plusDays(1).atStartOfDay(), owners)).isEmpty();
        assertThat(semiFinishedInventoryRepository.findByFactoryIdForWeightViewAndSourceOwnerIn(
                "ROW-SCOPE-QUANTITY", owners)).isEmpty();
        assertThat(finishedGoodsBatchRepository.findByFactoryIdAndCreatedByInAndProductionDateBetweenOrderByCreatedAtDesc(
                "ROW-SCOPE-QUANTITY", owners, start, end, PageRequest.of(0, 20))).isEmpty();
        assertThat(salesDeliveryItemRepository.findShippedMovementsForLedgerInSourceOwnerScope(
                "ROW-SCOPE-QUANTITY", start, end, owners)).isEmpty();

    }

    private void persist(String id, String factoryId, BigDecimal amount) {
        Voucher voucher = Voucher.builder()
                .id(id).factoryId(factoryId).voucherNumber("V-" + id)
                .voucherType(VoucherType.SALES_RECEIPT)
                .voucherDate(LocalDate.of(2026, 5, 1))
                .sourceBusinessType("MANUAL").sourceBusinessId("manual-" + id)
                .totalDebit(amount).totalCredit(amount).status(VoucherStatus.POSTED)
                .build();
        VoucherEntry debit = VoucherEntry.builder().voucher(voucher).lineNo(1)
                .subjectCode("1001").subjectName("库存现金")
                .debit(amount).credit(BigDecimal.ZERO).build();
        VoucherEntry credit = VoucherEntry.builder().voucher(voucher).lineNo(2)
                .subjectCode("6001").subjectName("主营业务收入")
                .debit(BigDecimal.ZERO).credit(amount).build();
        voucher.setEntries(List.of(debit, credit));
        voucherRepository.saveAndFlush(voucher);
    }
}
