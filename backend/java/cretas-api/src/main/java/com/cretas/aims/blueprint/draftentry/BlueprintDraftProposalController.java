package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts.AdapterMode;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.Blocker;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.Evidence;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.InputType;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.Option;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.Proposed;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.ProposedItem;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.RequiredInput;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.StructureKind;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only draft proposals: the request a capability's DRAFT command needs, pre-filled from the
 * factory's current blueprint, with the fields the blueprint cannot answer listed for the user.
 *
 * <p>The client posts {@code request} (after filling {@code requiredInputs}) to
 * {@code .../drafts/{capabilityId}/preview}; the command computes the preview digest, the user
 * confirms, and materialize writes the DRAFT. Nothing here writes.</p>
 *
 * <p>Same audience as preview/materialize: the factory's own operator with the capability's
 * permission ({@link BlueprintDraftOperatorResolver}). A capability that is not included and READY
 * in the blueprint gets a {@code BLOCKED} proposal with the blueprint's own next step, not a
 * request built from nothing.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/platform/factory-assembly/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}/drafts")
public class BlueprintDraftProposalController {

    public static final String SCHEMA_VERSION = "factory-blueprint-draft-proposal/v1";
    private static final Set<String> PROPOSABLE_BLUEPRINT_STATES = Set.of("DRAFT", "VALIDATED");
    /** Set by the user's confirmation or by the server, never by a proposal. */
    private static final List<String> CONFIRMATION_FIELDS = List.of("confirmedPreviewDigest", "idempotencyKey", "operatorId");
    private static final Pattern SEGMENT = Pattern.compile("([A-Za-z][A-Za-z0-9]*)(?:\\[(\\d+)])?");
    /** A STRUCTURE sub-field's relative path: one field name, no index, no dot. */
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    public enum Status { READY_FOR_PREVIEW, NEEDS_INPUT, BLOCKED }

    /** One DRAFT request: post {@code request} (with {@code requiredInputs} filled) to preview. */
    public record ProposalItem(String itemKey, String label, Status status, JsonNode request,
                               List<RequiredInput> requiredInputs, List<Evidence> evidence) {
    }

    public record DraftProposal(String schemaVersion, String capabilityId, Status status, String factoryId,
                                String blueprintId, Integer blueprintVersion, String sourceDigest,
                                String sourceDigestAlgorithm, List<ProposalItem> items, List<Blocker> blockers) {
    }

    private final Map<String, CapabilityDraftProposer> proposers = new HashMap<>();
    private final Set<String> openEntries;
    private final BlueprintDraftOperatorResolver operators;
    private final CanonicalFactoryBlueprintService blueprints;
    private final ObjectMapper mapper;
    private final FactoryCapabilityAdapterContracts.Registry registry = FactoryCapabilityAdapterContracts.b25Defaults();

    public BlueprintDraftProposalController(List<CapabilityDraftProposer> proposers, List<CapabilityDraftEntry> entries,
                                            BlueprintDraftOperatorResolver operators,
                                            CanonicalFactoryBlueprintService blueprints, ObjectMapper mapper) {
        this.openEntries = Set.copyOf(entries.stream().map(CapabilityDraftEntry::capabilityId).toList());
        for (CapabilityDraftProposer proposer : proposers) {
            String id = proposer.capabilityId();
            if (registry.require(id).adapterMode() != AdapterMode.MODULE_DRAFT || !openEntries.contains(id)) {
                throw new IllegalStateException("draft proposer without an open DRAFT entry: " + id);
            }
            if (this.proposers.put(id, proposer) != null) {
                throw new IllegalStateException("duplicate draft proposer: " + id);
            }
        }
        this.operators = operators;
        this.blueprints = blueprints;
        this.mapper = mapper;
    }

