package com.cretas.aims.service.finance;

import java.util.List;

/** Request-local voucher ids selected before a financial report/export is run. */
public final class VoucherRowScopeContext implements AutoCloseable {
    private static final ThreadLocal<List<String>> IDS = new ThreadLocal<>();
    private static final ThreadLocal<List<Long>> CREATORS = new ThreadLocal<>();
    private final List<String> previous;
    private final List<Long> previousCreators;

    private VoucherRowScopeContext(List<String> ids) {
        this(ids, null);
    }

    private VoucherRowScopeContext(List<String> ids, List<Long> creators) {
        previous = IDS.get();
        previousCreators = CREATORS.get();
        IDS.set(ids);
        CREATORS.set(creators);
    }

    public static VoucherRowScopeContext open(List<String> ids) {
        return new VoucherRowScopeContext(ids);
    }

    public static VoucherRowScopeContext open(List<String> ids, List<Long> creators) {
        return new VoucherRowScopeContext(ids, creators);
    }

    public static List<String> current() {
        return IDS.get();
    }

    public static List<Long> currentCreators() {
        return CREATORS.get();
    }

    @Override
    public void close() {
        if (previous == null) IDS.remove();
        else IDS.set(previous);
        if (previousCreators == null) CREATORS.remove();
        else CREATORS.set(previousCreators);
    }
}
