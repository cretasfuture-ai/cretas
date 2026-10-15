package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.*;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftItemRepository;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftCommand;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.blueprint.interview.InterviewModuleConfigCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Formal modules activation adapter; all reads remain owned by the draft/config services. */
public final class ModuleConfigurationFormalActivationCommand implements FactoryFormalModuleActivationCommand {
    private final AssemblyActivationActorResolver actors;
    private final ModuleConfigDraftCommand drafts;
    private final ModuleConfigDraftRepository draftRows;
    private final ModuleConfigDraftItemRepository items;
    private final BlueprintModuleConfigurationApplyService apply;
    private final FactoryConfigService runtime;
    private final FactoryFeatureConfigRepository features;

    public ModuleConfigurationFormalActivationCommand(AssemblyActivationActorResolver actors,
            ModuleConfigDraftCommand drafts, ModuleConfigDraftRepository draftRows,
            ModuleConfigDraftItemRepository items, BlueprintModuleConfigurationApplyService apply,
            FactoryConfigService runtime, FactoryFeatureConfigRepository features) {
        this.actors = Objects.requireNonNull(actors); this.drafts = Objects.requireNonNull(drafts);
        this.draftRows = Objects.requireNonNull(draftRows); this.apply = Objects.requireNonNull(apply);
        this.items = Objects.requireNonNull(items);
        this.runtime = Objects.requireNonNull(runtime);
        this.features = Objects.requireNonNull(features);
    }

    public String capabilityId() { return "modules"; }
    public String formalCommandName() { return "ModuleConfigurationFormalActivationCommand"; }
    public List<String> formalDependencies() { return List.of("factory"); }

    public List<ActivationIssue> validateFormalPrerequisites(String factoryId, String draftId, ActorRef actor) {
        try { actors.authorize(actor, factoryId, java.util.Set.of("factory-blueprint:activation:modules"));
            CurrentDraft current = currentDraft(factoryId, draftId, actor);
            return "DRAFT".equals(current.state()) ? List.of() : List.of(issue("MODULE_CONFIG_NOT_DRAFT", "模块配置草稿必须是 DRAFT"));
        } catch (BusinessException e) {
            return List.of(issue(e.getErrorCode() == null ? "MODULE_CONFIG_PREREQUISITES_BLOCKED" : e.getErrorCode(), e.getMessage()));
        }
    }

