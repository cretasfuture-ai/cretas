package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.cretas.aims.service.yield.YieldReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 缺口 13 (审查中 7): CCP 的<b>服务端兜底</b> —— 报工与 CCP 实测值同一事务。
 *
 * <p>原先 CCP 是「报工成功之后前端再单独提交一次」: 前端漏调、网络断在两次请求之间、或有人直接调报工 API,
 * 产出就带着「这道是关键控制点却没有任何实测值」的状态变成可用。现在两个报工入口
 * (App 逐道报工 / web 逐工序表正式提交) 都先过这里:
 * <ol>
 *   <li>这一道挂着启用的 CCP 且本次报工有产出 ⇒ 请求里必须带齐实测值, 少一项 400
 *       ({@link CcpMonitoringService#CODE_MISSING}), <b>一行都不落</b> (产出不入账);</li>
 *   <li>带齐了 ⇒ 报工落库后在<b>同一事务</b>里落 CCP 记录 (报工凭据 reportRef 去重, 重放不重记);
 *       超限 ⇒ 偏离 + 当场把这一批/计划的产出 (含刚入账的这笔) 扣成待检; 任一步失败整笔回滚。</li>
 * </ol>
 * 没挂 CCP 的工序、不产出的报工 (领料 / 工时段 / 草稿) 原样透传, 零回归。
 *
 * <p>选这条路而不是「缺 CCP 时把产出扣成待检」: 扣待检会让一条本该在报工现场补录的记录变成质检员的待办,
 * 而且「没录」和「录了超限」在待检单上长得一样; 直接拒绝时工人当场就知道缺哪一项 (提示里列出名称和限值)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcpReportFacade {

    public static final String REF_YIELD_PREFIX = "YR:";
    public static final String REF_SHEET_PREFIX = "PS:";

    private final YieldReportService yieldReportService;
    private final ProcessSheetService processSheetService;
    private final CcpMonitoringService ccpMonitoringService;
    private final WorkProcessTaskRepository taskRepository;
    private final WorkflowClerkSheetService workflowClerkSheetService;
    /** S3-16: 随行提交的锅次与报工同一事务落库 (可选注入: 独立构造本类的单测不受影响)。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.pot.ProcessSheetPotHook potHook;

    // ==================== App: 逐道报工 ====================

    @Transactional
    public Map<String, Object> submitYieldReport(String factoryId, Long batchId, Long workerId, String workerName,
                                                 YieldReportRequest req) {
        String workProcessId = producesOutput(req) ? appWorkProcessId(factoryId, req) : null;
        boolean ccpStep = workProcessId != null
                && !ccpMonitoringService.validateReadings(factoryId, workProcessId, req.getCcpReadings()).isEmpty();
        Map<String, Object> out = yieldReportService.submitReport(factoryId, batchId, workerId, req);
        if (!ccpStep) {
            return out;
        }
        Object reportId = out == null ? null : out.get("reportId");
        if (reportId == null) {
            throw new IllegalStateException("报工结果缺少 reportId, 无法关联 CCP 记录");
        }
        WorkProcessTask task = taskRepository.findByFactoryIdAndId(factoryId, req.getWorkProcessTaskId()).orElse(null);
        CcpMonitoringService.RecordResult ccp = ccpMonitoringService.record(factoryId,
                new CcpMonitoringService.RecordCommand(null, batchId, null, workProcessId,
                        task == null ? null : task.getProcessOrder(), CcpMonitoringService.SOURCE_APP,
                        req.getCcpReadings()),
                workerId, workerName, REF_YIELD_PREFIX + reportId);
        Map<String, Object> merged = new LinkedHashMap<>(out);
        merged.put("ccp", ccpSummary(ccp));
        return merged;
    }

    /** App 报工有没有产出: 产出阶段 / 旧式整合报工, 且产出量或任一半成品产出 > 0。 */
    static boolean producesOutput(YieldReportRequest req) {
        if (req == null) {
            return false;
        }
        String kind = req.getReportKind() == null ? null : req.getReportKind().trim().toUpperCase();
        if (kind != null && !kind.isEmpty() && !"OUTPUT".equals(kind)) {
            return false;
        }
        if (positive(req.getOutputQuantity()) || positive(req.getSemiOutputQuantity())) {
            return true;
        }
        return req.getSemiOutputs() != null
                && req.getSemiOutputs().stream().anyMatch(s -> s != null && positive(s.getQuantity()));
    }

    private String appWorkProcessId(String factoryId, YieldReportRequest req) {
        if (req.getWorkProcessTaskId() == null) {
            return null;   // 报工服务会以 400 拒绝, 这里不抢先报别的错
        }
        return taskRepository.findByFactoryIdAndId(factoryId, req.getWorkProcessTaskId())
                .map(WorkProcessTask::getWorkProcessId)
                .orElse(null);
    }

    // ==================== web: 逐工序表 ====================

    @Transactional
    public ProcessSheetRowResult submitProcessSheetRow(String factoryId, String planId, Long userId,
                                                       String userName, ProcessSheetRowRequest req) {
        String workProcessId = rowProducesOutput(req) ? sheetWorkProcessId(factoryId, planId, req) : null;
        boolean ccpStep = workProcessId != null
                && !ccpMonitoringService.validateReadings(factoryId, workProcessId, req.getCcpReadings()).isEmpty();
        if (potHook != null) {
            potHook.validateShape(req);
        }
        ProcessSheetRowResult result = processSheetService.submitRow(factoryId, planId, req, userId);
        if (potHook != null) {
            result = potHook.record(factoryId, planId, req, result, userId, userName);
        }
        // 正式提交的重放由 submitRow 自己挡 (已提交的行再提交 409, 整个事务连同这里的 CCP 一起回滚),
        // 所以这里不再按时间窗去重: 撤销后重新提交同一行, 新读数必须照记 (第二轮审查 H1)。
        return ccpStep ? recordSheetCcp(factoryId, planId, userId, userName, req, workProcessId, result, null)
                : result;
    }

    /** 旧契约 saveRow (upsert 即入账): 同样兜底, 不让它成为绕过 CCP 的后门。 */
    @Transactional
    public ProcessSheetRowResult saveProcessSheetRow(String factoryId, String planId, Long userId,
                                                     String userName, ProcessSheetRowRequest req) {
        String workProcessId = rowProducesOutput(req) ? sheetWorkProcessId(factoryId, planId, req) : null;
        boolean ccpStep = workProcessId != null
                && !ccpMonitoringService.validateReadings(factoryId, workProcessId, req.getCcpReadings()).isEmpty();
        if (potHook != null) {
            potHook.validateShape(req);
        }
        ProcessSheetRowResult result = processSheetService.saveRow(factoryId, planId, req, userId);
        if (potHook != null) {
            result = potHook.record(factoryId, planId, req, result, userId, userName);
        }
        // 旧契约 saveRow 是幂等 upsert, 重放不会被拒 ⇒ 另按时间窗挡网络重放
        return ccpStep ? recordSheetCcp(factoryId, planId, userId, userName, req, workProcessId, result,
                sheetRowScope(planId, req))
                : result;
    }

    private ProcessSheetRowResult recordSheetCcp(String factoryId, String planId, Long userId, String userName,
                                                 ProcessSheetRowRequest req, String workProcessId,
                                                 ProcessSheetRowResult result, String windowDedupScope) {
        String batchNumber = result == null ? null : result.getBatchNumber();
        Long pbId = result == null ? null : result.getBatchId();
        if (batchNumber == null && result != null && result.getOutputs() != null) {
            for (ProcessSheetRowResult.OutputResult o : result.getOutputs()) {
                if (o != null && o.getBatchNumber() != null) {
                    batchNumber = o.getBatchNumber();
                    pbId = o.getBatchId();
                    break;
                }
            }
        }
        if (batchNumber == null) {
            batchNumber = req.getBatchNumber();
        }
        String ref = sheetSubmissionRef(planId, req);
        CcpMonitoringService.RecordResult ccp = ccpMonitoringService.record(factoryId,
                new CcpMonitoringService.RecordCommand(batchNumber, pbId, planId, workProcessId,
                        req.getProcessOrder(), CcpMonitoringService.SOURCE_WEB, req.getCcpReadings()),
                userId, userName, ref, windowDedupScope);
        if (result == null) {
            return null;
        }
        if (ccp.deviationCount() > 0) {
            List<String> warnings = result.getWarnings() == null
                    ? new ArrayList<>() : new ArrayList<>(result.getWarnings());
            warnings.add(ccp.message());
            result.setWarnings(warnings);
        }
        return result;
    }

    /**
     * 逐工序表这一次提交的凭据 (第二轮审查 H1): 计划 + 行 + <b>每次提交一个新值</b>。
     * 旧版只有「计划 + 行」, 而撤销小结后行退回草稿、clientRowId 不变, 再次正式提交时新的实测值
     * 会被当成「同一次报工的重放」丢掉 —— 上一轮在限内、这一轮超限, 偏离不记、产出不扣待检。
     * 前端带了 idempotencyKey (同一次点击的重试共用) 就用它, 否则服务端生成。
     */
    static String sheetSubmissionRef(String planId, ProcessSheetRowRequest req) {
        String submission = req.getIdempotencyKey() == null || req.getIdempotencyKey().isBlank()
                ? java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : req.getIdempotencyKey().trim();
        String ref = sheetRowScope(planId, req) + submission;
        return ref.length() > 160 ? ref.substring(0, 160) : ref;
    }

    /** 同一计划同一行的凭据前缀 {@code PS:计划:行:} (saveRow 时间窗去重只在这一行内认重放, L-5)。 */
    static String sheetRowScope(String planId, ProcessSheetRowRequest req) {
        return REF_SHEET_PREFIX + planId + ":" + Objects.toString(req.getClientRowId(), "-") + ":";
    }

    /** web 行有没有产出: 单产出量 > 0, 或多产出任一端口 > 0。 */
    static boolean rowProducesOutput(ProcessSheetRowRequest req) {
        if (req == null) {
            return false;
        }
        if (positive(req.getOutputQuantity())) {
            return true;
        }
        return req.getOutputs() != null
                && req.getOutputs().stream().anyMatch(o -> o != null && positive(o.getQuantity()));
    }

    /**
     * 这一行是哪道工序: 与录入表同一份投影 (Workflow 录入配置按 processOrder 取 workProcessId),
     * 前端显示的 CCP 与这里要求的 CCP 是同一个来源。legacy (非 Workflow) 计划没有工序身份 ⇒ null。
     */
    private String sheetWorkProcessId(String factoryId, String planId, ProcessSheetRowRequest req) {
        if (req.getProcessOrder() == null) {
            return null;
        }
        WorkflowClerkSheetConfigDTO config = workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId);
        if (config == null || config.getProcesses() == null) {
            return null;
        }
        return config.getProcesses().stream()
                .filter(p -> p != null && req.getProcessOrder().equals(p.getProcessOrder()))
                .map(WorkflowClerkSheetConfigDTO.ProcessDescriptor::getWorkProcessId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // ==================== 内部 ====================

    private static Map<String, Object> ccpSummary(CcpMonitoringService.RecordResult ccp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviationCount", ccp.deviationCount());
        m.put("heldOutputs", ccp.heldOutputs());
        m.put("recordCount", ccp.records() == null ? 0 : ccp.records().size());
        m.put("message", ccp.message());
        return m;
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }
}
