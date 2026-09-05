package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 一张生产计划的<b>主原料份额</b>（portId → 份额%）的唯一解析入口。
 *
 * <h3>为什么要抽出来（形态 D：同一个东西有两份，它一定会漂）</h3>
 *
 * <p>这段两级解析原本是 {@code FactoryMaterialRequisitionServiceImpl#resolveInputShares}
 * 里的一个 {@code private} 方法。缺料预警（{@code ProductionPlanServiceImpl#scanMaterialAdvisory}）
 * 也要用同一份份额 —— 复制过去就是第二份，而这段逻辑<b>正好是最容易漂的那一种</b>：
 * 它有两个数据源，且「什么时候读哪个」是隐含的时序条件。
 *
 * <h3>两个数据源，以及为什么两个都得要</h3>
 *
 * <ol>
 *   <li><b>已转批次且运行时实例已物化</b> ⇒ 读编译冻结列
 *       {@code workflow_task_ports.input_share_ratio}。与报工读的是同一列，
 *       编译器做过的归一化/钳位对下游承重。</li>
 *   <li><b>还没转批次</b> ⇒ 退回读计划钉住的那个修订的 {@code nodesJson}。</li>
 * </ol>
 *
 * <p>⚠️ 不能只读 ①。prod 只读探针（2026-09-02）：按计划生成的领料单 17 张里
 * <b>3 张先于首批次、2 张根本没批次</b> —— 只读编译列，那 5 张会静默退成
 * 「按实际领用」。而缺料预警比领料更早：它跑在<b>建计划的时候</b>，
 * 那一刻<b>必然</b>还没有批次，所以对它来说 ② 才是主路径，① 反而是例外。</p>
 *
 * <p>两条路都走 {@link InputShareRatios#parsePercent}，⛔ 不各自解析
 * —— 否则会出现「编译器拒了、这边照收」的漂移。</p>
 *
 * <h3>口径</h3>
 *
 * <p>份额是<b>相对量</b>：各端口之和不必等于 100，推算时按<b>参与的那几个端口</b>归一化。
 * 份额为 null 的端口既不被推、也不当分母。</p>
 *
 * <p>⛔ 解析不出来时返回<b>空表</b>，不返回「全 0」——
 * 0 会让下游算出「需要 0kg 主料」，那是个看起来很确定的假答案。
 * 空表让调用方能说出「这一项算不出来」，而那正是它该说的话。</p>
 */
@Slf4j
@Component
public class PlanInputShareResolver {

    @Autowired(required = false)
    private WorkflowClerkSheetService workflowClerkSheetService;

    @Autowired(required = false)
    private ProductProcessWorkflowRevisionRepository workflowRevisionRepository;

    /**
     * @return portId → 份额(%)。解析不到时返回空表（⛔ 不是 null，也不是全 0）。
     */
    public Map<String, BigDecimal> resolve(String factoryId, ProductionPlan plan) {
        if (factoryId == null || plan == null) {
            return Map.of();
        }

        // ① 已转批次 ⇒ 编译冻结列 (与报工同源)
        if (workflowClerkSheetService != null && plan.getId() != null) {
            Optional<Map<String, BigDecimal>> compiled =
                    workflowClerkSheetService.resolveCompiledInputShares(factoryId, plan.getId());
            if (compiled.isPresent()) {
                return compiled.get();
            }
        }

        // ② 还没转批次 ⇒ 读计划钉住的修订。建计划阶段走的是这一条。
        if (workflowRevisionRepository == null || plan.getSelectedWorkflowRevisionId() == null) {
            return Map.of();
        }
        return workflowRevisionRepository.findById(plan.getSelectedWorkflowRevisionId())
                .filter(rev -> factoryId.equals(rev.getFactoryId()))
                .map(rev -> InputShareRatios.fromNodesJson(rev.getNodesJson()))
                .orElse(Map.of());
    }

    /**
     * 把「计划要产出多少成品」摊给各个主原料端口。
     *
     * <pre>
     *   总投入   = 计划产出量 ÷ (标准出成率 / 100)
     *   某端口   = 总投入 × (该端口份额 ÷ 参与端口份额之和)
     * </pre>
     *
     * <p>⚠️ 归一化的分母是<b>传进来的这几个端口</b>的份额之和，不是 100 ——
     * 份额是相对量，见类注释。</p>
     *
     * @param plannedOutput  计划产出量
     * @param yieldRatePercent 标准出成率(%)。⛔ null 或 &lt;=0 时返回 null：
     *        没有标准就算不出投入量，这时候必须说「算不出」，不能拿产出量当投入量顶替。
     * @return portId → 需投入量；算不出时返回<b>空表</b>
     */
    public Map<String, BigDecimal> spreadToPorts(Map<String, BigDecimal> shares,
                                                 BigDecimal plannedOutput,
                                                 BigDecimal yieldRatePercent) {
        if (shares == null || shares.isEmpty()
                || plannedOutput == null || plannedOutput.signum() <= 0
                || yieldRatePercent == null || yieldRatePercent.signum() <= 0) {
            return Map.of();
        }
        BigDecimal shareSum = shares.values().stream()
                .filter(v -> v != null && v.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (shareSum.signum() <= 0) {
            return Map.of();
        }
        BigDecimal totalInput = plannedOutput.divide(
                yieldRatePercent.divide(new BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP),
                6, java.math.RoundingMode.HALF_UP);

        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : shares.entrySet()) {
            BigDecimal share = e.getValue();
            if (share == null || share.signum() <= 0) {
                continue;   // 份额为 null 的端口不参与 —— 既不被推, 也没当分母
            }
            out.put(e.getKey(), totalInput
                    .multiply(share)
                    .divide(shareSum, 6, java.math.RoundingMode.HALF_UP));
        }
        return out;
    }
}
