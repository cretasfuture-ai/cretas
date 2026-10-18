package com.cretas.aims.repository;

import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.finance.VoucherEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real Hibernate startup and execution: old COGS plus its red voucher cannot survive as duplicate P&L cost. */
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
class VoucherCostCorrectionRepositoryQueryValidationTest {
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherEntryRepository entries;
    private static final String FACTORY="P22-JPA-COST";
    private static final LocalDate DATE=LocalDate.of(2026,10,31);

    @Test void recloseUsesOnlyCurrentCogsWhilePreservingOrdinaryBusinessReversalNets() {
        Voucher old=save(FACTORY,VoucherType.COST_CARRYOVER,VoucherStatus.POSTED,"265.95",null,false,"6401");
        assertCost("265.95");
        old.setStatus(VoucherStatus.REVERSED);vouchers.saveAndFlush(old);
        save(FACTORY,VoucherType.COST_CARRYOVER,VoucherStatus.POSTED,"265.95",old.getId(),true,"6401");
        save(FACTORY,VoucherType.COST_CARRYOVER,VoucherStatus.POSTED,"270.95",null,false,"6401");
        save(FACTORY,VoucherType.PL_CLOSING,VoucherStatus.POSTED,"270.95",null,true,"6401");
        save(FACTORY,VoucherType.COST_CARRYOVER,VoucherStatus.DRAFT,"999",null,false,"6401");
        save("P22-OTHER",VoucherType.COST_CARRYOVER,VoucherStatus.POSTED,"999",null,false,"6401");
        Voucher expense=save(FACTORY,VoucherType.EXPENSE,VoucherStatus.REVERSED,"20",null,false,"6601");
        save(FACTORY,VoucherType.EXPENSE,VoucherStatus.POSTED,"20",expense.getId(),true,"6601");
        assertCost("270.95");
        var rows=entries.aggregateBySubjectExcludingDraftAndClosing(FACTORY,DATE.withDayOfMonth(1),DATE);
        var ordinary=rows.stream().filter(r->"6601".equals(r.getSubjectCode())).findFirst().orElseThrow();
        assertEquals(0,ordinary.getTotalDebit().compareTo(ordinary.getTotalCredit()));
        var closing=entries.aggregateBySubjectPosted(FACTORY,DATE.withDayOfMonth(1),DATE);
        var closingExpense=closing.stream().filter(r->"6601".equals(r.getSubjectCode())).findFirst().orElseThrow();
        assertEquals(0,closingExpense.getTotalDebit().compareTo(closingExpense.getTotalCredit()),"ordinary reversal must net to zero in closing too");
        var closingCost=closing.stream().filter(r->"6401".equals(r.getSubjectCode())).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("270.95").compareTo(closingCost.getTotalDebit().subtract(closingCost.getTotalCredit())));
        Voucher sale=save(FACTORY,VoucherType.SALES_RECEIPT,VoucherStatus.REVERSED,"100",null,true,"6001");
        save(FACTORY,VoucherType.SALES_RECEIPT,VoucherStatus.POSTED,"100",sale.getId(),false,"6001");
        var closingSale=entries.aggregateBySubjectPosted(FACTORY,DATE.withDayOfMonth(1),DATE).stream()
                .filter(r->"6001".equals(r.getSubjectCode())).findFirst().orElseThrow();
        assertEquals(0,closingSale.getTotalDebit().compareTo(closingSale.getTotalCredit()));
    }

    private void assertCost(String expected) {
        var row=entries.aggregateBySubjectExcludingDraftAndClosing(FACTORY,DATE.withDayOfMonth(1),DATE)
                .stream().filter(r->"6401".equals(r.getSubjectCode())).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal(expected).compareTo(row.getTotalDebit().subtract(row.getTotalCredit())));
    }
    private Voucher save(String factory,VoucherType type,VoucherStatus status,String value,String original,boolean reverse,String subject) {
        BigDecimal amount=new BigDecimal(value);
        Voucher v=Voucher.builder().factoryId(factory).voucherNumber("V-"+UUID.randomUUID()).voucherDate(DATE)
                .sourceBusinessType(type.name()).sourceBusinessId(UUID.randomUUID().toString())
                .voucherType(type).status(status).originalVoucherId(original).totalDebit(amount).totalCredit(amount).build();
        v.getEntries().add(VoucherEntry.builder().voucher(v).lineNo(1).subjectCode(subject).subjectName("测试科目")
                .debit(reverse?BigDecimal.ZERO:amount).credit(reverse?amount:BigDecimal.ZERO).build());
        v.getEntries().add(VoucherEntry.builder().voucher(v).lineNo(2).subjectCode("1405").subjectName("库存商品")
                .debit(reverse?amount:BigDecimal.ZERO).credit(reverse?BigDecimal.ZERO:amount).build());
        return vouchers.saveAndFlush(v);
    }
}
