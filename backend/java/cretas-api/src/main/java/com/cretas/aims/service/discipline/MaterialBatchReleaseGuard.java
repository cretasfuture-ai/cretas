package com.cretas.aims.service.discipline;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.material.MaterialBatchRelease;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.material.MaterialBatchReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 批次放行的闸与台账 (纪律排查 A3, Steve 2026-11-10)。
 *
 * <p><b>什么算放行</b>: 批次从「不可投产」({@link MaterialBatchStatus#blocksProduction()} —— 质检中 / 过期 /
 * 报废 / 不良) 变成可投产的任何状态。不只是改成「可用」: 质检中改成「鲜品」一样能领去生产。
 *
 * <p><b>四条放行出口</b> (2026-11-10 数清, 改动前后各 4):
 * <ol>
 *   <li>仓库页改状态 {@code PUT /material-batches/{id}/status} —— 手动, 过 {@link #assertManualReleaseAllowed}</li>
 *   <li>AI {@code batch_update} 改状态 —— 手动, 过 {@link #assertManualReleaseAllowed}</li>
 *   <li>AI 质量主管放行 {@code release_decision} —— 质检放行 (须 {@code quality:read_write}), 只记台账</li>
 *   <li>质检单处置判放行 —— 质检放行, 只记台账</li>
 * </ol>
 * 系统内部把批次翻回可用的 11 处 (退料 / 撤回 / 盘点把「已用完」恢复为可用, 新建批次) 都不是从不可投产状态出发,
 * 不算放行, 故意不接。
 *
 * <p><b>规则由工厂配置</b> ({@link FactoryDisciplinePolicy#batchRelease}): 默认只能走质检放行;
 * 配成 QC_OR_MANUAL 后, 手动放行要过角色 (可配) 和原因 (可配是否必填)。
 * <b>不论怎么配, 每一次放行都写一行 {@link MaterialBatchRelease}</b>, 手动放行拿不到放行人直接拒绝。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaterialBatchReleaseGuard {

    public static final String CODE_QC_ONLY = "BATCH_RELEASE_QC_ONLY";
    public static final String CODE_ROLE_NOT_ALLOWED = "BATCH_RELEASE_ROLE_NOT_ALLOWED";
    public static final String CODE_REASON_REQUIRED = "BATCH_RELEASE_REASON_REQUIRED";
    public static final String CODE_OPERATOR_REQUIRED = "BATCH_RELEASE_OPERATOR_REQUIRED";

    private final FactoryDisciplinePolicy policy;
    private final MaterialBatchReleaseRepository releaseRepository;

    /** 手动改状态的人和理由。 */
    public record ManualChange(MaterialBatchRelease.Channel channel, String reason, Long operatorId,
                               String operatorRole) {
    }

    public static boolean isRelease(MaterialBatchStatus before, MaterialBatchStatus after) {
        return before != null && before.blocksProduction() && after != null && !after.blocksProduction();
    }

    /**
     * 手动改状态 (仓库页 / AI batch_update) 构成放行时, 按本厂规则判能不能放。不构成放行直接返回。
     */
    public void assertManualReleaseAllowed(String factoryId, MaterialBatch batch, MaterialBatchStatus target,
                                           ManualChange change) {
        MaterialBatchStatus before = batch.getStatus();
        if (!isRelease(before, target)) {
            return;
        }
        String label = (batch.getBatchNumber() != null ? batch.getBatchNumber() : batch.getId())
                + " 当前「" + before.getDisplayName() + "」";
        FactoryDisciplinePolicy.ReleaseRule rule = policy.batchRelease(factoryId);
        if (rule.mode() != FactoryDisciplinePolicy.ReleaseMode.QC_OR_MANUAL) {
            String configNote = rule.rawMode() != null && !rule.rawMode().isBlank()
                    && !FactoryDisciplinePolicy.ReleaseMode.QC_ONLY.name().equalsIgnoreCase(rule.rawMode().trim())
                    ? " (本厂放行配置值「" + rule.rawMode().trim() + "」认不出, 按只能质检放行处理)" : "";
            throw new BusinessException(409, "批次 " + label + ", 本厂规定只能经质检放行, 不能直接改成「"
                    + target.getDisplayName() + "」" + configNote)
                    .withCode(CODE_QC_ONLY)
                    .withHint("请由质检在「质量管理 → 质检放行」判定合格后放行; 是否允许手动放行由本厂在"
                            + "「业务阈值配置 → 质量」里设置 (quality.batch_release.mode)")
                    .withHintTarget("status");
        }
        if (change == null || change.operatorId() == null) {
            throw new BusinessException(409, "批次 " + label + ": 拿不到放行人, 不能放行")
                    .withCode(CODE_OPERATOR_REQUIRED)
                    .withHint("放行必须记录是谁放的; 请重新登录后再试")
                    .withHintTarget("status");
        }
        if (!rule.roleMayRelease(change.operatorRole())) {
            throw new BusinessException(403, "批次 " + label + ": 你的角色不在本厂允许手动放行的名单里")
                    .withCode(CODE_ROLE_NOT_ALLOWED)
                    .withHint("本厂允许手动放行的角色: " + String.join(", ", rule.manualRoles())
                            + "; 请联系这些人放行, 或走质检放行")
                    .withHintTarget("status");
        }
        if (rule.requireReason() && (change.reason() == null || change.reason().isBlank())) {
            throw new BusinessException(409, "批次 " + label + ": 手动放行必须填写放行原因")
                    .withCode(CODE_REASON_REQUIRED)
                    .withHint("请说明为什么不经质检就放行 (例如: 已线下复检合格, 单号 xxx)")
                    .withHintTarget("reason");
        }
    }

    /**
     * 构成放行就写一行台账; 不构成放行什么都不做。调用方在状态写库的同一事务里调用。
     */
    public void recordIfRelease(String factoryId, MaterialBatch batch, MaterialBatchStatus before,
                                MaterialBatchStatus after, MaterialBatchRelease.Channel channel, String reason,
                                String inspectionId, Long operatorId, String operatorRole) {
        if (!isRelease(before, after)) {
            return;
        }
        if (operatorId == null) {
            log.warn("批次放行没有放行人 (系统任务?): factoryId={} batchId={} channel={}", factoryId, batch.getId(), channel);
        }
        String trimmed = reason == null || reason.isBlank() ? null : reason.trim();
        if (trimmed != null && trimmed.length() > 500) {
            trimmed = trimmed.substring(0, 500);
        }
        releaseRepository.save(MaterialBatchRelease.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(factoryId)
                .materialBatchId(batch.getId())
                .fromStatus(before.name())
                .toStatus(after.name())
                .channel(channel.name())
                .reason(trimmed)
                .inspectionId(inspectionId)
                .releasedBy(operatorId)
                .releasedByRole(operatorRole)
                .releasedAt(LocalDateTime.now())
                .build());
    }
}
