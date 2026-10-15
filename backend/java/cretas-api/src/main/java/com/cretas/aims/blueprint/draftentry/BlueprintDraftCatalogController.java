package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts.AdapterMode;
import com.cretas.aims.dto.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only: which capabilities have an open DRAFT entry, and the order DRAFTs can be written in.
 *
 * <p>The blueprint's capability status says whether a capability may enter <em>review</em>
 * (catalog order). That is not the same as whether its DRAFT can be <em>written</em>: a BOM DRAFT
 * pins a Workflow DRAFT revision, so BOM is "ready to review" long before it can be written. This
 * view gives the write order and, per capability, the write dependencies whose own entry is not
 * open yet — so a client can hold BOM back instead of letting the user click into a refusal.</p>
 *
 * <p>{@code proposalOpen}: the server can pre-fill this capability's request from the blueprint
 * ({@code GET .../drafts/{capabilityId}/proposal}). Without it the client asks the user for the
 * whole request.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/platform/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}/drafts")
public class BlueprintDraftCatalogController {

    public static final String SCHEMA_VERSION = "factory-blueprint-draft-write-order/v1";

    private final Set<String> open;
    private final Set<String> proposable;
    private final FactoryCapabilityAdapterContracts.Registry registry = FactoryCapabilityAdapterContracts.b25Defaults();

    public BlueprintDraftCatalogController(List<CapabilityDraftEntry> entries, List<CapabilityDraftProposer> proposers) {
        this.open = entries.stream().map(CapabilityDraftEntry::capabilityId).collect(Collectors.toUnmodifiableSet());
        this.proposable = proposers.stream().map(CapabilityDraftProposer::capabilityId).collect(Collectors.toUnmodifiableSet());
    }

    public record CapabilityWriteView(String capabilityId, String adapterMode, boolean entryOpen, boolean proposalOpen,
                                      String requiredPermission, List<String> writeDependencies,
                                      List<String> dependenciesWithoutOpenEntry) {
    }

    public record WriteOrderView(String schemaVersion, List<CapabilityWriteView> capabilities) {
    }

    @GetMapping
    @Operation(summary = "建厂草稿的写入顺序与各能力入口开放情况(只读)")
    public ApiResponse<WriteOrderView> writeOrder(@PathVariable String factoryId, @PathVariable String blueprintId) {
        List<CapabilityWriteView> rows = registry.writeOrder().stream()
                .map(c -> {
                    boolean entryOpen = c.adapterMode() == AdapterMode.MODULE_DRAFT && open.contains(c.capabilityId());
                    return new CapabilityWriteView(c.capabilityId(), c.adapterMode().name(), entryOpen,
                        entryOpen && proposable.contains(c.capabilityId()),
                        c.requiredPermission(), c.dependencies(),
                        c.dependencies().stream().filter(this::needsAnEntryThatIsNotOpen).toList());
                })
                .toList();
        return ApiResponse.success(new WriteOrderView(SCHEMA_VERSION, rows));
    }

    /** Read-only capabilities (factory identity, route compiler) need no DRAFT entry. */
    private boolean needsAnEntryThatIsNotOpen(String capabilityId) {
        var contract = registry.require(capabilityId);
        return contract.adapterMode() == AdapterMode.MODULE_DRAFT && !open.contains(capabilityId);
    }
}
