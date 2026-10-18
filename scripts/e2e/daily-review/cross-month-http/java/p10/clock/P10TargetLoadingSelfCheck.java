package p10.clock;

import org.springframework.data.auditing.CurrentDateTimeProvider;

import java.time.LocalDateTime;

/** Loads every production target under the agent without starting Spring or a database. */
public final class P10TargetLoadingSelfCheck {
    private static final String[] TARGETS = {
            "com.cretas.aims.service.inventory.ReturnRefundService",
            "com.cretas.aims.service.voucher.impl.VoucherServiceImpl",
            "com.cretas.aims.service.finance.impl.ArApServiceImpl",
            "com.cretas.aims.service.finance.impl.AccountingPeriodServiceImpl",
            "com.cretas.aims.entity.BaseEntity",
            "com.cretas.aims.entity.finance.AccountingPeriod",
            "org.springframework.data.auditing.CurrentDateTimeProvider"
    };

    private P10TargetLoadingSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        P10BusinessClock.initializeFromSystemProperties();
        for (String target : TARGETS) {
            Class.forName(target, false, Thread.currentThread().getContextClassLoader());
        }
        P10BusinessClock.Context context = P10BusinessClock.readContext(null);
        LocalDateTime audited = P10BusinessClock.withContext(context,
                () -> LocalDateTime.from(CurrentDateTimeProvider.INSTANCE.getNow().orElseThrow()));
        if (!context.businessDate().equals(audited.toLocalDate())) {
            throw new IllegalStateException("P10_TARGET_DATE_SUBSTITUTION_FAILED");
        }
        if (P10BusinessClock.hasActiveContext()) {
            throw new IllegalStateException("P10_TARGET_CONTEXT_NOT_RESTORED");
        }
        System.out.println("P10_TARGET_LOAD_SELF_CHECK=PASS targets=" + TARGETS.length
                + " auditDate=" + audited.toLocalDate());
    }
}
