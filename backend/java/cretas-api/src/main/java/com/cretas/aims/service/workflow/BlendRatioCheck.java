package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.BlendRatioDeviation;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 缺口 06「汇合配比校验」—— 两个报工写入口 (web 逐道录入正式提交 / App 报工) 共用的<b>唯一</b>实现。
 *
 * <h3>规则 (Steve 2026-10-25 裁定, 不再问)</h3>
 * <p>一道工序投入了多个<b>带份额</b>的主原料 (画布 INPUT 端口 {@code inputShareRatio}) 时,
 * 按实际投料量算每种主料的实际份额; 任一主料偏离配方份额超过 ±{@link #DEFAULT_TOLERANCE_PP} 个百分点
 * (绝对差, 例: 配方 30% / 实际 32.5% = 2.5pp ⇒ 超), 必须带原因, 否则 409
 * {@link #ERROR_CODE}; 带原因直接放行 (不要主管审批)。偏差与原因落库留痕。</p>
 *
 * <h3>口径</h3>
 * <ul>
 *   <li>只有份额 &gt; 0 的端口参与 (与 {@link InputShareRatios#derive} 同口径: 份额 0 = 不参与推算);
 *       没份额的投入 (调料等) 既不进分子也不进分母。</li>
 *   <li>配方份额是<b>相对量</b>, 按<b>本次实际投了的</b>带份额端口归一化 —— 没投的端口 (替代料没选用)
 *       不进分母; 漏投是否允许由端口选择组规则决定, 不是本闸的事。⛔ 不看 {@code required}
 *       (prod 全部端口 required=false)。</li>
 *   <li>实际投入的带份额端口少于 2 个 ⇒ 不是「汇合」, 不校验。</li>
 *   <li>单位: 全部同一单位 ⇒ 直接按投料数比 (与报工表「填一个推其余」的预填同一口径);
 *       单位不同 ⇒ 经 {@link FgQuantityUnitConverter} 统一折成克 (计数单位用端口冻结克重);
 *       折不了 ⇒ <b>本次跳过校验并留痕 SKIPPED_UNITS</b>, ⛔ 不因为单位换算不了而拦报工。</li>
 * </ul>
 *
 * <p>容差现在是全厂默认 2pp; 以后要按产品覆盖, 只改 {@link #tolerancePp} 这一个查找点。</p>
 */
@Slf4j
@Component
public class BlendRatioCheck {

    public static final String ERROR_CODE = "BLEND_RATIO_REASON_REQUIRED";
    public static final String INVALID_REASON_CODE = "BLEND_RATIO_REASON_INVALID";
    /** 全厂默认容差 (百分点, 绝对差)。 */
    public static final BigDecimal DEFAULT_TOLERANCE_PP = new BigDecimal("2");
    public static final String REASON_OTHER = "其他";
    /** 标准原因 (前端下拉同一份; 改这里要同步 web blendRatio.ts / RN blendRatio.ts)。 */
    public static final List<String> STANDARD_REASONS =
            List.of("原料规格差异", "称重误差", "按主管口头调整", "原料不足", REASON_OTHER);

    public static final String STATUS_WITHIN = "WITHIN";
    public static final String STATUS_OVER_WITH_REASON = "OVER_WITH_REASON";
    public static final String STATUS_SKIPPED_UNITS = "SKIPPED_UNITS";
    /** evaluate 的中间态: 超差, 还没看原因。⛔ 不落库。 */
    static final String STATUS_OVER_PENDING = "OVER";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int NOTE_MAX = 200;

    @Autowired(required = false)
    private WorkflowTaskPortRepository taskPortRepository;

    @Autowired(required = false)
    private MaterialBatchRepository materialBatchRepository;

    @Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;

    /**
     * 工艺保密: 超差提示里的「配方 30%」就是配比。只有能看完整工艺的人 (或产品不受保护) 才在提示里给配方份额,
     * 其余人只看到料名、实际份额和偏高 / 偏低 —— 后端照常按真实份额裁决。只在真超差时才判 (免得每次报工都记审计)。
     * 产品识别不出 (多产出计划的锚点 {@code __...}) 时按不能看处理。
     */
    boolean revealRecipeShares(String factoryId, String productTypeId, BlendRatioDeviation evaluation) {
        if (evaluation == null || !STATUS_OVER_PENDING.equals(evaluation.getStatus())) return true;
        if (processSecretGuard == null) return true;
        if (productTypeId == null || productTypeId.startsWith("__")) {
            return !processSecretGuard.factoryHasProtectedProducts(factoryId);
        }
        return processSecretGuard.permitsProduct(factoryId, productTypeId,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL, "blend-ratio deviation message");
    }

    /** 带份额的投入端口 (纯数据, 便于两个写入口共用)。 */
    public record SharePort(String portId, String materialName, BigDecimal share, String unit,
                            BigDecimal gramsPerUnit) {
    }

    /** 一笔实际投料: 落在哪个端口、多少、什么单位。 */
    public record Fed(String portId, BigDecimal quantity, String unit) {
    }

    /**
     * 容差查找点 (百分点)。现在恒为全厂默认; 按产品覆盖时在这里接配置, 两个写入口自动生效。
     */
    public BigDecimal tolerancePp(String factoryId, String productTypeId) {
        return DEFAULT_TOLERANCE_PP;
    }

    // ------------------------------------------------------------------
    // 写入口 1: web 逐道录入正式提交 (ProcessSheetServiceImpl.submitRow → validateWorkflowSubmissionSelections)
    // ------------------------------------------------------------------

    /**
     * 端口已由 applyWorkflowInputPorts 归位 (workflowPortId / unit 都是端口的) 之后调用。
     * 结果写回 {@code req.blendRatio} (随 row_payload 落库); 不适用时清成 null, 客户端伪造的快照不作数。
     */
    public void enforceProcessSheet(String factoryId, String productTypeId,
                                    WorkflowClerkSheetConfigDTO.ProcessDescriptor descriptor,
                                    ProcessSheetRowRequest req) {
        if (descriptor == null || req == null || descriptor.getInputs() == null) {
            if (req != null) req.setBlendRatio(null);
            return;
        }
        List<SharePort> ports = descriptor.getInputs().stream()
                .filter(Objects::nonNull)
                .map(p -> new SharePort(p.getWorkflowPortId(), p.getMaterialName(), p.getInputShareRatio(),
                        p.getUnit(), p.getGramsPerUnit()))
                .toList();
        Map<String, String> portUnits = new LinkedHashMap<>();
        ports.forEach(p -> { if (p.portId() != null) portUnits.put(p.portId(), p.unit()); });

        List<Fed> fed = new ArrayList<>();
        if (req.getMaterialInputTotals() != null && !req.getMaterialInputTotals().isEmpty()) {
            for (ProcessSheetRowRequest.MaterialInputTotal in : req.getMaterialInputTotals()) {
                if (in == null) continue;
                fed.add(new Fed(in.getWorkflowPortId(), in.getQuantity(),
                        firstNonBlank(in.getUnit(), portUnits.get(in.getWorkflowPortId()))));
            }
        } else if (req.getRawMaterialInputs() != null) {
            for (ProcessSheetRowRequest.RawInput in : req.getRawMaterialInputs()) {
                if (in == null || Boolean.TRUE.equals(in.getAutomatic())) continue;   // 包材/调料自动分摊不是主料
                fed.add(new Fed(in.getWorkflowPortId(), in.getQuantity(),
                        firstNonBlank(in.getUnit(), portUnits.get(in.getWorkflowPortId()))));
            }
        }
        if (req.getUpstreamSources() != null) {
            for (ProcessSheetRowRequest.UpstreamRef in : req.getUpstreamSources()) {
                if (in == null || in.isAutomaticRework()) continue;              // 自动回料不是端口投入
                // feedQuantityKg 名字带 Kg, 实际是端口单位的投料量 (前端按 inputUnit 展示, 后端按端口单位校验)
                fed.add(new Fed(in.getWorkflowPortId(), in.getFeedQuantityKg(), portUnits.get(in.getWorkflowPortId())));
            }
        }
        BlendRatioDeviation evaluation = evaluate(ports, fed, tolerancePp(factoryId, productTypeId));
        if (evaluation != null && STATUS_SKIPPED_UNITS.equals(evaluation.getStatus())) {
            log.warn("[blend-ratio] 跳过配比校验 factory={} processOrder={} clientRowId={}: {}",
                    factoryId, req.getProcessOrder(), req.getClientRowId(), evaluation.getSkipReason());
        }
        req.setBlendRatio(enforce(evaluation, req.getBlendRatio(),
                revealRecipeShares(factoryId, productTypeId, evaluation)));
    }

    // ------------------------------------------------------------------
    // 写入口 2: App 报工 (YieldReportServiceImpl.submitReport, 落库前)
    // ------------------------------------------------------------------

    /**
     * App 报工的主料投料 = 领料批次引用 (已由 TaskMaterialBatchPickerService 校验, 数量是批次原生单位);
     * 批次的物料类型对上端口 skuId 就是这个端口的投料。快照并入 {@code report.customFields.blendRatio}。
     */
    public void enforceYieldReport(String factoryId, WorkProcessTask task, List<MaterialBatchRef> refs,
                                   BlendRatioDeviation submitted, ProductionReport report) {
        if (task == null || task.getWorkflowInstanceId() == null || refs == null || refs.size() < 2
                || taskPortRepository == null || materialBatchRepository == null || report == null) {
            return;
        }
        List<WorkflowTaskPort> sharePorts = taskPortRepository
                .findByFactoryIdAndWorkflowInstanceId(factoryId, task.getWorkflowInstanceId()).stream()
                .filter(p -> Objects.equals(task.getId(), p.getTaskId()))
                .filter(p -> p.getDirection() == WorkflowTaskPort.Direction.INPUT)
                .filter(p -> p.getInputShareRatio() != null && p.getInputShareRatio().signum() > 0)
                .filter(p -> p.getSkuId() != null && !p.getSkuId().isBlank())
                .toList();
        if (sharePorts.size() < 2) {
            return;   // 不足两个带份额的端口, 不可能是汇合 —— 也省掉批次查询
        }
        Map<String, WorkflowTaskPort> portBySku = new LinkedHashMap<>();
        sharePorts.forEach(p -> portBySku.putIfAbsent(p.getSkuId(), p));
        Map<String, String> names = new LinkedHashMap<>();
        List<Fed> fed = new ArrayList<>();
        for (MaterialBatchRef ref : refs) {
            if (ref == null || ref.getMaterialBatchId() == null) continue;
            MaterialBatch batch = materialBatchRepository
                    .findByIdAndFactoryId(ref.getMaterialBatchId(), factoryId).orElse(null);
            if (batch == null) continue;
            WorkflowTaskPort port = portBySku.get(batch.getMaterialTypeId());
            if (port == null) continue;
            if (batch.getMaterialType() != null && batch.getMaterialType().getName() != null) {
                names.putIfAbsent(port.getWorkflowPortId(), batch.getMaterialType().getName());
            }
            fed.add(new Fed(port.getWorkflowPortId(), ref.getQuantity(),
                    firstNonBlank(ref.getUnit(), batch.getQuantityUnit(), port.getUnit())));
        }
        List<SharePort> ports = sharePorts.stream()
                .map(p -> new SharePort(p.getWorkflowPortId(),
                        names.getOrDefault(p.getWorkflowPortId(), p.getSkuId()),
                        p.getInputShareRatio(), p.getUnit(), p.getNetWeightGramsSnapshot()))
                .toList();
        BlendRatioDeviation evaluation = evaluate(ports, fed, tolerancePp(factoryId, task.getProductTypeId()));
        if (evaluation != null && STATUS_SKIPPED_UNITS.equals(evaluation.getStatus())) {
            log.warn("[blend-ratio] 跳过配比校验 factory={} task={}: {}",
                    factoryId, task.getId(), evaluation.getSkipReason());
        }
        BlendRatioDeviation snapshot = enforce(evaluation, submitted,
                revealRecipeShares(factoryId, task.getProductTypeId(), evaluation));
        if (snapshot == null) {
            return;
        }
        Map<String, Object> fields = report.getCustomFields() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(report.getCustomFields());
        fields.put("blendRatio", toAuditMap(snapshot));
        report.setCustomFields(fields);
    }

    // ------------------------------------------------------------------
    // 纯计算
    // ------------------------------------------------------------------

    /**
     * 算配比。返回 null = 本次不适用 (投了的带份额端口不足 2 个);
     * status = SKIPPED_UNITS = 单位统一不了; 否则 WITHIN / OVER(待看原因)。
     */
    public static BlendRatioDeviation evaluate(List<SharePort> ports, List<Fed> fed, BigDecimal tolerancePp) {
        if (ports == null || fed == null || tolerancePp == null) {
            return null;
        }
        Map<String, SharePort> shareById = new LinkedHashMap<>();
        for (SharePort p : ports) {
            if (p != null && p.portId() != null && p.share() != null && p.share().signum() > 0) {
                shareById.putIfAbsent(p.portId(), p);
            }
        }
        List<Fed> relevant = fed.stream()
                .filter(Objects::nonNull)
                .filter(f -> f.portId() != null && shareById.containsKey(f.portId()))
                .filter(f -> f.quantity() != null && f.quantity().signum() > 0)
                .toList();
        Set<String> fedPorts = relevant.stream().map(Fed::portId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (fedPorts.size() < 2) {
            return null;
        }

        // 口径: 同一单位 ⇒ 原数; 否则统一折成克, 折不了就跳过
        Set<String> codes = relevant.stream()
                .map(f -> unitCode(f.unit()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String basis;
        Map<String, BigDecimal> qtyByPort = new LinkedHashMap<>();
        if (codes.size() == 1 && !codes.contains("")) {
            basis = displayUnit(relevant.get(0).unit());
            for (Fed f : relevant) qtyByPort.merge(f.portId(), f.quantity(), BigDecimal::add);
        } else {
            basis = "g";
            for (Fed f : relevant) {
                SharePort port = shareById.get(f.portId());
                BigDecimal grams = f.unit() == null || f.unit().isBlank() ? null
                        : FgQuantityUnitConverter.convert(f.quantity(), UnitContractServiceImpl.crossLanguageCode(f.unit()),
                                "g", port.gramsPerUnit());
                if (grams == null) {
                    return BlendRatioDeviation.builder()
                            .status(STATUS_SKIPPED_UNITS)
                            .tolerancePp(tolerancePp)
                            .skipReason("「" + nameOf(port) + "」的单位 " + f.unit()
                                    + " 无法与其他主料统一折算 (单位: " + String.join("/", codes) + ")")
                            .build();
                }
                qtyByPort.merge(f.portId(), grams, BigDecimal::add);
            }
        }

        BigDecimal totalQty = qtyByPort.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalShare = fedPorts.stream().map(id -> shareById.get(id).share())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalQty.signum() <= 0 || totalShare.signum() <= 0) {
            return null;
        }
        List<BlendRatioDeviation.Line> lines = new ArrayList<>();
        BigDecimal maxDev = BigDecimal.ZERO;
        boolean anyOver = false;
        for (String portId : fedPorts) {
            SharePort port = shareById.get(portId);
            BigDecimal qty = qtyByPort.get(portId);
            BigDecimal recipe = port.share().multiply(HUNDRED).divide(totalShare, 6, RoundingMode.HALF_UP);
            BigDecimal actual = qty.multiply(HUNDRED).divide(totalQty, 6, RoundingMode.HALF_UP);
            BigDecimal deviation = actual.subtract(recipe);
            boolean over = deviation.abs().compareTo(tolerancePp) > 0;
            anyOver |= over;
            if (deviation.abs().compareTo(maxDev) > 0) maxDev = deviation.abs();
            lines.add(BlendRatioDeviation.Line.builder()
                    .workflowPortId(portId)
                    .materialName(nameOf(port))
                    .quantity(qty.stripTrailingZeros())
                    .recipeSharePercent(recipe.setScale(2, RoundingMode.HALF_UP))
                    .actualSharePercent(actual.setScale(2, RoundingMode.HALF_UP))
                    .deviationPp(deviation.setScale(2, RoundingMode.HALF_UP))
                    .overTolerance(over)
                    .build());
        }
        return BlendRatioDeviation.builder()
                .status(anyOver ? STATUS_OVER_PENDING : STATUS_WITHIN)
                .tolerancePp(tolerancePp)
                .basis(basis)
                .maxDeviationPp(maxDev.setScale(2, RoundingMode.HALF_UP))
                .lines(lines)
                .build();
    }

    /**
     * 看原因: 超差没原因 ⇒ 409 {@link #ERROR_CODE}; 有原因 ⇒ 返回带原因的快照 (落库用)。
     * 不适用 ⇒ null; 跳过 / 容差内 ⇒ 原样返回快照 (容差内不留原因, 免得审计里出现没必要的原因)。
     */
    public static BlendRatioDeviation enforce(BlendRatioDeviation evaluation, BlendRatioDeviation submitted) {
        return enforce(evaluation, submitted, true);
    }

    /**
     * @param revealRecipeShares false = 工艺保密且调用者无完整查看权: 提示里不给配方份额和偏差百分点,
     *                           只给料名、实际份额和偏高 / 偏低 (裁决本身不变)
     */
    public static BlendRatioDeviation enforce(BlendRatioDeviation evaluation, BlendRatioDeviation submitted,
                                              boolean revealRecipeShares) {
        if (evaluation == null) {
            return null;
        }
        if (!STATUS_OVER_PENDING.equals(evaluation.getStatus())) {
            evaluation.setReason(null);
            evaluation.setNote(null);
            return evaluation;
        }
        String reason = submitted == null || submitted.getReason() == null ? "" : submitted.getReason().trim();
        String note = submitted == null || submitted.getNote() == null ? "" : submitted.getNote().trim();
        String tol = plain(evaluation.getTolerancePp());
        String overText = evaluation.getLines().stream()
                .filter(BlendRatioDeviation.Line::isOverTolerance)
                .map(l -> revealRecipeShares
                        ? "「" + l.getMaterialName() + "」配方 " + pct(l.getRecipeSharePercent())
                                + " / 实际 " + pct(l.getActualSharePercent())
                                + "（" + signed(l.getDeviationPp()) + " 个百分点）"
                        : "「" + l.getMaterialName() + "」实际 " + pct(l.getActualSharePercent())
                                + (l.getDeviationPp() != null && l.getDeviationPp().signum() > 0 ? "，偏高" : "，偏低"))
                .collect(Collectors.joining("；"));
        if (reason.isEmpty()) {
            throw new BusinessException(409, "主料配比偏差超过 ±" + tol + " 个百分点：" + overText + "，必须填写偏差原因")
                    .withCode(ERROR_CODE)
                    .withHint("请先核对各主料投料量；确实按此配比投料的，在「配比核对」里选择偏差原因（选「其他」需写明）后再正式报工")
                    .withHintTarget("配比偏差原因")
                    .withSeverity("BLOCKING");
        }
        if (!STANDARD_REASONS.contains(reason)) {
            throw new BusinessException(400, "配比偏差原因「" + reason + "」不在可选范围内")
                    .withCode(INVALID_REASON_CODE)
                    .withHint("请从下拉中选择：" + String.join(" / ", STANDARD_REASONS))
                    .withHintTarget("配比偏差原因")
                    .withSeverity("BLOCKING");
        }
        if (REASON_OTHER.equals(reason) && note.isEmpty()) {
            throw new BusinessException(409, "配比偏差原因选了「其他」，请写明具体原因：" + overText)
                    .withCode(ERROR_CODE)
                    .withHint("在「配比核对」的说明框里写明为什么按这个配比投料")
                    .withHintTarget("配比偏差原因")
                    .withSeverity("BLOCKING");
        }
        if (note.length() > NOTE_MAX) {
            note = note.substring(0, NOTE_MAX);
        }
        evaluation.setStatus(STATUS_OVER_WITH_REASON);
        evaluation.setReason(reason);
        evaluation.setNote(note.isEmpty() ? null : note);
        return evaluation;
    }

    /** App 报工 custom_fields(jsonb) 用的纯 Map 形状 (不依赖 ObjectMapper 配置)。 */
    public static Map<String, Object> toAuditMap(BlendRatioDeviation d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", d.getStatus());
        out.put("tolerancePp", d.getTolerancePp());
        if (d.getBasis() != null) out.put("basis", d.getBasis());
        if (d.getMaxDeviationPp() != null) out.put("maxDeviationPp", d.getMaxDeviationPp());
        if (d.getSkipReason() != null) out.put("skipReason", d.getSkipReason());
        if (d.getReason() != null) out.put("reason", d.getReason());
        if (d.getNote() != null) out.put("note", d.getNote());
        if (d.getLines() != null) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (BlendRatioDeviation.Line l : d.getLines()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("workflowPortId", l.getWorkflowPortId());
                m.put("materialName", l.getMaterialName());
                m.put("quantity", l.getQuantity());
                m.put("recipeSharePercent", l.getRecipeSharePercent());
                m.put("actualSharePercent", l.getActualSharePercent());
                m.put("deviationPp", l.getDeviationPp());
                m.put("overTolerance", l.isOverTolerance());
                lines.add(m);
            }
            out.put("lines", lines);
        }
        return out;
    }

    // ------------------------------------------------------------------

    private static String unitCode(String unit) {
        if (unit == null || unit.isBlank()) return "";
        String code = UnitContractServiceImpl.crossLanguageCode(unit);
        return code == null ? "" : code;
    }

    private static String displayUnit(String unit) {
        return unit == null ? "" : unit.trim();
    }

    private static String nameOf(SharePort port) {
        return port.materialName() == null || port.materialName().isBlank() ? port.portId() : port.materialName();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    private static String pct(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private static String signed(BigDecimal v) {
        String s = v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return v.signum() > 0 ? "+" + s : s;
    }
}
