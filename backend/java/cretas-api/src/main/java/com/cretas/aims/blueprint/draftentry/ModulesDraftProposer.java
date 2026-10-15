package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest.ModuleConfigItem;
import com.cretas.aims.blueprint.interview.InterviewModuleConfigCatalog;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftCommand;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.DraftRequest;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftModels.RequestedModule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code modules} proposal: one answer per entry of {@link InterviewModuleConfigCatalog}, because the
 * command refuses a draft that leaves any catalog module unanswered.
 *
 * <p>Two interview facts speak about modules, in two vocabularies:</p>
 * <ul>
 *   <li>{@code suggestedData.moduleConfigs[].moduleId} — the catalog's own ids, the command's
 *       vocabulary. Used directly.</li>
 *   <li>{@code suggestedData.modules} — keys are {@code factory_settings} {@code enableXxx} fields,
 *       backend feature bits, not catalog modules. Only {@code enableEquipmentManagement} is tied to
 *       a catalog module by code ({@code FactoryBuildApplyValidator} reports
 *       {@code MODULE_SWITCH_CONTRADICTION} unless it equals the {@code equipment} module). The other
 *       five keys have no catalog counterpart anywhere in the code; attendance is not "人事",
 *       quality check is not the "质检" screens. They are not used, and the evidence says so.</li>
 * </ul>
 *
 * <p>A module the interview did not answer, answered with no on/off, or answered both ways is left
 * empty and asked. The interview's old "listed means on" reading of a null {@code enabled} is not
 * carried over: the command requires an explicit choice ({@code MODULE_CONFIG_ENABLED_REQUIRED}).</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class ModulesDraftProposer implements CapabilityDraftProposer {

    /**
     * {@code factory_settings} switch → catalog module, only where the code itself binds the two.
     * Every other {@code enableXxx} key is reported as unused, never mapped by name resemblance.
     */
    static final Map<String, String> LEGACY_SWITCH_TO_MODULE = Map.of("enableEquipmentManagement", "equipment");

    private static final String ENABLED_CONSTRAINT = "启用 / 暂不开（必须明确选择）";
    /** {@code MODULE_CONFIG_RATIONALE_TOO_LONG}. */
    static final int MAX_RATIONALE = 500;
    private static final String RATIONALE_CONSTRAINT = "1–" + MAX_RATIONALE + " 字：适用范围、当前需求或暂不开的原因";

    @Override
    public String capabilityId() {
        return ModuleConfigDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        var blueprint = source.blueprint();
        ApplyAIInitRequest.SuggestedData suggested = blueprint.getSourcePayload().getSuggestedData();
        List<ModuleConfigItem> configs = suggested == null || suggested.getModuleConfigs() == null
                ? List.of() : suggested.getModuleConfigs();
        Map<String, Boolean> switches = suggested == null || suggested.getModules() == null
                ? Map.of() : suggested.getModules();
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();

        // moduleId -> the answers the interview gave for it, with where each came from
        Map<String, List<Answer>> answers = new LinkedHashMap<>();
        for (int k = 0; k < configs.size(); k++) {
            ModuleConfigItem config = configs.get(k);
            if (config == null) {
                continue;
            }
            String at = "sourcePayload.suggestedData.moduleConfigs[" + k + "]";
            InterviewModuleConfigCatalog.Entry entry = InterviewModuleConfigCatalog.find(config.getModuleId());
            if (entry == null) {
                evidence.add(new Evidence(at + ".moduleId", config.getModuleId() + "（不在模块目录里，未使用）"));
                continue;
            }
            answers.computeIfAbsent(entry.moduleId(), ignored -> new ArrayList<>())
                    .add(new Answer(at + ".enabled", config.getEnabled(), "模块配置"));
            if (config.getConfig() != null && !config.getConfig().isEmpty()) {
                evidence.add(new Evidence(at + ".config", "（模块草稿没有承载这类配置的字段，未带入）"));
            }
        }
        for (Map.Entry<String, Boolean> sw : switches.entrySet()) {
            String at = "sourcePayload.suggestedData.modules." + sw.getKey();
            String moduleId = LEGACY_SWITCH_TO_MODULE.get(sw.getKey());
            if (moduleId == null) {
                evidence.add(new Evidence(at, sw.getValue() + "（后端功能位，模块目录里没有对应模块，未用于预填）"));
                continue;
            }
            answers.computeIfAbsent(moduleId, ignored -> new ArrayList<>())
                    .add(new Answer(at, sw.getValue(), "功能开关"));
        }

        List<RequestedModule> modules = new ArrayList<>();
        List<InterviewModuleConfigCatalog.Entry> catalog = InterviewModuleConfigCatalog.all();
        for (int i = 0; i < catalog.size(); i++) {
            InterviewModuleConfigCatalog.Entry entry = catalog.get(i);
            String at = "modules[" + i + "].";
            String label = "“" + entry.label() + "”模块";
            List<Answer> given = answers.getOrDefault(entry.moduleId(), List.of());
            Set<Boolean> decided = new LinkedHashSet<>();
            given.stream().filter(answer -> answer.value() != null).forEach(answer -> decided.add(answer.value()));

            if (decided.size() == 1) {
                Boolean enabled = decided.iterator().next();
                given.stream().filter(answer -> answer.value() != null)
                        .forEach(answer -> evidence.add(new Evidence(answer.path(), String.valueOf(answer.value()))));
                modules.add(new RequestedModule(entry.moduleId(), enabled,
                        "依据建厂蓝图 v" + blueprint.getVersion() + "：访谈里选择" + (enabled ? "开启" : "暂不开")
                                + "“" + entry.label() + "”（" + sources(given) + "）"));
                continue;
            }
            String reason;
            if (decided.size() > 1) {
                reason = "访谈里前后说法不一致（" + conflict(given) + "）";
                given.stream().filter(answer -> answer.value() != null)
                        .forEach(answer -> evidence.add(new Evidence(answer.path(), answer.value() + "（与另一处矛盾，未采用）")));
            } else if (!given.isEmpty()) {
                reason = "访谈列出了这个模块，但没有说开还是关";
            } else {
                reason = "建厂访谈没有问到这个模块";
            }
            inputs.add(RequiredInput.bool(at + "enabled", label + "要不要启用", reason, ENABLED_CONSTRAINT));
            inputs.add(RequiredInput.text(at + "rationale", label + "启用或暂不开的理由", reason, RATIONALE_CONSTRAINT,
                    MAX_RATIONALE));
            modules.add(new RequestedModule(entry.moduleId(), null, null));
        }

        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), modules, null, null, 0L);
        return Proposed.single(capabilityId(), "模块开关", request, inputs, evidence);
    }

    private record Answer(String path, Boolean value, String sourceLabel) {
    }

    private static String sources(List<Answer> given) {
        return String.join("、", given.stream().filter(answer -> answer.value() != null)
                .map(Answer::sourceLabel).distinct().toList());
    }

    private static String conflict(List<Answer> given) {
        return String.join("，", given.stream().filter(answer -> answer.value() != null)
                .map(answer -> answer.sourceLabel() + "为" + (answer.value() ? "开启" : "关闭")).toList());
    }
}
