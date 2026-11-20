package com.cretas.aims.dto.scheduling;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 产线人数配置 (网页「产线产能与设备」→ 人数配置)。整体替换: 传 null = 清空 = 不比较。
 *
 * <ul>
 *   <li>{@code insertMinWorkers} 插单最少人数: 紧急插单比较当天排班人数用, 不够只提示、不拦。
 *       ⚠️ 不是 {@code ProductionLine.minWorkers}; 字段名特意带 insert, 免得和 APS 用的 min_workers 当成一回事。
 *       存在 {@code production_lines.standard_workers} (可空列; 空 = 不比较)。
 *       ⚠️ 不写 {@code min_workers} 列: 那一列建表时 DEFAULT 1, 每条产线都有值, 分不出「没配」,
 *       且被 APS 当模型特征读; 两者合并见 docs/specs/production-line-staffing-config-plan.md。</li>
 *   <li>{@code maxWorkers} 最多人数: 存 {@code max_workers}, 目前只保存, 插单不用。</li>
 *   <li>{@code requiredSkillLevel} 所需技能等级 1-5: 存 {@code required_skill_level}, 目前只保存, 插单不用。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductionLineStaffingRequest {
    private Integer insertMinWorkers;
    private Integer maxWorkers;
    private Integer requiredSkillLevel;
}
