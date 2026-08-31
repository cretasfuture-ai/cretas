package com.cretas.aims.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSettlementCoverageControllerWiringContractTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/controller/ProductionPlanController.java");

    @Test
    void prefillAndOneClickSettlementBothUseCustomerSuppliedCoverageGuard() throws Exception {
        String source = Files.readString(SOURCE);
        int settle = source.indexOf("public ApiResponse<ProductionSettlementResponse> settleProduction(");
        int prefill = source.indexOf("public ApiResponse<ProductionSettlementPrefillResponse> getSettlementPrefill(");
        int settleAudit = source.indexOf("customerSuppliedSettlementCoverageGuard.audit(", settle);
        int settleRequire = source.indexOf("customerSuppliedSettlementCoverageGuard.requireCleanForOneClick(", settle);
        int settleEnd = source.indexOf("productionPlanService.settleProduction(", settle);
        int prefillAudit = source.indexOf("customerSuppliedSettlementCoverageGuard.audit(", prefill);

        assertTrue(settle >= 0 && settleAudit > settle && settleRequire > settleAudit
                        && settleEnd > settleRequire,
                "confirm=true must be audited and rejected before the settlement mutation");
        assertTrue(prefill >= 0 && prefillAudit > prefill,
                "the read-only prefill endpoint must expose the same blocker to the Web form");
    }
}
