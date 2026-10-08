package com.cretas.aims.service.execution;

import com.cretas.aims.entity.config.FactoryToolConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static com.cretas.aims.service.execution.SseFactoryFollowUpContractTest.codes;

class FactoryFollowUpServiceContractTest {
    @Test
    void audienceFilterExcludesWarehouseReceivingForQualityInspectorWithWarehousePositive() throws Exception {
        try (var quality = new SseFactoryFollowUpContractTest.Harness("quality_inspector");
             var warehouse = new SseFactoryFollowUpContractTest.Harness("warehouse_worker")) {
            assertThat(codes(quality.suggest("MATERIAL_BATCH_USE", "COMPLETED"))).doesNotContain("MATERIAL_TODAY_RECEIVING_QUERY");
            assertThat(codes(warehouse.suggest("MATERIAL_BATCH_USE", "COMPLETED"))).contains("MATERIAL_TODAY_RECEIVING_QUERY");
        }
    }

    @Test
    void factoryDisabledReadIsRemovedWithEnabledPositiveControl() throws Exception {
        try (var h = new SseFactoryFollowUpContractTest.Harness("warehouse_worker")) {
            assertThat(codes(h.suggest("MATERIAL_BATCH_QUERY", "SUCCESS"))).contains("MATERIAL_LOW_STOCK_ALERT");
            FactoryToolConfig off = new FactoryToolConfig(); off.setEnabled(false);
            when(h.switches.findByFactoryIdAndToolName("F006", "material_low_stock_alert")).thenReturn(Optional.of(off));
            assertThat(codes(h.suggest("MATERIAL_BATCH_QUERY", "SUCCESS")))
                    .contains("MATERIAL_TODAY_RECEIVING_QUERY").doesNotContain("MATERIAL_LOW_STOCK_ALERT");
        }
    }

    @Test
    void inactiveReadIsRemovedAndNoAlternativeIsInvented() throws Exception {
        try (var h = new SseFactoryFollowUpContractTest.Harness("quality_inspector")) {
            h.assertExecutableReads(h.suggest("QUALITY_CHECK_QUERY", "SUCCESS"));
            h.configs.get("QUALITY_STATS").setIsActive(false);
            h.configs.remove("RECEIVE_QUALITY_CHECK_TODAY");
            assertThat(h.suggest("QUALITY_CHECK_QUERY", "SUCCESS")).isEmpty();
        }
    }

    @Test
    void permissionCodeDenialRemovesReadWithAllowedPositiveControl() throws Exception {
        try (var h = new SseFactoryFollowUpContractTest.Harness("quality_inspector")) {
            assertThat(codes(h.suggest("QUALITY_CHECK_QUERY", "SUCCESS"))).contains("QUALITY_STATS");
            h.configs.get("QUALITY_STATS").setRequiredPermission("inventory:legacy_import");
            assertThat(codes(h.suggest("QUALITY_CHECK_QUERY", "SUCCESS")))
                    .contains("RECEIVE_QUALITY_CHECK_TODAY").doesNotContain("QUALITY_STATS");
        }
    }

    @Test
    void processWriteOffersExistingProcessReadAndIncompleteStatusOffersNothing() throws Exception {
        try (var h = new SseFactoryFollowUpContractTest.Harness("operator")) {
            h.configs.put("WORK_PROCESS_TASK_COMPLETE", com.cretas.aims.entity.config.AIIntentConfig.builder()
                    .intentCode("WORK_PROCESS_TASK_COMPLETE").intentCategory("DATA_OP").isActive(true).build());
            assertThat(codes(h.suggest("WORK_PROCESS_TASK_COMPLETE", "COMPLETED"))).containsExactly("PROCESS_TASK_QUERY");
            assertThat(h.suggest("WORK_PROCESS_TASK_COMPLETE", "WRITE_CONFIRM_REQUIRED")).isEmpty();
        }
    }
}
