package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.draftentry.BlueprintDraftOperatorResolver.DraftOperator;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftEntry.WrittenDraft;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftEntry.WrittenScope;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only: the DRAFT rows already written for the factory's current blueprint lineage, per
 * capability — so a client can show what is written after a reload, and find the draft ids it
 * needs for readback.
 *
 * <p>Scope is the current blueprint's id, version and {@link BlueprintSourceDigest}: a DRAFT
 * written from an earlier payload does not match the lineage any more and is not listed (the
 * downstream commands would refuse to pin it). Rows only for capabilities the operator holds
 * the permission of; {@code supported=false} for a capability that cannot list yet.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/platform/factory-assembly/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}/drafts")
public class BlueprintDraftWrittenController {

    public static final String SCHEMA_VERSION = "factory-blueprint-draft-written/v1";

    private final List<CapabilityDraftEntry> entries;
    private final BlueprintDraftOperatorResolver operators;
    private final CanonicalFactoryBlueprintService blueprints;
    private final FactoryCapabilityAdapterContracts.Registry registry = FactoryCapabilityAdapterContracts.b25Defaults();

    public BlueprintDraftWrittenController(List<CapabilityDraftEntry> entries, BlueprintDraftOperatorResolver operators,
                                           CanonicalFactoryBlueprintService blueprints) {
        this.entries = List.copyOf(entries);
        this.operators = operators;
        this.blueprints = blueprints;
    }

    public record CapabilityWritten(String capabilityId, boolean supported, List<WrittenDraft> drafts) {
    }

    public record WrittenView(String schemaVersion, String blueprintId, Integer blueprintVersion, String sourceDigest,
                              List<CapabilityWritten> capabilities) {
    }

    @GetMapping("/written")
    @Operation(summary = "当前蓝图血缘下已写入的建厂草稿(只读)")
    public ApiResponse<WrittenView> written(@PathVariable String factoryId, @PathVariable String blueprintId,
                                            HttpServletRequest http) {
        DraftOperator operator = operators.resolveDraftForAny(http, factoryId,
                entries.stream().map(CapabilityDraftEntry::requiredPermission).toList());
        BlueprintResponse blueprint = blueprints.current(factoryId);
        if (blueprint == null || !blueprintId.equals(blueprint.getId())) {
            throw new BusinessException(409, "只能查看本厂当前蓝图的建厂草稿，请刷新蓝图后重试")
                    .withCode("BLUEPRINT_DRAFT_BLUEPRINT_NOT_CURRENT").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
        }
        if (blueprint.getSourcePayload() == null) {
            return ApiResponse.success(new WrittenView(SCHEMA_VERSION, blueprint.getId(), blueprint.getVersion(),
                    null, List.of()));
        }
        String sourceDigest = BlueprintSourceDigest.of(blueprint);
        WrittenScope scope = new WrittenScope(factoryId, blueprint.getId(), blueprint.getVersion(), sourceDigest);
        List<CapabilityWritten> rows = new ArrayList<>();
        for (var contract : registry.writeOrder()) {
            entries.stream().filter(entry -> entry.capabilityId().equals(contract.capabilityId())).findFirst()
                    .filter(entry -> operator.permissions().contains(entry.requiredPermission()))
                    .ifPresent(entry -> {
                        Optional<List<WrittenDraft>> listed = entry.written(scope);
                        rows.add(new CapabilityWritten(entry.capabilityId(), listed.isPresent(),
                                listed.map(List::copyOf).orElse(List.of())));
                    });
        }
        return ApiResponse.success(new WrittenView(SCHEMA_VERSION, blueprint.getId(), blueprint.getVersion(),
                sourceDigest, rows));
    }
}
