package com.cretas.aims.service.config;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.config.SchedulerRunResolution;
import com.cretas.aims.entity.config.SchedulerRunResult;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.SchedulerRunResolutionRepository;
import com.cretas.aims.repository.config.SchedulerRunResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** Run history of scheduled configs and the ruling on UNKNOWN runs (S6-03 / S6-06). Always factory scoped. */
@Service
@RequiredArgsConstructor
public class SchedulerRunService {
    public static final int MAX_NOTE_CHARS = 1000;

    private final SchedulerRunResultRepository results;
    private final SchedulerRunResolutionRepository resolutions;

    /** The list/latest shape: no data snapshot (it can be large); the detail call returns it. */
    public record RunView(String runId, Long configId, String factoryId, String taskCode, String toolName,
            java.time.LocalDateTime startedAt, java.time.LocalDateTime finishedAt, String status,
            String rawStatus, String effectiveStatus, String error, String summary, boolean hasDataSnapshot,
            boolean snapshotTruncated, String resolution, Long resolvedBy, java.time.LocalDateTime resolvedAt,
            String resolutionNote, String tokenUsageState, int llmCallCount, Integer promptTokens,
            Integer completionTokens, Integer totalTokens, Object dataSnapshot, Object configSnapshot) {
    }

    static RunView view(SchedulerRunResult r, boolean withSnapshot) {
        return new RunView(r.getRunId(), r.getConfigId(), r.getFactoryId(), r.getTaskCode(), r.getToolName(),
                r.getStartedAt(), r.getFinishedAt(), r.getStatus(), r.getRawStatus(), r.effectiveStatus(),
                r.getErrorMsg(), r.getSummary(), r.getDataSnapshot() != null,
                Boolean.TRUE.equals(r.getSnapshotTruncated()), r.getResolution(), r.getResolvedBy(),
                r.getResolvedAt(), r.getResolutionNote(), r.getTokenUsageState(),
                r.getLlmCallCount() == null ? 0 : r.getLlmCallCount(), r.getPromptTokens(),
                r.getCompletionTokens(), r.getTotalTokens(),
                withSnapshot ? r.getDataSnapshot() : null, withSnapshot ? r.getConfigSnapshot() : null);
    }

    @Transactional(readOnly = true)
    public PageResponse<RunView> history(String factoryId, String taskCode, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        Page<SchedulerRunResult> result = results.findByFactoryIdAndTaskCodeOrderByStartedAtDesc(
                factoryId, taskCode, PageRequest.of(p - 1, s));
        return PageResponse.of(result.getContent().stream().map(r -> view(r, false)).toList(),
                p, s, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Optional<RunView> latest(String factoryId, String taskCode) {
        return results.findFirstByFactoryIdAndTaskCodeOrderByStartedAtDesc(factoryId, taskCode)
                .map(r -> view(r, true));
    }

    @Transactional(readOnly = true)
    public RunView detail(String factoryId, String runId) {
        return view(results.findByRunIdAndFactoryId(runId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "找不到这次运行记录")), true);
    }

    /**
     * Rules on a run whose outcome was UNKNOWN. Only an UNKNOWN run that nobody has ruled on can be
     * ruled on, with a note, once; the ruling is audited in its own append-only row.
     */
    @Transactional
    public RunView resolve(String factoryId, String runId, String resolution, String note,
            Long actorId, String actorRole) {
        if (!SchedulerRunResult.SUCCESS.equals(resolution) && !SchedulerRunResult.FAILED.equals(resolution)) {
            throw new BusinessException(400, "裁定结果只能是成功(SUCCESS)或失败(FAILED)");
        }
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "请填写裁定说明：你凭什么判断这次运行成功或失败");
        }
        if (trimmed.length() > MAX_NOTE_CHARS) {
            throw new BusinessException(400, "裁定说明不能超过 " + MAX_NOTE_CHARS + " 字");
        }
        SchedulerRunResult run = results.findForUpdate(runId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "找不到这次运行记录"));
        if (!SchedulerRunResult.UNKNOWN.equals(run.getStatus())) {
            throw new BusinessException(409, "这次运行的结果是明确的（" + run.getStatus() + "），只有结果未知的运行才需要裁定");
        }
        if (run.getResolution() != null) {
            throw new BusinessException(409, "这次运行已经裁定为 " + run.getResolution() + "，不能重复裁定");
        }
        LocalDateTime now = LocalDateTime.now();
        run.setResolution(resolution);
        run.setResolvedBy(actorId);
        run.setResolvedAt(now);
        run.setResolutionNote(trimmed);
        results.save(run);
        resolutions.save(SchedulerRunResolution.builder()
                .runId(runId).factoryId(factoryId)
                .fromStatus(SchedulerRunResult.UNKNOWN).toStatus(resolution)
                .note(trimmed).resolvedBy(actorId).resolvedByRole(actorRole).resolvedAt(now)
                .build());
        return view(run, false);
    }
}
