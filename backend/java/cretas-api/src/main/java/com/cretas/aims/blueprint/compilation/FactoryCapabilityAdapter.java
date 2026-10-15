package com.cretas.aims.blueprint.compilation;

import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CapabilityArtifact;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.CompilationIssue;
import com.cretas.aims.blueprint.compilation.FactoryBlueprintCompilationModels.SourceFact;
import com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteCompilation;

import java.util.List;

/**
 * Read-only adapter SPI. B20 intentionally exposes no materialize, publish, activate or SQL API.
 */
public interface FactoryCapabilityAdapter {

    String adapterId();

    String capabilityId();

    String artifactType();

    String targetSchemaVersion();

    boolean hasInput(CompilationContext context);

    AdapterOutput compile(CompilationContext context);

    record CompilationContext(
            String factoryId,
            String blueprintId,
            int blueprintVersion,
            List<SourceFact> confirmedFacts,
            List<RouteCompilation> routeCompilations) {
        public CompilationContext {
            confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
            routeCompilations = routeCompilations == null ? List.of() : List.copyOf(routeCompilations);
        }

        public List<SourceFact> factsFor(String requestedCapabilityId) {
            return confirmedFacts.stream()
                    .filter(fact -> requestedCapabilityId.equals(fact.capabilityId()))
                    .toList();
        }
    }

    record AdapterOutput(CapabilityArtifact artifact, List<CompilationIssue> issues) {
        public AdapterOutput {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
