package com.cretas.aims.service.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReportCorrectionFlowContractTest {

    private static String source(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/cretas/aims").resolve(relativePath),
                StandardCharsets.UTF_8);
    }

    @Test
    void settlementBlocksPendingCorrectionBeforeReservationOrInventoryMutation() throws IOException {
        String source = source("service/impl/ProductionPlanServiceImpl.java");
        int guard = source.indexOf("productionReportCorrectionService.assertNoPendingForPlan(factoryId, planId)");
        int release = source.indexOf("workshopReservationService.releaseForPlan(factoryId, planId)");
        assertThat(guard).isGreaterThan(0);
        assertThat(release).isGreaterThan(guard);
    }

    @Test
    void correctionRequestCannotCarryInputInventoryOrCostFields() throws IOException {
        String request = source("dto/production/ProductionReportCorrectionRequest.java");
        assertThat(request).contains("outputQuantity", "goodQuantity", "defectQuantity",
                "totalWorkers", "totalWorkMinutes", "notes", "reason");
        assertThat(request).doesNotContain("inputQuantity", "materialBatch", "consumption", "cost");
    }

    @Test
    void oaCallbackAndStocktakeWarningAreWiredWithoutRoleConstants() throws IOException {
        String controller = source("controller/workflow/WorkflowInstanceController.java");
        String correction = source("service/impl/ProductionReportCorrectionServiceImpl.java");
        String stocktake = source("service/factory/impl/FactoryStocktakeServiceImpl.java");
        assertThat(controller).contains("PRODUCTION_REPORT_CORRECTION",
                "productionReportCorrectionService.applyWorkflowAction");
        assertThat(correction).contains("请先配置报工修正审批流程");
        assertThat(correction).doesNotContain("factory_super_admin", "production_manager", "finance_manager");
        assertThat(stocktake).contains("本批次存在经审批修正的报工数据，请重点核对实物数量",
                "approvedForProducts");
    }

    @Test
    void correctionBoundaryKeepsInputSegmentOutOfOutputPathAndExposesTrustedMetadata() throws IOException {
        String correction = source("service/impl/ProductionReportCorrectionServiceImpl.java");
        assertThat(correction).contains("PRODUCTION_REPORT_CORRECTION_OUTPUT_ONLY",
                "PRODUCTION_REPORT_CORRECTION_UNKNOWN_KIND",
                "isOutputOrLegacy",
                "PRODUCTION_REPORT_CORRECTION_MIXED_OUTPUT_UNITS",
                "reportKind",
                "outputUnit");
        assertThat(correction).contains("filter(this::isCorrectionableKind)");
    }
}
