package com.cretas.aims.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSettlementCoverageControllerWiringContractTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/controller/ProductionPlanController.java");

    @Test
    void prefillAndLockedOneClickSettlementUseTheSameCoverageGuard() throws Exception {
        String source = Files.readString(SOURCE);
        int settle = source.indexOf("public ApiResponse<ProductionSettlementResponse> settleProduction(");
        int prefill = source.indexOf("public ApiResponse<ProductionSettlementPrefillResponse> getSettlementPrefill(");
        int settleEnd = source.indexOf("productionPlanService.settleProduction(", settle);
        int prefillAudit = source.indexOf("customerSuppliedSettlementCoverageGuard.audit(", prefill);
        String service = Files.readString(Path.of("src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java"));
        int lockedSettle = service.indexOf("public ProductionSettlementResponse settleProduction(");
        int replay = service.indexOf("if (sameRequest.isPresent())", lockedSettle);
        int deriveCall = service.indexOf("? deriveConfirmedSettlementRequest(", replay);
        int posting = service.indexOf("productionSettlementRepository.save(settlement)", deriveCall);
        int deriveMethod = service.indexOf("private ProductionSettlementRequest deriveConfirmedSettlementRequest(");
        int audit = service.indexOf("customerSuppliedSettlementCoverageGuard.audit(", deriveMethod);
        int require = service.indexOf("customerSuppliedSettlementCoverageGuard.requireCleanForOneClick(", audit);

        assertTrue(settle >= 0 && settleEnd > settle,
                "controller must delegate the mutation to the transactional settlement service");
        assertTrue(!source.substring(settle, settleEnd).contains("getSettlementPrefill("),
                "controller must not audit consumed stock before the idempotent return");
        assertTrue(replay > lockedSettle && deriveCall > replay && posting > deriveCall
                        && audit > deriveMethod && require > audit,
                "new confirmations must retain the coverage guard before posting, after idempotent return");
        assertTrue(prefill >= 0 && prefillAudit > prefill,
                "the read-only prefill endpoint must expose the same blocker to the Web form");
    }
}
