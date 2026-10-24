package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.event.WorkflowTaskProgressRequestedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Workflow 工序任务进度回写 (web 逐道录入 → {@link WorkProcessTask#getActualQuantity()})。
 *
 * <p><b>G08 (2026-10-24) 口径</b>: 任务实际产量 = 该工序节点<b>全部有效</b> process_sheet_rows 的主产出合计,
 * 每次保存 / 提交 / 删除 / 撤回成草稿后<b>重算</b>, ⛔ 不再用事件里那一行的产出量覆盖
 * (同一工序两行 48 + 32 时旧版留下 32)。与 App 报工路径同一口径:
 * {@code YieldReportServiceImpl} 按该任务全部 YIELD 报工求和,
 * {@code ProductionReportCorrectionServiceImpl} 更正后重算求和。
 *
 * <p><b>有效行</b>: 未软删 (实体 {@code @Where}) + 已物化 ({@code row_status} 不是 DRAFT) +
 * 不是草稿提交态 ({@code submission_status} 不是 DRAFT; LEGACY 与 SUBMITTED 都算 —— 旧版 /rows 保存直接物化库存)。
 *
 * <p><b>多产出口径 = 主产出端口</b>: 多产出报工落成 base#0..base#n 多行, 每行一种产品、可能不同单位。
 * base#0 只是客户端顺序里第一个非零产出 (它承载全部投入), ⛔ 不等于主产出 —— 用户只报了副产时 base#0 就是副产。
 * 所以只计 {@code workflowPortId == 主产出端口} (ProcessDescriptor.output: 第一个非副产产出端口,
 * plannedUnit 也取它) 的那一行。
 *
 * <p><b>诚实 null</b>: 没有有效行 → null (回到「未报」, ⛔ 不写 0); 有效行里产出量缺失 / payload 读不出 /
 * 单位不一致 → null + WARN (⛔ 不拿部分合计冒充全量)。
 *
 * <p><b>状态</b>: 只在事件 {@code markCompleted} 时置 COMPLETED (沿用旧语义: 首行保存即完成);
 * 删除 / 撤回触发的重算不动状态。COMPLETED 的语义本次不改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTaskProgressWriter {

    private final WorkProcessTaskRepository taskRepository;
    private final ProcessSheetRowRepository rowRepository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stamp(WorkflowTaskProgressRequestedEvent event) {
        WorkProcessTask located = taskRepository
                .findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(
                        event.factoryId(), event.workflowInstanceId())
                .stream()
                .filter(candidate -> event.workflowNodeId().equals(candidate.getWorkflowNodeId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(409, "Workflow 工序任务不存在，无法回写报工进度")
                        .withCode("WORKFLOW_RUNTIME_TASK_NOT_FOUND")
                        .withHint("工序任务可能已被撤回或重建；刷新生产计划后重新提交这一行报工"));
        // 先锁任务行, 再读行: 并发的两次重算里后拿到锁的那次一定读得到双方已提交的行。
        WorkProcessTask task = taskRepository
                .findByFactoryIdAndIdForUpdate(event.factoryId(), located.getId())
                .orElseThrow(() -> new BusinessException(409, "Workflow 工序任务不存在，无法回写报工进度")
                        .withCode("WORKFLOW_RUNTIME_TASK_NOT_FOUND")
                        .withHint("工序任务可能已被撤回或重建；刷新生产计划后重新提交这一行报工"));
        // 🔴 上面那次不加锁的列表查询已经把这条任务放进一级缓存; 加锁查询虽然发了 FOR UPDATE,
        //    返回的却是缓存里那个【旧实例】, 不会用数据库新值覆盖它的字段。而 saveAndFlush 是全列 UPDATE
        //    (无 @Version / @DynamicUpdate) ⇒ 两次查询之间别人改过的 status 会被写回旧值。
        //    ⇒ 拿到锁之后从数据库重读一次。由 WorkflowTaskProgressWriterStaleStatusTest 钉住。
        entityManager.refresh(task);

        BigDecimal total = aggregatePrimaryOutput(event);
        task.setActualQuantity(total);
        if (event.markCompleted()) {
            task.setStatus(WorkProcessTask.Status.COMPLETED);
        }
        taskRepository.saveAndFlush(task);
        log.info("[workflow-progress] 重算工序产量: plan={}, node={}, processOrder={}, triggerRowQty={}, total={}, markCompleted={}",
                event.planId(), event.workflowNodeId(), event.processOrder(), event.actualQuantity(),
                total, event.markCompleted());
    }

    /** 该工序节点全部有效行的主产出合计; 没有有效行 / 读不全 / 单位不一 → null。 */
    BigDecimal aggregatePrimaryOutput(WorkflowTaskProgressRequestedEvent event) {
        List<ProcessSheetRow> rows = rowRepository.findByFactoryIdAndPlanId(event.factoryId(), event.planId());
        BigDecimal total = BigDecimal.ZERO;
        int counted = 0;
        Set<String> units = new LinkedHashSet<>();
        for (ProcessSheetRow row : rows) {
            if ((row.getProcessOrder() != null && !row.getProcessOrder().equals(event.processOrder()))
                    || !isEffective(row)) {
                continue;
            }
            ProcessSheetRowRequest payload = parse(row);
            if (payload == null) {
                log.warn("[workflow-progress] 行 payload 读不出, 本道产量记为未知: plan={}, rowId={}, clientRowId={}",
                        event.planId(), row.getId(), row.getClientRowId());
                return null;
            }
            Integer order = row.getProcessOrder() != null ? row.getProcessOrder() : payload.getProcessOrder();
            if (!Objects.equals(order, event.processOrder())
                    || !isPrimaryOutput(payload, event.primaryOutputPortId())) {
                continue;
            }
            if (payload.getOutputQuantity() == null) {
                log.warn("[workflow-progress] 有效行缺产出量, 本道产量记为未知: plan={}, rowId={}, clientRowId={}",
                        event.planId(), row.getId(), row.getClientRowId());
                return null;
            }
            total = total.add(payload.getOutputQuantity());
            counted++;
            String unit = firstNonBlank(payload.getOutputUnit(), payload.getUnit());
            units.add(unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT));
        }
        if (counted == 0) {
            return null;
        }
        if (units.size() > 1) {
            log.warn("[workflow-progress] 同一工序有效行单位不一致 {}, 不相加: plan={}, processOrder={}",
                    units, event.planId(), event.processOrder());
            return null;
        }
        return total;
    }

    private static boolean isEffective(ProcessSheetRow row) {
        return !"DRAFT".equals(row.getRowStatus())
                && !ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus());
    }

    private static boolean isPrimaryOutput(ProcessSheetRowRequest payload, String primaryOutputPortId) {
        boolean member = Boolean.TRUE.equals(payload.getMultiOutputMember());
        String port = payload.getWorkflowPortId();
        if (primaryOutputPortId == null) {
            return !member;
        }
        if (member) {
            return primaryOutputPortId.equals(port);
        }
        return port == null || port.isBlank() || primaryOutputPortId.equals(port);
    }

    private ProcessSheetRowRequest parse(ProcessSheetRow row) {
        if (row.getRowPayload() == null || row.getRowPayload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(row.getRowPayload(), ProcessSheetRowRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
