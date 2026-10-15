package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.entity.hazardanalysisdraft.HazardAnalysisDraft;
import com.cretas.aims.entity.supplierreceiving.SupplierReceivingPolicyDraft;
import com.cretas.aims.repository.supplierreceiving.SupplierReceivingPolicyDraftRepository;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraft;
import com.cretas.aims.entity.sanitationcipdraft.SanitationCipTemplateDraft;
import com.cretas.aims.entity.hygieniczone.HygienicZoneDraft;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraft;
import com.cretas.aims.entity.moduleconfig.ModuleConfigDraftItem;
import com.cretas.aims.repository.hazardanalysisdraft.HazardAnalysisDraftRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.equipmentplan.EquipmentCalibrationPlanDraftRepository;
import com.cretas.aims.repository.sanitationcipdraft.SanitationCipTemplateDraftRepository;
import com.cretas.aims.repository.hygieniczone.HygienicZoneDraftRepository;
import com.cretas.aims.service.sanitationcipdraft.SanitationCipDraftModels;
import com.cretas.aims.service.hygieniczonedraft.HygienicZoneDraftModels;
import com.cretas.aims.service.equipmentplandraft.EquipmentCalibrationPlanDraftModels;
import com.cretas.aims.service.bomdraft.BomRecipeDraftModels;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftItemRepository;
import com.cretas.aims.repository.moduleconfig.ModuleConfigDraftRepository;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds the upstream DRAFT row a proposal must pin. Read-only; shared by the alerts, forms and
 * critical-controls proposers so the lineage rule lives in one place.
 *
 * <p>"Same lineage" is exactly what the downstream command re-checks when it resolves the pin
 * ({@code AlertPolicyDraftCommand#sameLineage}, {@code FormSchemaDraftCommand#sameLineage},
 * {@code CriticalControlDraftCommand#resolveHazard}): same factory, blueprint id, blueprint
 * version and source digest, status {@code DRAFT}, dependency closure complete (and for the hazard
 * analysis, the dependency basis and risk algorithm the command accepts). A row that the command
 * would refuse is never offered as a pin.</p>
 *
 * <p>The repositories have no lineage finder, so the query is by example (exact match on every
 * non-null probe property, newest first) and the result is re-checked here — the probe narrows,
 * the predicate decides.</p>
 */
final class UpstreamDraftPins {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private UpstreamDraftPins() {
    }

    static Optional<BomRecipeDraft> latestBomDraft(BomRecipeDraftRepository drafts,
                                                   CapabilityDraftProposer.Source source) {
        BomRecipeDraft probe = new BomRecipeDraft();
        probe.setFactoryId(source.factoryId());
        probe.setBlueprintId(source.blueprint().getId());
        probe.setBlueprintVersion(blueprintVersion(source));
        probe.setSourceDigest(source.sourceDigest());
        probe.setStatus("DRAFT");
        probe.setDependencyClosureComplete(true);
        probe.setDependencyBasis(BomRecipeDraftModels.DEPENDENCY_BASIS);
        List<BomRecipeDraft> rows = drafts.findAll(Example.of(probe), NEWEST_FIRST);
        return rows.stream().filter(row -> sameBomLineage(row, source)).findFirst();
    }

    static Optional<EquipmentCalibrationPlanDraft> latestEquipmentPlan(
            EquipmentCalibrationPlanDraftRepository drafts, CapabilityDraftProposer.Source source) {
        EquipmentCalibrationPlanDraft probe = new EquipmentCalibrationPlanDraft();
        probe.setFactoryId(source.factoryId()); probe.setBlueprintId(source.blueprint().getId());
        probe.setBlueprintVersion(blueprintVersion(source)); probe.setSourceDigest(source.sourceDigest());
        probe.setStatus("DRAFT"); probe.setDependencyClosureComplete(true);
        probe.setDependencyBasis(EquipmentCalibrationPlanDraftModels.DEPENDENCY_BASIS);
        List<EquipmentCalibrationPlanDraft> rows = drafts.findAll(Example.of(probe), NEWEST_FIRST);
        return rows.stream().filter(row -> sameEquipmentLineage(row, source)).findFirst();
    }

    static Optional<SanitationCipTemplateDraft> latestSanitation(SanitationCipTemplateDraftRepository drafts, CapabilityDraftProposer.Source source) {
        SanitationCipTemplateDraft probe=new SanitationCipTemplateDraft(); probe.setFactoryId(source.factoryId()); probe.setBlueprintId(source.blueprint().getId()); probe.setBlueprintVersion(blueprintVersion(source)); probe.setSourceDigest(source.sourceDigest()); probe.setStatus("DRAFT"); probe.setDependencyClosureComplete(true); probe.setDependencyBasis(SanitationCipDraftModels.DEPENDENCY_BASIS);
        return drafts.findAll(Example.of(probe),NEWEST_FIRST).stream().filter(row -> sameSanitation(row,source)).findFirst();
    }
    static Optional<HygienicZoneDraft> latestHygienicZone(HygienicZoneDraftRepository drafts, CapabilityDraftProposer.Source source) {
        HygienicZoneDraft probe=new HygienicZoneDraft(); probe.setFactoryId(source.factoryId()); probe.setBlueprintId(source.blueprint().getId()); probe.setBlueprintVersion(blueprintVersion(source)); probe.setSourceDigest(source.sourceDigest()); probe.setStatus("DRAFT"); probe.setDependencyClosureComplete(true); probe.setDependencyBasis(HygienicZoneDraftModels.DEPENDENCY_BASIS);
        return drafts.findAll(Example.of(probe),NEWEST_FIRST).stream().filter(row -> sameZone(row,source)).findFirst();
    }

    static Optional<ModuleConfigDraft> latestModuleConfig(ModuleConfigDraftRepository drafts,
                                                          CapabilityDraftProposer.Source source) {
        ModuleConfigDraft probe = new ModuleConfigDraft();
        probe.setFactoryId(source.factoryId());
        probe.setBlueprintId(source.blueprint().getId());
        probe.setBlueprintVersion(blueprintVersion(source));
        probe.setSourceDigest(source.sourceDigest());
        probe.setStatus("DRAFT");
        probe.setDependencyClosureComplete(true);
        List<ModuleConfigDraft> rows = drafts.findAll(Example.of(probe), NEWEST_FIRST);
        return rows.stream().filter(row -> sameModuleLineage(row, source)).findFirst();
    }

    /** Module ids the pinned module DRAFT switches on — the only ones a policy or form may bind to. */
    static List<String> enabledModules(ModuleConfigDraftItemRepository items, String factoryId, String draftId) {
        return items.findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId).stream()
                .filter(item -> Boolean.TRUE.equals(item.getDesiredEnabled()))
                .map(ModuleConfigDraftItem::getModuleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    static Optional<HazardAnalysisDraft> latestHazardAnalysis(HazardAnalysisDraftRepository drafts,
                                                              CapabilityDraftProposer.Source source) {
        HazardAnalysisDraft probe = new HazardAnalysisDraft();
        probe.setFactoryId(source.factoryId());
        probe.setBlueprintId(source.blueprint().getId());
        probe.setBlueprintVersion(blueprintVersion(source));
        probe.setSourceDigest(source.sourceDigest());
        probe.setStatus("DRAFT");
        probe.setDependencyClosureComplete(true);
        probe.setDependencyBasis(HazardAnalysisDraftModels.DEPENDENCY_BASIS);
        probe.setRiskAlgorithmVersion(HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION);
        List<HazardAnalysisDraft> rows = drafts.findAll(Example.of(probe), NEWEST_FIRST);
        return rows.stream().filter(row -> sameHazardLineage(row, source)).findFirst();
    }

    private static boolean sameModuleLineage(ModuleConfigDraft row, CapabilityDraftProposer.Source source) {
        return row != null && row.getId() != null && isDigest(row.getDesiredDigest())
                && source.factoryId().equals(row.getFactoryId())
                && "DRAFT".equals(row.getStatus())
                && Boolean.TRUE.equals(row.getDependencyClosureComplete())
                && source.blueprint().getId().equals(row.getBlueprintId())
                && Objects.equals(blueprintVersion(source), row.getBlueprintVersion())
                && source.sourceDigest().equals(row.getSourceDigest());
    }

    private static boolean sameBomLineage(BomRecipeDraft row, CapabilityDraftProposer.Source source) {
        return row != null && row.getId() != null && isDigest(row.getDesiredDigest())
                && source.factoryId().equals(row.getFactoryId())
                && "DRAFT".equals(row.getStatus())
                && Boolean.TRUE.equals(row.getDependencyClosureComplete())
                && BomRecipeDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())
                && source.blueprint().getId().equals(row.getBlueprintId())
                && Objects.equals(blueprintVersion(source), row.getBlueprintVersion())
                && source.sourceDigest().equals(row.getSourceDigest());
    }

    private static boolean sameEquipmentLineage(EquipmentCalibrationPlanDraft row, CapabilityDraftProposer.Source source) {
        return row != null && row.getId() != null && isDigest(row.getDesiredDigest())
                && source.factoryId().equals(row.getFactoryId()) && "DRAFT".equals(row.getStatus())
                && Boolean.TRUE.equals(row.getDependencyClosureComplete())
                && EquipmentCalibrationPlanDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())
                && source.blueprint().getId().equals(row.getBlueprintId())
                && Objects.equals(blueprintVersion(source), row.getBlueprintVersion())
                && source.sourceDigest().equals(row.getSourceDigest());
    }
    private static boolean sameSanitation(SanitationCipTemplateDraft row, CapabilityDraftProposer.Source source) { return row!=null&&row.getId()!=null&&isDigest(row.getDesiredDigest())&&source.factoryId().equals(row.getFactoryId())&&"DRAFT".equals(row.getStatus())&&Boolean.TRUE.equals(row.getDependencyClosureComplete())&&SanitationCipDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())&&source.blueprint().getId().equals(row.getBlueprintId())&&Objects.equals(blueprintVersion(source),row.getBlueprintVersion())&&source.sourceDigest().equals(row.getSourceDigest()); }
    private static boolean sameZone(HygienicZoneDraft row, CapabilityDraftProposer.Source source) { return row!=null&&row.getId()!=null&&isDigest(row.getDesiredDigest())&&source.factoryId().equals(row.getFactoryId())&&"DRAFT".equals(row.getStatus())&&Boolean.TRUE.equals(row.getDependencyClosureComplete())&&HygienicZoneDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())&&source.blueprint().getId().equals(row.getBlueprintId())&&Objects.equals(blueprintVersion(source),row.getBlueprintVersion())&&source.sourceDigest().equals(row.getSourceDigest()); }

    private static boolean sameHazardLineage(HazardAnalysisDraft row, CapabilityDraftProposer.Source source) {
        return row != null && row.getId() != null && isDigest(row.getDesiredDigest())
                && source.factoryId().equals(row.getFactoryId())
                && "DRAFT".equals(row.getStatus())
                && Boolean.TRUE.equals(row.getDependencyClosureComplete())
                && HazardAnalysisDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())
                && HazardAnalysisDraftModels.RISK_ALGORITHM_VERSION.equals(row.getRiskAlgorithmVersion())
                && source.blueprint().getId().equals(row.getBlueprintId())
                && Objects.equals(blueprintVersion(source), row.getBlueprintVersion())
                && source.sourceDigest().equals(row.getSourceDigest());
    }

    private static Long blueprintVersion(CapabilityDraftProposer.Source source) {
        Integer version = source.blueprint().getVersion();
        return version == null ? null : version.longValue();
    }

    static java.util.Optional<SupplierReceivingPolicyDraft> latestSupplierReceiving(SupplierReceivingPolicyDraftRepository repo, CapabilityDraftProposer.Source source) {
        SupplierReceivingPolicyDraft probe=SupplierReceivingPolicyDraft.builder().factoryId(source.factoryId()).blueprintId(source.blueprint().getId()).blueprintVersion(blueprintVersion(source)).sourceDigest(source.sourceDigest()).status("DRAFT").dependencyClosureComplete(true).dependencyBasis(SupplierReceivingPolicyDraftModels.DEPENDENCY_BASIS).build();
        return repo.findAll(Example.of(probe),NEWEST_FIRST).stream().filter(row -> sameSupplierReceiving(row,source)).findFirst();
    }

    private static boolean sameSupplierReceiving(SupplierReceivingPolicyDraft row, CapabilityDraftProposer.Source source) { return row!=null&&row.getId()!=null&&isDigest(row.getDesiredDigest())&&source.factoryId().equals(row.getFactoryId())&&"DRAFT".equals(row.getStatus())&&Boolean.TRUE.equals(row.getDependencyClosureComplete())&&SupplierReceivingPolicyDraftModels.DEPENDENCY_BASIS.equals(row.getDependencyBasis())&&source.blueprint().getId().equals(row.getBlueprintId())&&Objects.equals(blueprintVersion(source),row.getBlueprintVersion())&&source.sourceDigest().equals(row.getSourceDigest()); }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}




