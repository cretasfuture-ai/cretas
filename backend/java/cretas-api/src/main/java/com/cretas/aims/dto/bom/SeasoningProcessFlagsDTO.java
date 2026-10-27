package com.cretas.aims.dto.bom;

import java.util.List;

/**
 * 报工表用的调料工序标记: 当前 BOM 里哪些工序配了调料、哪些要录续锅锅数。
 * <p>只有工序 id 和布尔值, ⛔ 不放配比 / 用量 / 续锅比例 —— 工艺保密产品对无授权的人也照常返回,
 * 逐道录入才不会因为看不到配方而整张打不开 (F006 验收 2026-10-27)。</p>
 */
public record SeasoningProcessFlagsDTO(List<ProcessFlag> processes) {

    public record ProcessFlag(String workProcessId, boolean potEnabled) {
    }
}