    @GetMapping("/{capabilityId}/proposal")
    @Operation(summary = "按当前蓝图预填某个能力的建厂草稿请求(只读，不写库)")
    public ApiResponse<DraftProposal> propose(@PathVariable String factoryId, @PathVariable String blueprintId,
                                              @PathVariable String capabilityId, HttpServletRequest http) {
        CapabilityDraftProposer proposer = proposers.get(capabilityId);
        if (proposer == null) {
            throw new BusinessException(404, "这类建厂草稿暂不提供预填，请按页面提示逐项填写")
                    .withCode("BLUEPRINT_DRAFT_PROPOSAL_NOT_OPEN");
        }
        operators.resolveDraft(http, factoryId, registry.require(capabilityId).requiredPermission());

        BlueprintResponse blueprint = blueprints.current(factoryId);
        if (blueprint == null || !blueprintId.equals(blueprint.getId())
                || !PROPOSABLE_BLUEPRINT_STATES.contains(blueprint.getLifecycleState())) {
            throw new BusinessException(409, "只能按本厂当前处于草稿或已校验状态的蓝图预填，请刷新蓝图后重试")
                    .withCode("BLUEPRINT_DRAFT_BLUEPRINT_NOT_CURRENT").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
        }
        if (blueprint.getSourcePayload() == null) {
            return ApiResponse.success(blocked(capabilityId, blueprint, new Blocker("BLUEPRINT_SOURCE_MISSING",
                    "蓝图里没有已复核的建厂资料，无法预填", "先完成建厂访谈并保存蓝图")));
        }
        CapabilityView capability = blueprint.getCapabilities() == null ? null : blueprint.getCapabilities().stream()
                .filter(view -> capabilityId.equals(view.getId())).findFirst().orElse(null);
        if (capability == null || !capability.isIncluded() || !"READY".equals(capability.getStatus())) {
            return ApiResponse.success(blocked(capabilityId, blueprint, new Blocker("BLUEPRINT_CAPABILITY_NOT_READY",
                    "蓝图里这项能力还没有就绪，现在预填只会得到空请求",
                    capability == null || capability.getNextStep() == null
                            ? "回到建厂访谈补充这项能力的资料" : capability.getNextStep())));
        }

        String sourceDigest = BlueprintSourceDigest.of(blueprint);
        Proposed proposed = proposer.propose(new CapabilityDraftProposer.Source(factoryId, blueprint, capability, sourceDigest));
        if (!proposed.blockers().isEmpty()) {
            return ApiResponse.success(new DraftProposal(SCHEMA_VERSION, capabilityId, Status.BLOCKED, factoryId,
                    blueprint.getId(), blueprint.getVersion(), sourceDigest, BlueprintSourceDigest.ALGORITHM_VERSION,
                    List.of(), proposed.blockers()));
        }
        if (proposed.items().isEmpty()) {
            return ApiResponse.success(blocked(capabilityId, blueprint, new Blocker("BLUEPRINT_PROPOSAL_EMPTY",
                    "蓝图里没有可用于这项能力的资料", "回到建厂访谈补充这项能力的资料")));
        }
        Set<String> keys = new HashSet<>();
        List<ProposalItem> items = proposed.items().stream().map(item -> {
            if (item.itemKey() == null || !keys.add(item.itemKey())) {
                throw defect(capabilityId, "条目标识缺失或重复：" + item.itemKey());
            }
            return toItem(item, capabilityId);
        }).toList();
        Status status = items.stream().anyMatch(item -> item.status() == Status.NEEDS_INPUT)
                ? Status.NEEDS_INPUT : Status.READY_FOR_PREVIEW;
        return ApiResponse.success(new DraftProposal(SCHEMA_VERSION, capabilityId, status, factoryId, blueprint.getId(),
                blueprint.getVersion(), sourceDigest, BlueprintSourceDigest.ALGORITHM_VERSION, items, List.of()));
    }

    private ProposalItem toItem(ProposedItem item, String capabilityId) {
        ObjectNode request = mapper.valueToTree(item.request());
        CONFIRMATION_FIELDS.forEach(request::remove);
        item.requiredInputs().forEach(input -> {
            checkTyped(input, capabilityId);
            // A STRUCTURE the proposer pre-filled (some rows known, the user completes them) keeps its value:
            // the client starts from those rows. Every other input is emitted as an explicit null.
            setNull(request, input.path(), capabilityId, input.inputType() == InputType.STRUCTURE);
        });
        Status status = item.requiredInputs().isEmpty() ? Status.READY_FOR_PREVIEW : Status.NEEDS_INPUT;
        return new ProposalItem(item.itemKey(), item.label(), status, request, item.requiredInputs(), item.evidence());
    }

    /**
     * The client renders each input from its type alone, so an input it cannot render — no type, a
     * choice without choices or with two choices of the same value, an inverted range — would reach
     * the user as a dead field. That is a proposer bug, refused here rather than shipped.
     */
    private static void checkTyped(RequiredInput input, String capabilityId) {
        checkTyped(input, input.path(), capabilityId);
    }

    /**
     * @param where the input's full location for the message: its path, or for a sub-field the parent's
     *              location plus the sub-field's relative path
     */
    private static void checkTyped(RequiredInput input, String where, String capabilityId) {
        if (input.inputType() == null) {
            throw defect(capabilityId, "待填字段 " + where + " 没有输入类型");
        }
        if (input.inputType() == InputType.ENUM || input.inputType() == InputType.MULTI_ENUM) {
            if (input.options().isEmpty()) {
                throw defect(capabilityId, "待填字段 " + where + " 是选择题却没有选项");
            }
            Set<String> values = new HashSet<>();
            for (Option option : input.options()) {
                if (option == null || option.value() == null || option.value().isBlank() || !values.add(option.value())) {
                    throw defect(capabilityId, "待填字段 " + where + " 的选项值缺失或重复");
                }
            }
        }
        if (input.min() != null && input.max() != null && input.min().compareTo(input.max()) > 0) {
            throw defect(capabilityId, "待填字段 " + where + " 的下限大于上限");
        }
        if (input.inputType() == InputType.STRUCTURE) {
            checkStructure(input, where, capabilityId);
        } else if (input.structureKind() != null || !input.itemFields().isEmpty() || input.minItems() != null
                || input.maxItems() != null || input.itemLabel() != null) {
            throw defect(capabilityId, "待填字段 " + where + " 不是结构化输入却带了子字段定义");
        }
    }

