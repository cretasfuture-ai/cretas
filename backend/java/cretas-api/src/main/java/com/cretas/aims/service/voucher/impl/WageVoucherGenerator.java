package com.cretas.aims.service.voucher.impl;

import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.service.voucher.VoucherSubjectResolver;
import com.cretas.aims.service.voucher.AbstractVoucherGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 工资发放凭证 generator.
 *
 * 借: 2211 应付职工薪酬 (冲减负债)
 * 贷: 1002 银行存款 (现金流出)
 *
 * 业务: PayrollRecord status=PAID → 财务记 "应付薪酬已支付".
 *
 * Note: PayrollRecord.id 是 Long, 转 String 存 source_business_id.
 */
@Component
public class WageVoucherGenerator extends AbstractVoucherGenerator<PayrollRecord> {

    public static final String BUSINESS_TYPE = "PAYROLL_RECORD";

    @Override
    public VoucherType getType() {
        return VoucherType.WAGE;
    }

    @Override
    public boolean supports(String businessType) {
        return BUSINESS_TYPE.equals(businessType);
    }

    @Override
    protected String extractSourceBusinessType() {
        return BUSINESS_TYPE;
    }

    @Override
    protected String extractSourceBusinessId(PayrollRecord r) {
        return r.getId() != null ? r.getId().toString() : null;
    }

    @Override
    protected LocalDate extractVoucherDate(PayrollRecord r) {
        return r.getPeriodStart();
    }

    @Override
    protected String extractDescription(PayrollRecord r) {
        return "工资发放 — " + r.getWorkerName() + " (" + r.getPeriodStart() + ")";
    }

    @Override
    public List<VoucherEntry> buildEntries(PayrollRecord r) {
        return buildEntries(r.getFactoryId(), r);
    }

    /** 应付职工薪酬同时是辅助核算匹配键 —— 与分录一起解析。 */
    private VoucherSubjectResolver.Subject payableSubject(String factoryId) {
        return subject(factoryId, "DEBIT", "2211", "应付职工薪酬");
    }

    @Override
    protected List<VoucherEntry> buildEntries(String factoryId, PayrollRecord r) {
        VoucherSubjectResolver.Subject payable = payableSubject(factoryId);
        VoucherSubjectResolver.Subject bank = subject(factoryId, "CREDIT", "1002", "银行存款");
        BigDecimal amount = nullToZero(r.getTotalWage());
        String workerId = r.getWorkerId() != null ? r.getWorkerId().toString() : null;
        return List.of(
                // 应付职工薪酬按职员分账 (R-HJ Round 11 §G.1 职员工资明细账)
                debitEntryWithAuxiliary(1, payable.code(), payable.name(), amount, "支付 " + r.getWorkerName(),
                        workerId != null ? AuxiliaryType.EMPLOYEE : null, workerId),
                creditEntry(2, bank.code(), bank.name(), amount, "工资银行划款")
        );
    }

    /**
     * 🔴🔒 #1207: template 路径把 EMPLOYEE 辅助核算附到应付职工薪酬借方行 (与 buildEntries 一致)。
     * 借方 = 应付职工薪酬 (冲减负债, 按职员分账), 前缀 2211; 银行存款贷方不挂职员维度。
     */
    @Override
    protected void applyAuxiliary(List<VoucherEntry> entries, PayrollRecord r) {
        String workerId = r.getWorkerId() != null ? r.getWorkerId().toString() : null;
        attachAuxiliary(entries, EntrySide.DEBIT, payableSubject(r.getFactoryId()).code(),
                workerId != null ? AuxiliaryType.EMPLOYEE : null, workerId);
    }
}
