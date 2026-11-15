package com.cretas.aims.service.quality.firstarticle;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.quality.FirstArticleDtos.InspectionRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.InspectionView;
import com.cretas.aims.dto.quality.FirstArticleDtos.JudgePreview;
import com.cretas.aims.dto.quality.FirstArticleDtos.JudgeRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.PolicyView;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardPreview;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardRequest;
import com.cretas.aims.dto.quality.FirstArticleDtos.StandardView;
import com.cretas.aims.dto.quality.FirstArticleDtos.TaskRow;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection.Status;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection.Verdict;
import com.cretas.aims.entity.quality.firstarticle.FirstArticlePolicy;
import com.cretas.aims.entity.quality.firstarticle.FirstArticleStandard;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleInspectionRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticlePolicyRepository;
import com.cretas.aims.repository.quality.firstarticle.FirstArticleStandardRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.workflow.FactorySelfApprovalGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工序首检: 开关、标准、送检、判定、作废, 以及「工序首检」表。设计见
 * {@code docs/specs/process-first-article-inspection-design.md}。
 *
 * <p>所有读写都带 {@code factoryId}: 别的厂的任务 / 标准 / 首检单一律当「不存在」(404, 不透出是否存在)。
 * 权限 (谁能送检、谁能判定) 由 {@code FirstArticleController} 上的 {@code @RequirePermission} 在服务端拦。
 */
@Service
@RequiredArgsConstructor
public class FirstArticleService {

    private static final int MAX_PAGE_SIZE = 100;

    private final FirstArticlePolicyRepository policyRepository;
    private final FirstArticleStandardRepository standardRepository;
    private final FirstArticleInspectionRepository inspectionRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductionBatchRepository batchRepository;
    private final ObjectMapper objectMapper;
    /** 工厂「允许本人审批」开关 (四眼) 的唯一判定处; 读不到 = 关 = 送检人不能判自己的样。 */
    private final FactorySelfApprovalGate selfApprovalGate;

    /** 送检人判自己送的样, 而本厂没开「允许本人审批」。 */
    public static final String SELF_JUDGE_FORBIDDEN = "FIRST_ARTICLE_SELF_JUDGE_FORBIDDEN";

    // ==================== 开关 ====================

    @Transactional(readOnly = true)
    public PolicyView getPolicy(String factoryId) {
        return policyRepository.findById(factoryId)
                .map(p -> policyView(p.getMode(), true))
                .orElseGet(() -> policyView(FirstArticlePolicy.Mode.OFF, false));
    }

    @Transactional
    public PolicyView updatePolicy(String factoryId, FirstArticlePolicy.Mode mode, Long operatorId) {
        if (mode == null) {
            throw new BusinessException(400, "请选择首检开关: 关 / 提示 / 拦截").withCode("FIRST_ARTICLE_INVALID");
        }
        FirstArticlePolicy p = policyRepository.findById(factoryId).orElseGet(() -> {
            FirstArticlePolicy n = new FirstArticlePolicy();
            n.setFactoryId(factoryId);
            return n;
        });
        p.setMode(mode);
        p.setUpdatedBy(operatorId);
        policyRepository.save(p);
        return policyView(mode, true);
    }

    private static PolicyView policyView(FirstArticlePolicy.Mode mode, boolean configured) {
        String label = switch (mode) {
            case OFF -> "关 (不查首检)";
            case WARN -> "提示 (没首检合格只提醒, 不拦报工)";
            case BLOCK -> "拦截 (没首检合格不能报完工)";
        };
        return new PolicyView(mode, label, configured);
    }

    // ==================== 标准 ====================

    @Transactional(readOnly = true)
    public List<StandardView> listStandards(String factoryId, boolean includeInactive) {
        List<FirstArticleStandard> list = standardRepository.listForFactory(factoryId, includeInactive);
        Map<String, String> names = processNames(factoryId,
                list.stream().map(FirstArticleStandard::getWorkProcessId).collect(Collectors.toSet()));
        return list.stream().map(s -> standardView(s, names.get(s.getWorkProcessId()))).toList();
    }

