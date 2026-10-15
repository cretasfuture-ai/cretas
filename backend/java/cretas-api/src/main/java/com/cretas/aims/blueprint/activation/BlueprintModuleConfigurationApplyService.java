package com.cretas.aims.blueprint.activation;

import com.cretas.aims.entity.moduleconfig.ModuleConfigDraftItem;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftItemRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.module.ModuleSwitchLayers;
import com.cretas.aims.service.module.ModuleSwitchService;
import com.cretas.aims.service.moduleconfigdraft.ModuleConfigDraftCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Applies an already validated module draft through the formal configuration writer. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class BlueprintModuleConfigurationApplyService implements ModuleConfigurationActivationCommand {
    private final ModuleConfigDraftRepository drafts;
    private final ModuleConfigDraftItemRepository items;
    private final FactoryConfigService config;
    private final ModuleSwitchService moduleSwitch;
    private ModuleConfigDraftCommand draftCommand;

    public BlueprintModuleConfigurationApplyService(ModuleConfigDraftRepository drafts,
                                                    ModuleConfigDraftItemRepository items,
                                                    FactoryConfigService config,
                                                    ModuleSwitchService moduleSwitch) {
        this.drafts = drafts; this.items = items; this.config = config; this.moduleSwitch = moduleSwitch;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setDraftCommand(ModuleConfigDraftCommand draftCommand) { this.draftCommand = draftCommand; }

    @Transactional
    @Override
    public ApplyReceipt apply(String factoryId, String draftId, ActorRef actor) {
        if (actor == null) throw new IllegalArgumentException("actor is required");
        var draft = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> new IllegalArgumentException("module draft not found"));
        if (!"DRAFT".equals(draft.getStatus())) throw new IllegalStateException("module draft is not DRAFT");
        validateDraftIntegrity(draft);
        if (draftCommand == null) throw new IllegalStateException("module draft current-authority reader is unavailable");
        draftCommand.assertCurrentAuthority(factoryId, draftId);
        List<ModuleConfigDraftItem> rows = items.findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        if (rows.isEmpty()) throw new IllegalStateException("module draft has no module decisions");
        if (!Integer.valueOf(rows.size()).equals(draft.getModuleCount())) {
            throw new IllegalStateException("module draft module count changed");
        }
        int gateCount = rows.stream().mapToInt(row -> row.getGateCodeCount() == null ? 0 : row.getGateCodeCount()).sum();
        if (!Integer.valueOf(gateCount).equals(draft.getGateCodeCount())) {
            throw new IllegalStateException("module draft gate count changed");
        }
        for (ModuleConfigDraftItem row : rows) {
            moduleSwitch.switchModuleByActor(factoryId, row.getModuleId(),
                    Boolean.TRUE.equals(row.getDesiredEnabled()), ModuleSwitchLayers.BOTH, actor);
        }
        config.publishConfigByActor(factoryId, actor, "Blueprint module configuration " + draftId);
        draft.setStatus("APPLIED");
        drafts.save(draft);
        return new ApplyReceipt(factoryId, draftId, rows.size(), actor.factoryUserId());
    }

    private void validateDraftIntegrity(com.cretas.aims.entity.moduleconfig.ModuleConfigDraft draft) {
        if (draft.getModuleCount() == null || draft.getGateCodeCount() == null
                || draft.getModuleCount() < 1 || draft.getGateCodeCount() < 0
                || !Boolean.TRUE.equals(draft.getDependencyClosureComplete())) {
            throw new IllegalStateException("module draft dependency gate is incomplete");
        }
        for (String digest : List.of(draft.getSourceDigest(), draft.getCatalogDigest(),
                draft.getSourceSnapshotDigest(), draft.getDesiredDigest(), draft.getPreviewDigest(),
                draft.getRequestDigest())) {
            if (digest == null || !digest.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalStateException("module draft digest is stale or malformed");
            }
        }
    }

    public record ApplyReceipt(String factoryId, String draftId, int moduleCount, Long legacyUserId) { }
}
