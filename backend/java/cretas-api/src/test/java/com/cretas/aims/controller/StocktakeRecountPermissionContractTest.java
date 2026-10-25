package com.cretas.aims.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Source contract for the recovery route's permission and service-side guards. */
@DisplayName("Stocktake drift recovery permission contract")
class StocktakeRecountPermissionContractTest {

    private static String source(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/cretas/aims").resolve(relativePath),
                StandardCharsets.UTF_8);
    }

    @Test
    void routeRequiresWarehouseWritePermissionAndServerRole() throws IOException {
        String controller = source("controller/StocktakeController.java");
        assertThat(controller).contains(
                "@PostMapping(\"/{stocktakeId}/recount-after-drift\")",
                "@RequirePermission(\"warehouse:read_write\")",
                "extractRole(request)",
                "recountAfterDrift");
    }

    @Test
    void serviceLocksTenantRowAndOnlyReleasesApprovedDrift() throws IOException {
        String service = source("service/factory/impl/FactoryStocktakeServiceImpl.java");
        int method = service.indexOf("StocktakeDTO recountAfterDrift");
        assertThat(method).isGreaterThan(0);
        String guarded = service.substring(method, service.indexOf("private FactoryStocktake createRecountStocktake", method));
        assertThat(guarded).contains(
                "assertRecountRole(requestRole)",
                "findAndValidateForUpdate(stocktakeId, factoryId)",
                "assertExpectedVersion(source, request.getExpectedVersion())",
                "FactoryStocktake.Status.APPROVED",
                "hasCurrentInventoryDrift(source, factoryId)",
                "FactoryStocktake.Status.RECOUNT_REQUIRED",
                "stocktakeRepo.save(recount)",
                "stocktakeRepo.save(source)");
        assertThat(guarded).doesNotContain("apply(", "adjustmentRepo.save", "materialBatchRepo.save");
    }

    @Test
    void requestRequiresReasonAndOptimisticVersion() throws IOException {
        String request = source("dto/factory/StocktakeRecountRequest.java");
        assertThat(request).contains("@NotNull", "expectedVersion", "@NotBlank", "reason");
    }
}
