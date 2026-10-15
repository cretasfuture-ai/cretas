package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.WorkProcessItem;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.processdraft.WorkProcessDraftCommand;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.DraftRequest;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.RequestedProcess;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code processes} proposal: one {@link WorkProcessDraftCommand} request per
 * {@code suggestedData.workProcesses} entry, keyed by the process name (the interview's
 * idempotency key; work processes have no code).
 *
 * <p>New vs update follows the command: with a blank {@code targetWorkProcessId} it refuses a name
 * that already exists in the factory catalog (NFKC, whitespace-insensitive, case-insensitive), and a
 * target must be active and not merged. So: exactly one same-name process that is selectable =>
 * target it; a same-name process that is inactive/merged, or several, leave no target the command
 * accepts, so the user is asked for another name.</p>
 *
 * <p>Category: an existing catalog category is used as-is ({@code createCategory=false}); a category
 * the catalog does not have needs the user's explicit "create a new category" confirmation (the
 * command's rule), so it is asked, never pre-confirmed. {@code needsInput} and estimated minutes are
 * not interview facts and are asked; output kind and yield are copied when the interview gave them.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class ProcessesDraftProposer implements CapabilityDraftProposer {

    private static final String OUTPUT_KINDS = Arrays.stream(WorkProcessOutputMaterialKind.values())
            .map(Enum::name).collect(Collectors.joining("、"));
    static final List<Option> OUTPUT_KIND_OPTIONS = Option.ofEnum(WorkProcessOutputMaterialKind.class, Map.of(
            WorkProcessOutputMaterialKind.SEMI_FINISHED, "半成品", WorkProcessOutputMaterialKind.FINISHED_GOOD, "成品"));
    /** The command's limits (name / category / description length, estimated minutes, yield ratio). */
    static final int MAX_NAME = 100;
    static final int MAX_CATEGORY = 50;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_MINUTES = 525600;
    static final BigDecimal MIN_YIELD = new BigDecimal("0.0001");
    static final BigDecimal MAX_YIELD = new BigDecimal("99.9999");

    private final WorkProcessRepository workProcessRepository;

    public ProcessesDraftProposer(WorkProcessRepository workProcessRepository) {
        this.workProcessRepository = workProcessRepository;
    }

    @Override
    public String capabilityId() {
        return WorkProcessDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        ApplyAIInitRequest.SuggestedData suggested = source.blueprint().getSourcePayload().getSuggestedData();
        List<WorkProcessItem> processes = suggested == null || suggested.getWorkProcesses() == null
                ? List.of() : suggested.getWorkProcesses();

        Set<String> seen = new HashSet<>();
        for (WorkProcessItem process : processes) {
            String name = process == null ? "" : canonicalText(process.getName());
            if (!name.isEmpty() && !seen.add(identity(name))) {
                return Proposed.blocked("BLUEPRINT_DUPLICATE_PROCESS_NAME",
                        "建厂蓝图里工序“" + name + "”出现了不止一次，无法确定每条草稿对应哪道工序",
                        "回到建厂访谈，合并或改名重复的工序");
            }
        }
        if (processes.stream().allMatch(p -> p == null)) {
            // No processes => no items; the controller reports BLUEPRINT_PROPOSAL_EMPTY.
            return new Proposed(List.of());
        }

        List<WorkProcess> catalog = workProcessRepository.findByFactoryId(source.factoryId());
        List<ProposedItem> items = new ArrayList<>();
        for (int i = 0; i < processes.size(); i++) {
            WorkProcessItem process = processes.get(i);
            if (process == null) continue;
            items.add(item(source, i, process, catalog));
        }
        return new Proposed(items);
    }

    private ProposedItem item(Source source, int index, WorkProcessItem process, List<WorkProcess> catalog) {
        var blueprint = source.blueprint();
        String from = "sourcePayload.suggestedData.workProcesses[" + index + "].";
        String name = canonicalText(process.getName());
        String category = canonicalText(process.getCategory());
        String description = text(process.getDescription());
        String title = name.isEmpty() ? "第 " + (index + 1) + " 道工序" : name;
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();

        if (name.isEmpty()) {
            inputs.add(RequiredInput.text("desired.name", "“" + title + "”叫什么",
                    "建厂访谈没有给这道工序的名称",
                    "不能为空，不超过 " + MAX_NAME + " 字；与本厂已有工序同名会被当作更新那道工序", MAX_NAME));
        } else {
            evidence.add(new Evidence(from + "name", name));
        }

        // ---- category
        String resolvedCategory = null;
        boolean createCategoryKnown = false;
        if (category.isEmpty()) {
            // Free text, not a choice: a new category is legal once confirmed (desired.createCategory).
            inputs.add(RequiredInput.text("desired.category", "“" + title + "”属于哪个工序类别",
                    "建厂访谈没有给这道工序的类别",
                    "不超过 " + MAX_CATEGORY + " 字；优先选本厂已有类别" + existingCategories(catalog), MAX_CATEGORY));
        } else {
            evidence.add(new Evidence(from + "category", category));
            Optional<String> existing = catalog.stream().map(WorkProcess::getProcessCategory)
                    .map(ProcessesDraftProposer::canonicalText).filter(v -> !v.isEmpty())
                    .filter(v -> identity(v).equals(identity(category))).findFirst();
            resolvedCategory = existing.orElse(category);
            createCategoryKnown = existing.isPresent();
        }
        if (!createCategoryKnown) {
            inputs.add(RequiredInput.bool("desired.createCategory",
                    category.isEmpty() ? "如果选的是新类别，确认要新建它" : "本厂还没有“" + category + "”这个工序类别，确认要新建它",
                    category.isEmpty() ? "类别未定，是否新建取决于选哪个" : "新建类别必须由用户明确确认",
                    "选本厂已有类别填 否；新类别必须明确确认填 是"));
        }

        // ---- facts the interview never asks
        inputs.add(RequiredInput.bool("desired.needsInput", "“" + title + "”报工时要不要录投入量",
                "建厂访谈没有问这道工序是否录投入", "是 / 否（纯包装、检验类工序可以不录）"));
        inputs.add(RequiredInput.integer("desired.estimatedMinutes", "“" + title + "”一般做多少分钟",
                "建厂访谈没有问工序时长", "1–" + MAX_MINUTES + " 的整数分钟；未知时明确留空", 1, MAX_MINUTES).allowNull());

        if (description.isEmpty()) {
            inputs.add(RequiredInput.text("desired.description", "“" + title + "”的说明",
                    "建厂访谈没有给工序说明", "不超过 " + MAX_DESCRIPTION + " 字；没有时明确留空", MAX_DESCRIPTION).allowNull());
        } else {
            evidence.add(new Evidence(from + "description", description));
        }

        // ---- output kind
        WorkProcessOutputMaterialKind outputKind = null;
        String rawKind = text(process.getOutputKind());
        if (!rawKind.isEmpty()) {
            outputKind = Arrays.stream(WorkProcessOutputMaterialKind.values())
                    .filter(k -> k.name().equalsIgnoreCase(rawKind)).findFirst().orElse(null);
        }
        if (outputKind == null) {
            inputs.add(RequiredInput.oneOf("desired.defaultOutputMaterialKind", "“" + title + "”默认产出半成品还是成品",
                    rawKind.isEmpty() ? "建厂访谈没有给这道工序的产出类型"
                            : "访谈给的产出类型“" + rawKind + "”不是可识别的类型",
                    OUTPUT_KINDS + " 之一（SEMI_FINISHED 半成品、FINISHED_GOOD 成品）", OUTPUT_KIND_OPTIONS));
        } else {
            evidence.add(new Evidence(from + "outputKind", rawKind));
        }

        // ---- yield (ratio; may exceed 1 for brine-injected/marinated products)
        BigDecimal yieldMin = process.getYieldMin();
        BigDecimal yieldMax = process.getYieldMax();
        if (yieldMin == null) {
            inputs.add(RequiredInput.decimal("desired.standardYieldMin", "“" + title + "”出成率下限",
                    "建厂访谈没有给出成率下限",
                    MIN_YIELD.toPlainString() + "–" + MAX_YIELD.toPlainString()
                            + " 的比例（如 0.85；滚揉保水可大于 1）；不大于上限；未知时明确留空",
                    MIN_YIELD, MAX_YIELD).allowNull());
        } else {
            evidence.add(new Evidence(from + "yieldMin", yieldMin.toPlainString()));
        }
        if (yieldMax == null) {
            inputs.add(RequiredInput.decimal("desired.standardYieldMax", "“" + title + "”出成率上限",
                    "建厂访谈没有给出成率上限",
                    MIN_YIELD.toPlainString() + "–" + MAX_YIELD.toPlainString() + " 的比例；不小于下限；未知时明确留空",
                    MIN_YIELD, MAX_YIELD).allowNull());
        } else {
            evidence.add(new Evidence(from + "yieldMax", yieldMax.toPlainString()));
        }

        // ---- new vs update (the command's identity is the normalized name)
        String targetId = null;
        String requestName = name;
        if (!name.isEmpty()) {
            List<WorkProcess> sameName = catalog.stream()
                    .filter(p -> identity(p.getProcessName()).equals(identity(name))).toList();
            if (sameName.size() == 1 && sameName.get(0).isSelectableForNew()) {
                targetId = sameName.get(0).getId();
                evidence.add(new Evidence("factory.workProcesses[name=" + name + "].id", targetId));
            } else if (!sameName.isEmpty()) {
                // No target the command accepts: an inactive/merged one is PROCESS_DRAFT_TARGET_NOT_CANONICAL,
                // one of several is PROCESS_DRAFT_NAME_CONFLICT (the others keep the name), none is
                // PROCESS_DRAFT_TARGET_REQUIRED. The only answer the draft itself can carry is another name.
                inputs.add(RequiredInput.text("desired.name", "“" + title + "”换个什么名称",
                        sameName.size() > 1 ? "本厂已有 " + sameName.size() + " 道同名工序，更新哪一道都会与另一道重名，新建也会重名"
                                : "本厂已有同名工序，但它已停用或已归并，不能更新它，新建又会重名",
                        "本厂没有的名称，不超过 " + MAX_NAME + " 字；或先在工序管理里合并或恢复同名工序后重新预填", MAX_NAME));
                requestName = "";
            }
        }

        RequestedProcess desired = new RequestedProcess(nullIfEmpty(requestName), resolvedCategory, false,
                nullIfEmpty(description), null, null, outputKind, yieldMin, yieldMax);
        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), targetId, desired, null, null, 0L);
        String itemKey = name.isEmpty() ? "#" + (index + 1) : name;
        return new ProposedItem(itemKey, (targetId == null ? "新建工序：" : "更新工序：") + title, request, inputs, evidence);
    }

    private static String existingCategories(List<WorkProcess> catalog) {
        List<String> categories = catalog.stream().map(WorkProcess::getProcessCategory)
                .map(ProcessesDraftProposer::canonicalText).filter(v -> !v.isEmpty()).distinct().toList();
        return categories.isEmpty() ? "（本厂目前没有工序类别，新类别需明确确认新建）" : "：" + String.join("、", categories);
    }

    /** Same normalization as the command's {@code canonicalText}; pinned by the "already exists" test. */
    private static String canonicalText(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim();
    }

    /** Same normalization as the command's {@code normalizeIdentity}; pinned by the "already exists" test. */
    private static String identity(String value) {
        return canonicalText(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }
}
