package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cron schedules and event trigger chains: read-only tools only, no identity parameters, and
 * every run goes through the tool gateway as the configured run-as factory user. A tool is never
 * executed directly by these runners.
 */
class AutonomousToolExecutionContractTest {
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ToolExecutor readTool = tool(AccessMode.READ);
    private final ToolExecutor writeTool = tool(AccessMode.WRITE);
    private final ToolExecutor undeclaredTool = mock(ToolExecutor.class);
    private final ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
    private final AuthenticatedToolPrincipalFactory principals = mock(AuthenticatedToolPrincipalFactory.class);
    // Unless a test stubs consume(), no delegated grant exists: every write is refused before the gateway.
    private final AutonomousGrantService grants = mock(AutonomousGrantService.class);
    private final com.cretas.aims.service.PreviewTokenService previewTokens =
            mock(com.cretas.aims.service.PreviewTokenService.class);
    // The scheduled-fire consumption (config lock + binding re-check) is covered on PostgreSQL; here it
    // hands straight through to the grant service so the grant stubs below decide.
    private final AutonomousGrantIssuer issuer = mock(AutonomousGrantIssuer.class);
    private final AutonomousToolRunner runner = new AutonomousToolRunner(gateway, principals, registry, new ObjectMapper(),
            grants, previewTokens, issuer);
    private final FactorySchedulerConfigRepository schedulerRepo = mock(FactorySchedulerConfigRepository.class);
    private final DynamicSchedulerService scheduler =
            new DynamicSchedulerService(schedulerRepo, registry, runner, new ObjectMapper());