    public CurrentDraft currentDraft(String factoryId, String draftId, ActorRef actor) {
        actors.authorize(actor, factoryId, java.util.Set.of("factory-blueprint:activation:modules"));
        ModuleConfigDraft row = draftRows.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> blocked("MODULE_CONFIG_DRAFT_NOT_FOUND", "当前工厂没有该模块配置草稿", "刷新当前工厂的模块配置草稿回执"));
        if (!"DRAFT".equals(row.getStatus())) throw blocked("MODULE_CONFIG_DRAFT_NOT_DRAFT", "模块配置草稿已不是 DRAFT", "基于当前蓝图重新生成模块配置草稿");
        drafts.assertCurrentAuthority(factoryId, draftId);
        return new CurrentDraft(capabilityId(), row.getId(), Math.addExact(Objects.requireNonNullElse(row.getLockVersion(), 0L), 1L),
                row.getDesiredDigest(), row.getStatus());
    }

    public ModuleCommandReceipt activate(ModuleActivationCommandRequest request) {
        CurrentDraft current = currentDraft(request.factoryId(), request.draftId(), request.executedActor());
        if (current.revision() != request.expectedRevision() || !current.digest().equals(request.expectedDigest()))
            throw blocked("MODULE_CONFIG_DRAFT_DRIFTED", "模块配置草稿版本或摘要已漂移", "重新读取当前模块配置草稿后重新预览");
        var applied = apply.apply(request.factoryId(), request.draftId(), request.executedActor());
        String target = request.factoryId() + ":" + request.draftId();
        ModuleCommandReceipt provisional = new ModuleCommandReceipt(capabilityId(), "modules:" + request.draftId(),
                // Logical business actions: each customer-facing decision plus one publication.
                // SQL rows (gates, features, audit and draft state) are counted separately in native acceptance.
                target, current.revision(), current.digest(), 1 + applied.moduleCount());
        RuntimeReadback readback = readBack(request.factoryId(), provisional);
        if (!"ACTIVE".equals(readback.runtimeState())) throw new IllegalStateException("module configuration was not published");
        return new ModuleCommandReceipt(capabilityId(), "modules:" + request.draftId(),
                target, readback.activeRevision(), readback.activeDigest(), provisional.businessWriteCount());
    }

    public RuntimeReadback readBack(String factoryId, ModuleCommandReceipt receipt) {
        String[] parts = receipt.targetRuntimeId().split(":", 2);
        if (parts.length != 2 || !factoryId.equals(parts[0])) throw blocked("MODULE_CONFIG_RUNTIME_TARGET_INVALID", "模块配置运行态目标无效", "重新预览当前模块配置");
        ModuleConfigDraft row = draftRows.findByIdAndFactoryId(parts[1], factoryId)
                .orElseThrow(() -> blocked("MODULE_CONFIG_DRAFT_NOT_FOUND", "模块配置草稿不存在", "重新读取当前模块配置草稿"));
        if (!"APPLIED".equals(row.getStatus())) throw blocked("MODULE_CONFIG_DRAFT_NOT_APPLIED", "模块配置草稿尚未应用", "重新执行模块配置正式激活");
        var selected = items.findByDraftIdAndFactoryIdOrderByOrdinal(row.getId(), factoryId);
        var gate = runtime.describeModuleGate(factoryId);
        var enabled = runtime.getEnabledModules(factoryId);
        List<String> states = new java.util.ArrayList<>();
        boolean selectedMatch = !selected.isEmpty() && Integer.valueOf(selected.size()).equals(row.getModuleCount());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var item : selected) {
            var entry = InterviewModuleConfigCatalog.find(item.getModuleId());
            if (entry == null || !seen.add(item.getModuleId())) {
                throw blocked("MODULE_CONFIG_RUNTIME_MODULE_INVALID", "模块配置运行态目录不一致", "重新复核模块目录");
            }
            boolean desired = Boolean.TRUE.equals(item.getDesiredEnabled());
            var feature = features.findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, entry.moduleId());
            selectedMatch &= feature.isPresent() && Boolean.valueOf(desired).equals(feature.get().getEnabled());
            states.add(entry.moduleId() + ":frontend=" + feature.map(value -> String.valueOf(value.getEnabled())).orElse("MISSING"));
            for (String code : entry.gateCodes()) {
                boolean actual = runtime.isModuleEnabled(factoryId, code);
                selectedMatch &= actual == desired;
                states.add(entry.moduleId() + ":" + code + "=" + actual);
            }
        }
        boolean active = gate != null && gate.effective() && selectedMatch;
        String digest = runtimeDigest(factoryId, gate, enabled, states);
        return new RuntimeReadback(capabilityId(), receipt.targetRuntimeId(),
                gate == null || gate.publishedVersion() == null ? 0L : gate.publishedVersion(), digest,
                active ? "ACTIVE" : "NOT_ACTIVE");
    }

    public String recovery(ModuleCommandReceipt receipt) { return "创建新的模块配置草稿后重新确认"; }

    private static ActivationIssue issue(String code, String message) {
        return new ActivationIssue(code, "modules", message == null ? "形式激活前置条件不满足" : message,
                "修复当前模块配置草稿后重新预览");
    }
    private static BusinessException blocked(String code, String message, String hint) {
        return new BusinessException(409, message).withCode(code).withHint(hint).withSeverity("BLOCKING");
    }
    private static String runtimeDigest(String factoryId, com.cretas.aims.service.config.ModuleGateStatus gate,
                                        List<com.cretas.aims.dto.config.ModuleSummaryDTO> modules, List<String> states) {
        String value = factoryId + "|" + (gate == null ? "" : gate.publishedVersion()) + "|"
                + modules.stream().map(com.cretas.aims.dto.config.ModuleSummaryDTO::getModuleCode).sorted().toList()
                + "|" + states.stream().sorted().toList();
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("module runtime digest failed", e); }
    }
}
