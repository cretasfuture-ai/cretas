package com.cretas.aims.dto.yield;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * SP1 T4 — Output options for a production batch's report screen.
 *
 * <p>The RN report screen calls
 * {@code GET /api/mobile/{factoryId}/processing/batches/{batchId}/output-options}
 * to populate the "semi output code" dropdown when {@code outputKind=SEMI|BOTH}.
 *
 * <p>Items are assembled from <b>two</b> independent sources in
 * {@code WipInventoryServiceImpl.getOutputOptions} (union, not either/or):
 * <ul>
 *   <li><b>Legacy (工序管理) tasks</b>: one item per {@code WorkProcessTask} whose parent
 *       {@code WorkProcess.semiFinishedOutputCode} is configured (non-blank).</li>
 *   <li><b>Workflow (画布) tasks</b>: {@code WorkProcess.semiFinishedOutputCode} is never
 *       populated for these — the canvas expresses output structure via
 *       {@code workflow_task_ports} instead. One item per port with
 *       {@code direction=OUTPUT, materialKind=SEMI_FINISHED} that is not flagged
 *       {@code data.isByproduct} on the canvas (see {@code WorkflowByproductNodes}); a single
 *       task can legitimately contribute more than one item (2B.2 multi-output). {@code semiCode}
 *       is the resolved {@code ProductType.code} (e.g. {@code PTSEMI-F006-2001}), not the raw
 *       {@code skuId} UUID. 回料端口 (画布 {@code data.isRework} 标记, 见 {@code WorkflowReworkNodes})
 *       与副产恰好相反 —— 刻意<b>保留</b>并在 {@link OutputOptionItem#getIsRework()} 上打标记, 不过滤掉:
 *       金检/X光剔出的不良品仍要能选成产出目标, 回料链路才走得通。</li>
 * </ul>
 */
@Data
@Builder
public class OutputOptionsResponse {

    /** All tasks for this batch that have a configured semi output code. */
    private List<OutputOptionItem> items;

    @Data
    @Builder
    public static class OutputOptionItem {

        /** WorkProcessTask ID (used to bind the report to a specific task). */
        private Long taskId;

        /** Process/step name, shown to the operator as the label. */
        private String processName;

        /**
         * The semi-finished batch code that will be used as the ledger key
         * ({@code SemiFinishedInventory.intermediateBatchNo}). Legacy tasks: this is
         * {@code WorkProcess.semiFinishedOutputCode}. Workflow tasks: this is the semi-finished
         * product's {@code ProductType.code} (human-readable, unique per factory).
         */
        private String semiCode;

        /** Process order (1-based), for display ordering in the dropdown. */
        private Integer processOrder;

        /**
         * 这一项是不是回料 (金检/X光 剔出的不良品去处)。
         *
         * <p>{@code true} 时报工屏把这一栏显示成「不良品数量」而不是「半成品产出量」,
         * 并把填的数同时回填 {@code ProductionReport.defectQuantity} (质量账)。
         *
         * <p>判据唯一来源 {@link com.cretas.aims.service.workflow.WorkflowReworkNodes}。
         * 旧版 App 不认这个字段, 会把回料当普通半成品产出项渲染 —— 物料账仍然对,
         * 只是少了「不良品」这个措辞。这是刻意的向后兼容, 不是缺陷。
         */
        private Boolean isRework;

        /**
         * 这一项是不是不良品 (剔出来、还没复查的那部分)。
         *
         * <p>判据唯一来源 {@link com.cretas.aims.service.workflow.WorkflowDefectiveNodes}。
         *
         * <p>🔴 它<b>只是个显示标</b>。不良品端口<b>必须留在</b>这个列表里 ——
         * 它照常报工、照常入库存着，走的就是「没有标记的半成品产出」那条默认路径。
         * ⛔ 不要因为有了这个字段就顺手在 {@code workflowOutputOptions} 里按它加一道
         * {@code .filter(...)}：那会让不良品从报工屏上消失，而症状（「找不到这一项」）
         * 很难往回追到那一行。
         *
         * <p>加它的理由是 owner 2026-09-01「好的区别一下吧」：在此之前这个列表里没有
         * 任何字段能把不良品和普通半成品产出分开，操作员只能靠品名猜。
         */
        private Boolean isDefective;
    }
}