    @BeforeEach void tools() throws Exception {
        // These cases are about grants, not the kill switch: a switch that lets writes through.
        AutonomousWriteSwitch switchedOn = mock(AutonomousWriteSwitch.class);
        when(switchedOn.suspension(any())).thenReturn(Optional.empty());
        java.lang.reflect.Field field = AutonomousToolRunner.class.getDeclaredField("writeSwitch");
        field.setAccessible(true);
        field.set(runner, switchedOn);
        when(issuer.consumeForScheduledFire(any(), any(), any())).thenAnswer(call ->
                grants.consume(call.getArgument(0), call.getArgument(1), call.getArgument(2), grantor -> true));
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(readTool));
        when(registry.getExecutor("dictionary_add")).thenReturn(Optional.of(writeTool));
        when(registry.getExecutor("legacy_tool")).thenReturn(Optional.of(undeclaredTool));
        when(registry.isToolEnabledForFactory(any(), any())).thenReturn(true);
        when(readTool.getVersion()).thenReturn("1.0.0");
        when(undeclaredTool.getToolName()).thenReturn("legacy_tool");
        when(principals.create(eq("F001"), any(), any())).thenAnswer(call -> principal(call.getArgument(1)));
        when(gateway.execute(any())).thenAnswer(call -> result(call.getArgument(0), ToolExecutionStatus.SUCCEEDED));
    }

    @Test void policyAllowsOnlyDeclaredReadToolsWithoutIdentityParameters() {
        assertThat(AutonomousToolPolicy.rejection("material_expired_query", readTool, Map.of("limit", 5))).isNull();
        assertThat(AutonomousToolPolicy.rejection("dictionary_add", writeTool, Map.of())).contains("dictionary_add");
        assertThat(AutonomousToolPolicy.rejection("legacy_tool", undeclaredTool, Map.of())).contains("legacy_tool");
        assertThat(AutonomousToolPolicy.rejection("material_expired_query", readTool, Map.of("factoryId", "F-OTHER")))
                .contains("factoryId");
        assertThat(AutonomousToolPolicy.refusal(AutonomousToolPolicy.rejection("dictionary_add", writeTool, Map.of())))
                .hasMessageContaining("只能使用只读工具").hasMessageContaining("dictionary_add");
    }

    @Test void accessModeComesFromTheFailClosedResolverIncludingTheMigrationSeed() {
        ToolExecutor seededRead = mock(ToolExecutor.class);
        when(seededRead.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(seededRead.getToolName()).thenReturn("restaurant_avg_ticket");
        assertThat(AutonomousToolPolicy.rejection("restaurant_avg_ticket", seededRead, Map.of())).isNull();
        ToolExecutor defaultWrite = mock(ToolExecutor.class);
        when(defaultWrite.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(defaultWrite.getToolName()).thenReturn("not_in_seed");
        assertThat(AutonomousToolPolicy.rejection("not_in_seed", defaultWrite, Map.of())).contains("not_in_seed");
    }

    @Test void scheduledReadToolRunsThroughTheGatewayAsTheRunAsUserNeverDirectly() throws Exception {
        FactorySchedulerConfig config = schedule(73L, "material_expired_query", Map.of("limit", 5), 42L);
        when(schedulerRepo.findById(73L)).thenReturn(Optional.of(config));
        runScheduled(config);

        ArgumentCaptor<ToolExecutionCommand> command = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().principal().principalId()).isEqualTo("42");
        assertThat(command.getValue().principal().principalType()).isEqualTo(PrincipalType.USER);
        assertThat(command.getValue().principal().tenantId()).isEqualTo("F001");
        assertThat(command.getValue().source()).isEqualTo(ToolExecutionSource.SCHEDULER);
        assertThat(command.getValue().mode()).isEqualTo(ToolExecutionMode.EXECUTE);
        assertThat(command.getValue().toolName()).isEqualTo("material_expired_query");
        assertThat(command.getValue().parameters().get("limit").asInt()).isEqualTo(5);
        assertThat(command.getValue().confirmationProof()).isEmpty();
        verify(readTool, never()).execute(any(), any());
        assertThat(config.getLastExecutionStatus()).isEqualTo("SUCCESS");
    }

    @Test void noRunAsUserMeansTheGatewayIsNeverCalled() throws Exception {
        FactorySchedulerConfig config = schedule(74L, "material_expired_query", Map.of(), null);
        when(schedulerRepo.findById(74L)).thenReturn(Optional.of(config));
        runScheduled(config);
        verifyNoInteractions(gateway, principals);
        assertThat(config.getLastExecutionStatus()).isEqualTo("RUN_AS_MISSING");
    }

    @Test void gatewayDecisionsAreRecordedWithTheAuditIdAndUnknownIsNotSuccess() throws Exception {
        doAnswer(call -> result(call.getArgument(0), ToolExecutionStatus.DENIED)).when(gateway).execute(any());
        FactorySchedulerConfig denied = schedule(75L, "material_expired_query", Map.of(), 42L);
        when(schedulerRepo.findById(75L)).thenReturn(Optional.of(denied));
        runScheduled(denied);
        assertThat(denied.getLastExecutionStatus()).isEqualTo("GATEWAY_DENIED");
        assertThat(denied.getLastExecutionError()).contains("audit-1");

        doAnswer(call -> result(call.getArgument(0), ToolExecutionStatus.OUTCOME_UNKNOWN)).when(gateway).execute(any());
        FactorySchedulerConfig unknown = schedule(76L, "material_expired_query", Map.of(), 42L);
        when(schedulerRepo.findById(76L)).thenReturn(Optional.of(unknown));
        runScheduled(unknown);
        assertThat(unknown.getLastExecutionStatus()).isEqualTo("OUTCOME_UNKNOWN");

        doThrow(new SecurityException("tenant unavailable")).when(principals).create(eq("F001"), eq(43L), any());
        FactorySchedulerConfig inactiveTenant = schedule(77L, "material_expired_query", Map.of(), 43L);
        when(schedulerRepo.findById(77L)).thenReturn(Optional.of(inactiveTenant));
        runScheduled(inactiveTenant);
        assertThat(inactiveTenant.getLastExecutionStatus()).isEqualTo("GATEWAY_DENIED");
    }

    @Test void writeAndUndeclaredToolsNeverReachTheGateway() throws Exception {
        FactorySchedulerConfig write = schedule(78L, "dictionary_add", Map.of(), 42L);
        when(schedulerRepo.findById(78L)).thenReturn(Optional.of(write));
        runScheduled(write, PLANNED);
        FactorySchedulerConfig undeclared = schedule(79L, "legacy_tool", Map.of(), 42L);
        when(schedulerRepo.findById(79L)).thenReturn(Optional.of(undeclared));
        runScheduled(undeclared, PLANNED);
        FactorySchedulerConfig foreign = schedule(80L, "material_expired_query", Map.of("factoryId", "F-OTHER"), 42L);
        when(schedulerRepo.findById(80L)).thenReturn(Optional.of(foreign));
        runScheduled(foreign);
        verifyNoInteractions(gateway);
        verify(writeTool, never()).execute(any(), any());
        // Writes look for a delegated grant bound to exactly this config run; none exists, so they stop there.
        assertThat(write.getLastExecutionStatus()).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        assertThat(undeclared.getLastExecutionStatus()).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
        verify(grants).consume(org.mockito.ArgumentMatchers.argThat(binding -> "78".equals(binding.sourceRef())
                && "dictionary_add".equals(binding.toolName()) && Long.valueOf(42L).equals(binding.runAsUserId())
                && binding.configRevision() != null), eq(PLANNED), any(), any());
        assertThat(foreign.getLastExecutionStatus()).isEqualTo("CONFIG_INVALID");
    }

    @Test void writeToolIsScheduledButARunWithoutAGrantNeverReachesTheGateway() throws Exception {
        org.springframework.scheduling.TaskScheduler taskScheduler = mock(org.springframework.scheduling.TaskScheduler.class);
        Field field = DynamicSchedulerService.class.getDeclaredField("taskScheduler");
        field.setAccessible(true);
        field.set(scheduler, taskScheduler);
        FactorySchedulerConfig config = schedule(81L, "dictionary_add", Map.of(), 42L);
        when(schedulerRepo.findById(81L)).thenReturn(Optional.of(config));
        doReturn(mock(java.util.concurrent.ScheduledFuture.class)).when(taskScheduler)
                .schedule(any(Runnable.class), any(org.springframework.scheduling.Trigger.class));
        scheduler.scheduleTask(config);
        verify(taskScheduler).schedule(any(Runnable.class), any(org.springframework.scheduling.Trigger.class));
        runScheduled(config, PLANNED);
        verifyNoInteractions(gateway);
        assertThat(config.getLastExecutionStatus()).isEqualTo(AutonomousToolRunner.GRANT_DENIED);
    }

    @Test void withoutASwitchBeanOrWithTheSwitchOffAWriteIsSuspendedBeforeAnyGrant() throws Exception {
        FactorySchedulerConfig config = schedule(82L, "dictionary_add", Map.of(), 42L);
        when(schedulerRepo.findById(82L)).thenReturn(Optional.of(config));
        Field field = AutonomousToolRunner.class.getDeclaredField("writeSwitch");
        field.setAccessible(true);

        field.set(runner, null);
        runScheduled(config, PLANNED);
        assertThat(config.getLastExecutionStatus()).isEqualTo(AutonomousToolRunner.WRITES_SUSPENDED);
        assertThat(config.getLastExecutionError()).contains("unavailable");

        AutonomousWriteSwitch off = mock(AutonomousWriteSwitch.class);
        when(off.suspension("F001")).thenReturn(Optional.of("not switched on"));
        field.set(runner, off);
        runScheduled(config, PLANNED);
        assertThat(config.getLastExecutionStatus()).isEqualTo(AutonomousToolRunner.WRITES_SUSPENDED);

        verifyNoInteractions(gateway);
        verify(grants, never()).consume(any(), any(), any(), any());
    }

    @Test void aRefusedWriteIsAuditedEvenWhenThePrincipalLookupFailsUnexpectedly() throws Exception {
        com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService ledger =
                mock(com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService.class);
        when(ledger.beginAudit(any(), any())).thenReturn("audit-refusal");
        Field ledgerField = AutonomousToolRunner.class.getDeclaredField("ledger");
        ledgerField.setAccessible(true);
        ledgerField.set(runner, ledger);
        when(principals.create(eq("F001"), any(), any())).thenThrow(new IllegalStateException("factory lookup failed"));
        AutonomousWriteSwitch off = mock(AutonomousWriteSwitch.class);
        when(off.suspension("F001")).thenReturn(Optional.of("not switched on"));
        Field switchField = AutonomousToolRunner.class.getDeclaredField("writeSwitch");
        switchField.setAccessible(true);
        switchField.set(runner, off);
        FactorySchedulerConfig config = schedule(83L, "dictionary_add", Map.of(), 42L);
        when(schedulerRepo.findById(83L)).thenReturn(Optional.of(config));

        runScheduled(config, PLANNED);

        assertThat(config.getLastExecutionStatus()).isEqualTo(AutonomousToolRunner.WRITES_SUSPENDED);
        assertThat(config.getLastExecutionError()).contains("audit-refusal");
        ArgumentCaptor<ExecutionPrincipal> actor = ArgumentCaptor.forClass(ExecutionPrincipal.class);
        verify(ledger).beginAudit(any(), actor.capture());
        assertThat(actor.getValue().principalType()).isEqualTo(PrincipalType.SYSTEM);
        verify(ledger).completeAudit("audit-refusal", ToolExecutionStatus.DENIED,
                com.cretas.aims.ai.tool.gateway.GatewayResultCode.WRITES_SUSPENDED);
        verifyNoInteractions(gateway);
    }

    @Test void aWriteWithoutAPlannedFireIdentityIsRefusedRatherThanKeyedByItsStartTime() throws Exception {
        FactorySchedulerConfig write = schedule(84L, "dictionary_add", Map.of(), 42L);
        when(schedulerRepo.findById(84L)).thenReturn(Optional.of(write));
        runScheduled(write);
        assertThat(write.getLastExecutionStatus()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
        assertThat(write.getLastExecutionError()).contains("no planned fire identity");
        var binding = SchedulerGrantBinding.of(write, null, new ObjectMapper());
        // An actual-time key (the old fallback) is not an identity either.
        assertThat(runner.run("F001", 42L, "dictionary_add", Map.of(), ToolExecutionSource.SCHEDULER, "c", binding,
                "fire:2026-10-22T07:00").status()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
        verifyNoInteractions(gateway, grants, previewTokens);
    }

    @Test void eachFireReReadsTheConfigStopsWhenDisabledAndReschedulesWhenTheCronChanged() throws Exception {
        org.springframework.scheduling.TaskScheduler taskScheduler = mock(org.springframework.scheduling.TaskScheduler.class);
        Field field = DynamicSchedulerService.class.getDeclaredField("taskScheduler");
        field.setAccessible(true);
        field.set(scheduler, taskScheduler);
        java.util.concurrent.ScheduledFuture<?> future = mock(java.util.concurrent.ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(org.springframework.scheduling.Trigger.class));
        FactorySchedulerConfig scheduled = schedule(82L, "material_expired_query", Map.of("limit", 1), 42L);
        scheduler.scheduleTask(scheduled);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(org.springframework.scheduling.Trigger.class));

        // Edited elsewhere: new params are what this fire runs with.
        FactorySchedulerConfig edited = schedule(82L, "material_expired_query", Map.of("limit", 9), 42L);
        when(schedulerRepo.findById(82L)).thenReturn(Optional.of(edited));
        task.getValue().run();
        ArgumentCaptor<ToolExecutionCommand> command = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        assertThat(command.getValue().parameters().get("limit").asInt()).isEqualTo(9);

        // Cron changed elsewhere: rescheduled here, not run under the old cron.
        FactorySchedulerConfig recron = schedule(82L, "material_expired_query", Map.of("limit", 9), 42L);
        recron.setCronExpression("0 30 9 1 * ?");
        when(schedulerRepo.findById(82L)).thenReturn(Optional.of(recron));
        task.getValue().run();
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(org.springframework.scheduling.Trigger.class));
        verify(gateway, times(1)).execute(any());

        // Disabled elsewhere: this instance stops and nothing runs.
        FactorySchedulerConfig disabled = schedule(82L, "material_expired_query", Map.of("limit", 9), 42L);
        disabled.setEnabled(false);
        when(schedulerRepo.findById(82L)).thenReturn(Optional.of(disabled));
        task.getValue().run();
        verify(gateway, times(1)).execute(any());
        verify(future, atLeastOnce()).cancel(false);
    }

    @Test void theRunKeyIsThePlannedFireInstantNotAnActualTime() {
        java.time.Instant planned = java.time.Instant.parse("2026-10-21T23:00:00Z");
        assertThat(DynamicSchedulerService.plannedFireKey(planned)).isEqualTo("slot:2026-10-21T23:00:00Z");
        assertThat(DynamicSchedulerService.plannedFireKey(null)).isNull();
    }

    @Test void grantedWriteReachesTheGatewayWithAPerRunProofAndIdempotencyKey() {
        when(writeTool.getVersion()).thenReturn("1.0.0");
        when(grants.consume(any(), any(), any(), any())).thenAnswer(call -> Optional.of(new AutonomousGrantService.Consumption(
                "g1", "autonomous:g1:" + call.getArgument(1), "1.0.0", false)));
        when(previewTokens.createBoundToken(any())).thenAnswer(call -> com.cretas.aims.entity.intent.IntentPreviewToken
                .builder().token("tok").commandDigest("digest").build());
        FactorySchedulerConfig config = schedule(78L, "dictionary_add", Map.of("name", "x"), 42L);
        var binding = SchedulerGrantBinding.of(config, "1.0.0", new ObjectMapper());

        assertThat(runner.run("F001", 42L, "dictionary_add", Map.of("name", "x"), ToolExecutionSource.SCHEDULER,
                "c", binding, "slot:1").status()).isEqualTo("SUCCESS");
        assertThat(runner.run("F001", 42L, "dictionary_add", Map.of("name", "x"), ToolExecutionSource.SCHEDULER,
                "c", binding, "slot:2").status()).isEqualTo("SUCCESS");

        ArgumentCaptor<ToolExecutionCommand> commands = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway, times(2)).execute(commands.capture());
        assertThat(commands.getAllValues()).extracting(c -> c.idempotencyKey().orElseThrow())
                .containsExactly("autonomous:g1:slot:1", "autonomous:g1:slot:2");
        assertThat(commands.getAllValues()).allSatisfy(c -> {
            assertThat(c.confirmationProof().orElseThrow().proofToken()).isEqualTo("tok");
            assertThat(c.principal().principalId()).isEqualTo("42");
        });
        ArgumentCaptor<com.cretas.aims.service.PreviewTokenService.BoundTokenRequest> tokens =
                ArgumentCaptor.forClass(com.cretas.aims.service.PreviewTokenService.BoundTokenRequest.class);
        verify(previewTokens, times(2)).createBoundToken(tokens.capture());
        // Each run gets its own intent code: issuing a token cancels pending tokens with the same code.
        assertThat(tokens.getAllValues()).extracting(com.cretas.aims.service.PreviewTokenService.BoundTokenRequest::intentCode)
                .doesNotHaveDuplicates().allMatch(code -> code.startsWith("AUTONOMOUS_TOOL_RUN:"));
        // RUNNING is written inside the consuming transaction (issuer); the runner records each outcome once.
        verify(issuer, times(2)).consumeForScheduledFire(any(), any(), any());
        verify(issuer).recordOutcome(eq("g1"), eq("slot:1"), eq("SUCCEEDED"), any(), any());
        verify(issuer).recordOutcome(eq("g1"), eq("slot:2"), eq("SUCCEEDED"), any(), any());
    }

    @Test void aBindingThatDoesNotDescribeThisRunOrAnEventChainWriteIsRefusedBeforeAnyGrant() {
        when(writeTool.getVersion()).thenReturn("1.0.0");
        FactorySchedulerConfig config = schedule(78L, "dictionary_add", Map.of("name", "x"), 42L);
        var binding = SchedulerGrantBinding.of(config, "1.0.0", new ObjectMapper());
        // Other parameters than the ones the binding was computed for.
        assertThat(runner.run("F001", 42L, "dictionary_add", Map.of("name", "y"), ToolExecutionSource.SCHEDULER,
                "c", binding, "slot:1").status()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
        // Another user, another tool version.
        assertThat(runner.run("F001", 43L, "dictionary_add", Map.of("name", "x"), ToolExecutionSource.SCHEDULER,
                "c", binding, "slot:1").status()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
        when(writeTool.getVersion()).thenReturn("2.0.0");
        assertThat(runner.run("F001", 42L, "dictionary_add", Map.of("name", "x"), ToolExecutionSource.SCHEDULER,
                "c", binding, "slot:1").status()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
        // Event chains get no delegated writes whatever is passed.
        when(writeTool.getVersion()).thenReturn("1.0.0");
        assertThat(runner.run("F001", 42L, "dictionary_add", Map.of("name", "x"), ToolExecutionSource.TRIGGER,
                "c", binding, "slot:1").status()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
        verifyNoInteractions(gateway, grants, previewTokens);
    }

    @Test void replayedSlotNeverReachesTheGatewayAndReportsTheRecordedOutcome() {
        when(writeTool.getVersion()).thenReturn("1.0.0");
        when(grants.consume(any(), any(), any(), any())).thenReturn(Optional.of(
                new AutonomousGrantService.Consumption("g1", "autonomous:g1:slot:1", "1.0.0", true)));
        com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord succeeded =
                mock(com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.class);
        when(succeeded.getState()).thenReturn(com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord.State.SUCCEEDED);
        when(grants.gatewayRecord(eq("F001"), eq(42L), eq("dictionary_add"), any()))
                .thenReturn(Optional.empty(), Optional.of(succeeded));
        var binding = SchedulerGrantBinding.of(schedule(78L, "dictionary_add", Map.of(), 42L), "1.0.0", new ObjectMapper());

        var inFlight = runner.run("F001", 42L, "dictionary_add", Map.of(), ToolExecutionSource.SCHEDULER, "c", binding,
                "slot:1");
        var done = runner.run("F001", 42L, "dictionary_add", Map.of(), ToolExecutionSource.SCHEDULER, "c", binding,
                "slot:1");
        assertThat(inFlight.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(done.status()).isEqualTo("SUCCESS");
        assertThat(List.of(inFlight, done)).allMatch(AutonomousToolRunner.Outcome::replay);
        verifyNoInteractions(gateway, previewTokens);
        verify(issuer, never()).recordOutcome(any(), any(), any(), any(), any());
    }

    @Test void aReplayReportsTheOutcomeTheFirstRunRecordedBeforeAskingTheLedger() {
        when(writeTool.getVersion()).thenReturn("1.0.0");
        when(grants.consume(any(), any(), any(), any())).thenReturn(Optional.of(
                new AutonomousGrantService.Consumption("g1", "autonomous:g1:slot:1", "1.0.0", true)));
        when(issuer.recordedOutcome("g1", "slot:1")).thenReturn(Optional.of("DENIED"));
        var binding = SchedulerGrantBinding.of(schedule(78L, "dictionary_add", Map.of(), 42L), "1.0.0", new ObjectMapper());
        var replay = runner.run("F001", 42L, "dictionary_add", Map.of(), ToolExecutionSource.SCHEDULER, "c", binding,
                "slot:1");
        assertThat(replay.status()).isEqualTo("GATEWAY_DENIED");
        assertThat(replay.replay()).isTrue();
        verify(grants, never()).gatewayRecord(any(), any(), any(), any());
        verifyNoInteractions(gateway, previewTokens);
    }

    @Test void recordedOutcomesNeverCallAnUnknownASuccess() {
        assertThat(AutonomousToolRunner.recordedOutcomeOf(ToolExecutionStatus.SUCCEEDED)).isEqualTo("SUCCEEDED");
        assertThat(AutonomousToolRunner.recordedOutcomeOf(ToolExecutionStatus.FAILED)).isEqualTo("FAILED");
        assertThat(AutonomousToolRunner.recordedOutcomeOf(ToolExecutionStatus.OUTCOME_UNKNOWN)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(AutonomousToolRunner.recordedOutcomeOf(ToolExecutionStatus.TIMEOUT)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(AutonomousToolRunner.recordedOutcomeOf(ToolExecutionStatus.DENIED)).isEqualTo("DENIED");
        assertThat(AutonomousToolRunner.recordedOutcomeOf(ToolExecutionStatus.CONFIRMATION_REQUIRED)).isEqualTo("DENIED");
    }

    @Test void aReplayedSlotDoesNotOverwriteTheStatusTheExecutingInstanceRecorded() throws Exception {
        when(writeTool.getVersion()).thenReturn("1.0.0");
        when(grants.consume(any(), any(), any(), any())).thenReturn(Optional.of(
                new AutonomousGrantService.Consumption("g1", "autonomous:g1:slot:1", "1.0.0", true)));
        when(grants.gatewayRecord(any(), any(), any(), any())).thenReturn(Optional.empty());
        FactorySchedulerConfig config = schedule(83L, "dictionary_add", Map.of(), 42L);
        config.setLastExecutionStatus("SUCCESS");
        when(schedulerRepo.findById(83L)).thenReturn(Optional.of(config));
        runScheduled(config, PLANNED);
        assertThat(config.getLastExecutionStatus()).isEqualTo("SUCCESS");
        verify(schedulerRepo, never()).save(any());
    }

    @Test void configTimeGateRejectsWriteToolsAndIdentityParamsButLeavesUnknownToolsToTheRunner() {
        assertThatThrownBy(() -> scheduler.requireSchedulable("dictionary_add", Map.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("dictionary_add");
        assertThatThrownBy(() -> scheduler.requireSchedulable("material_expired_query", Map.of("tenantId", "X")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("tenantId");
        scheduler.requireSchedulable("material_expired_query", Map.of("limit", 1));
        when(registry.getExecutor("missing_tool")).thenReturn(Optional.empty());
        scheduler.requireSchedulable("missing_tool", Map.of());
    }

    /**
     * Event trigger chains: read-only tools only, no identity parameters, and every step goes
     * through the tool gateway as the chain's run-as user with source TRIGGER (S6-02). The chain
     * executor never calls a tool itself.
     */
    @Nested class TriggerChainsRunThroughTheGateway {
        @BeforeEach void chainTools() throws Exception {
            when(registry.getExecutor("stock_summary")).thenReturn(Optional.of(readTool));
            when(registry.getExecutor("dictionary_add")).thenReturn(Optional.of(writeTool));
            when(registry.getExecutor("legacy_tool")).thenReturn(Optional.of(undeclaredTool));
            when(registry.isToolEnabledForFactory(any(), any())).thenReturn(true);
            when(readTool.execute(any(), any())).thenReturn("{\"success\":true}");
            when(writeTool.execute(any(), any())).thenReturn("{\"success\":true}");
            when(undeclaredTool.getToolName()).thenReturn("legacy_tool");
            when(undeclaredTool.execute(any(), any())).thenReturn("{\"success\":true}");
        }

        @Test void triggerChainWriteStepIsRefusedAndCountedWhileReadStepRuns() throws Exception {
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            FactoryTriggerChain chain = chain("CONTINUE", List.of(
                    step(1, "stock_summary", Map.of()), step(2, "dictionary_add", Map.of()),
                    step(3, "stock_summary", Map.of("factoryId", "F-OTHER"))));

            runChain(executor, chain);

            ArgumentCaptor<ToolExecutionCommand> command = ArgumentCaptor.forClass(ToolExecutionCommand.class);
            verify(gateway, times(1)).execute(command.capture());
            assertThat(command.getValue().source()).isEqualTo(ToolExecutionSource.TRIGGER);
            assertThat(command.getValue().principal().principalId()).isEqualTo("42");
            assertThat(command.getValue().toolName()).isEqualTo("stock_summary");
            verify(readTool, never()).execute(any(), any());
            verify(writeTool, never()).execute(any(), any());
            ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
            verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), status.capture(), error.capture());
            assertThat(status.getValue()).isEqualTo("PARTIAL");
            assertThat(error.getValue()).contains("dictionary_add").contains("factoryId");
        }

        @Test void stopStrategyHaltsAtTheRefusedStep() throws Exception {
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            FactoryTriggerChain chain = chain("STOP", List.of(step(1, "dictionary_add", Map.of()), step(2, "stock_summary", Map.of())));
            runChain(executor, chain);
            verify(gateway, never()).execute(any());
            verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), eq("FAILED"), contains("dictionary_add"));
        }

        @Test void missingBlankOrUnregisteredToolIsAFailureNeverASilentSuccess() throws Exception {
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            FactoryTriggerChain unregistered = chain("CONTINUE", List.of(step(1, "no_such_tool", Map.of())));
            FactoryTriggerChain blank = chain("CONTINUE", List.of(step(1, " ", Map.of())));
            FactoryTriggerChain missing = chain("CONTINUE", List.of(Map.of("order", 1, "params", Map.of())));
            FactoryTriggerChain mixed = chain("CONTINUE", List.of(step(1, "no_such_tool", Map.of()), step(2, "stock_summary", Map.of())));

            for (FactoryTriggerChain chain : List.of(unregistered, blank, missing, mixed)) runChain(executor, chain);

            verify(self).persistExecutionMetadata(eq(unregistered), any(LocalDateTime.class), eq("FAILED"),
                    contains("tool not registered: no_such_tool"));
            verify(self).persistExecutionMetadata(eq(blank), any(LocalDateTime.class), eq("FAILED"), contains("step has no tool"));
            verify(self).persistExecutionMetadata(eq(missing), any(LocalDateTime.class), eq("FAILED"), contains("step has no tool"));
            verify(self).persistExecutionMetadata(eq(mixed), any(LocalDateTime.class), eq("PARTIAL"), contains("no_such_tool"));
            verify(gateway, times(1)).execute(any());
            verify(readTool, never()).execute(any(), any());
        }

        @Test void stopHaltsAtAnUnregisteredToolWhileContinueRunsTheNextStep() throws Exception {
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            List<Map<String, Object>> steps = List.of(step(1, "no_such_tool", Map.of()), step(2, "stock_summary", Map.of()));
            FactoryTriggerChain stop = chain("STOP", steps);
            runChain(executor, stop);
            verify(gateway, never()).execute(any());
            verify(self).persistExecutionMetadata(eq(stop), any(LocalDateTime.class), eq("FAILED"), contains("no_such_tool"));

            FactoryTriggerChain proceed = chain("CONTINUE", steps);
            runChain(executor, proceed);
            verify(gateway, times(1)).execute(any());
        }

        @Test void toolDisabledForTheFactoryIsReportedNotRunAndNotSuccess() throws Exception {
            when(registry.isToolEnabledForFactory("F001", "stock_summary")).thenReturn(false);
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            FactoryTriggerChain chain = chain("CONTINUE", List.of(step(1, "stock_summary", Map.of())));
            runChain(executor, chain);
            verify(gateway, never()).execute(any());
            verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), eq("FAILED"),
                    contains("tool disabled for this factory: stock_summary"));
        }

        @Test void malformedStepFieldsAreCountedFailuresInsteadOfAbortingTheChain() throws Exception {
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            FactoryTriggerChain chain = chain("CONTINUE", List.of(
                    Map.of("order", 1, "tool", "stock_summary", "enabled", "yes"),
                    Map.of("order", "two", "tool", "stock_summary"),
                    Map.of("order", 3, "tool", 42),
                    Map.of("order", 4, "tool", "stock_summary", "condition", 7),
                    step(5, "stock_summary", Map.of())));
            runChain(executor, chain);
            verify(gateway, times(1)).execute(any());
            ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
            verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), eq("PARTIAL"), error.capture());
            assertThat(error.getValue()).contains("step enabled must be true or false", "step order must be a number",
                    "step has no tool", "step condition must be text");
        }

        @Test void chainWithNoDueStepIsSkippedNotSuccess() throws Exception {
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            FactoryTriggerChain chain = chain("CONTINUE", List.of(
                    Map.of("order", 1, "tool", "stock_summary", "enabled", false),
                    Map.of("order", 2, "tool", "no_such_tool", "enabled", false)));
            runChain(executor, chain);
            verify(gateway, never()).execute(any());
            verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), eq("SKIPPED"), isNull());
        }

        @Test void chainWithoutRunAsUserNeverReachesTheGatewayAndIsAFailure() throws Exception {
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            FactoryTriggerChain chain = chain("CONTINUE", List.of(step(1, "stock_summary", Map.of())));
            chain.setRunAsUserId(null);
            runChain(executor, chain);
            verifyNoInteractions(gateway);
            verify(readTool, never()).execute(any(), any());
            verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), eq("FAILED"),
                    contains("RUN_AS_MISSING"));
        }

        @Test void gatewayDeniedOrUnknownStepIsAFailureWithTheAuditIdNeverASuccess() throws Exception {
            TriggerChainExecutor executor = chainExecutor();
            TriggerChainExecutor self = mock(TriggerChainExecutor.class);
            setSelf(executor, self);
            doAnswer(call -> result(call.getArgument(0), ToolExecutionStatus.DENIED)).when(gateway).execute(any());
            FactoryTriggerChain denied = chain("CONTINUE", List.of(step(1, "stock_summary", Map.of())));
            runChain(executor, denied);
            verify(self).persistExecutionMetadata(eq(denied), any(LocalDateTime.class), eq("FAILED"),
                    contains("GATEWAY_DENIED"));

            doAnswer(call -> result(call.getArgument(0), ToolExecutionStatus.OUTCOME_UNKNOWN)).when(gateway).execute(any());
            FactoryTriggerChain unknown = chain("CONTINUE", List.of(step(1, "stock_summary", Map.of())));
            runChain(executor, unknown);
            verify(self).persistExecutionMetadata(eq(unknown), any(LocalDateTime.class), eq("FAILED"),
                    contains("OUTCOME_UNKNOWN"));
            verify(readTool, never()).execute(any(), any());
        }

        private TriggerChainExecutor chainExecutor() {
            return new TriggerChainExecutor(mock(FactoryTriggerChainRepository.class), registry, new ObjectMapper(), runner);
        }

        private static void setSelf(TriggerChainExecutor executor, TriggerChainExecutor self) throws Exception {
            Field field = TriggerChainExecutor.class.getDeclaredField("self");
            field.setAccessible(true);
            field.set(executor, self);
        }

        private static void runChain(TriggerChainExecutor executor, FactoryTriggerChain chain) throws Exception {
            Method run = TriggerChainExecutor.class.getDeclaredMethod("executeChain",
                    FactoryTriggerChain.class, String.class, ApplicationEvent.class);
            run.setAccessible(true);
            run.invoke(executor, chain, "F001", new ApplicationEvent("test") { });
        }

        private static FactoryTriggerChain chain(String strategy, List<Map<String, Object>> steps) {
            FactoryTriggerChain chain = new FactoryTriggerChain();
            chain.setFactoryId("F001");
            chain.setChainCode("CHAIN");
            chain.setEventType("TEST");
            chain.setErrorStrategy(strategy);
            chain.setEnabled(true);
            chain.setSteps(steps);
            chain.setRunAsUserId(42L);
            return chain;
        }
    }

    /** A planned fire identity as the scheduler hands it to a run. */
    private static final String PLANNED = "slot:2026-10-21T23:00:00Z";

    private void runScheduled(FactorySchedulerConfig config, String plannedFireKey) throws Exception {
        Method run = DynamicSchedulerService.class.getDeclaredMethod("executeTask", FactorySchedulerConfig.class,
                String.class);
        run.setAccessible(true);
        run.invoke(scheduler, config, plannedFireKey);
    }

    private void runScheduled(FactorySchedulerConfig config) throws Exception {
        Method run = DynamicSchedulerService.class.getDeclaredMethod("executeTask", FactorySchedulerConfig.class);
        run.setAccessible(true);
        run.invoke(scheduler, config);
    }

    private static ExecutionPrincipal principal(Long userId) {
        return new ExecutionPrincipal("F001", "FACTORY", String.valueOf(userId), PrincipalType.USER,
                Set.of(AutonomousToolRunner.ASSERTED_ROLE), Set.of(), Set.of());
    }

    private static ToolExecutionResult result(ToolExecutionCommand command, ToolExecutionStatus status) {
        return new ToolExecutionResult(command.requestId(), command.toolName(), "1.0.0", "audit-1",
                command.traceId(), status, JsonNodeFactory.instance.objectNode().put("success", true), "gateway result", false);
    }

    private static FactorySchedulerConfig schedule(Long id, String tool, Map<String, Object> params, Long runAs) {
        return FactorySchedulerConfig.builder().id(id).factoryId("F001").taskCode("t" + id)
                .cronExpression("0 0 9 1 * ?").enabled(true).toolOrMethod(tool).params(params).runAsUserId(runAs).build();
    }

    private static Map<String, Object> step(int order, String tool, Map<String, Object> params) {
        return Map.of("order", order, "tool", tool, "params", params);
    }

    private static ToolExecutor tool(AccessMode mode) {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getAccessMode()).thenReturn(mode);
        return tool;
    }
}