    /** 保存前的预览: 校验 + 规整, 不写库。前端「预览 → 确认」的第一步。 */
    @Transactional(readOnly = true)
    public StandardPreview previewStandard(String factoryId, StandardRequest req) {
        Resolved r = resolveStandard(factoryId, req);
        return new StandardPreview(r.existing == null ? "CREATE" : "REVISE", r.workProcessId, r.processName,
                r.name, r.items, r.items.stream().map(FirstArticleItemRules::ruleText).toList(),
                r.existing == null ? 1 : r.existing.getRevision() + 1);
    }

    @Transactional
    public StandardView saveStandard(String factoryId, StandardRequest req, Long operatorId) {
        Resolved r = resolveStandard(factoryId, req);
        FirstArticleStandard s = r.existing;
        if (s == null) {
            s = new FirstArticleStandard();
            s.setFactoryId(factoryId);
            s.setWorkProcessId(r.workProcessId);
            s.setRevision(1);
            s.setActive(Boolean.TRUE);
            s.setCreatedBy(operatorId);
        } else {
            s.setRevision(s.getRevision() + 1);
        }
        s.setName(r.name);
        s.setItemsJson(FirstArticleItemRules.write(objectMapper, r.items));
        s.setUpdatedBy(operatorId);
        try {
            s = standardRepository.saveAndFlush(s);
        } catch (DataIntegrityViolationException dup) {
            throw activeExists(r.processName);
        }
        return standardView(s, r.processName);
    }

