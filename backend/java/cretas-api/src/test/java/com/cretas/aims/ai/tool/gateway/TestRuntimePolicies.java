package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolPolicyEntry;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolPolicyLoader;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolPolicyManifest;
import com.cretas.aims.ai.tool.gateway.descriptor.ToolDescriptorInventoryLoader;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Test only: the shipped runtime policies with extra allowed sources for one tool, so a test can
 * drive a real write tool from an autonomous source through the real gateway. Production policies
 * are unchanged; every other field of the entry (permissions, confirmation, idempotency) is kept.
 */
public final class TestRuntimePolicies {

    private TestRuntimePolicies() {
    }

    public static ToolRuntimeRegistry runtimeWithSources(ToolRegistry toolRegistry,
            FactoryToolConfigRepository factoryToolConfigs, String toolName, Set<ToolExecutionSource> extra) {
        RuntimeToolPolicyManifest shipped = new RuntimeToolPolicyLoader().loadDefault();
        List<RuntimeToolPolicyEntry> policies = shipped.policies().stream()
                .map(entry -> entry.toolName().equals(toolName) ? withSources(entry, extra) : entry)
                .toList();
        if (policies.stream().noneMatch(entry -> entry.toolName().equals(toolName))) {
            throw new IllegalArgumentException("no shipped runtime policy for " + toolName);
        }
        RuntimeToolDescriptorRegistry descriptors = new RuntimeToolDescriptorRegistry(
                new ToolDescriptorInventoryLoader().loadDefault(),
                new RuntimeToolPolicyManifest(shipped.schemaVersion(), shipped.expectedPolicyCount(), policies));
        return new ToolRuntimeRegistry(toolRegistry, factoryToolConfigs, descriptors);
    }

    private static RuntimeToolPolicyEntry withSources(RuntimeToolPolicyEntry entry, Set<ToolExecutionSource> extra) {
        Set<ToolExecutionSource> sources = EnumSet.copyOf(entry.allowedSources());
        sources.addAll(extra);
        return new RuntimeToolPolicyEntry(entry.implementationClass(), entry.toolName(), entry.actionType(),
                entry.riskLevel(), entry.requiredPermissions(), entry.allowedRoles(), entry.allowedBusinessTypes(),
                entry.domainTags(), entry.version(), entry.supportsPreview(), entry.confirmationPolicy(),
                entry.approvalPolicy(), entry.idempotencyPolicy(), entry.dataClassification(), sources,
                entry.egressPolicy(), entry.provenance());
    }
}
