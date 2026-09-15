package com.cretas.aims.dto.source;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.enums.DnaVerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 源头个体档案<b>详情</b> —— 这头牛(猪/鸭…)是谁、父母是谁、它去了哪些批次/成品、
 * 同一头种公牛的其它后代。
 *
 * <p>「从一块好肉倒推到公牛、再看这头公牛的其它后代」是选种的最小闭环;
 * 物种是字段值, 这个 DTO 对牛/猪/鸭通用, ⛔ 不把「牛」焊进任何字段名。
 *
 * <h3>三种「没有」分开说</h3>
 * <ul>
 *   <li>{@code sire}/{@code dam} 为 {@code null}: 未登记, <b>或</b>登记了但那份档案已查不到
 *       (软删)。两者靠 {@code individual.sireIndividualId} 区分 —— 它非空而 {@code sire}
 *       为空, 就是「登记过、已不可查」。前端据此显示不同的话。</li>
 *   <li>{@code offspring} / {@code siblingsBySire} / {@code batches} 为<b>空列表</b>:
 *       确实没有。⛔ 不是 {@code null} —— {@code null} 在这里没有第二种含义可表达。</li>
 *   <li>{@code downstream} 三个数组为<b>空列表</b>: 挂了批次但尚未进入生产, 或根本没挂批次。
 *       同样 ⛔ 不是 {@code null}。</li>
 * </ul>
 *
 * <p>内部接口(带 token), 不脱敏。公开侧的个体信息走
 * {@link TraceabilityDTO.SourceIndividualBrief}, 两者字段集合不同, ⛔ 不要把这个类
 * 塞进公开响应。
 *
 * @since 2026-09-15 (FEAT-RN-SOURCE-INDIVIDUAL-DETAIL-20260915)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "源头个体档案详情: 本体 + 系谱 + 去向")
public class SourceIndividualDetailDTO {

    @Schema(description = "个体本体 (与 GET /{id} 同一份 DTO)")
    private SourceIndividualDTO individual;

    @Schema(description = "父本摘要; 未登记或档案已不可查时为 null")
    private Summary sire;

    @Schema(description = "母本摘要; 未登记或档案已不可查时为 null")
    private Summary dam;

    @Schema(description = "它的后代 (父本是它或母本是它), 按档案编号升序; 没有就是空数组")
    private List<Summary> offspring;

    @Schema(description = "同父本的其它个体 (不含自身), 按档案编号升序; 父本未登记时为空数组")
    private List<Summary> siblingsBySire;

    @Schema(description = "挂了该个体的原料批次, 最近入库在前; 没有就是空数组")
    private List<BatchSummary> batches;

    @Schema(description = "上面那些原料批次往下游合并去重后的生产批次 / 成品批次 / 出货单")
    private Downstream downstream;

    /** 另一头个体的摘要 —— 只给系谱页一行要显示的东西, 不带 notes/extAttributes。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "个体摘要 (系谱行)")
    public static class Summary {
        private String id;
        private String individualCode;
        private String tagNumber;
        private String species;
        private String breed;
        private String sex;
        private DnaVerificationStatus dnaVerificationStatus;
    }

    /** 原料批次摘要 —— 「它去了哪」的第一跳。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "原料批次摘要")
    public static class BatchSummary {
        private String id;
        private String batchNumber;
        /** 物料名; 物料类型查不到时为 null (⛔ 不编「未知物料」)。 */
        private String materialName;
        private BigDecimal quantity;
        private String unit;
        private LocalDate receiptDate;
    }

    /**
     * 下游三组。元素直接复用血缘节点 DTO —— 召回影响面接口
     * ({@code /traceability/impact/MATERIAL_BATCH/{id}}) 已经把节点批量解析过了,
     * 这里只做跨批次的<b>合并去重</b>(按 {@code nodeType#nodeId}), ⛔ 不再逐条查实体。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "下游去向 (合并去重后)")
    public static class Downstream {
        private List<TraceabilityDTO.LineageNode> productionBatches;
        private List<TraceabilityDTO.LineageNode> finishedBatches;
        private List<TraceabilityDTO.LineageNode> shipments;
    }
}
