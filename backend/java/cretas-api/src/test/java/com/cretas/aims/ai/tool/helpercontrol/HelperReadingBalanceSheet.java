package com.cretas.aims.ai.tool.helpercontrol;

import com.cretas.aims.service.finance.BalanceSheetService;

/**
 * Positive control for GatedDataToolAiPathPermissionTest: an independent helper (not a tool, not a data owner)
 * that reads the balance sheet, which the App serves only behind finance permissions.
 */
public final class HelperReadingBalanceSheet {

    private final BalanceSheetService balanceSheetService;

    public HelperReadingBalanceSheet(BalanceSheetService balanceSheetService) {
        this.balanceSheetService = balanceSheetService;
    }

    public Object read(String factoryId, int year, int month) {
        return balanceSheetService.generate(factoryId, year, month, null);
    }
}
