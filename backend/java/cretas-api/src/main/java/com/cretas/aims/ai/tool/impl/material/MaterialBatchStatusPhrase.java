package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.StatusFilter;
import com.cretas.aims.entity.enums.MaterialBatchStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 原料批次状态的词表 (ERP-S6-10)。解析本身是共用的 {@link StatusFilter}; 这里只声明批次自己的说法。
 */
final class MaterialBatchStatusPhrase {

    /** {@link MaterialBatchQueryTool} 的 status 属性 —— 词表、要换个问法的说法、不算状态的固定说法。 */
    static Map<String, Object> property() {
        return StatusFilter.property("批次状态筛选",
            StatusFilter.wordsOf(MaterialBatchStatus.class,
                    // 「库存中」在日常说法里是「在库存里」(「库存中有哪些已过期的」), 不当成 IN_STOCK; 要这个旧状态请说代码。
                    s -> s == MaterialBatchStatus.IN_STOCK ? null : s.getDisplayName(),
                    Map.of(
                            MaterialBatchStatus.RESERVED, List.of("预留"),
                            MaterialBatchStatus.USED_UP, List.of("用完", "用光"),
                            MaterialBatchStatus.DEPLETED, List.of("耗尽"),
                            MaterialBatchStatus.EXPIRED, List.of("过期"),
                            MaterialBatchStatus.INSPECTING, List.of("检验中", "待检验", "待质检", "待检"),
                            MaterialBatchStatus.SCRAPPED, List.of("报废"))),
            // 和某个状态词重叠、但意思不是那个状态: 「即将过期」不是已过期; 「不可用」不是某一个状态
            List.of("即将过期", "快过期", "快要过期", "临期", "快到期", "将要过期", "未过期", "没过期",
                    "不可用", "未用完", "没用完"),
            // 说的是日期字段不是状态: 「按过期时间排序」
            List.of("过期时间", "过期日期", "过期日"));
    }

    private MaterialBatchStatusPhrase() {
    }

    static String normalizeParam(String raw) {
        return StatusFilter.normalize(property(), raw);
    }

    static Optional<MaterialBatchStatus> fromSentence(String sentence) {
        return StatusFilter.fromSentence(property(), sentence).map(MaterialBatchStatus::valueOf);
    }
}
