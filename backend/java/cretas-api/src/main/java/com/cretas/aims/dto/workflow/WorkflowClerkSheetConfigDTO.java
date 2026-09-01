package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.math.BigDecimal;

/**
 * 2B Task B2: 把 workflow 快照投影成文员逐工序过程单 (clerk 过程单) 可消费的结构。
 *
 * <p>Thin projection — 不引入新的报工引擎, FE {@code resolveProcesses()} 拿到这份配置后
 * 复用现有 saveRow → materializeBatch → interim-settle 全部库存/成本/结转逻辑不变。
 *
 * <p>legacy (非 workflow) 计划: service 返回 {@code null}, controller 用
 * {@code ApiResponse.success(null)} 包装, FE 据此回落原 {@code getProductWorkProcesses} 路径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowClerkSheetConfigDTO {

    private Long workflowBatchId;
    private Long workflowInstanceId;
    private String productTypeId;
    private List<ProcessDescriptor> processes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessDescriptor {
        private String workflowNodeId;
        private String workProcessId;
        private String processName;
        /** 工序类别 (WorkProcess.processCategory, 如 熟制/注射/加工) — 调料配方按工序: 报工据此驱动锅数录入。 */
        private String processCategory;
        private String defaultCostCategory;
        private Integer processOrder;
        private String plannedUnit;
        private Boolean allowMultipleUpstreamSources;
        private Boolean allowFinishedGoodsSource;
        private Object customFieldSchema;
        private List<PortDescriptor> inputs;
        /**
         * 首个产出端口。向后兼容单产出 FE (2B); 多产出时 == {@code outputs.get(0)}。
         */
        private PortDescriptor output;
        /**
         * 2B.2: 全部产出端口 (按 ordinal 排序)。单产出时 size==1。多产出时 FE 逐端口录入 N 条产出。
         */
        private List<PortDescriptor> outputs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PortDescriptor {
        private String workflowPortId;
        /** 2B.2 端口身份: 对应 workflow 物料 Cell (节点) id (供 FE 回填 output line 的端口身份)。 */
        private String materialNodeId;
        private String materialKind;
        private String skuId;
        /**
         * Exact material candidates authorized for this logical input by the
         * production plan's pinned BOM (main material plus structured substitutes).
         * Empty is never interpreted as "all materials".
         */
        private List<String> allowedSkuIds;
        private String materialName;
        private String unit;
        /** Workflow 运行时物化时锁定的 SKU 每基本单位净重（克）。 */
        private BigDecimal gramsPerUnit;
        private Boolean required;
        private String selectionGroupId;
        private String selectionGroupLabel;
        private String selectionGroupMode;
        private Integer selectionGroupMinSelections;
        private Integer selectionGroupMaxSelections;
        /** false 时 skuId 已无法解析 (物料/产品被删除) — FE 应显示 "SKU 已失效, 请回 Workflow 配置" 提示。 */
        private Boolean skuResolved;
        /** 仅 output 端口有意义: materialKind == FINISHED_GOOD。 */
        private Boolean finished;
        /**
         * 仅 output 端口有意义: 画布上这个物料 Cell 被标记成了**副产**。
         *
         * <p>⚠️ 与 {@link #materialKind} 正交 —— 副产节点的 kind 仍然是 SEMI_FINISHED
         * (画布刻意没有第 5 个 kind), 所以 {@code materialKind} 一个人回答不了「这是不是副产」。
         * 在这个字段出现之前, 报工界面只能按 {@code finished} 二选一渲染 成品/半成品,
         * 于是画布上标成副产的物料到了报工单上变成「半成品」并独立成行。
         */
        private Boolean byproduct;
        /** 仅 output 端口有意义：冻结 Workflow 物料节点 data.isRework 的服务端投影。 */
        private Boolean rework;
        /**
         * 仅 output 端口有意义：冻结 Workflow 物料节点 data.isDefective 的服务端投影。
         *
         * <p>不良品 = 剔出来、<b>还没复查</b>的那部分（owner 2026-09-01：⛔ 不是判废、⛔ 不是不入库）。
         * 它和副产在录入表上<b>一起填</b>，靠这个标区分底层分类。
         *
         * <p>⚠️ 与 {@link #byproduct} / {@link #rework} 三者互斥地表达「角色」，但它们是
         * <b>三个独立布尔</b>而不是一个枚举 —— 历史快照里可能出现同时打两个标的脏数据，
         * 用枚举会逼出一个「优先谁」的隐式判断。消费端自己决定怎么显示。
         */
        private Boolean defective;
        /**
         * 仅 input 端口有意义: 主原料在本工序投入端口间的相对份额(%)。null = 不参与推算。
         * 报工表/领料单用它做「填一个原料, 其余按份额自动跳」(owner 2026-09-02: 30 吨 ⇒ 70 吨)。
         */
        private BigDecimal inputShareRatio;
    }
}
