package com.cretas.aims.service.pot;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.entity.pot.ProductionPot;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.pot.ProductionPotRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * S3-16: 逐工序报工 (web 逐工序表 saveRow / submitRow) 随行提交的锅次, 与报工<b>同一事务</b>落库
 * (由 CcpReportFacade 在报工之后调用; 任一锅不合法 ⇒ 整笔报工回滚, 不出现「报工成功、锅次丢了」)。
 *
 * <p>请求里没有 pots ⇒ 原样返回, 零回归。
 */
@Component
@RequiredArgsConstructor
public class ProcessSheetPotHook {

    private final ProductionPotService potService;
    private final ProductionPotRepository potRepository;
    private final ProcessSheetRowRepository rowRepository;
    private final PotNodeIndex nodeIndex;

    /** 报工前的形状校验 (不碰库): 多产出行不收锅次; 锅号不能重复。 */
    public void validateShape(ProcessSheetRowRequest req) {
        List<ProcessSheetPotEntry> pots = req == null ? null : req.getPots();
        if (pots == null || pots.isEmpty()) {
            return;
        }
        if (req.getOutputs() != null && req.getOutputs().size() > 1) {
            throw ProductionPotService.invalid("多产出报工暂不支持随行录入锅次")
                    .withHint("请先报工, 再在生产批次详情的「锅次」里逐锅补录");
        }
        Set<Integer> seen = new TreeSet<>();
        for (ProcessSheetPotEntry p : pots) {
            if (p == null || p.getPotNumber() == null || p.getPotNumber() <= 0) {
                throw ProductionPotService.invalid("锅号必须是正整数").withHintTarget("pots");
            }
            if (!seen.add(p.getPotNumber())) {
                throw ProductionPotService.invalid("锅号重复: 第 " + p.getPotNumber() + " 锅").withHintTarget("pots");
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProcessSheetRowResult record(String factoryId, String planId, ProcessSheetRowRequest req,
                                        ProcessSheetRowResult result, Long userId, String userName) {
        List<ProcessSheetPotEntry> pots = req == null ? null : req.getPots();
        if (pots == null || pots.isEmpty() || result == null) {
            return result;
        }
        validateShape(req);
        Long pbId = result.getBatchId();
        if (pbId == null && result.getOutputs() != null && result.getOutputs().size() == 1) {
            pbId = result.getOutputs().get(0).getBatchId();
        }
        if (pbId == null) {
            throw ProductionPotService.invalid("这一行报工没有产生生产批次, 不能记录锅次")
                    .withHint("锅次要挂在有产出的报工上");
        }
        Long rowId = rowRepository.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                        factoryId, planId, req.getProcessCode(), req.getClientRowId())
                .map(r -> r.getId()).orElse(null);

        // 旧契约 saveRow 是 upsert: 同一行同一组锅号的重放不重记; 锅号不同 ⇒ 请走更正
        if (rowId != null) {
            List<ProductionPot> existing = potRepository.findByFactoryIdAndProcessSheetRowId(factoryId, rowId).stream()
                    .filter(p -> !p.isVoided()).toList();
            if (!existing.isEmpty()) {
                Set<Integer> before = existing.stream().map(ProductionPot::getPotNumber).collect(Collectors.toCollection(TreeSet::new));
                Set<Integer> now = pots.stream().map(ProcessSheetPotEntry::getPotNumber).collect(Collectors.toCollection(TreeSet::new));
                if (before.equals(now)) {
                    return result;
                }
                throw new BusinessException(409, "这一行报工已经录过锅次")
                        .withCode(ProductionPotService.CODE_DUPLICATE_NUMBER)
                        .withHint("已录的锅请在生产批次详情的「锅次」里更正或作废, 不要在报工里重录");
            }
        }

        // 产出: 这一行唯一的产出批次 (多个产出节点时不分摊, 由主管在批次详情补)
        Map<String, PotNodeIndex.BatchNode> outNodes = nodeIndex.outputsOf(factoryId, pbId);
        PotNodeIndex.BatchNode onlyOutput = outNodes.size() == 1 ? outNodes.values().iterator().next() : null;

        List<String> warnings = result.getWarnings() == null ? new ArrayList<>() : new ArrayList<>(result.getWarnings());
        int unallocatedInputs = 0;
        for (ProcessSheetPotEntry p : pots) {
            List<ProductionPotService.PotLink> inputs = p.getInputs() == null ? List.of() : p.getInputs().stream()
                    .filter(Objects::nonNull)
                    .map(l -> new ProductionPotService.PotLink(
                            l.nodeType() == null ? LineageEdgeRecorder.NODE_MATERIAL_BATCH : l.nodeType(),
                            l.nodeId(), l.quantity(), l.unit()))
                    .toList();
            if (inputs.isEmpty()) {
                unallocatedInputs++;
            }
            List<ProductionPotService.PotLink> outputs = List.of();
            if (onlyOutput != null && p.getOutputQuantity() != null && p.getOutputQuantity().signum() > 0) {
                outputs = List.of(new ProductionPotService.PotLink(onlyOutput.type(), onlyOutput.id(),
                        p.getOutputQuantity(), onlyOutput.unit()));
            }
            potService.record(factoryId, new ProductionPotService.PotCommand(
                    pbId, planId, rowId, req.getProcessCode(), req.getProcessName(), p.getPotNumber(),
                    p.getEquipmentLabel(), p.getStartedAt(), p.getEndedAt(), p.getTimeSource(), p.getBindingStatus(),
                    userId, p.getOperatorName() != null ? p.getOperatorName() : userName,
                    p.getOutputQuantity(), p.getOutputUnit() != null ? p.getOutputUnit() : req.getUnit(),
                    p.getProcessParams(), inputs, outputs, p.getRemark()), userId);
        }
        if (unallocatedInputs > 0) {
            warnings.add("有 " + unallocatedInputs + " 锅没有填写用了哪批料: 召回时这一批按整批算, 可在批次详情补录");
        }
        result.setWarnings(warnings);
        return result;
    }
}
