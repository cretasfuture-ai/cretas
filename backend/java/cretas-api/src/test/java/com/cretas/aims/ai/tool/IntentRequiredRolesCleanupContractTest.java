package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.impl.shipment.TraceBatchTool;
import com.cretas.aims.ai.tool.impl.shipment.TraceFullTool;
import com.cretas.aims.entity.config.AIIntentConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261103_140 drops the legacy intent-level required_roles of read intents (S6 follow-up: the intent layer
 * must not be stricter than the App page; 2026-11-03 retest: quality_manager / production_manager got NO_PERMISSION
 * for 「追溯批次」 while /traceability/full and /impact answer them). This pins what the migration may touch:
 * only intents whose tool is a READ tool, so the tool's own gate (pinned to the App by
 * GatedDataToolAiPathPermissionTest) takes over; no write intent is loosened.
 */
@DisplayName("V20261103_140: 只去掉读意图的旧角色名单, 写意图一个不动")
class IntentRequiredRolesCleanupContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/flyway/V20261103_140__read_intents_drop_legacy_required_roles.sql");

    private static GatedDataScan scan;
    private static Map<String, String> pairs;

    @BeforeAll
    static void load() throws Exception {
        scan = GatedDataScan.run();
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        pairs = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\\('([A-Z0-9_]+)', '([a-z0-9_]+)'\\)").matcher(sql);
        while (m.find()) {
            assertThat(pairs.put(m.group(1), m.group(2))).as("duplicate intent %s", m.group(1)).isNull();
        }
        // The guard against everything else: the UPDATE only touches rows without a permission code.
        assertThat(sql).contains("(required_permission IS NULL OR btrim(required_permission) = '')")
                .contains("SET required_roles = NULL");
    }

    @Test
    void everyIntentItTouchesBindsAReadTool() {
        assertThat(pairs).hasSize(55).containsEntry("TRACE_BATCH", "trace_batch").containsEntry("TRACE_FULL", "trace_full");
        List<String> problems = new ArrayList<>();
        pairs.forEach((intent, tool) -> {
            ToolExecutor instance = scan.instances.get(tool);
            if (instance == null) problems.add(intent + " → " + tool + ": no such tool");
            else if (ToolAccessModes.isWrite(instance)) problems.add(intent + " → " + tool + ": WRITE tool, must keep its roles");
        });
        assertThat(problems).isEmpty();
    }

    @Test
    void qualityManagerAskingToTraceABatchPassesTheIntentGateOnceTheRoleListIsGone() {
        // The real intent gate: TRACE_BATCH has no required_permission, so IntentPermissionGate hands over to the
        // legacy required_roles check (IntentConfigManagementServiceImpl#checkRolePermission).
        AIIntentConfig before = AIIntentConfig.builder().intentCode("TRACE_BATCH").intentName("批次溯源")
                .toolName("trace_batch")
                .requiredRoles("[\"factory_super_admin\", \"department_admin\", \"quality_inspector\"]").build();
        assertThat(new com.cretas.aims.service.execution.IntentPermissionGate().check(before, 42L, "quality_manager").isLegacy())
                .isTrue();
        com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl intents =
                org.mockito.Mockito.mock(com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl.class,
                        org.mockito.Mockito.CALLS_REAL_METHODS);
        // The prod row before V20261103_140: 「追溯批次」 refused for quality_manager / production_manager (2026-11-03 retest).
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                intents, "checkRolePermission", before, "quality_manager")).isFalse();
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                intents, "checkRolePermission", before, "production_manager")).isFalse();
        // After the migration (required_roles = NULL): admitted at the intent layer; the tool's own gate decides.
        AIIntentConfig after = AIIntentConfig.builder().intentCode("TRACE_BATCH").intentName("批次溯源")
                .toolName("trace_batch").requiredRoles(null).build();
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                intents, "checkRolePermission", after, "quality_manager")).isTrue();
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                intents, "checkRolePermission", after, "production_manager")).isTrue();
    }

    @Test
    void withTheRoleListGoneTheTraceIntentsAreOpenLikeTheTraceabilityPages() {
        // Intent layer: an empty required_roles admits every role (IntentConfigManagementServiceImpl#checkRolePermission).
        AIIntentConfig cleared = AIIntentConfig.builder().intentCode("TRACE_BATCH").toolName("trace_batch").build();
        assertThat(cleared.getRequiredRoles()).isNull();
        // Tool layer: the trace tools carry no gate, as TraceabilityController /batch, /full and /impact carry none.
        for (Class<?> type : List.of(TraceBatchTool.class, TraceFullTool.class)) {
            ToolExecutor tool = GatedDataScan.instance(type);
            assertThat(tool.getRequiredPermissions()).as(type.getSimpleName()).isEmpty();
            assertThat(tool.getRequiredRoles()).as(type.getSimpleName()).isEmpty();
            assertThat(tool.requiresPermission() && !tool.hasPermission("quality_manager")).as(type.getSimpleName()).isFalse();
            assertThat(tool.requiresPermission() && !tool.hasPermission("production_manager")).as(type.getSimpleName()).isFalse();
        }
        assertThat(scan.readEndpoints.get("TraceabilityController#getFullTrace").gated()).isFalse();
        assertThat(scan.readEndpoints.get("TraceabilityController#getImpact").gated()).isFalse();
    }
}