    /**
     * A STRUCTURE is rendered from its sub-fields alone; without them the client is back to a raw JSON
     * box, and a sub-field the client cannot address (a dotted or indexed path, the same name twice)
     * would be written to the wrong key or overwrite another one.
     */
    private static void checkStructure(RequiredInput input, String where, String capabilityId) {
        StructureKind kind = input.structureKind();
        if (kind == null) {
            throw defect(capabilityId, "待填字段 " + where + " 是结构化输入却没有结构类型");
        }
        if (input.itemFields().isEmpty()) {
            throw defect(capabilityId, "待填字段 " + where + " 是结构化输入却没有子字段定义");
        }
        Set<String> names = new HashSet<>();
        for (RequiredInput field : input.itemFields()) {
            if (field == null || field.path() == null || !FIELD_NAME.matcher(field.path()).matches() || !names.add(field.path())) {
                throw defect(capabilityId, "待填字段 " + where + " 的子字段路径不合法或重复："
                        + (field == null ? null : field.path()));
            }
            checkTyped(field, where + "." + field.path(), capabilityId);
        }
        if (kind == StructureKind.OBJECT) {
            if (input.minItems() != null || input.maxItems() != null || input.itemLabel() != null) {
                throw defect(capabilityId, "待填字段 " + where + " 是单个对象却带了行数或行标题");
            }
            return;
        }
        if (input.minItems() == null || input.minItems() < 0) {
            throw defect(capabilityId, "待填字段 " + where + " 是列表却没有行数下限");
        }
        if (input.maxItems() != null && input.minItems() > input.maxItems()) {
            throw defect(capabilityId, "待填字段 " + where + " 的行数下限大于上限");
        }
        if (kind == StructureKind.VALUES) {
            RequiredInput value = input.itemFields().get(0);
            Matcher last = SEGMENT.matcher(where.substring(where.lastIndexOf('.') + 1));
            if (input.itemFields().size() != 1 || value.inputType() == InputType.STRUCTURE
                    || value.inputType() == InputType.MULTI_ENUM || !last.matches() || !last.group(1).equals(value.path())) {
                throw defect(capabilityId, "待填字段 " + where + " 是值列表，只能有一个描述单个值的子字段，且路径是列表自己的字段名");
            }
        }
    }

    /**
     * A malformed proposal is a server defect. Not IllegalStateException: the global handler turns
     * that into 409 "refresh and retry", which reads as a user-side conflict.
     */
    private static BusinessException defect(String capabilityId, String detail) {
        return new BusinessException(500, "预填结果有缺陷（" + capabilityId + "：" + detail + "），已拦下，请联系管理员")
                .withCode("BLUEPRINT_DRAFT_PROPOSAL_INVALID");
    }

    private static DraftProposal blocked(String capabilityId, BlueprintResponse blueprint, Blocker blocker) {
        return new DraftProposal(SCHEMA_VERSION, capabilityId, Status.BLOCKED, blueprint.getFactoryId(),
                blueprint.getId(), blueprint.getVersion(), null, BlueprintSourceDigest.ALGORITHM_VERSION,
                List.of(), List.of(blocker));
    }

    /**
     * Emits a required input as an explicit JSON null, so the client sees the slot — except a STRUCTURE
     * the proposer pre-filled ({@code keepPrefilled}), whose rows the client starts from. A path whose
     * parent does not exist in the request is a proposer bug and fails loudly rather than being
     * dropped — otherwise the user would never be asked for that field.
     */
    private static void setNull(ObjectNode request, String path, String capabilityId, boolean keepPrefilled) {
        String[] segments = path.split("\\.");
        JsonNode node = request;
        for (int i = 0; i < segments.length; i++) {
            Matcher m = SEGMENT.matcher(segments[i]);
            boolean last = i == segments.length - 1;
            if (!m.matches() || !(node instanceof ObjectNode object) || (last && m.group(2) != null)) {
                throw defect(capabilityId, "待填字段 " + path + " 在请求里不存在");
            }
            if (last) {
                JsonNode current = object.get(m.group(1));
                // An empty list is not a pre-fill: the commands' request records turn a null list into [],
                // so "no rows known" serializes as [] and must still reach the client as null.
                boolean prefilled = current != null && !current.isNull() && !(current.isArray() && current.isEmpty());
                if (!keepPrefilled || !prefilled) {
                    object.putNull(m.group(1));
                }
                return;
            }
            node = object.get(m.group(1));
            if (m.group(2) != null) {
                node = node == null || !node.isArray() ? null : node.get(Integer.parseInt(m.group(2)));
            }
        }
    }
}
