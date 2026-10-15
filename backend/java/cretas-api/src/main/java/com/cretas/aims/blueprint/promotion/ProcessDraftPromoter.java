package com.cretas.aims.blueprint.promotion;

import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.Blocker;
import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.entity.workprocess.WorkProcessDraft;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.WorkProcessService;
import com.cretas.aims.security.ActorRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * processes: {@code work_process_drafts} -> {@code work_processes} through {@link WorkProcessService}.
 *
 * <p>⚠️ work_processes has no created_by column, and {@link WorkProcessService#create} takes no
 * operator. The operator of a process promotion is recorded only on the promotion row
 * ({@code master_data_draft_promotions.operator_id}).</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
class ProcessDraftPromoter implements DraftPromoter {

    static final String CAPABILITY_ID = "processes";
    static final String PERMISSION = "process:draft:promote";

    private final WorkProcessService workProcessService;
    private final WorkProcessRepository workProcessRepository;

    @PersistenceContext
    private EntityManager entityManager;

    ProcessDraftPromoter(WorkProcessService workProcessService, WorkProcessRepository workProcessRepository) {
        this.workProcessService = workProcessService;
        this.workProcessRepository = workProcessRepository;
    }

    @Override
    public String capabilityId() {
        return CAPABILITY_ID;
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public Optional<DraftSnapshot> read(String factoryId, String draftId, boolean forUpdate) {
        TypedQuery<WorkProcessDraft> query = entityManager.createQuery(
                "select d from WorkProcessDraft d where d.id = :id and d.factoryId = :factoryId "
                        + "and d.deletedAt is null", WorkProcessDraft.class)
                .setParameter("id", draftId).setParameter("factoryId", factoryId);
        if (forUpdate) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        List<WorkProcessDraft> rows = query.getResultList();
        if (rows.isEmpty()) return Optional.empty();
        WorkProcessDraft draft = rows.get(0);
        entityManager.detach(draft);
        return Optional.of(new DraftSnapshot(draft.getId(), draft.getFactoryId(), draft.getBlueprintId(),
                draft.getBlueprintVersion(), draft.getSourceDigest(), draft.getDesiredDigest(),
                draft.getLockVersion(), draft.getStatus(), draft.getNaturalKey(),
                draft.getTargetWorkProcessId(), after(draft), draft));
    }

    private static Map<String, Object> after(WorkProcessDraft draft) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("processName", draft.getDesiredProcessName());
        after.put("processCategory", draft.getDesiredProcessCategory());
        after.put("description", draft.getDesiredDescription());
        after.put("estimatedMinutes", draft.getDesiredEstimatedMinutes());
        after.put("needsInput", draft.getDesiredNeedsInput());
        after.put("defaultOutputMaterialKind", draft.getDesiredDefaultOutputMaterialKind());
        after.put("standardYieldMin", draft.getDesiredStandardYieldMin());
        after.put("standardYieldMax", draft.getDesiredStandardYieldMax());
        return after;
    }

    @Override
    public Optional<LiveRow> findLiveConflict(String factoryId, DraftSnapshot draft) {
        // Same identity rule as WorkProcessDraftCommand: NFKC, whitespace-insensitive, case-insensitive.
        String wanted = identity(String.valueOf(draft.after().get("processName")));
        return workProcessRepository.findByFactoryId(factoryId).stream()
                .filter(row -> identity(row.getProcessName()).equals(wanted))
                .findFirst()
                .map(row -> new LiveRow(row.getId(), row.getProcessName()));
    }

    @Override
    public List<Blocker> capabilityBlockers(DraftSnapshot draft) {
        WorkProcessDraft source = (WorkProcessDraft) draft.source();
        if (source.getDesiredStandardYieldMin() != null && source.getDesiredStandardYieldMax() != null
                && source.getDesiredStandardYieldMin().compareTo(source.getDesiredStandardYieldMax()) >= 0) {
            return List.of(Blocker.of("PROMOTION_PROCESS_YIELD_RANGE_INVALID",
                    "正式工序接口要求标准出成率下限小于上限，当前草稿不满足，所以不能转正",
                    "在蓝图中确认有效的出成率区间，重新保存并验证后生成工序草稿"));
        }
        return List.of();
    }

    @Override
    public String create(String factoryId, DraftSnapshot draft, ActorRef actor) {
        WorkProcessDraft source = (WorkProcessDraft) draft.source();
        WorkProcessDTO dto = new WorkProcessDTO();
        dto.setProcessName(source.getDesiredProcessName());
        dto.setProcessCategory(source.getDesiredProcessCategory());
        dto.setCreateCategory(Boolean.TRUE.equals(source.getDesiredCreateCategory()));
        dto.setDescription(source.getDesiredDescription());
        dto.setEstimatedMinutes(source.getDesiredEstimatedMinutes());
        dto.setNeedsInput(source.getDesiredNeedsInput());
        dto.setDefaultOutputMaterialKind(
                WorkProcessOutputMaterialKind.valueOf(source.getDesiredDefaultOutputMaterialKind()));
        dto.setStandardYieldMin(source.getDesiredStandardYieldMin());
        dto.setStandardYieldMax(source.getDesiredStandardYieldMax());
        return workProcessService.create(factoryId, dto).getId();
    }

    @Override
    public LiveReadback readBack(String factoryId, String liveTargetId) {
        entityManager.flush();
        Optional<WorkProcess> found = workProcessRepository.findByFactoryIdAndId(factoryId, liveTargetId);
        if (found.isEmpty()) return null;
        WorkProcess row = found.get();
        entityManager.refresh(row);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("processName", row.getProcessName());
        fields.put("processCategory", row.getProcessCategory());
        fields.put("description", row.getDescription());
        fields.put("estimatedMinutes", row.getEstimatedMinutes());
        fields.put("needsInput", row.getNeedsInput());
        fields.put("defaultOutputMaterialKind", row.getDefaultOutputMaterialKind() == null
                ? null : row.getDefaultOutputMaterialKind().name());
        fields.put("standardYieldMin", row.getStandardYieldMin());
        fields.put("standardYieldMax", row.getStandardYieldMax());
        return new LiveReadback(row.getId(), fields, null);
    }

    @Override
    public boolean recordsCreatedBy() {
        return false;
    }

    private static String identity(String value) {
        String text = value == null ? "" : value;
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
