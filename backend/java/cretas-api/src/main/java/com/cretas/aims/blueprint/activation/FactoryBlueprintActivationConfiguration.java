package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the B26 activation center into the application context — closed.
 *
 * <p>Two things are deliberately true here. The first is asserted by
 * {@code FactoryActivationSurfaceMustStayClosedTest} (a source scan, mutation-verified to go red
 * when an implementation is planted); {@code FactoryBlueprintActivationWiringJpaTest} additionally
 * checks that this configuration itself wires nothing in. ⚠️ The name written here previously,
 * {@code FactoryBlueprintActivationWiringTest}, was a class that does not exist -- the same defect
 * as a capability contract naming an implementation nobody wrote.</p>
 *
 * <ol>
 *   <li>The center is built from whatever {@link FactoryModuleActivationCommand} beans exist. Today
 *       there are none, so every activation preview comes back {@code BLOCKED} with
 *       {@code ACTIVATION_COMMAND_NOT_REGISTERED}. Registering a command is a deliberate act by the
 *       owning module, not something that happens by being on the classpath.</li>
 *   <li>No controller is registered. A durable store and an orchestrator existing in the context do
 *       not make activation reachable: there is no endpoint that reaches this bean, so the platform
 *       cannot activate anything however it is called.</li>
 * </ol>
 *
 * <p>A module command may only be registered once its own activation path is readback-ready — that
 * is, it can state what it activated, read it back from the runtime, and describe its recovery. The
 * 24 module DRAFT commands are <em>draft</em> commands; none of them implements an activation path,
 * so none of them is registered here and none should be until it does.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Configuration
public class FactoryBlueprintActivationConfiguration {

    @Bean
    public FactoryBlueprintActivationCenter factoryBlueprintActivationCenter(
            ObjectProvider<FactoryModuleActivationCommand> commands,
            FactoryActivationExecutionStore executionStore,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            PlatformTransactionManager transactionManager) {
        List<FactoryModuleActivationCommand> registered = commands.orderedStream().toList();
        return new FactoryBlueprintActivationCenter(registered, executionStore, canonicalBlueprints,
                Clock.systemUTC(), FactoryBlueprintActivationCenter.DEFAULT_APPROVAL_TTL,
                new FactoryActivationModuleTransaction(transactionManager));
    }
}