    @Transactional
    public StandardView deactivateStandard(String factoryId, Long id, String reason, Long operatorId) {
        String why = requireReason(reason, "停用标准要写原因");
        FirstArticleStandard s = standardRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("首检标准不存在"));
        if (!Boolean.TRUE.equals(s.getActive())) {
            throw new BusinessException(409, "这条首检标准已经停用").withCode("FIRST_ARTICLE_STATE")
                    .withHint("刷新首检标准列表; 这道工序还要首检的话, 新建一条标准");
        }
        s.setActive(Boolean.FALSE);
        s.setDeactivatedReason(why);
        s.setUpdatedBy(operatorId);
        s = standardRepository.save(s);
        return standardView(s, processNames(factoryId, Set.of(s.getWorkProcessId())).get(s.getWorkProcessId()));
    }

    private Resolved resolveStandard(String factoryId, StandardRequest req) {
        if (req == null) throw new BusinessException(400, "请求体不能为空").withCode("FIRST_ARTICLE_INVALID");
        String name = req.name() == null ? null : req.name().trim();
        if (name == null || name.isEmpty()) {
            throw new BusinessException(400, "请填写标准名称").withCode("FIRST_ARTICLE_INVALID");
        }
        if (name.length() > 100) throw new BusinessException(400, "标准名称超过 100 字").withCode("FIRST_ARTICLE_INVALID");
        List<FirstArticleItemRules.Item> items = FirstArticleItemRules.validate(req.items());
        FirstArticleStandard existing = null;
        String workProcessId;
        if (req.id() != null) {
            existing = standardRepository.findByFactoryIdAndId(factoryId, req.id())
                    .orElseThrow(() -> new ResourceNotFoundException("首检标准不存在"));
            if (!Boolean.TRUE.equals(existing.getActive())) {
                throw new BusinessException(409, "停用的标准不能再改, 请新建一条").withCode("FIRST_ARTICLE_STATE")
                        .withHint("在「首检标准」页点「新建首检标准」, 选同一道工序重新配置检验项");
            }
            if (req.workProcessId() != null && !req.workProcessId().equals(existing.getWorkProcessId())) {
                throw new BusinessException(400, "标准的工序不能改; 换工序请停用后新建").withCode("FIRST_ARTICLE_INVALID");
            }
            workProcessId = existing.getWorkProcessId();
        } else {
            workProcessId = req.workProcessId();
            if (workProcessId == null || workProcessId.isBlank()) {
                throw new BusinessException(400, "请选择要首检的工序").withCode("FIRST_ARTICLE_INVALID");
            }
        }
        WorkProcess wp = workProcessRepository.findByFactoryIdAndId(factoryId, workProcessId)
                .orElseThrow(() -> new ResourceNotFoundException("工序不存在"));
        if (existing == null && standardRepository.findActive(factoryId, workProcessId).isPresent()) {
            throw activeExists(wp.getProcessName());
        }
        return new Resolved(existing, workProcessId, wp.getProcessName(), name, items);
    }

    private record Resolved(FirstArticleStandard existing, String workProcessId, String processName, String name,
                            List<FirstArticleItemRules.Item> items) {
    }

    private static BusinessException activeExists(String processName) {
        return new BusinessException(409, "工序「" + processName + "」已经有一条启用的首检标准, 请直接修改它")
                .withCode("FIRST_ARTICLE_STATE")
                .withHint("在「首检标准」列表找到这道工序的标准点「修改」; 要换成全新标准, 先停用旧的再新建");
    }

    // ==================== 工序首检表 ====================

    /**
     * 要首检的工序任务及其首检状态。{@code onlyInProgress=true} 只看进行中 (默认), 否则含待开始 / 已完成。
     * 本厂没有启用标准 → 空表 (如实为空, 不是没查)。
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskRow> listTasks(String factoryId, boolean onlyInProgress, int page, int size) {
        int p = Math.max(1, page);
        int sz = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Map<String, FirstArticleStandard> stdByProcess = new HashMap<>();
        for (FirstArticleStandard s : standardRepository.listForFactory(factoryId, false)) {
            stdByProcess.put(s.getWorkProcessId(), s);
        }
        if (stdByProcess.isEmpty()) {
            return PageResponse.of(List.of(), p, sz, 0L);
        }
        List<WorkProcessTask.Status> statuses = onlyInProgress
                ? List.of(WorkProcessTask.Status.IN_PROGRESS)
                : List.of(WorkProcessTask.Status.PENDING, WorkProcessTask.Status.IN_PROGRESS,
                WorkProcessTask.Status.COMPLETED);
        Page<WorkProcessTask> tasks = inspectionRepository.findTasksRequiringFirstArticle(factoryId,
                stdByProcess.keySet(), statuses, PageRequest.of(p - 1, sz, Sort.by(Sort.Direction.DESC, "id")));
        List<Long> taskIds = tasks.getContent().stream().map(WorkProcessTask::getId).toList();
        Map<Long, FirstArticleInspection> latest = latestByTask(factoryId, taskIds);
        Map<String, String> names = processNames(factoryId, stdByProcess.keySet());
        Map<Long, String> batchNos = batchNumbers(factoryId,
                tasks.getContent().stream().map(WorkProcessTask::getProductionBatchId).collect(Collectors.toSet()));
        List<TaskRow> rows = new ArrayList<>();
        for (WorkProcessTask t : tasks.getContent()) {
            FirstArticleStandard s = stdByProcess.get(t.getWorkProcessId());
            FirstArticleInspection i = latest.get(t.getId());
            String fa = i == null ? "NOT_REQUESTED" : i.getStatus().name();
            String faLabel = i == null ? "还没送检" : i.getStatus().getDisplayName();
            rows.add(new TaskRow(t.getId(), t.getProductionBatchId(), batchNos.get(t.getProductionBatchId()),
                    t.getWorkProcessId(), names.get(t.getWorkProcessId()), t.getProductTypeId(),
                    t.getStatus().name(), t.getStatus().getDisplayName(), t.getActualStartAt(),
                    s.getId(), s.getName(), fa, faLabel, i == null ? null : i.getId()));
        }
        return PageResponse.of(rows, p, sz, tasks.getTotalElements());
    }

    // ==================== 首检单 ====================

    @Transactional(readOnly = true)
    public PageResponse<InspectionView> listInspections(String factoryId, Status status, Long taskId, int page, int size) {
        int p = Math.max(1, page);
        int sz = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Page<FirstArticleInspection> found = inspectionRepository.search(factoryId,
                status == null ? null : status.name(), taskId,
                PageRequest.of(p - 1, sz, Sort.by(Sort.Direction.DESC, "requestedAt", "id")));
        Map<String, String> names = processNames(factoryId,
                found.getContent().stream().map(FirstArticleInspection::getWorkProcessId).collect(Collectors.toSet()));
        return PageResponse.of(found.getContent().stream()
                .map(i -> inspectionView(i, names.get(i.getWorkProcessId()))).toList(), p, sz, found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public InspectionView getInspection(String factoryId, Long id) {
        FirstArticleInspection i = inspectionRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("首检单不存在"));
        return inspectionView(i, processNames(factoryId, Set.of(i.getWorkProcessId())).get(i.getWorkProcessId()));
    }

    /** 送检: 进行中的任务、工序有启用标准、没有待判定的单、还没合格 (合格了要重检先作废)。 */
    @Transactional
    public InspectionView requestInspection(String factoryId, InspectionRequest req, Long operatorId) {
        if (req == null || req.taskId() == null) {
            throw new BusinessException(400, "请选择要首检的工序任务").withCode("FIRST_ARTICLE_INVALID");
        }
        if (req.sampleQuantity() != null && req.sampleQuantity().signum() <= 0) {
            throw new BusinessException(400, "送检数量要大于 0").withCode("FIRST_ARTICLE_INVALID");
        }
        String unit = req.sampleUnit() == null || req.sampleUnit().isBlank() ? null : req.sampleUnit().trim();
        if (req.sampleQuantity() != null && unit == null) {
            throw new BusinessException(400, "填了送检数量就要填写单位").withCode("FIRST_ARTICLE_INVALID");
        }
        WorkProcessTask task = taskRepository.findByFactoryIdAndId(factoryId, req.taskId())
                .orElseThrow(() -> new ResourceNotFoundException("工序任务不存在"));
        if (task.getStatus() != WorkProcessTask.Status.IN_PROGRESS) {
            throw new BusinessException(409, "工序任务现在是「" + task.getStatus().getDisplayName()
                    + "」, 只有进行中的工序才能送首检").withCode("FIRST_ARTICLE_STATE")
                    .withHint("先开工这道工序, 做出首件后再送检; 已完成的工序不再需要首检");
        }
        FirstArticleStandard std = standardRepository.findActive(factoryId, task.getWorkProcessId())
                .orElseThrow(() -> new BusinessException(409, "这道工序没有启用的首检标准, 不需要首检")
                        .withCode("FIRST_ARTICLE_STATE")
                        .withHint("这道工序要首检的话, 请质量负责人先在「首检标准」里给它配一条标准"));
        FirstArticleInspection latest = latestByTask(factoryId, List.of(task.getId())).get(task.getId());
        if (latest != null && latest.getStatus() == Status.PENDING) {
            throw new BusinessException(409, "这道工序已经送检, 等质检判定").withCode("FIRST_ARTICLE_STATE")
                    .withHint("不用重复送检; 请质检在「工序首检」表里点「判定」, 送错了可以作废后重送");
        }
        if (latest != null && latest.getStatus() == Status.PASSED) {
            throw new BusinessException(409, "这道工序首检已经合格; 要重新首检请先作废那张单")
                    .withCode("FIRST_ARTICLE_STATE")
                    .withHint("在「首检单」里找到那张合格单点「作废」并写明原因, 再重新送检");
        }
        FirstArticleInspection i = new FirstArticleInspection();
        i.setFactoryId(factoryId);
        i.setWorkProcessTaskId(task.getId());
        i.setProductionBatchId(task.getProductionBatchId());
        i.setWorkProcessId(task.getWorkProcessId());
        i.setStandardId(std.getId());
        i.setStandardRevision(std.getRevision());
        i.setStandardName(std.getName());
        i.setStandardItemsJson(std.getItemsJson());
        i.setStatus(Status.PENDING);
        i.setSampleQuantity(req.sampleQuantity());
        i.setSampleUnit(unit);
        i.setRequestNotes(clip(req.notes()));
        i.setRequestedBy(operatorId);
        i.setRequestedAt(LocalDateTime.now());
        try {
            i = inspectionRepository.saveAndFlush(i);
        } catch (DataIntegrityViolationException dup) {
            throw new BusinessException(409, "这道工序已经送检, 等质检判定").withCode("FIRST_ARTICLE_STATE")
                    .withHint("刚有人同时送检了这道工序; 刷新「工序首检」表, 等质检判定那一张");
        }
        return inspectionView(i, processNames(factoryId, Set.of(i.getWorkProcessId())).get(i.getWorkProcessId()));
    }

    /** 判定预览: 按送检时的标准快照逐项算, 不写库。 */
    @Transactional(readOnly = true)
    public JudgePreview previewJudge(String factoryId, Long id, JudgeRequest req, Long operatorId) {
        FirstArticleInspection i = inspectionRepository.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("首检单不存在"));
        JudgePreview p = judgePreview(i, req);
        if (!isMaker(i, operatorId) || !p.canSubmit()) {
            return isMaker(i, operatorId) ? withSelf(p, p.canSubmit(), p.message()) : p;
        }
        // 预览与写路径同一判据 (FactorySelfApprovalGate), 只是不抛, 改成 canSubmit=false + 原因
        if (selfApprovalGate == null || !selfApprovalGate.isEnabled(factoryId)) {
            return withSelf(p, false, SELF_JUDGE_MESSAGE);
        }
        if (req.selfJudgeReason() == null || req.selfJudgeReason().isBlank()) {
            return withSelf(p, false, "本厂允许本人判定, 但要写明为什么由送检人本人判定");
        }
        return withSelf(p, true, p.message() + " (本人判定, 原因会留痕)");
    }

    private static final String SELF_JUDGE_MESSAGE =
            "这张首检单是你送检的, 本厂没有允许本人判定, 请由另一位有质量权限的人判定";

    private static boolean isMaker(FirstArticleInspection i, Long operatorId) {
        return operatorId != null && operatorId.equals(i.getRequestedBy());
    }

    private static JudgePreview withSelf(JudgePreview p, boolean canSubmit, String message) {
        return new JudgePreview(p.inspectionId(), p.results(), p.autoVerdict(), p.failedItems(), p.verdict(),
                canSubmit, message, true);
    }

    @Transactional
    public InspectionView judge(String factoryId, Long id, JudgeRequest req, Long operatorId) {
        FirstArticleInspection i = inspectionRepository.lockByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("首检单不存在"));
        // 四眼: 送检人判自己送的样 → 看工厂「允许本人审批」开关; 关 (默认 / 读不到) → 403, 开 → 必须写原因 (422) 并留痕
        FactorySelfApprovalGate.Decision self = FactorySelfApprovalGate.decide(selfApprovalGate, factoryId,
                isMaker(i, operatorId), req == null ? null : req.selfJudgeReason());
        if (self.forbidden()) {
            throw new BusinessException(403, SELF_JUDGE_MESSAGE)
                    .withCode(SELF_JUDGE_FORBIDDEN)
                    .withHint(FactorySelfApprovalGate.SWITCH_HINT);
        }
        JudgePreview preview = judgePreview(i, req);
        if (!preview.canSubmit()) {
            throw new BusinessException(409, preview.message()).withCode("FIRST_ARTICLE_STATE")
                    .withHint("按提示调整判定结果或补写原因, 先点「预览判定」确认能提交后再确认");
        }
        FirstArticleItemRules.Evaluation ev = new FirstArticleItemRules.Evaluation(
                preview.results(), preview.autoVerdict(), preview.failedItems());
        i.setResultsJson(FirstArticleItemRules.write(objectMapper, ev.items()));
        i.setAutoVerdict(ev.autoVerdict());
        i.setStatus(req.verdict() == Verdict.PASSED ? Status.PASSED : Status.FAILED);
        i.setJudgedBy(operatorId);
        i.setJudgedAt(LocalDateTime.now());
        i.setJudgeNotes(clip(req.notes()));
        i.setSelfJudged(self.selfApproved());
        i.setSelfJudgeReason(self.selfApproved() ? self.reason() : null);
        i = inspectionRepository.save(i);
        return inspectionView(i, processNames(factoryId, Set.of(i.getWorkProcessId())).get(i.getWorkProcessId()));
    }

    private JudgePreview judgePreview(FirstArticleInspection i, JudgeRequest req) {
        if (req == null) throw new BusinessException(400, "请求体不能为空").withCode("FIRST_ARTICLE_INVALID");
        if (i.getStatus() != Status.PENDING) {
            return new JudgePreview(i.getId(), List.of(), null, List.of(), req.verdict(), false,
                    "这张首检单现在是「" + i.getStatus().getDisplayName() + "」, 不能再判定", false);
        }
        List<FirstArticleItemRules.Item> items = FirstArticleItemRules.readItems(objectMapper, i.getStandardItemsJson());
        FirstArticleItemRules.Evaluation ev = FirstArticleItemRules.evaluate(items, req.results());
        Verdict verdict = req.verdict();
        boolean canSubmit;
        String message;
        if (verdict == null) {
            canSubmit = false;
            message = "系统按标准算出: " + label(ev.autoVerdict()) + "; 请选择判定结果";
        } else if (verdict == Verdict.PASSED && ev.autoVerdict() == Verdict.FAILED) {
            canSubmit = false;
            message = "有不合格项 (" + String.join("、", ev.failedItemNames()) + "), 不能判首检合格";
        } else if (verdict == Verdict.FAILED && ev.autoVerdict() == Verdict.PASSED
                && (req.notes() == null || req.notes().isBlank())) {
            canSubmit = false;
            message = "各项都合格却判不合格, 请写明原因";
        } else {
            canSubmit = true;
            message = "确认判定为「" + label(verdict) + "」"
                    + (ev.failedItemNames().isEmpty() ? "" : " (不合格项: " + String.join("、", ev.failedItemNames()) + ")");
        }
        return new JudgePreview(i.getId(), ev.items(), ev.autoVerdict(), ev.failedItemNames(), verdict, canSubmit, message, false);
    }

    @Transactional
    public InspectionView voidInspection(String factoryId, Long id, String reason, Long operatorId) {
        String why = requireReason(reason, "作废首检单要写原因");
        FirstArticleInspection i = inspectionRepository.lockByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new ResourceNotFoundException("首检单不存在"));
        if (i.getStatus() == Status.VOID) {
            throw new BusinessException(409, "这张首检单已经作废").withCode("FIRST_ARTICLE_STATE")
                    .withHint("刷新首检单列表; 这道工序还要首检的话, 重新送检开一张新单");
        }
        i.setStatus(Status.VOID);
        i.setVoidReason(why);
        i.setVoidedBy(operatorId);
        i.setVoidedAt(LocalDateTime.now());
        i = inspectionRepository.save(i);
        return inspectionView(i, processNames(factoryId, Set.of(i.getWorkProcessId())).get(i.getWorkProcessId()));
    }

    // ==================== helpers ====================

    private Map<Long, FirstArticleInspection> latestByTask(String factoryId, Collection<Long> taskIds) {
        Map<Long, FirstArticleInspection> out = new HashMap<>();
        if (taskIds.isEmpty()) return out;
        for (FirstArticleInspection i : inspectionRepository.findEffectiveForTasks(factoryId, taskIds)) {
            out.putIfAbsent(i.getWorkProcessTaskId(), i); // 已按 requestedAt DESC 排序, 第一张 = 最新
        }
        return out;
    }

    private Map<String, String> processNames(String factoryId, Collection<String> ids) {
        Map<String, String> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        for (WorkProcess wp : workProcessRepository.findByFactoryIdAndIdIn(factoryId, new ArrayList<>(new HashSet<>(ids)))) {
            m.put(wp.getId(), wp.getProcessName());
        }
        return m;
    }

    private Map<Long, String> batchNumbers(String factoryId, Collection<Long> ids) {
        Map<Long, String> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        for (ProductionBatch b : batchRepository.findByIdInAndFactoryId(ids, factoryId)) {
            m.put(b.getId(), b.getBatchNumber());
        }
        return m;
    }

    private StandardView standardView(FirstArticleStandard s, String processName) {
        return new StandardView(s.getId(), s.getWorkProcessId(), processName, s.getName(),
                FirstArticleItemRules.readItems(objectMapper, s.getItemsJson()), Boolean.TRUE.equals(s.getActive()),
                s.getRevision(), s.getDeactivatedReason(), s.getUpdatedAt());
    }

    private InspectionView inspectionView(FirstArticleInspection i, String processName) {
        return new InspectionView(i.getId(), i.getWorkProcessTaskId(), i.getProductionBatchId(), i.getWorkProcessId(),
                processName, i.getStandardId(), i.getStandardRevision(), i.getStandardName(),
                FirstArticleItemRules.readItems(objectMapper, i.getStandardItemsJson()),
                i.getStatus(), i.getStatus().getDisplayName(), i.getSampleQuantity(), i.getSampleUnit(),
                i.getRequestNotes(), i.getRequestedBy(), i.getRequestedAt(),
                FirstArticleItemRules.readResults(objectMapper, i.getResultsJson()), i.getAutoVerdict(),
                i.getJudgedBy(), i.getJudgedAt(), i.getJudgeNotes(), Boolean.TRUE.equals(i.getSelfJudged()),
                i.getSelfJudgeReason(), i.getVoidReason(), i.getVoidedAt());
    }

    private static String label(Verdict v) {
        return v == Verdict.PASSED ? "首检合格" : "首检不合格";
    }

    private static String requireReason(String reason, String msg) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, msg).withCode("FIRST_ARTICLE_INVALID");
        }
        return clip(reason);
    }

    private static String clip(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }
}
